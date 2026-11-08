package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityView;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class EmbeddedMapsTest {
    @Test public void reusingAnExistingTaskDoesNotWaitForTaskCreated() throws Exception {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        ResolveInfo resolved = new ResolveInfo();
        resolved.activityInfo = new ActivityInfo();
        resolved.activityInfo.packageName = EmbeddedMaps.PACKAGE;
        resolved.activityInfo.name = "com.google.android.maps.MapsActivity";
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(EmbeddedMaps.PACKAGE);
        shadowOf(host.getPackageManager()).addResolveInfoForIntent(launcher, resolved);
        EmbeddedMaps maps = new EmbeddedMaps(host);
        ReusingActivityView view = new ReusingActivityView(host);
        Field activityView = EmbeddedMaps.class.getDeclaredField("activityView");
        activityView.setAccessible(true);
        activityView.set(maps, view);
        Method launch = EmbeddedMaps.class.getDeclaredMethod("launchNew", int.class);
        launch.setAccessible(true);
        launch.invoke(maps, 2);
        assertTrue(view.started);
        Field launching = EmbeddedMaps.class.getDeclaredField("launching");
        launching.setAccessible(true);
        assertFalse("A reused Maps task sends no onTaskCreated callback; the dock must stay usable",
                launching.getBoolean(maps));
    }

    private static final class ReusingActivityView extends ActivityView {
        boolean started;
        ReusingActivityView(Activity host) { super(host); }
        @Override public void startActivity(PendingIntent intent) {
            // A synchronous launch can reuse a task without firing onTaskCreated.
            started = true;
        }
    }

    @Test public void mapsThatAreNotLiveInItsPanelNeverFloatAndHomeStillReachesTheVideo() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        EmbeddedMaps maps = new EmbeddedMaps(host);
        List<String> events = new ArrayList<>();
        assertFalse(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, new LiveSurfaceWindow.Listener() {
            @Override public void settled(FloatingPlacement.Corner corner) { events.add("settled"); }
            @Override public void tapped() { events.add("tapped"); }
        }, LiveSurfaceWindow.Entry.HELD_AT_CARD));
        maps.leavePanel();
        maps.home(() -> events.add("video returns"));
        maps.returnFromFloating();
        assertEquals(List.of("video returns"), events);
        maps.release();
    }
}
