package dev.augie.wend;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import java.util.Arrays;
import java.util.Collections;
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
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    private final LauncherSettings settings = new LauncherSettings(
            activity.getSharedPreferences(LauncherSettings.PREFERENCES, Context.MODE_PRIVATE));

    @Test public void systemScreensOpenInTheirOwnTaskInsteadOfTheHomeTask() {
        AppLaunch.openSystemScreen(activity, new Intent(Settings.ACTION_SETTINGS), "Android settings");

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Settings.ACTION_SETTINGS, started.getAction());
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("System screens are not recent apps", settings.recentApps().packages().isEmpty());
    }

    @Test public void eachOpenedAppBecomesTheMostRecent() {
        TestApps.installInPackageManager(activity, "com.waze", "Waze");
        TestApps.installInPackageManager(activity, "com.spotify.music", "Spotify");

        AppLaunch.open(activity, "com.waze");
        AppLaunch.open(activity, "com.spotify.music");
        AppLaunch.open(activity, "com.waze");

        assertEquals(Arrays.asList("com.waze", "com.spotify.music"), settings.recentApps().packages());
    }

    @Test public void anAppThatCannotOpenIsNotRecorded() {
        AppLaunch.open(activity, "com.not.installed");

        assertEquals(Collections.<String>emptyList(), settings.recentApps().packages());
    }

    @Test public void aWorkProfileAppIsNotRecorded() {
        TestApps.install(activity, TestApps.workProfile(activity), "com.slack", "Slack", new ColorDrawable(Color.RED));
        LauncherApp slack = TestApps.loadedCatalog(activity).apps().get(0);

        AppLaunch.open(activity, slack);

        assertEquals(Collections.<String>emptyList(), settings.recentApps().packages());
    }
}
