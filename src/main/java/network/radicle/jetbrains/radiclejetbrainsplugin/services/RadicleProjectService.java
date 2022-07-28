package network.radicle.jetbrains.radiclejetbrainsplugin.services;

import com.google.common.base.Strings;
import com.intellij.dvcs.push.PushInfo;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import git4idea.repo.GitRepository;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.BasicAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.RadPush;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.RadSync;
import network.radicle.jetbrains.radiclejetbrainsplugin.config.RadicleSettingsHandler;
import network.radicle.jetbrains.radiclejetbrainsplugin.dialog.SelectActionDialog;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class RadicleProjectService {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(RadicleProjectService.class);

    private Project project = null;
    private final String GIT_PUSH_DISPLAY_ID = "git.push.result";
    public List<PushInfo> pushDetails;
    public boolean forceRadPush;

    public RadicleProjectService(Project project) {
        logger.info(RadicleBundle.message("projectService", project.getName()));
        this.project = project;
        setupNotificationListener();
    }

    protected void setupNotificationListener() {
        logger.warn("setting up notification listener");
        project.getMessageBus().connect(project).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(@NotNull Notification notification) {
                /* Check intellij notification for successful git push message */
                if (isGitPushNotification(notification)) {
                    var rsh = new RadicleSettingsHandler();
                    var rs = rsh.loadSettings();
                    /* Check if the user has configured rad */
                    if (!Strings.isNullOrEmpty(rs.getPath())) {
                        var repos = pushDetails.stream().map(detail -> (GitRepository) detail.getRepository())
                                .collect(Collectors.toList());
                        var radSync = rs.getRadSync();
                        if (forceRadPush) {
                            forceRadPush = false;
                            var basicAction = new BasicAction(new RadPush());
                            basicAction.perform(repos, project);
                            /* Check if user has configured plugin to run automatically sync action */
                        } else if (Boolean.parseBoolean(radSync)) {
                            var basicAction = new BasicAction(new RadSync());
                            basicAction.perform(repos,project);
                        } else {
                            var dialog = new SelectActionDialog(project, repos);
                            dialog.showAndGet();
                        }
                    }
                }
            }
        });
    }

    private boolean isGitPushNotification(Notification notification) {
        return notification.getDisplayId() != null && notification.getDisplayId().equals(GIT_PUSH_DISPLAY_ID) &&
                notification.getType().equals(NotificationType.INFORMATION);
    }

}