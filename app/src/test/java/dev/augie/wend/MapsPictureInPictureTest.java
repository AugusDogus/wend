package dev.augie.wend;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MapsPictureInPictureTest {
    private static final int MAPS_UID = 10123;
    private final Context context = RuntimeEnvironment.getApplication();
    private final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);

    private void installMaps() {
        PackageInfo maps = new PackageInfo();
        maps.packageName = EmbeddedMaps.PACKAGE;
        maps.applicationInfo = new ApplicationInfo();
        maps.applicationInfo.packageName = EmbeddedMaps.PACKAGE;
        maps.applicationInfo.uid = MAPS_UID;
        shadowOf(context.getPackageManager()).installPackage(maps);
    }

    private int mode() {
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, MAPS_UID, EmbeddedMaps.PACKAGE);
    }

    @Test public void homeCannotShrinkTheManagedMapsTaskIntoSystemPip() throws Exception {
        installMaps();
        assertEquals(AppOpsManager.MODE_ALLOWED, mode());
        assertEquals(MapsPictureInPicture.Outcome.DISABLED,
                MapsPictureInPicture.disable(context, EmbeddedMaps.PACKAGE));
        assertEquals(AppOpsManager.MODE_IGNORED, mode());
    }

    @Test public void anExistingSettingsChoiceIsLeftAlone() throws Exception {
        installMaps();
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, MAPS_UID, EmbeddedMaps.PACKAGE,
                AppOpsManager.MODE_ERRORED);
        assertEquals(MapsPictureInPicture.Outcome.ALREADY_DISABLED,
                MapsPictureInPicture.disable(context, EmbeddedMaps.PACKAGE));
        assertEquals(AppOpsManager.MODE_ERRORED, mode());
    }

    @Test public void missingMapsIsReportedWithoutChangingAppOps() throws Exception {
        assertEquals(MapsPictureInPicture.Outcome.NOT_INSTALLED,
                MapsPictureInPicture.disable(context, EmbeddedMaps.PACKAGE));
        assertEquals(AppOpsManager.MODE_ALLOWED, mode());
    }
}
