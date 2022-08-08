package network.radicle.jetbrains.radiclejetbrainsplugin.config;

import com.intellij.testFramework.LightPlatform4TestCase;
import network.radicle.jetbrains.radiclejetbrainsplugin.ActionsTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RadicleSettingsHandlerTest extends LightPlatform4TestCase {
    RadicleSettingsHandler radicleSettingsHandler;

    @Before
    public void before() {
        radicleSettingsHandler = new RadicleSettingsHandler();
        radicleSettingsHandler.savePath(null);
        radicleSettingsHandler.saveRadSync(RadicleSettings.RadSyncType.ASK);
    }

    @After
    public void after() {
        radicleSettingsHandler.savePath(null);
        radicleSettingsHandler.saveRadSync(RadicleSettings.RadSyncType.ASK);
    }

    @Test
    public void loadSettings() {
        var settings = radicleSettingsHandler.loadSettings();
        assertThat(settings.getPath()).isEmpty();
        assertThat(settings.getRadSync()).isEqualTo(RadicleSettings.RadSyncType.ASK.val);
        radicleSettingsHandler.savePath(ActionsTest.radPath);
        radicleSettingsHandler.saveRadSync(RadicleSettings.RadSyncType.YES);
        var newSettings = radicleSettingsHandler.loadSettings();
        assertThat(newSettings.getPath()).isEqualTo(ActionsTest.radPath);
        assertThat(newSettings.getRadSync()).isEqualTo(RadicleSettings.RadSyncType.YES.val);
        radicleSettingsHandler.saveRadSync(RadicleSettings.RadSyncType.NO);
        newSettings = radicleSettingsHandler.loadSettings();
        assertThat(newSettings.getRadSync()).isEqualTo(RadicleSettings.RadSyncType.NO.val);
    }


}
