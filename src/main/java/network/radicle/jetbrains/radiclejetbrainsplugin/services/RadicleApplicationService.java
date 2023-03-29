package network.radicle.jetbrains.radiclejetbrainsplugin.services;

import com.google.common.base.Strings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.serviceContainer.NonInjectable;
import git4idea.repo.GitRepository;
import git4idea.util.GitVcsConsoleWriter;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.config.RadicleSettingsHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RadicleApplicationService {
    private static final Logger logger = LoggerFactory.getLogger(RadicleApplicationService.class);
    private static final int TIMEOUT = 60_000;
    private final RadicleSettingsHandler settingsHandler;

    public RadicleApplicationService() {
        this(new RadicleSettingsHandler());
    }

    @NonInjectable
    public RadicleApplicationService(RadicleSettingsHandler radicleSettingsHandler) {
        this.settingsHandler = radicleSettingsHandler;
    }

    public ProcessOutput homePath() {
        return executeCommand(".", List.of("path"), null);
    }

    public ProcessOutput radPath() {
        return executeCommand("which", ".", List.of("rad"), null);
    }

    public ProcessOutput self(String radHome, String radPath) {
        return executeCommand(radPath, radHome, ".", List.of("self"), null, "");
    }

    public ProcessOutput clone(String urn, String directory) {
        return executeCommand(directory, List.of("clone", urn, "--no-confirm"), null);
    }

    public boolean isIdentityUnlocked(String key) {
        if (Strings.isNullOrEmpty(key)) {
            return false;
        }
        var output = executeCommand("ssh-add", "", ".", List.of("-l"), null, "");
        if (!RadAction.isSuccess(output)) {
            return false;
        }
        return output.getStdout().contains(key);
    }

    public ProcessOutput getVersion(String path) {
        if (Strings.isNullOrEmpty(path)) {
            return executeCommand(".", List.of("--version"), null);
        } else {
            return executeCommand(path, ".", List.of("--version"), null);
        }
    }

    public ProcessOutput track(GitRepository root, String reference, String nodeUrl) {
        List<String> args = new ArrayList<>();
        args.add("track");
        if (!Strings.isNullOrEmpty(reference)) {
            args.add(reference);
        }
        if (!Strings.isNullOrEmpty(nodeUrl)) {
            args.addAll(List.of("--seed", nodeUrl, "--remote"));
        }
        return executeCommand(root.getRoot().getPath(), args, root);
    }

    public ProcessOutput init(GitRepository root, String name, String description, String branch) {
        return executeCommand(root.getRoot().getPath(), List.of("init", "--name", name, "--description", description,
                "--default-branch", branch, "--no-confirm"), root);
    }

    public ProcessOutput auth(String passphrase, String radHome, String radPath) {
        return executeCommandWithStdin(".", radHome, radPath, List.of("auth", "--stdin"), null, passphrase);
    }

    public ProcessOutput inspect(GitRepository root) {
        return executeCommand(root.getRoot().getPath(), List.of("inspect"), root);
    }

    public ProcessOutput push(GitRepository root, String seed) {
        return executeCommand(root.getRoot().getPath(), List.of("push", "--seed", seed), root);
    }

    public ProcessOutput pull(GitRepository root) {
        return executeCommand(root.getRoot().getPath(), List.of("pull"), root);
    }

    public ProcessOutput remoteList(GitRepository root) {
        return executeCommand(root.getRoot().getPath(), List.of("remote", "ls"), root);
    }

    public ProcessOutput fetch(GitRepository root) {
        return executeCommand(root.getRoot().getPath(), List.of("fetch"), root);
    }

    public ProcessOutput executeCommandWithStdin(String workDir, String radHome, String radPath, List<String> args,
                                                 @Nullable GitRepository repo, String stdin) {
        final var settings = settingsHandler.loadSettings();
        final var path = Strings.isNullOrEmpty(radPath) ? settings.getPath() : radPath;
        final var home = Strings.isNullOrEmpty(radHome) ? settings.getRadHome() : radHome;
        return executeCommand(path, home, workDir, args, repo, stdin);
    }

    public ProcessOutput executeCommand(String workDir, List<String> args, @Nullable GitRepository repo) {
        final var settings = settingsHandler.loadSettings();
        final var radPath = settings.getPath();
        final var radHome = settings.getRadHome();
        return executeCommand(radPath, radHome, workDir, args, repo, "");
    }

    public ProcessOutput executeCommand(String exePath, String workDir, List<String> args, @Nullable GitRepository repo) {
        final var settings = settingsHandler.loadSettings();
        final var radHome = settings.getRadHome();
        return executeCommand(exePath, radHome, workDir, args, repo, "");
    }

    public ProcessOutput executeCommand(
            String exePath, String radHome, String workDir, List<String> args, @Nullable GitRepository repo, String stdin) {
        ProcessOutput result;
        final var cmdLine = new GeneralCommandLine();
        var params = "";
        if (!Strings.isNullOrEmpty(radHome)) {
            params = "export RAD_HOME=" + radHome + "; " + exePath + " " + String.join(" ", args);
        } else {
            params = exePath + " " + String.join(" ", args);
        }
        if (SystemInfo.isWindows) {
            cmdLine.withExePath("wsl").withParameters("bash", "-ic").withParameters(params);
        } else {
            cmdLine.withExePath(exePath).withParameters(args);
            if (!Strings.isNullOrEmpty(radHome)) {
                cmdLine.withEnvironment("RAD_HOME", radHome);
            }
        }
        cmdLine.withCharset(StandardCharsets.UTF_8).withWorkDirectory(workDir)
                // we need parent environment to be present to our rad execution
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.SYSTEM)
                // make sure that the base directory containing our configured rad cli too. exists in the execution PATH
                .withEnvironment("PATH", new File(exePath).getParent() + File.pathSeparator +
                        cmdLine.getParentEnvironment().get("PATH"));
        try {
            var console = repo == null ? null : GitVcsConsoleWriter.getInstance(repo.getProject());
            if (console != null) {
                console.showCommandLine("[" + workDir + "] " + cmdLine.getCommandLineString());
            }

            if (!Strings.isNullOrEmpty(stdin)) {
                result = execAndGetOutputWithStdin(cmdLine, stdin);
            } else {
                result = execAndGetOutput(cmdLine);
            }
            if (console != null) {
                var stdout = result.getStdout();
                if (!Strings.isNullOrEmpty(stdout)) {
                    console.showMessage(stdout);
                }
                var stderr = result.getStderr();
                if (!Strings.isNullOrEmpty(stderr)) {
                    console.showErrorMessage(stderr);
                }
            }
            return result;
        } catch (ExecutionException ex) {
            logger.error("unable to execute rad command", ex);
            return new ProcessOutput(-1);
        }
    }

    public ProcessOutput execAndGetOutputWithStdin(GeneralCommandLine cmdLine, String stdin) {
        var output = ExecUtil.execAndGetOutput(cmdLine, stdin);
        var exitCode = Strings.isNullOrEmpty(output) ? -1 : 0;
        var pr = new ProcessOutput(exitCode);
        pr.appendStdout(output);
        return pr;
    }

    public ProcessOutput execAndGetOutput(GeneralCommandLine cmdLine) throws ExecutionException {
        return ExecUtil.execAndGetOutput(cmdLine, TIMEOUT);
    }
}
