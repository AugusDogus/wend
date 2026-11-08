package dev.augie.roadhome;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AppLaunchTest {
    @Test public void systemScreensOpenInTheirOwnTaskInsteadOfTheHomeTask() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        AppLaunch.openSystemScreen(activity, new Intent(Settings.ACTION_SETTINGS), "Android settings");

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Settings.ACTION_SETTINGS, started.getAction());
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }
}
