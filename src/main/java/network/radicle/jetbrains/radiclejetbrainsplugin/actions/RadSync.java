package network.radicle.jetbrains.radiclejetbrainsplugin.actions;

import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import git4idea.repo.GitRepository;
import network.radicle.jetbrains.radiclejetbrainsplugin.RadicleBundle;
import network.radicle.jetbrains.radiclejetbrainsplugin.services.RadicleApplicationService;

public class RadSync implements RadAction {

    @Override
    public ProcessOutput run (GitRepository repo) {
        var rad = ApplicationManager.getApplication().getService(RadicleApplicationService.class);
        return rad.sync(repo);
    }

    @Override
    public String getErrorMessage() {
        return RadicleBundle.message("errorInRadSync");
    }

    @Override
    public String getSuccessMessage() {
        return RadicleBundle.message("successInRadSync");
    }

    @Override
    public String getNotificationSuccessMessage() {
        return RadicleBundle.message("radSyncNotification");
    }
}
