package network.radicle.jetbrains.radiclejetbrainsplugin.actions;

import com.google.common.base.Strings;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.config.RadicleSettingsHandler;
import network.radicle.jetbrains.radiclejetbrainsplugin.config.RadicleSettingsView;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.RadicleApplicationService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

public class BasicAction {

    private static final Logger logger = LoggerFactory.getLogger(BasicAction.class);
    private RadAction action;
    private GitRepository repo;
    private Project project;
    private final CountDownLatch countDownLatch;

    public BasicAction(@NotNull RadAction action, @NotNull GitRepository repo, @NotNull Project project,
                       @NotNull CountDownLatch countDownLatch) {
        this.action = action;
        this.project = project;
        this.repo = repo;
        this.countDownLatch = countDownLatch;
    }

    public static boolean isCliPathConfigured(@NotNull AnActionEvent e) {
        logger.debug("action performed: {}", e);
        var rsh = new RadicleSettingsHandler();
        var rs = rsh.loadSettings();
        logger.debug("settings are: {}", rs);
        // check if rad cli is configured
        if (Strings.isNullOrEmpty(rs.getPath())) {
            logger.warn("no rad cli path configured");
            final var project = e.getProject();
            showNotification(e.getProject(), "radCliPathMissing", "radCliPathMissingText", NotificationType.WARNING,
                    List.of(new ConfigureRadCliNotificationAction(project, RadicleBundle.lazyMessage("configure"))));
            return false;
        }
        return true;
    }

    public static boolean hasGitRepos(@NotNull AnActionEvent e) {
        var project = e.getProject();
        var gitRepoManager = GitRepositoryManager.getInstance(project);
        var repos = gitRepoManager.getRepositories();
        if (repos.isEmpty()) {
            logger.warn("no git repos found!");
            return false;
        }
        return true;
    }

    public void perform() {
        var output = action.run(repo);
        var success = output.checkSuccess(com.intellij.openapi.diagnostic.Logger.getInstance(RadicleApplicationService.class));
        countDownLatch.countDown();
        //TODO maybe show notification inside Update Background Class
        if (!success) {
            logger.warn(action.getErrorMessage() + ": exit:{}, out:{} err:{}", output.getExitCode(), output.getStdout(), output.getStderr());
            showErrorNotification(repo.getProject(), "radCliError", output.getStderr());
            return;
        }
        logger.info(action.getSuccessMessage() + ": exit:{}, out:{} err:{}", output.getExitCode(), output.getStdout(), output.getStderr());
        showNotification(project, "", action.getNotificationSuccessMessage(), NotificationType.INFORMATION, null);
    }

    public static class ConfigureRadCliNotificationAction extends NotificationAction {
        final Project project;

        public ConfigureRadCliNotificationAction(Project p, Supplier<String> msg) {
            super(msg);
            this.project = p;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
            logger.debug("clicked configure rad cli notification action");
            notification.hideBalloon();
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RadicleSettingsView.class);
        }
    }

    public static void showErrorNotification(Project project, String title, String content) {
        showNotification(project, title, content, NotificationType.ERROR, null);
    }

    public static void showNotification(
            Project project, String title, String content, NotificationType type,
            Collection<NotificationAction> actions) {
        type = type != null ? type : NotificationType.ERROR;
        var notif = NotificationGroupManager.getInstance()
                .getNotificationGroup("Radicle.NotificationGroup")
                .createNotification(
                        Strings.isNullOrEmpty(title) ? "" : RadicleBundle.message(title),
                        RadicleBundle.message(content), type);
        if (actions != null && !actions.isEmpty()) {
            notif.addActions(actions);
        }
        notif.notify(project);
    }


}
