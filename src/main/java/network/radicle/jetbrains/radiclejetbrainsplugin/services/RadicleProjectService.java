package network.radicle.jetbrains.radiclejetbrainsplugin.services;

import com.google.common.base.Strings;
import com.intellij.dvcs.DvcsUtil;
import com.intellij.dvcs.push.PushSpec;
import com.intellij.execution.CommandLineUtil;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.serviceContainer.NonInjectable;
import git4idea.GitLocalBranch;
import git4idea.GitStandardRemoteBranch;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.changes.GitChangeUtils;
import git4idea.fetch.GitFetchSupport;
import git4idea.push.GitPushOperation;
import git4idea.push.GitPushRepoResult;
import git4idea.push.GitPushSource;
import git4idea.push.GitPushSupport;
import git4idea.push.GitPushTarget;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.util.GitVcsConsoleWriter;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadCobList;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadComment;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadNodeStatus;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadPath;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadTrack;
import network.radicle.jetbrains.radiclejetbrainsplugin.commands.RadicleScriptCommandFactory;
import network.radicle.jetbrains.radiclejetbrainsplugin.config.RadicleProjectSettingsHandler;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadPatch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class RadicleProjectService {
    private static final Logger logger = Logger.getInstance(RadicleProjectService.class);
    private static final int TIMEOUT = 60_000;
    protected final RadicleProjectSettingsHandler projectSettingsHandler;
    protected String wslDistro;
    protected Project project;

    public RadicleProjectService(Project project) {
        this(new RadicleProjectSettingsHandler(project));
        this.project = project;
    }

    @NonInjectable
    public RadicleProjectService(RadicleProjectSettingsHandler radicleProjectSettingsHandler) {
        this.projectSettingsHandler = radicleProjectSettingsHandler;
    }

    public String detectRadPath() {
        ProcessOutput output = radPath();
        if (!RadAction.isSuccess(output)) {
            return "";
        }
        var pathInfo = output.getStdoutLines();
        // which command return empty and where command return INFO if the os  cant find the program path
        if (!pathInfo.isEmpty() && !Strings.isNullOrEmpty(pathInfo.getFirst()) && !pathInfo.getFirst().contains("INFO")) {
            return pathInfo.getFirst();
        }
        return "";
    }

    public boolean isNodeRunning() {
        boolean isRunning = false;
        var radNode = new RadNodeStatus(project, projectSettingsHandler.getPath());
        var res = radNode.run();
        if (RadAction.isSuccess(res)) {
            var output = res.getStdout();
            if (!output.contains("stopped")) {
                isRunning = true;
            }
        }
        return isRunning;
    }

    public String detectRadHome(String radPath) {
        if (Strings.isNullOrEmpty(radPath)) {
            return "";
        }
        var radHome = new RadPath(projectSettingsHandler.getProject(), radPath);
        var output = radHome.perform();
        if (!RadAction.isSuccess(output)) {
            return "";
        }
        return output.getStdout().replace("\n", "");
    }

    public ProcessOutput homePath(String radPath) {
        return executeCommand(radPath, ".", List.of("path"), null);
    }

    public ProcessOutput radPath() {
        return executeCommand("which", ".", List.of("rad"), null);
    }

    public ProcessOutput self(String radHome, String radPath) {
        var alias = executeCommand(radPath, radHome, ".", List.of("self", "--alias"), null);
        var nid = executeCommand(radPath, radHome, ".", List.of("self", "--nid"), null);
        var did = executeCommand(radPath, radHome, ".", List.of("self", "--did"), null);
        var sshFingerPrint = executeCommand(radPath, radHome, ".", List.of("self", "--ssh-fingerprint"), null);
        if (!RadAction.isSuccess(alias) || !RadAction.isSuccess(nid) || !RadAction.isSuccess(did) ||
                !RadAction.isSuccess(sshFingerPrint)) {
            return new ProcessOutput(-1);
        }
        var aliasStr = alias.getStdout();
        var nidStr = nid.getStdout();
        var didStr = did.getStdout();
        var sshFingerPrintStr = sshFingerPrint.getStdout();
        var output = new ProcessOutput(0);
        output.appendStdout(aliasStr + nidStr + didStr + sshFingerPrintStr);
        return output;
    }

    public String getBranchRevision(Project myProject, GitRepository repo, String branchName) {
        AtomicReference<String> gitRevisionNumber = new AtomicReference<>("");
        try {
            var waiter = new CountDownLatch(1);
            new Task.Backgroundable(myProject, "Getting revision number", true) {
                @Override
                public void run(@NotNull ProgressIndicator progressIndicator) {
                    try {
                        var rev = GitChangeUtils.resolveReference(project, repo.getRoot(), branchName).getRev();
                        gitRevisionNumber.set(rev);
                    } catch (Exception e) {
                        logger.warn("Unable to get revision number. repo: " + repo + ", branch name: {}" + branchName);
                    }
                    waiter.countDown();
                }
            }.queue();
            waiter.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Unable to get revision number. repo: " + repo + ", branch name: {}" + branchName);
        }
        return gitRevisionNumber.get();
    }

    public Map<String, Object> pushChanges(GitRepository gitRepository, GitLocalBranch gitLocalBranch, GitRemote gitRemote) {
        var isNewBranch = gitLocalBranch.findTrackedBranch(gitRepository) == null;
        var pushTarget = new GitPushTarget(new GitStandardRemoteBranch(gitRemote, gitLocalBranch.getName()), isNewBranch);
        var pushSource = GitPushSource.create(gitLocalBranch);
        var pushSpec = new PushSpec<>(pushSource, pushTarget);
        var gitPushSupport = DvcsUtil.getPushSupport(GitVcs.getInstance(gitRepository.getProject()));
        if (gitPushSupport == null) {
            return null;
        }
        var pushOperation = new GitPushOperation(gitRepository.getProject(), (GitPushSupport) gitPushSupport,
                Map.of(gitRepository, pushSpec), null, false, false);
        var results = pushOperation.execute().getResults();
        GitUtil.refreshVfsInRoots(List.of(gitRepository.getRoot()));
        var gitPushRepoResult = results.get(gitRepository);
        var isSuccess = isSuccessPush(gitPushRepoResult);
        return Map.of("success", isSuccess, "message", Strings.nullToEmpty(gitPushRepoResult.getError()));
    }

    public boolean isSuccessPush(GitPushRepoResult gitPushRepoResult) {
        var type = gitPushRepoResult.getType();
        return type == GitPushRepoResult.Type.SUCCESS || type == GitPushRepoResult.Type.NEW_BRANCH ||
                type == GitPushRepoResult.Type.UP_TO_DATE || type == GitPushRepoResult.Type.FORCED;
    }

    public ProcessOutput setUpstream(String branch, String targetBranch, GitRepository repository) {
        return executeCommand("git", "", repository.getRoot().getPath(),
                List.of("branch", branch, "--set-upstream-to", targetBranch), repository);
    }

    public ProcessOutput fetchPeerChanges(GitRepository repo) {
        var countDown = new CountDownLatch(1);
        GitVcs.runInBackground(new Task.Backgroundable(repo.getProject(), RadicleBundle.message("fetching"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                final GitFetchSupport gfs = GitFetchSupport.fetchSupport(repo.getProject());
                var gfr = gfs.fetchAllRemotes(List.of(repo));
                var success = gfr.showNotificationIfFailed();
                if (success) {
                    repo.update();
                }
            }

            @Override
            public void onFinished() {
                countDown.countDown();
            }
        });
        try {
            countDown.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Error while fetching remotes");
        }
        return new ProcessOutput(0);
    }

    public ProcessOutput cobShow(GitRepository repo, String projectId, String objectId, RadCobList.Type type) {
        return executeCommand(repo.getRoot().getPath(), List.of("cob", "show", "--repo", projectId, "--type", type.value, "--object", objectId), repo);
    }

    public ProcessOutput cobList(GitRepository repo, String projectId, RadCobList.Type type) {
        return executeCommand(repo.getRoot().getPath(), List.of("cob", "list", "--repo", projectId, "--type", type.value), repo);
    }

    public ProcessOutput createPatch(GitRepository repo, String title, String description, String branch) {
        branch = branch + ":refs/patches";
        var params = List.of("push", "rad", "-o", "patch.message=" + CommandLineUtil.posixQuote(title),
                 "-o", "patch.message=" + CommandLineUtil.posixQuote(description), branch);
        return executeCommandFromFile("git", repo, params);
    }

    public ProcessOutput clone(String urn, String directory, String radPath, String radHome) {
        if (!Strings.isNullOrEmpty(radPath) && !Strings.isNullOrEmpty(radHome)) {
            return executeCommand(radPath, radHome, directory, List.of("clone", urn, "--no-confirm"), null);
        } else {
            return executeCommand(directory, List.of("clone", urn, "--no-confirm"), null);
        }
    }

    public boolean isIdentityUnlocked(String key) {
        if (Strings.isNullOrEmpty(key)) {
            return false;
        }
        var output = executeCommand("ssh-add", "", ".", List.of("-l"), null);
        if (!RadAction.isSuccess(output)) {
            return false;
        }
        return output.getStdout().contains(key);
    }

    public boolean isPassphraseCorrect(String passphrase, String radHome) {
        //ssh-keygen -y -P "1" -f ~/.radicle/keys/radicle
        var output = executeCommand("ssh-keygen", "", ".",
                List.of("-y", "-P", CommandLineUtil.posixQuote(Strings.nullToEmpty(passphrase)), "-f", radHome + "/keys/radicle"), null);
        return RadAction.isSuccess(output);
    }

    public ProcessOutput getVersion(String path) {
        if (Strings.isNullOrEmpty(path)) {
            return executeCommand(".", List.of("--version"), null);
        } else {
            return executeCommand(path, ".", List.of("--version"), null);
        }
    }

    public String getWslDistro() {
        if (!Strings.isNullOrEmpty(wslDistro)) {
            return wslDistro;
        }
        var output = executeCommand("echo", "", ".", List.of("$WSL_DISTRO_NAME"), null);
        if (RadAction.isSuccess(output)) {
            wslDistro = output.getStdout().trim();
            return wslDistro;
        }
        return "";
    }

    private String getWindowPathManually(String path) {
        var distroName = getWslDistro();
        if (Strings.isNullOrEmpty(distroName)) {
            return null;
        }
        return "\\\\wsl$\\" + distroName + path;
    }

    private String getWindowPathUsingWslPath(String path) {
        /*
        wlspath is a command that is installed by default with the Ubuntu distributions and translate
        linux path to windows path
        */
        var output = executeCommand("wslpath", "", ".", List.of("-w", path), null);
        if (RadAction.isSuccess(output)) {
            return output.getStdout().trim();
        }
        return null;
    }

    public String getWindowsPath(String path) {
        // Attempt to get the Windows path using wslpath utility function
        String windowsPath = getWindowPathUsingWslPath(path);
        if (windowsPath != null) {
            return windowsPath;
        }
        // If wslpath fails, try to construct the Windows path manually
        return getWindowPathManually(path);
    }

    public ProcessOutput trackRepo(RadTrack.Repo repo) {
        List<String> args = new ArrayList<>();
        args.add("track");
        args.add(repo.rid());
        if (!repo.scope().equals(RadTrack.Scope.NONE)) {
            args.add("--scope");
            args.add(repo.scope().value);
        }
        return executeCommand(".", args, null);
    }

    public ProcessOutput trackPeer(RadTrack.Peer peer) {
        List<String> args = new ArrayList<>();
        args.add("track");
        args.add(peer.nid());
        if (!Strings.isNullOrEmpty(peer.alias())) {
            args.add("--alias");
            args.add(peer.alias());
        }
        return executeCommand(".", args, null);
    }

    public ProcessOutput checkNodeStatus(String radPath) {
        return executeCommand(radPath, ".", List.of("node", "status"), null);
    }

    public ProcessOutput init(GitRepository root, String name, String description, String branch, String visibility) {
        return executeCommand(root.getRoot().getPath(), List.of("init", "--name", name, "--description", description,
                "--default-branch", branch, visibility, "--no-confirm"), root);
    }

    public ProcessOutput auth(String passphrase, String alias, String radHome, String radPath) {
        if (!Strings.isNullOrEmpty(alias)) {
            return executeCommandWithStdin(".", radHome, radPath, List.of("auth", "--stdin", "--alias", alias), null, passphrase);
        }
        return executeCommandWithStdin(".", radHome, radPath, List.of("auth", "--stdin"), null, passphrase);
    }

    public ProcessOutput remoteList(GitRepository root) {
        return executeCommand(root.getRoot().getPath(), List.of("remote", "list"), root);
    }

    public ProcessOutput checkout(GitRepository root, String patchId) {
        return executeCommand(root.getRoot().getPath(), List.of("patch", "checkout", patchId), root);
    }

    public ProcessOutput inspect(GitRepository root) {
        return executeCommand(root.getRoot().getPath(), List.of("inspect"), root);
    }

    public ProcessOutput inspectDelegates(GitRepository root) {
        return executeCommand(root.getRoot().getPath(), List.of("inspect", "--delegates"), root);
    }

    public ProcessOutput inspectIdentity(GitRepository root) {
        return executeCommand(root.getRoot().getPath(), List.of("inspect", "--identity"), root);
    }

    public ProcessOutput sync(GitRepository root, boolean fetch) {
        if (fetch) {
            return executeCommand(root.getRoot().getPath(), List.of("sync", "-f"), root);
        }
        return executeCommand(root.getRoot().getPath(), List.of("sync"), root);
    }

    public ProcessOutput addRemoveIssueAssignee(GitRepository root, String issueId, List<String> addedAssignees, List<String> deletedAssignees) {
        var addAssigneesStr = addedAssignees.stream().map(s -> "--add " + CommandLineUtil.posixQuote(s)).collect(Collectors.joining(" "));
        var deletedAssigneesStr = deletedAssignees.stream().map(s -> "--delete " + CommandLineUtil.posixQuote(s)).collect(Collectors.joining(" "));
        List<String> params = new ArrayList<>(Arrays.asList("issue", "assign", issueId));
        if (!Strings.isNullOrEmpty(addAssigneesStr)) {
            params.add(addAssigneesStr);
        }
        if (!Strings.isNullOrEmpty(deletedAssigneesStr)) {
            params.add(deletedAssigneesStr);
        }
        return executeCommandFromFile(root, params);
    }

    public ProcessOutput editPatchTitleDescription(GitRepository repo, String patchId, String title, String description) {
        return executeCommandFromFile(repo, List.of("patch", "edit", patchId, "--message",
                CommandLineUtil.posixQuote(Strings.nullToEmpty(title) + "\n\n" + Strings.nullToEmpty(description))));
    }

    public ProcessOutput editIssueTitleDescription(GitRepository repo, String issueId, String title, String description) {
        // TODO: this is not going to work, as `rad issue edit` does not support `--title` and `--description` yet
        return executeCommandFromFile(repo, List.of("issue", "edit", issueId, "--title", CommandLineUtil.posixQuote(Strings.nullToEmpty(title)),
                "--description", CommandLineUtil.posixQuote(Strings.nullToEmpty(description))));
    }

    public ProcessOutput addRemovePatchLabels(GitRepository root, String patchId, List<String> addedLabels, List<String> deletedLabels) {
        return addRemoveLabels(root, patchId, addedLabels, deletedLabels, true);
    }

    public ProcessOutput addRemoveIssueLabels(GitRepository root, String issueId, List<String> addedLabels, List<String> deletedLabels) {
        return addRemoveLabels(root, issueId, addedLabels, deletedLabels, false);
    }

    private ProcessOutput addRemoveLabels(GitRepository root, String id, List<String> addedLabels, List<String> deletedLabels, boolean isPatch) {
        var addLabelsStr = addedLabels.stream().map(s -> "--add " + CommandLineUtil.posixQuote(s)).collect(Collectors.joining(" "));
        var deletedLabelsStr = deletedLabels.stream().map(s -> "--delete " + CommandLineUtil.posixQuote(s)).collect(Collectors.joining(" "));
        var type = isPatch ? "patch" : "issue";
        List<String> params = new ArrayList<>(Arrays.asList(type, "label", id));
        if (!Strings.isNullOrEmpty(addLabelsStr)) {
            params.add(addLabelsStr);
        }
        if (!Strings.isNullOrEmpty(deletedLabelsStr)) {
            params.add(deletedLabelsStr);
        }
        return executeCommandFromFile(root, params);
    }

    public ProcessOutput createIssue(GitRepository root, String title, String description, List<String> assignees, List<String> labels) {
        var labelsStr = labels.stream().map(label -> "--label " + CommandLineUtil.posixQuote(label)).collect(Collectors.joining(" "));
        var assigneesStr = assignees.stream().map(assignee -> "--assign " + CommandLineUtil.posixQuote(assignee)).collect(Collectors.joining(" "));
        List<String> params = new ArrayList<>(Arrays.asList("issue", "open", "--title",
                CommandLineUtil.posixQuote(title), "--description", CommandLineUtil.posixQuote(description)));
        if (!Strings.isNullOrEmpty(labelsStr)) {
            params.add(labelsStr);
        }
        if (!Strings.isNullOrEmpty(assigneesStr)) {
            params.add(assigneesStr);
        }
        return executeCommandFromFile(root, params);
    }

    public ProcessOutput reactToIssueComment(GitRepository repo, String issueId, String commentId, String emoji, boolean active) {
        // active = false does not work :p
        return executeCommand(repo, List.of("issue", "react", issueId, "--emoji", emoji, "--to", commentId));
    }

    public ProcessOutput addReview(GitRepository repo, String verdict, String message, String id) {
        List<String> params = new ArrayList<>(Arrays.asList("patch", "review", id, verdict));
        if (!Strings.isNullOrEmpty(message)) {
            params.add("--message");
            params.add(CommandLineUtil.posixQuote(message));
        }
        return executeCommandFromFile(repo, params);
    }

    public ProcessOutput changePatchState(GitRepository repo, String patchId, String currState, String state) {
        if (Strings.isNullOrEmpty(currState) || Strings.isNullOrEmpty(state) || currState.equals(state)) {
            logger.warn("cannot change patch state with invalid curr:" + currState + "/new:" + state + " states for patch:" + patchId);
            return new ProcessOutput(-1);
        }
        if (RadPatch.State.MERGED.status.equals(currState) || RadPatch.State.MERGED.status.equals(state)) {
            logger.warn("cannot change patch state to/from merged for patch:" + patchId);
            return new ProcessOutput(-1);
        }
        ProcessOutput res = null;
        // we now make sure that the patch state is open
        if (RadPatch.State.ARCHIVED.status.equals(currState)) {
            res = executeCommand(repo, List.of("patch", "archive", patchId, "--undo"));
        } else if (RadPatch.State.DRAFT.status.equals(currState)) {
            res = executeCommand(repo, List.of("patch", "ready", patchId));
        }

        if (RadPatch.State.OPEN.status.equals(state)) {
            return res;
        } else if (RadPatch.State.ARCHIVED.status.equals(state)) {
            return executeCommand(repo, List.of("patch", "archive", patchId));
        } else if (RadPatch.State.DRAFT.status.equals(state)) {
            return executeCommand(repo, List.of("patch", "ready", patchId, "--undo"));
        }

        return executeCommand(repo, List.of("patch", "state", patchId, state));
    }

    public ProcessOutput changeIssueState(GitRepository root, String issueId, String state) {
        return executeCommand(root.getRoot().getPath(), List.of("issue", "state", issueId, state), root);
    }

    public ProcessOutput comment(GitRepository root, String id, String message, String replyTo, RadComment.Type type) {
        List<String> params = new ArrayList<>(Arrays.asList(type.value, "comment", id, "--message", CommandLineUtil.posixQuote(message)));
        if (!Strings.isNullOrEmpty(replyTo)) {
            params.add("--reply-to");
            params.add(replyTo);
        }
        return executeCommandFromFile(root, params);
    }

    public ProcessOutput executeCommandWithStdin(
            String workDir, String radHome, String radPath, List<String> args, @Nullable GitRepository repo, String stdin) {
        final var projectSettings = projectSettingsHandler.loadSettings();
        final var path = Strings.isNullOrEmpty(radPath) ? projectSettings.getPath() : radPath;
        final var home = Strings.isNullOrEmpty(radHome) ? projectSettings.getRadHome() : radHome;
        return executeCommand(path, home, workDir, args, repo, stdin);
    }

    public ProcessOutput executeCommand(GitRepository repo, List<String> args) {
        return executeCommand(repo.getRoot().getPath(), args, repo);
    }

    public ProcessOutput executeCommand(String workDir, List<String> args, @Nullable GitRepository repo) {
        final var projectSettings = projectSettingsHandler.loadSettings();
        final var radPath = projectSettings.getPath();
        final var radHome = projectSettings.getRadHome();
        return executeCommand(radPath, radHome, workDir, args, repo);
    }

    public ProcessOutput executeCommand(String exePath, String workDir, List<String> args, @Nullable GitRepository repo) {
        final var projectSettings = projectSettingsHandler.loadSettings();
        final var radHome = projectSettings.getRadHome();
        return executeCommand(exePath, radHome, workDir, args, repo);
    }

    public ProcessOutput executeCommand(String exePath, String radHome, String workDir, List<String> args, @Nullable GitRepository repo) {
        return executeCommand(exePath, radHome, workDir, args, repo, null);
    }

    public ProcessOutput executeCommandFromFile(String exePath, GitRepository repo, List<String> params) {
        final var projectSettings = projectSettingsHandler.loadSettings();
        final var radHome = projectSettings.getRadHome();
        // if command must be run in the context of a repo (e.g. `rad patch list`), then `repo` must NOT be null
        var workDir = repo == null ? "." : repo.getRoot().getPath();
        var scriptCommand = RadicleScriptCommandFactory.create(workDir, exePath, radHome, params, this, project);
        var output = runCommand(scriptCommand.getCommandLine(), repo, workDir, null);
        scriptCommand.deleteTempFile();
        return output;
    }

    public ProcessOutput executeCommandFromFile(GitRepository repo, List<String> params) {
        final var projectSettings = projectSettingsHandler.loadSettings();
        final var radPath = projectSettings.getPath();
        return executeCommandFromFile(radPath, repo, params);
    }

    public ProcessOutput runCommand(GeneralCommandLine cmdLine, GitRepository repo, String workDir, String stdin) {
        ProcessOutput result;
        if (cmdLine == null) {
            return new ProcessOutput(-1);
        }
        try {
            var console = repo == null ? null : GitVcsConsoleWriter.getInstance(repo.getProject());
            if (console != null) {
                console.showCommandLine("[" + workDir + "] " + cmdLine.getCommandLineString());
            }
            if (stdin != null) {
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
        } catch (Exception ex) {
            logger.warn("unable to execute rad command", ex);
            return new ProcessOutput(-1);
        }
    }

    public ProcessOutput executeCommand(
            String exePath, String radHome, String workDir, List<String> args, @Nullable GitRepository repo, String stdin) {
        final var cmdLine = new GeneralCommandLine();
        if (SystemInfo.isWindows) {
            var params = "";
            if (!Strings.isNullOrEmpty(radHome)) {
                params = "export RAD_HOME=" + radHome + "; ";
            }
            params += exePath + " " + String.join(" ", args);
            cmdLine.withExePath("wsl").withParameters("bash", "-ic").withParameters(params);
        } else {
            cmdLine.withExePath(exePath).withParameters(args);
            if (!Strings.isNullOrEmpty(radHome)) {
                cmdLine.withEnvironment("RAD_HOME", radHome);
            }
        }
        cmdLine.withCharset(StandardCharsets.UTF_8).withWorkDirectory(workDir)
                // we need parent environment to be present to our rad execution
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.SYSTEM);
        var path = new File(exePath).getParent();
        if (!Strings.isNullOrEmpty(path)) {
            // make sure that the base directory containing our configured rad cli too. exists in the execution PATH
            cmdLine.withEnvironment("PATH", path + File.pathSeparator + cmdLine.getParentEnvironment().get("PATH"));
        }
        return runCommand(cmdLine, repo, workDir, stdin);
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

    public void executeInBackground(Runnable task) {
        try {
            final var latch = new CountDownLatch(1);
            ProgressManager.getInstance().run(new Task.Backgroundable(project, RadicleBundle.message("loading"), false) {
                @Override
                public void run(@NotNull ProgressIndicator progressIndicator) {
                    try {
                        task.run();
                    } catch (Exception e) {
                        logger.warn("Error while executing task", e);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Exception e) {
            logger.warn("Error while executing task", e);
        }
    }
}
