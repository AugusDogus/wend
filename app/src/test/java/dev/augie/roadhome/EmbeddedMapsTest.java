package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityView;
import android.app.PendingIntent;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
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

    /** Navigation is gone from the screen until Maps floats: it never waits long for Maps' layout. */
    @Test public void mapsFloatsAboveTheVideoWithinHalfASecondOfReturningEvenBeforeItsLayout() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, 0, 1);
        maps.returnBeneathVideo(() -> events.add("float"));
        maps.resume();
        while (!tasks.calls.contains("move to " + panelDisplay(maps))) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        }
        // No layer query can report Maps here, as when Maps is slow to lay out at its panel size.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450));
        assertEquals(List.of(), events);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
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

    @Test public void aFailedMoveToFullscreenNeverFloatsTheVideo() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        tasks.failMoves = true;
        maps.openFullscreen(covering());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(List.of(), events);
        assertEquals("Maps could not be restored. Open Maps from Recents to recover your route.", panelMessage(maps));
        maps.release();
    }

    @Test public void mapsFloatsAboveTheVideoAppRoadHomeOpensAndStaysWhileRoadHomeStops() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        // The dock or the card opens the video app on the main display: Road Home is leaving.
        maps.suspend();
        assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        // Road Home stops behind the video app.
        maps.suspend();
        assertTrue("Maps keeps floating above the video app", floating(maps).active());
        maps.release();
    }

    @Test public void mapsFloatingAboveTheLiveVideoStillLeavesWhenAnotherAppStopsRoadHome() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        assertTrue(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, listener(),
                LiveSurfaceWindow.Entry.HELD_AT_CARD));
        maps.suspend();
        assertFalse(floating(maps).active());
        maps.release();
    }

    @Test public void anotherAppComingForwardEndsMapsFloatingAboveTheVideoApp() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        maps.suspend();
        assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        TaskStackListener appSwitches = appSwitches(maps);
        appSwitches.onTaskCreated(40, new ComponentName(EmbeddedVideo.PACKAGE, "VideoActivity"));
        appSwitches.onTaskCreated(41, new ComponentName(EmbeddedMaps.PACKAGE, "MapsActivity"));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("The video app and Maps itself keep it", floating(maps).active());
        appSwitches.onTaskCreated(42, new ComponentName("com.android.settings", "Settings"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertFalse(floating(maps).active());
        assertNull("Maps waits in its panel, with no message", panelMessage(maps));
        maps.release();
    }

    @Test public void roadHomeShowingAgainReturnsMapsFloatingAboveTheVideoApp() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        maps.suspend();
        assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        // The video app went away, or Home: Road Home starts, then its window gets a surface.
        maps.resume();
        assertTrue("Maps floats until Road Home's window can show the panel", floating(maps).active());
        hostSurfaceCreated(maps);
        assertFalse(floating(maps).active());
        assertEquals(List.of(), tasks.calls);
        maps.release();
    }

    /**
     * Above the video app Road Home is stopped and lays out nothing, so a tap on floating Maps
     * grows its display directly; once Road Home's window is back, its layout sizes it again.
     */
    @Test public void mapsDisplayGrowsDirectlyOnlyWhileRoadHomeIsHidden() throws Exception {
        EmbeddedMaps maps = readyMaps(new FakeTasks(host));
        EmbeddedDisplayLifecycle lifecycle = lifecycle(maps);
        assertTrue(lifecycle.resizeWhileHidden(1280, 720));
        android.graphics.Point size = new android.graphics.Point();
        host.getSystemService(DisplayManager.class).getDisplay(panelDisplay(maps)).getRealSize(size);
        assertEquals(new android.graphics.Point(1280, 720), size);
        hostSurfaceCreated(maps);
        assertFalse(lifecycle.resizeWhileHidden(833, 720));
        host.getSystemService(DisplayManager.class).getDisplay(panelDisplay(maps)).getRealSize(size);
        assertEquals(new android.graphics.Point(1280, 720), size);
        maps.release();
    }

    @Test public void aVideoThatFloatedAsMapsStartedReturnsIfMapsThenFailsToMove() {
        EmbeddedMaps.CoverOnce once = new EmbeddedMaps.CoverOnce(covering());
        assertEquals(new Rect(1, 2, 3, 4), once.starting());
        assertNull("Covering starts once", once.starting());
        once.abandoned();
        once.abandoned();
        assertEquals(List.of("starting", "abandoned"), events);
    }

    @Test public void aMoveThatFailsBeforeCoveringStartsNeverStartsIt() {
        EmbeddedMaps.CoverOnce once = new EmbeddedMaps.CoverOnce(covering());
        once.abandoned();
        assertNull(once.starting());
        assertEquals(List.of(), events);
    }

    private MapPanel.Covering covering() {
        return new MapPanel.Covering() {
            @Override public Rect starting() { events.add("starting"); return new Rect(1, 2, 3, 4); }
            @Override public void abandoned() { events.add("abandoned"); }
        };
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
        boolean failMoves;

        FakeTasks(Activity host) { super(host); }

        @Override MapsTask.Task find() { return maps; }

        @Override void move(MapsTask.Task task, int displayId) {
            if (failMoves) throw new IllegalStateException("Moving Maps task " + task.id + " failed: -96");
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

    private static LiveSurfaceWindow floating(EmbeddedMaps maps) throws ReflectiveOperationException {
        Field field = EmbeddedMaps.class.getDeclaredField("floating");
        field.setAccessible(true);
        return (LiveSurfaceWindow) field.get(maps);
    }

    /** The app-switch callback Android calls while Maps floats above the video app. */
    private static TaskStackListener appSwitches(EmbeddedMaps maps) throws ReflectiveOperationException {
        Field watcher = EmbeddedMaps.class.getDeclaredField("overVideoApp");
        watcher.setAccessible(true);
        Object value = watcher.get(maps);
        assertTrue("Maps watches app switches while it floats above the video app", value instanceof TaskFrontWatcher);
        Field registration = TaskFrontWatcher.class.getDeclaredField("registration");
        registration.setAccessible(true);
        Object listener = registration.get(value);
        assertTrue(listener instanceof TaskStackListener);
        return (TaskStackListener) listener;
    }

    /** Road Home's window has a surface again, so its panel can show Maps. */
    private static void hostSurfaceCreated(EmbeddedMaps maps) throws ReflectiveOperationException {
        EmbeddedDisplayLifecycle lifecycle = lifecycle(maps);
        Field holder = EmbeddedDisplayLifecycle.class.getDeclaredField("holder");
        holder.setAccessible(true);
        lifecycle.surfaceCreated((SurfaceHolder) holder.get(lifecycle));
    }

    private static EmbeddedDisplayLifecycle lifecycle(EmbeddedMaps maps) throws ReflectiveOperationException {
        Field field = EmbeddedMaps.class.getDeclaredField("displayLifecycle");
        field.setAccessible(true);
        Object lifecycle = field.get(maps);
        assertTrue(lifecycle instanceof EmbeddedDisplayLifecycle);
        return (EmbeddedDisplayLifecycle) lifecycle;
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
