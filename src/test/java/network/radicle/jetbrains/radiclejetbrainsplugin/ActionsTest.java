package network.radicle.jetbrains.radiclejetbrainsplugin;

import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import git4idea.repo.GitRepository;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.RadicleOpenInBrowserAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.RadicleSyncAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.RadicleSyncFetchAction;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadClone;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadSelf;
import network.radicle.jetbrains.radiclejetbrainsplugin.actions.rad.RadSync;
import network.radicle.jetbrains.radiclejetbrainsplugin.listeners.RadicleManagerListener;
import network.radicle.jetbrains.radiclejetbrainsplugin.models.RadDetails;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class ActionsTest extends AbstractIT {
    private static final Logger logger = Logger.getInstance(ActionsTest.class);

    @Test
    public void cloneTest() throws InterruptedException {
        var radUrl = "rad:git:123";
        var clone = new RadClone(radUrl, "C:\\",  "", "", getProject());
        clone.perform();
        var cmd = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertThat(cmd).isNotNull();
        assertCmd(cmd);
        assertThat(cmd.getCommandLineString()).contains("clone " + radUrl);
    }

    @Test
    public void radInspectAction() throws InterruptedException {
        // add back cli service that allows getRadRepo to go through
        radCli = replaceCliService("", false);
        runInBackground(() -> radCli.getRadRepo(firstRepo));
        var cmd = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertCmd(cmd);
        assertThat(cmd.getCommandLineString()).contains("inspect");
        var not = notificationsQueue.poll(100, TimeUnit.MILLISECONDS);
        assertThat(not).isNull();
    }

    @Test
    public void radSelfAction() throws InterruptedException {
        radicleProjectSettingsHandler.saveRadHome(AbstractIT.RAD_HOME);
        var radSelf = new RadSelf(getProject());
        radSelf.askForIdentity(false);
        var output = radSelf.perform();

        var aliasCmd = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertCmd(aliasCmd);
        assertThat(aliasCmd.getCommandLineString()).contains(RAD_PATH + " " + "self --alias");

        var nidCmd = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertCmd(nidCmd);
        assertThat(nidCmd.getCommandLineString()).contains(RAD_PATH + " " + "self --nid");

        var didCmd = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertCmd(didCmd);
        assertThat(didCmd.getCommandLineString()).contains(RAD_PATH + " " + "self --did");

        var fingerPrint = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertCmd(fingerPrint);
        assertThat(fingerPrint.getCommandLineString()).contains(RAD_PATH + " " + "self --ssh-fingerprint");

        //Test parsing
        var details = new RadDetails(output.getStdoutLines(true));
        assertThat(details.alias).isEqualTo(RadStub.SELF_ALIAS);
        assertThat(details.did).isEqualTo(RadStub.SELF_DID);
        assertThat(details.nodeId).isEqualTo(RadStub.SELF_NODEID);
        assertThat(details.keyHash).isEqualTo(RadStub.SELF_KEYHASH);
    }

    @Test
    public void radSyncAction() throws InterruptedException {
        var rfa = new RadicleSyncAction();
        rfa.performAction(getProject());
        executeUiTasks();

        var cmd = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertCmd(cmd);
        assertThat(cmd.getCommandLineString()).contains("sync");
        assertThat(cmd.getCommandLineString()).doesNotContain("-f");

        var not = notificationsQueue.poll(10, TimeUnit.SECONDS);
        assertThat(not).isNotNull();
        var repo = mock(GitRepository.class);
        when(repo.getProject()).thenReturn(getProject());
        assertThat(not.getContent()).contains(new RadSync(repo, false).getNotificationSuccessMessage());
    }

    @Test
    public void radFetchAction() throws InterruptedException {
        var rfa = new RadicleSyncFetchAction();
        rfa.performAction(getProject());
        executeUiTasks();
        var cmd = radStub.commands.poll(10, TimeUnit.SECONDS);
        assertCmd(cmd);
        assertThat(cmd.getCommandLineString()).contains("sync -f");
        var not = notificationsQueue.poll(10, TimeUnit.SECONDS);
        assertThat(not).isNotNull();
        var repo = mock(GitRepository.class);
        when(repo.getProject()).thenReturn(getProject());
        assertThat(not.getContent()).contains(new RadSync(repo, true).getNotificationSuccessMessage());
    }

    @Test
    public void testManagerListenerSettings() {
        radicleProjectSettingsHandler.saveRadHome(RAD_HOME1);
        radicleProjectSettingsHandler.savePath(RAD_PATH);

        var rm = new RadicleManagerListener();
        rm.execute(getProject(), NoopContinuation.NOOP);
        assertThat(radicleProjectSettingsHandler.getRadHome()).isEqualTo(RAD_HOME1);
        assertThat(radicleProjectSettingsHandler.getPath()).isEqualTo(RAD_PATH);
    }

    @Test
    public void testSuccessNotificationAfterInstalledWithValidSettings() throws InterruptedException {
        var rm = new RadicleManagerListener() {
            @Override
            public String detectRadPath(Project project) {
                return RAD_PATH;
            }

            @Override
            public String detectRadHome(Project project, String radPath) {
                return RAD_HOME;
            }
        };
        rm.execute(getProject(), NoopContinuation.NOOP);
        var not = notificationsQueue.poll(100, TimeUnit.MILLISECONDS);
        assertThat(not).isNull();
    }

    @Test
    public void testSuccessNotificationAfterInstalledWithoutValidSettings() throws InterruptedException {
        radicleProjectSettingsHandler.saveRadHome("");
        radicleProjectSettingsHandler.savePath("");

        var rm = new RadicleManagerListener() {
            @Override
            public String detectRadPath(Project project) {
                return "";
            }
        };
        rm.execute(getProject(), NoopContinuation.NOOP);
        var not = notificationsQueue.poll(10, TimeUnit.SECONDS);
        assertThat(not.getContent()).contains(RadicleBundle.message("installedSuccessfully"));
    }

    @Test
    public void cliConfiguredError() throws InterruptedException {
        radicleProjectSettingsHandler.savePath("");
        var rsa = new RadicleSyncFetchAction();
        rsa.performAction(getProject());

        var not = notificationsQueue.poll(10, TimeUnit.SECONDS);
        assertThat(not).isNotNull();
        assertThat(not.getContent()).contains(RadicleBundle.message("radCliPathMissingText"));

        var rps = new RadicleSyncFetchAction();
        rps.performAction(getProject(), List.of(firstRepo));

        not = notificationsQueue.poll(10, TimeUnit.SECONDS);
        assertThat(not.getContent()).isEqualTo(RadicleBundle.message("radCliPathMissingText"));

        var radSyncAction = new RadicleSyncAction();
        radSyncAction.performAction(getProject(), List.of(firstRepo));

        not = notificationsQueue.poll(10, TimeUnit.SECONDS);
        assertThat(not.getContent()).isEqualTo(RadicleBundle.message("radCliPathMissingText"));

        radicleProjectSettingsHandler.savePath(RAD_PATH);
    }

    @Test
    public void radOpenInBrowser() throws InterruptedException {
        BrowserLauncher browserUtilMock = mock(BrowserLauncher.class);
        doNothing().when(browserUtilMock).browse((URI) any());
        // var gitMock = mock(GitImpl.class);
        // ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), Git.class, gitMock, getTestRootDisposable());
        // when(gitMock.lsRemoteRefs(any(), any(), any(), any())).thenReturn(new GitCommandResult(false, 0, List.of(), List.of("abcd")));
        var radOpen = new RadicleOpenInBrowserAction();
        var fileToOpen = "/initial.txt";
        radOpen.openInBrowser(getProject(), firstRepo, fileToOpen, browserUtilMock);
        Thread.sleep(1000);
        ArgumentCaptor<URI> urlCaptor = ArgumentCaptor.forClass(URI.class);
        verify(browserUtilMock).browse(urlCaptor.capture());
        String url = String.valueOf(urlCaptor.getValue());
        var expected = RadicleOpenInBrowserAction.UI_URL + radCli.getWebUrl() + "/rad:123/tree" + fileToOpen;
        assertThat(url).isEqualTo(expected);
    }

    @Test
    public void testRadInitError() throws InterruptedException {
        removeRemoteRadUrl(firstRepo);
        var rsa = new RadicleSyncFetchAction();
        rsa.performAction(getProject());

        executeUiTasks();

        var not = notificationsQueue.poll(10, TimeUnit.SECONDS);
        assertThat(not.getContent()).isEqualTo(RadicleBundle.message("initializationError"));

        var syncAction = new RadicleSyncFetchAction();
        syncAction.performAction(getProject());

        executeUiTasks();

        not = notificationsQueue.poll(10, TimeUnit.SECONDS);
        assertThat(not.getContent()).isEqualTo(RadicleBundle.message("initializationError"));

        initializeProject(firstRepo);
    }
}
