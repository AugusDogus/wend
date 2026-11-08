package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityView;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowProcess;
import org.robolectric.shadows.ShadowSurfaceControl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class EmbeddedMapsTest {
    private final Activity host = Robolectric.buildActivity(Activity.class).setup().get();
    private final List<String> events = new ArrayList<>();

    @Test public void reusingAnExistingTaskDoesNotWaitForTaskCreated() throws Exception {
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

    @Test public void onlyMapsLiveInItsPanelFloatsAndHomeStillReachesTheVideo() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        // Maps is fullscreen on the main display, not in its panel.
        tasks.maps = new MapsTask.Task(7, 0, 1);
        assertFalse(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, listener(),
                LiveSurfaceWindow.Entry.HELD_AT_CARD));
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        assertTrue(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, listener(),
                LiveSurfaceWindow.Entry.HELD_AT_CARD));
        maps.leavePanel();
        maps.home(() -> events.add("video returns"));
        maps.returnFromFloating();
        assertEquals(List.of("video returns"), events);
        assertNull(panelMessage(maps));
        maps.release();
    }

    @Test public void mapsReturnsBeneathTheVideoAndFloatsOnceItsPanelIsLaidOut() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, 0, 1);
        maps.returnBeneathVideo(() -> events.add("float"));
        maps.resume();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of("show Road Home", "move to " + panelDisplay(maps)), tasks.calls);
        assertEquals(List.of("float"), events);
        maps.release();
    }

    @Test public void mapsClosedUnderTheVideoSaysSoInsteadOfFloating() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        maps.returnBeneathVideo(() -> events.add("float"));
        maps.resume();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of(), events);
        assertEquals("Maps was closed. Tap Open Maps to bring it back.", panelMessage(maps));
        maps.release();
    }

    @Test public void mapsClosedWhileFloatingKeepsItsMessageAfterHome() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        assertTrue(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, listener(),
                LiveSurfaceWindow.Entry.HELD_AT_CARD));
        tasks.maps = null;
        callback(maps).onTaskRemovalStarted(7);
        shadowOf(Looper.getMainLooper()).idle();
        maps.home(() -> events.add("video returns"));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("video returns"), events);
        assertEquals("Maps was closed. Tap Open Maps to bring it back.", panelMessage(maps));
        maps.release();
    }

    @Config(shadows = Screenshots.class)
    @Test public void homeDuringACoveredReturnToThePanelStillReturnsMaps() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        withoutLiveMotion(maps);
        tasks.maps = new MapsTask.Task(7, 0, 1);
        // Road Home starts behind fullscreen Maps; a frozen frame covers the move to the panel.
        maps.resume();
        assertEquals(List.of(), tasks.calls);
        // Android delivers the Home intent in the same main-thread message.
        maps.home(() -> events.add("video returns"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of("video returns"), events);
        assertEquals(List.of("move to " + panelDisplay(maps)), tasks.calls);
        maps.release();
    }

    private LiveSurfaceWindow.Listener listener() {
        return new LiveSurfaceWindow.Listener() {
            @Override public void settled(FloatingPlacement.Corner corner) { events.add("settled"); }
            @Override public void tapped() { events.add("tapped"); }
        };
    }

    /** Android's task service, holding at most the one Maps task. */
    private static final class FakeTasks extends MapsTask {
        final List<String> calls = new ArrayList<>();
        MapsTask.Task maps;

        FakeTasks(Activity host) { super(host); }

        @Override MapsTask.Task find() { return maps; }

        @Override void move(MapsTask.Task task, int displayId) {
            calls.add("move to " + displayId);
            maps = new MapsTask.Task(task.id, displayId, 1);
        }

        @Override void showHost() { calls.add("show Road Home"); }
    }

    /**
     * The panel as on the platform-signed head unit, laid out at 70% of 1280 x 720, with its
     * ActivityView's display and container surface created as Android 10 creates them.
     */
    private EmbeddedMaps readyMaps(FakeTasks tasks) throws ReflectiveOperationException {
        ShadowProcess.setUid(1000);
        EmbeddedMaps maps = new EmbeddedMaps(host, tasks);
        FrameLayout content = new FrameLayout(host);
        content.addView(maps, new FrameLayout.LayoutParams(833, 720));
        host.setContentView(content);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("The panel is laid out in Road Home's window", maps.isLaidOut() && maps.isAttachedToWindow());
        ActivityView view = activityView(maps);
        Field display = ActivityView.class.getDeclaredField("mVirtualDisplay");
        display.setAccessible(true);
        display.set(view, host.getSystemService(DisplayManager.class)
                .createVirtualDisplay("ActivityViewVirtualDisplay", 833, 720, 240, null, 0));
        Field root = ActivityView.class.getDeclaredField("mRootSurfaceControl");
        root.setAccessible(true);
        root.set(view, new SurfaceControl.Builder().setName("Road Home test map display").build());
        callback(maps).onActivityViewReady(view);
        assertEquals("The panel is ready, with no error", "Opening Google Maps…", panelMessage(maps));
        return maps;
    }

    private static ActivityView activityView(EmbeddedMaps maps) {
        View first = maps.getChildAt(0);
        assertTrue("The map panel hosts an ActivityView", first instanceof ActivityView);
        return (ActivityView) first;
    }

    private static int panelDisplay(EmbeddedMaps maps) { return activityView(maps).getVirtualDisplayId(); }

    private static ActivityView.StateCallback callback(EmbeddedMaps maps) throws ReflectiveOperationException {
        Field field = ActivityView.class.getDeclaredField("mActivityViewCallback");
        field.setAccessible(true);
        Object callback = field.get(activityView(maps));
        assertTrue(callback instanceof ActivityView.StateCallback);
        return (ActivityView.StateCallback) callback;
    }

    /** As when the live map motion cannot be created, so Maps moves under a frozen frame. */
    private static void withoutLiveMotion(EmbeddedMaps maps) throws ReflectiveOperationException {
        Field motion = EmbeddedMaps.class.getDeclaredField("motion");
        motion.setAccessible(true);
        motion.set(maps, null);
    }

    /** The message the panel shows over Maps, or null when Maps shows. */
    private static String panelMessage(EmbeddedMaps maps) {
        for (int i = 0; i < maps.getChildCount(); i++) {
            View child = maps.getChildAt(i);
            if (!(child instanceof LinearLayout) || child.getVisibility() != View.VISIBLE) continue;
            View first = ((LinearLayout) child).getChildAt(0);
            if (first instanceof TextView) return ((TextView) first).getText().toString();
        }
        return null;
    }

    /** Android 10's privileged screenshot, which Robolectric does not provide. */
    @Implements(value = SurfaceControl.class, isInAndroidSdk = false)
    public static class Screenshots extends ShadowSurfaceControl {
        @Implementation protected static Bitmap screenshot(Rect crop, int width, int height, int rotation) {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
    }
}
