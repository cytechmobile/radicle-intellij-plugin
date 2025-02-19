package network.radicle.jetbrains.radiclejetbrainsplugin;

import com.google.common.base.Strings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.testFramework.CoroutineKt;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.EDT;
import com.intellij.util.ui.UIUtil;
import git4idea.GitCommit;
import git4idea.commands.GitImpl;
import git4idea.config.GitConfigUtil;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import network.radicle.jetbrains.radiclejetbrainsplugin.config.RadicleProjectSettingsHandler;
import network.radicle.jetbrains.radiclejetbrainsplugin.dialog.IdentityDialog;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadAuthor;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadProject;
import network.radicle.jetbrains.radiclejetbrainsplugin.patches.PatchListPanelTest;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.RadicleCliService;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.RadicleNativeService;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static network.radicle.jetbrains.radiclejetbrainsplugin.GitTestUtil.addRadRemote;
import static network.radicle.jetbrains.radiclejetbrainsplugin.patches.TimelineTest.RAD_PROJECT_ID;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractIT extends HeavyPlatformTestCase {
    private static final Logger logger = Logger.getInstance(AbstractIT.class);
    public final BlockingQueue<Notification> notificationsQueue = new LinkedBlockingQueue<>();
    public static final String RAD_VERSION = "1.1.0";
    public static final String RAD_PATH = "/usr/bin/rad";
    public static final String RAD_HOME = "/home/test/radicle";
    public static final String RAD_HOME1 = "/test2/secondInstallation";
    public static final String WSL = "wsl";
    protected static final String REMOTE_NAME = "testRemote";
    protected static final String REMOTE_NAME_1 = "testRemote1";
    protected RadicleProjectSettingsHandler radicleProjectSettingsHandler;
    protected String remoteRepoPath;
    protected String remoteRepoPath1;
    protected GitRepository firstRepo;
    protected GitRepository secondRepo;

    private MessageBusConnection mbc;
    private MessageBusConnection applicationMbc;
    public RadStub radStub;
    public RadicleNativeStub nativeStub;
    public RadicleCliService radCli;
    public List<GitCommit> commitHistory;

    @Before
    public void before() throws Exception {
        /* initialize a git repository */
        runInBackground(myProject, () -> {
            remoteRepoPath = Files.createTempDirectory(REMOTE_NAME).toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
            firstRepo = GitTestUtil.createGitRepository(super.getProject(), remoteRepoPath);
        });

        assertThat(firstRepo).isNotNull();

        // Create a commit
        var fileToChange = new File(firstRepo.getRoot().getPath() + "/initial_file.txt");
        GitTestUtil.writeToFile(fileToChange, "Hello");
        GitExecutor.addCommit("my message");

        // Create second a commit
        fileToChange = new File(firstRepo.getRoot().getPath() + "/initial_file_2.txt");
        GitTestUtil.writeToFile(fileToChange, "Hello2");
        GitExecutor.addCommit("my second message");

        runInBackground(myProject, () -> commitHistory = GitHistoryUtils.history(firstRepo.getProject(), firstRepo.getRoot()));

        var myCommit = commitHistory.getFirst();
        var changes = (List<Change>) myCommit.getChanges();
        var change = changes.getFirst();

        remoteRepoPath1 = Files.createTempDirectory(REMOTE_NAME_1).toRealPath(LinkOption.NOFOLLOW_LINKS).toString();

        /* set rad path */
        radicleProjectSettingsHandler = new RadicleProjectSettingsHandler(getProject());
        radicleProjectSettingsHandler.loadSettings();
        radicleProjectSettingsHandler.savePath(RAD_PATH);

        /* initialize rad stub service */
        nativeStub = replaceNativeService();
        radStub = RadStub.replaceRadicleProjectService(this, change.getBeforeRevision().getRevisionNumber().asString(), getProject());
        radCli = replaceCliService("", true);
        replaceAuthService();
        logger.debug("Before revision hash : {}", change.getBeforeRevision().getRevisionNumber().asString());
        logger.debug("Current revision hash : {}", firstRepo.getCurrentRevision());

        /* add HTTP daemon in config */
        initializeProject(firstRepo);
        //addSeedNodeInConfig(firstRepo);
        applicationMbc = ApplicationManager.getApplication().getMessageBus().connect();
        mbc = getProject().getMessageBus().connect();
        mbc.setDefaultHandler((event1, params) -> {
            assertThat(params).hasSize(1);
            assertThat(params[0]).isInstanceOf(Notification.class);
            Notification n = (Notification) params[0];
            logger.warn("captured project notification: " + n);
            notificationsQueue.add(n);
        });
        applicationMbc.setDefaultHandler((event1, params) -> {
            assertThat(params).hasSize(1);
            assertThat(params[0]).isInstanceOf(Notification.class);
            Notification n = (Notification) params[0];
            logger.warn("captured application notification: " + n);
            notificationsQueue.add(n);
            logger.warn("notifications queue: " + notificationsQueue);
        });
        applicationMbc.subscribe(Notifications.TOPIC);
        mbc.subscribe(Notifications.TOPIC);
        logger.warn("created message bus connection and subscribed to notifications: " + mbc);
    }

    @After
    public final void after() {
        if (mbc != null) {
            mbc.disconnect();
        }
        if (applicationMbc != null) {
            applicationMbc.disconnect();
        }
        try {
            if (firstRepo != null) {
                firstRepo.dispose();
            }
            if (secondRepo != null) {
                secondRepo.dispose();
            }
        } catch (Exception e) {
            logger.warn("error disposing git repo", e);
        }
    }

    public static void assertCmd(GeneralCommandLine cmd) {
        assertThat(cmd).isNotNull();
        if (SystemInfo.isWindows) {
            assertThat(cmd.getExePath()).isEqualTo(WSL);
            assertThat("bash").isEqualTo(cmd.getParametersList().get(0));
            assertThat("-ic").isEqualTo(cmd.getParametersList().get(1));
            assertThat(cmd.getParametersList().get(2)).contains(RAD_PATH);
        } else {
            assertThat(cmd.getCommandLineString()).contains(RAD_PATH);
        }
    }

    protected void removeRemoteRadUrl(GitRepository repo) {
        runInBackground(() -> {
            var gitImpl = new GitImpl();
            for (GitRemote remote : repo.getRemotes()) {
                gitImpl.removeRemote(repo, remote);
            }
            repo.update();
        });
    }

    protected void initializeProject(GitRepository repo) {
        addRadRemote(repo);
    }

    protected void addSeedNodeInConfig(GitRepository repo) {
        try {
            runInBackground(repo.getProject(), () -> GitConfigUtil.setValue(super.getProject(), repo.getRoot(), "rad.seed", "https://maple.radicle.garden"));
        } catch (Exception e) {
            logger.warn("unable to write HTTP daemon in config file", e);
        }
    }

    public AuthService replaceAuthService() {
        var identityDialog = new IdentityDialog() {
            @Override
            public boolean showAndGet() {
                assertThat(getTitle()).isEqualTo(RadicleBundle.message("unlockIdentity"));
                return true;
            }
        };
        var authService = new AuthService(myProject, identityDialog) {
            @Override
            protected String sign(Session session, String password) {
                return "okisign";
            }
        };
        ServiceContainerUtil.replaceService(myProject, AuthService.class, authService, this.getTestRootDisposable());
        return authService;
    }

    public RadicleCliService replaceCliService(String head, boolean stubRepo) {
        var cliService = new RadicleCliService(myProject) {
            @Override
            public String getWebUrl() {
                return "url";
            }
            @Override
            public RadProject getRadRepo(GitRepository repo) {
                if (stubRepo) {
                    return new RadProject(RAD_PROJECT_ID, "TestProject", "TestProjectDescr", "main",
                            Strings.nullToEmpty(head), PatchListPanelTest.getTestProjects().get(0).delegates);
                }
                return getInBackground(() -> super.getRadRepo(repo));
            }
        };
        ServiceContainerUtil.replaceService(myProject, RadicleCliService.class, cliService, this.getTestRootDisposable());
        return cliService;
    }

    public RadicleNativeStub replaceNativeService() {
        final var nat = new RadicleNativeStub(myProject);
        ServiceContainerUtil.replaceService(myProject, RadicleNativeService.class, nat, this.getTestRootDisposable());
        return nat;
    }

    public <T> T getInBackground(BackgroundCallable<T> callable) {
        return getInBackground(myProject, callable);
    }

    public void runInBackground(BackgroundRunnable runnable) {
        runInBackground(myProject, runnable);
    }

    public void clearCommandQueues() {
        radStub.commands.clear();
        radStub.commandsStr.clear();
        nativeStub.getCommands().clear();
    }

    public void executeUiTasks() {
        executeUiTasks(myProject);
    }

    public static void executeUiTasks(Project prj) {
        EdtTestUtil.runInEdtAndWait(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                    CoroutineKt.executeSomeCoroutineTasksAndDispatchAllInvocationEvents(prj);
                    EDT.dispatchAllInvocationEvents();
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                    Thread.yield();
                } catch (Exception e) {
                    logger.warn("error executing ui tasks", e);
                }
            }
        });
    }

    public static void markAsShowing(Container parent, Component inner) {
        markAsShowing(parent);
        for (var hl : parent.getHierarchyListeners()) {
            hl.hierarchyChanged(new HierarchyEvent(inner, 0, inner, parent, HierarchyEvent.SHOWING_CHANGED));
        }
    }

    public static void markAsShowing(Component c) {
        var jc = (JComponent) c;

        // set headless to false, otherwise markAsShowing will be a no-op
        var prev = System.getProperty("java.awt.headless");
        System.setProperty("java.awt.headless", "false");
        UIUtil.markAsShowing(jc, true);
        System.setProperty("java.awt.headless", prev);

        //matching UiUtil IS_SHOWING key
        jc.putClientProperty(Key.findKeyByName("Component.isShowing"), Boolean.TRUE);
        assertThat(UIUtil.isShowing(jc, false)).isTrue();
    }

    public static RadAuthor randomAuthor() {
        return new RadAuthor("did:key:" + randomId(), randomId());
    }

    public static String randomId() {
        return UUID.randomUUID().toString();
    }

    public static <T> T getInBackground(Project project, BackgroundCallable<T> runnable) {
        try {
            var finished = new AtomicBoolean(false);
            var result = new AtomicReference<T>(null);
            Thread.ofVirtual().start(() -> {
                try {
                    var res = runnable.run();
                    result.set(res);
                    finished.set(true);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
            while (!finished.get()) {
                Thread.onSpinWait();
                AbstractIT.executeUiTasks(project);
            }
            return result.get();
        } catch (Exception e) {
            logger.warn("unable to run in background", e);
            throw new RuntimeException(e);
        }
    }

    public static void runInBackground(Project project, BackgroundRunnable runnable) {
        getInBackground(project, () -> {
            runnable.run();
            return null;
        });
    }

    public interface BackgroundCallable<T> {
        T run() throws Exception;
    }

    public interface BackgroundRunnable {
        void run() throws Exception;
    }

    public static class RadicleNativeStub extends RadicleNativeService {
        public final BlockingQueue<Capture> commands;

        public RadicleNativeStub(Project project) {
            super(project);
            commands = new LinkedBlockingQueue<>();
            this.jRad = stubJRad(commands);
        }

        public BlockingQueue<Capture> getCommands() {
            return commands;
        }

        public static JRad stubJRad(BlockingQueue<Capture> commands) {
            return new JRad() {
                @Override
                public String radHome(String input) {
                    commands.add(new Capture("radHome", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String changeIssueTitleDescription(String input) {
                    commands.add(new Capture("radHome", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String getEmbeds(String input) {
                    commands.add(new Capture("getEmbeds", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String getAlias(String input) {
                    commands.add(new Capture("getAlias", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String createPatchComment(String input) {
                    commands.add(new Capture("createPatchComment", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String editPatchComment(String input) {
                    commands.add(new Capture("editPatchComment", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String deletePatchComment(String input) {
                    commands.add(new Capture("deletePatchComment", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String editIssueComment(String input) {
                    commands.add(new Capture("editIssueComment", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String patchCommentReact(String input) {
                    commands.add(new Capture("patchCommentReact", input));
                    return "{\"ok\": true}";
                }

                @Override
                public String issueCommentReact(String input) {
                    commands.add(new Capture("issueCommentReact", input));
                    return "{\"ok\": true}";
                }
            };
        }

        public record Capture(String method, String input) { }
    }

    public static class NoopContinuation<T> implements Continuation<T> {
        public static final NoopContinuation<kotlin.Unit> NOOP = new NoopContinuation<>();
        @NotNull
        @Override
        public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        @Override
        public void resumeWith(@NotNull Object o) {
        }
    }

    public static class MockToolWindow extends ToolWindowHeadlessManagerImpl.MockToolWindow {
        public MockToolWindow(@NotNull Project project) {
            super(project);
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean isVisible() {
            return true;
        }
    }
}
