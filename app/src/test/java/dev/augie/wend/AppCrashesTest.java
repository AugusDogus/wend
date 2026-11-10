package dev.augie.wend;

import android.content.Context;
import android.os.DropBoxManager;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AppCrashesTest {
    private static final String MAPS = "com.google.android.apps.maps";
    private final DropBoxManager dropBox =
            (DropBoxManager) RuntimeEnvironment.getApplication().getSystemService(Context.DROPBOX_SERVICE);

    /** As Android 10 filed Maps' crash on the emulator (dumpsys dropbox --print system_app_crash). */
    private void crash(String tag, String process, long at) {
        String entry = "Process: " + process + "\nPID: 26629\nFlags: 0x28fbbec5\nPackage: " + MAPS
                + " v1068844695 (26.43.01.1002020710)\nForeground: Yes\n\njava.lang.RuntimeException: shell-induced crash\n";
        shadowOf(dropBox).addData(tag, at, entry.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void findsACrashOfTheAppFiledSinceThen() {
        assertFalse(AppCrashes.since(dropBox, MAPS, 1000));
        crash("system_app_crash", MAPS, 5000);
        assertTrue(AppCrashes.since(dropBox, MAPS, 1000));
        assertFalse("An older crash", AppCrashes.since(dropBox, MAPS, 5000));
    }

    @Test public void anAppInstalledByTheDriverOrCrashingInNativeCodeCountsToo() {
        crash("data_app_crash", MAPS, 5000);
        assertTrue(AppCrashes.since(dropBox, MAPS, 1000));
        crash("data_app_native_crash", MAPS, 9000);
        assertTrue(AppCrashes.since(dropBox, MAPS, 8000));
    }

    @Test public void otherAppsAndOtherProcessesOfTheAppDoNotCount() {
        crash("data_app_crash", "com.google.android.youtube", 5000);
        crash("data_app_crash", MAPS + ":background", 6000);
        // An app that stopped responding is not gone.
        crash("data_app_anr", MAPS, 7000);
        assertFalse(AppCrashes.since(dropBox, MAPS, 1000));
        crash("data_app_crash", MAPS, 8000);
        assertTrue("Found past the others", AppCrashes.since(dropBox, MAPS, 1000));
    }
}
