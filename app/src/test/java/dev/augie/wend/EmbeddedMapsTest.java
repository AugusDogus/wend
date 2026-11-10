package dev.augie.wend;

import android.app.Activity;
import android.app.Notification;
import android.app.ActivityView;
import android.app.PendingIntent;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import android.os.DropBoxManager;
import java.nio.charset.StandardCharsets;
import android.content.Context;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowProcess;
import org.robolectric.shadows.ShadowSurfaceControl;
import org.robolectric.shadows.ShadowViewRootImpl;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class EmbeddedMapsTest {
    private final Activity host = Robolectric.buildActivity(Activity.class).setup().get();
    /** The head unit's 1280 x 720 screen, in pixels. */
    private static final String SCREEN = "w1280dp-h720dp-land-mdpi";
    private final List<String> events = new ArrayList<>();
    /** The "resume guidance" setting each test's Maps panel reads. */
    private boolean resumesGuidance;

    @Test public void reusingAnExistingTaskDoesNotWaitForTaskCreated() throws Exception {
        installMaps();
        EmbeddedMaps maps = new EmbeddedMaps(host, () -> false);
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
        assertEquals(List.of("show Wend", "move to " + panelDisplay(maps)), tasks.calls);
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

    /**
     * The crop for floating Maps was chosen once, as it started floating. Guidance that began or
     * ended while it floated left the card over the car, or the trip sheet's top showing.
     */
    @Test public void theMapMovesUnderATurnCardThatComesOrGoesWhileMapsFloats() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        NavigationNotifications notifications = new NavigationNotifications();
        StatusBarNotification guidance = guidance();
        try {
            assertTrue(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, listener(),
                    LiveSurfaceWindow.Entry.HELD_AT_CARD));
            float density = host.getResources().getDisplayMetrics().density;
            assertEquals(FloatingMapCrop.of(833, 720, density, false, 0), floatingSource(maps));
            notifications.onNotificationPosted(guidance);
            assertEquals("Under the card", FloatingMapCrop.of(833, 720, density, true, 0), floatingSource(maps));
            notifications.onNotificationRemoved(guidance);
            assertEquals("Without it", FloatingMapCrop.of(833, 720, density, false, 0), floatingSource(maps));
        } finally {
            notifications.onListenerDisconnected();
            maps.release();
        }
    }

    /**
     * Maps' process dies (killed, or out of memory) in its panel. Android 10 then destroys its task
     * and reports only that, with no onTaskRemovalStarted: the panel's display stayed empty and
     * black until Home was pressed.
     */
    @Test public void mapsKilledInItsPanelReopensThere() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        tasks.maps = null;
        taskRemovals(maps).onTaskRemoved(7);
        shadowOf(Looper.getMainLooper()).idle();
        assertReopened("Wend reopens Maps in its panel");
        assertNull(panelMessage(maps));
        maps.release();
    }

    @Test public void anotherTaskRemovedLeavesMapsAlone() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        taskRemovals(maps).onTaskRemoved(40);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(host).getNextStartedActivity());
        assertNull(panelMessage(maps));
        maps.release();
    }

    /**
     * Without Maps, its display shows only the background color Maps last reported, which Android's
     * ActivityView keeps: black after night guidance, white after a fresh start, where the panel's
     * light message could not be read.
     */
    @Test public void thePanelsMessageCoversWhateverTheEmptyDisplayShows() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        tasks.maps = null;
        callback(maps).onTaskRemovalStarted(7);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Maps was closed. Tap Open Maps to bring it back.", panelMessage(maps));
        View message = null;
        for (int i = 0; i < maps.getChildCount(); i++) {
            View child = maps.getChildAt(i);
            if (child instanceof LinearLayout && child.getVisibility() == View.VISIBLE) message = child;
        }
        assertNotNull(message);
        assertEquals("It fills the panel", maps.getWidth(), message.getWidth());
        assertEquals(maps.getHeight(), message.getHeight());
        assertTrue(message.getBackground() instanceof ColorDrawable);
        assertEquals("Opaque, in Wend's panel color", Ui.PANEL, ((ColorDrawable) message.getBackground()).getColor());
        maps.release();
    }

    /** A message already tells the driver Maps is gone and offers Open Maps: nothing reopens under it. */
    @Test public void aTaskRemovedWhileThePanelOffersOpenMapsReopensNothing() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        tasks.maps = null;
        callback(maps).onTaskRemovalStarted(7);
        shadowOf(Looper.getMainLooper()).idle();
        taskRemovals(maps).onTaskRemoved(7);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(host).getNextStartedActivity());
        assertEquals("Maps was closed. Tap Open Maps to bring it back.", panelMessage(maps));
        maps.release();
    }

    /** Files a crash of Maps in Android's drop box, as Android 10 does when an app crashes. */
    private void fileMapsCrash() {
        DropBoxManager dropBox = (DropBoxManager) host.getSystemService(Context.DROPBOX_SERVICE);
        shadowOf(dropBox).addData("system_app_crash", System.currentTimeMillis(),
                ("Process: " + EmbeddedMaps.PACKAGE + "\nPID: 26629\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Android reports a crashed Maps as it does one the driver closed, so its panel said "Maps was
     * closed" and waited for a tap while guidance was lost, though the Maps that tap opens restores
     * its route. Android files each crash; a Maps it filed reopens like one whose process was killed.
     */
    @Test public void mapsThatCrashedWhileGuidingReopensAndIsAskedToResume() throws Exception {
        installMaps();
        resumesGuidance = true;
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        NavigationNotifications notifications = new NavigationNotifications();
        StatusBarNotification guidance = guidance();
        try {
            notifications.onNotificationPosted(guidance);
            fileMapsCrash();
            notifications.onNotificationRemoved(guidance);
            tasks.maps = null;
            callback(maps).onTaskRemovalStarted(7);
            shadowOf(Looper.getMainLooper()).idle();
            taskRemovals(maps).onTaskRemoved(7);
            shadowOf(Looper.getMainLooper()).idle();
            assertNull("No message asks for a tap", panelMessage(maps));
            assertReopened("Wend reopens Maps in its panel");
            assertTrue("And asks for Maps' Start to be pressed", guidanceResumeRequested());
        } finally {
            notifications.onListenerDisconnected();
            maps.release();
        }
    }

    /** Android files a crash on another thread, which can finish just after Maps' task is gone. */
    @Test public void aCrashFiledJustAfterMapsClosedStillReopensIt() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        tasks.maps = null;
        callback(maps).onTaskRemovalStarted(7);
        shadowOf(Looper.getMainLooper()).idle();
        taskRemovals(maps).onTaskRemoved(7);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Maps was closed. Tap Open Maps to bring it back.", panelMessage(maps));
        assertNull(shadowOf(host).getNextStartedActivity());
        fileMapsCrash();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertNull(panelMessage(maps));
        assertReopened("Wend reopens Maps in its panel");
        maps.release();
    }

    /** A Maps the driver closed stays closed: no crash was filed. */
    @Test public void mapsTheDriverClosedStaysClosed() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        tasks.maps = null;
        callback(maps).onTaskRemovalStarted(7);
        shadowOf(Looper.getMainLooper()).idle();
        taskRemovals(maps).onTaskRemoved(7);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals("Maps was closed. Tap Open Maps to bring it back.", panelMessage(maps));
        assertNull(shadowOf(host).getNextStartedActivity());
        maps.release();
    }

    /** Closed or crashed while floating, Maps got its message but left an empty window floating until Home. */
    @Test public void mapsClosedWhileFloatingClosesItsEmptyWindowAndKeepsItsMessageUntilHome() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        assertTrue(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, listener(),
                LiveSurfaceWindow.Entry.HELD_AT_CARD));
        maps.leavePanel();
        tasks.maps = null;
        // A close or a crash is reported as it starts, and again once the task is destroyed.
        callback(maps).onTaskRemovalStarted(7);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(floating(maps).active());
        taskRemovals(maps).onTaskRemoved(7);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("The empty window closes", floating(maps).active());
        assertNull("Maps that was closed is not reopened by itself", shadowOf(host).getNextStartedActivity());
        assertEquals("Maps was closed. Tap Open Maps to bring it back.", panelMessage(maps));
        // Home asks for Maps in its panel, as it does whenever Maps is not floating.
        maps.home(() -> events.add("video returns"));
        assertEquals(List.of("video returns"), events);
        assertReopened("Home reopens Maps in its panel");
        assertNull(panelMessage(maps));
        maps.release();
    }

    /** Killed while floating above the video app, Maps left an empty window floating there. */
    @Test public void mapsKilledWhileFloatingAboveTheVideoAppClosesItsWindowAndReopensOnHome() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        maps.suspend();
        assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        maps.suspend();
        assertTrue(floating(maps).active());
        tasks.maps = null;
        taskRemovals(maps).onTaskRemoved(7);
        // No time passes: only the report of the destroyed task can have closed it.
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("The empty window closes", floating(maps).active());
        assertNull("Wend is hidden: Maps reopens once its panel shows", shadowOf(host).getNextStartedActivity());
        // Home.
        maps.resume();
        assertReopened("Home reopens Maps in its panel");
        assertNull(panelMessage(maps));
        maps.release();
    }

    /** Above the live video Wend still shows, so Maps reopens in its panel beneath the video at once. */
    @Test public void mapsKilledWhileFloatingAboveTheLiveVideoClosesItsWindowAndReopensInItsPanel() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        assertTrue(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, listener(),
                LiveSurfaceWindow.Entry.HELD_AT_CARD));
        maps.leavePanel();
        assertTrue(floating(maps).active());
        tasks.maps = null;
        taskRemovals(maps).onTaskRemoved(7);
        // No time passes: only the report of the destroyed task can have closed it.
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("The empty window closes", floating(maps).active());
        assertReopened("Maps reopens in its panel beneath the video");
        assertNull(panelMessage(maps));
        maps.release();
    }

    /**
     * Even if Android's report of the dead task never arrives, Home from a float checks for Maps as
     * every other return to the panel does. It did not: the panel stayed black after Home.
     */
    @Test public void mapsGoneUnnoticedWhileFloatingReopensWhenItsWindowReturnsToThePanel() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        maps.suspend();
        assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        maps.suspend();
        tasks.maps = null;
        // Home: Wend starts, then its window gets a surface and the float returns to the panel.
        maps.resume();
        hostSurfaceCreated(maps);
        assertFalse(floating(maps).active());
        assertReopened("Maps reopens in its panel");
        assertNull(panelMessage(maps));
        maps.release();
    }

    /**
     * A reopened Maps restores its route preview but waits for Start. With the setting on, Wend asks
     * for that press when it reopens a Maps that died while guiding.
     */
    @Test public void mapsThatDiedWhileGuidingIsAskedToResumeAsItReopens() throws Exception {
        installMaps();
        resumesGuidance = true;
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        NavigationNotifications notifications = new NavigationNotifications();
        StatusBarNotification guidance = guidance();
        try {
            notifications.onNotificationPosted(guidance);
            assertFalse(guidanceResumeRequested());
            notifications.onNotificationRemoved(guidance);
            tasks.maps = null;
            taskRemovals(maps).onTaskRemoved(7);
            shadowOf(Looper.getMainLooper()).idle();
            assertReopened("Wend reopens Maps in its panel");
            assertTrue("And asks for Maps' Start to be pressed", guidanceResumeRequested());
        } finally {
            notifications.onListenerDisconnected();
            maps.release();
        }
    }

    /**
     * Maps restores no route for a trip nearly over (killed 934 m from its end it came back to its
     * home screen), and waits for Start on the ones it does restore. With the Maps bridge module,
     * Maps has told Wend where the trip goes, and Wend reopens it with the link that navigates there.
     */
    @Test public void mapsThatDiedWhileGuidingReopensNavigatingToTheTripItReported() throws Exception {
        installMaps();
        resumesGuidance = true;
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        NavigationNotifications notifications = new NavigationNotifications();
        StatusBarNotification guidance = guidance();
        try {
            notifications.onNotificationPosted(guidance);
            MapsTrips.reported("1340 N State St, Ukiah, CA 95482");
            notifications.onNotificationRemoved(guidance);
            tasks.maps = null;
            taskRemovals(maps).onTaskRemoved(7);
            shadowOf(Looper.getMainLooper()).idle();
            Intent started = shadowOf(host).getNextStartedActivity();
            assertNotNull("Wend reopens Maps", started);
            assertEquals(EmbeddedMaps.PACKAGE, started.getPackage());
            assertEquals("google.navigation:q=1340%20N%20State%20St%2C%20Ukiah%2C%20CA%2095482", started.getDataString());
            assertNull("Only once", shadowOf(host).getNextStartedActivity());
            assertTrue("Start is still pressed if Maps shows it instead of guiding", guidanceResumeRequested());
        } finally {
            MapsTrips.forget();
            notifications.onListenerDisconnected();
            maps.release();
        }
    }

    /** With the setting off the driver did not ask for guidance to resume, by a link either. */
    @Test public void aReportedTripIsNotResumedWithTheSettingOff() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        NavigationNotifications notifications = new NavigationNotifications();
        StatusBarNotification guidance = guidance();
        try {
            notifications.onNotificationPosted(guidance);
            MapsTrips.reported("1340 N State St, Ukiah, CA 95482");
            notifications.onNotificationRemoved(guidance);
            tasks.maps = null;
            taskRemovals(maps).onTaskRemoved(7);
            shadowOf(Looper.getMainLooper()).idle();
            assertReopened("Wend reopens Maps as it is");
        } finally {
            MapsTrips.forget();
            notifications.onListenerDisconnected();
            maps.release();
        }
    }

    @Test public void mapsThatDiedWhileGuidingIsNotAskedToResumeWithTheSettingOff() throws Exception {
        installMaps();
        resumesGuidance = false;
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        NavigationNotifications notifications = new NavigationNotifications();
        try {
            notifications.onNotificationPosted(guidance());
            tasks.maps = null;
            taskRemovals(maps).onTaskRemoved(7);
            shadowOf(Looper.getMainLooper()).idle();
            assertReopened("Wend reopens Maps in its panel");
            assertFalse(guidanceResumeRequested());
        } finally {
            notifications.onListenerDisconnected();
            maps.release();
        }
    }

    /** Guidance that ended a while before Maps died was over: Maps had arrived, or the driver stopped it. */
    @Test public void mapsThatDiedAfterGuidanceEndedIsNotAskedToStartAnything() throws Exception {
        installMaps();
        resumesGuidance = true;
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        NavigationNotifications notifications = new NavigationNotifications();
        StatusBarNotification guidance = guidance();
        try {
            notifications.onNotificationPosted(guidance);
            notifications.onNotificationRemoved(guidance);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
            tasks.maps = null;
            taskRemovals(maps).onTaskRemoved(7);
            shadowOf(Looper.getMainLooper()).idle();
            assertReopened("Wend reopens Maps in its panel");
            assertFalse(guidanceResumeRequested());
        } finally {
            notifications.onListenerDisconnected();
            maps.release();
        }
    }

    /** Above the video app Wend is hidden: Maps reopens on Home, however much later, and is asked then. */
    @Test public void mapsThatDiedWhileGuidingAboveTheVideoAppIsAskedToResumeWhenHomeReopensIt() throws Exception {
        installMaps();
        resumesGuidance = true;
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        NavigationNotifications notifications = new NavigationNotifications();
        try {
            notifications.onNotificationPosted(guidance());
            maps.suspend();
            assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
            maps.suspend();
            tasks.maps = null;
            taskRemovals(maps).onTaskRemoved(7);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3));
            assertFalse("Nothing is reopened yet", guidanceResumeRequested());
            maps.resume();
            assertReopened("Home reopens Maps in its panel");
            assertTrue(guidanceResumeRequested());
        } finally {
            notifications.onListenerDisconnected();
            maps.release();
        }
    }

    /** Maps that dies again right after Wend reopened it would otherwise be reopened in a loop. */
    @Test public void mapsDyingAgainRightAfterReopeningOffersOpenMapsInstead() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        tasks.maps = null;
        taskRemovals(maps).onTaskRemoved(7);
        shadowOf(Looper.getMainLooper()).idle();
        assertReopened("Wend reopens Maps once");
        tasks.maps = new MapsTask.Task(8, panelDisplay(maps), 1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        tasks.maps = null;
        taskRemovals(maps).onTaskRemoved(8);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull("Not reopened a second time", shadowOf(host).getNextStartedActivity());
        assertEquals("Maps keeps closing. Tap Open Maps to try again.", panelMessage(maps));
        maps.release();
    }

    @Test public void mapsDyingLongAfterReopeningReopensAgain() throws Exception {
        installMaps();
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        tasks.maps = null;
        taskRemovals(maps).onTaskRemoved(7);
        shadowOf(Looper.getMainLooper()).idle();
        assertReopened("Wend reopens Maps");
        tasks.maps = new MapsTask.Task(8, panelDisplay(maps), 1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
        tasks.maps = null;
        taskRemovals(maps).onTaskRemoved(8);
        shadowOf(Looper.getMainLooper()).idle();
        assertReopened("Wend reopens Maps again");
        assertNull(panelMessage(maps));
        maps.release();
    }

    @Config(shadows = Screenshots.class)
    @Test public void homeDuringACoveredReturnToThePanelStillReturnsMaps() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        withoutLiveMotion(maps);
        tasks.maps = new MapsTask.Task(7, 0, 1);
        // Wend starts behind fullscreen Maps; a frozen frame covers the move to the panel.
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

    @Test public void mapsFloatsAboveTheVideoAppWendOpensAndStaysWhileWendStops() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        // The dock or the card opens the video app on the main display: Wend is leaving.
        maps.suspend();
        assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        // Wend stops behind the video app.
        maps.suspend();
        assertTrue("Maps keeps floating above the video app", floating(maps).active());
        maps.release();
    }

    @Test public void mapsFloatingAboveTheLiveVideoStillLeavesWhenAnotherAppStopsWend() throws Exception {
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

    @Test public void wendShowingAgainReturnsMapsFloatingAboveTheVideoApp() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        maps.suspend();
        assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        // The video app went away, or Home: Wend starts, then its window gets a surface.
        maps.resume();
        assertTrue("Maps floats until Wend's window can show the panel", floating(maps).active());
        hostSurfaceCreated(maps);
        assertFalse(floating(maps).active());
        assertEquals(List.of(), tasks.calls);
        maps.release();
    }

    /**
     * Above the video app Wend is stopped and lays out nothing, so a tap on floating Maps
     * grows its display directly; once Wend's window is back, its layout sizes it again.
     */
    @Test public void mapsDisplayGrowsDirectlyOnlyWhileWendIsHidden() throws Exception {
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

    /**
     * Device report BUG-1 (fixtures, P4 and P8): the video moved from Wend's display to the
     * main display left Wend resumed, with its window, under the video app. A tap on floating
     * Maps then laid Maps out through that window, and Wend's stop half a second later called
     * the takeover off, leaving Maps on its panel display, shown nowhere.
     */
    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void aTapAboveTheVideoAppWhileWendStillHasItsWindowBringsMapsFullscreen() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        hostSurfaceCreated(maps);
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        whileLayingOut(maps);
        // Wend stops only now, as the takeover lays Maps out.
        hostSurfaceDestroyed(maps);
        maps.suspend();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsFullscreen(maps, tasks);
    }

    /** Wend resumed or paused under the video app until Maps covers it. */
    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void aTapAboveTheVideoAppWhileWendIsNeverStoppedBringsMapsFullscreen() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        hostSurfaceCreated(maps);
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsFullscreen(maps, tasks);
    }

    /** Review R1: Android 10 stops Wend only once the video app idles, which can be mid-takeover. */
    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void wendStoppingDuringATapAboveTheVideoAppStillBringsMapsFullscreen() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        whileLayingOut(maps);
        maps.suspend();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsFullscreen(maps, tasks);
    }

    /** Review R1, its other half: the stop lands after the growth, before the frozen frame's first frame. */
    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void wendStoppingBeforeTheFrozenFrameStartsStillBringsMapsFullscreen() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        while (frozenFrames() == 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        assertEquals("The frozen frame waits for its first frame", List.of(), events);
        maps.suspend();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsFullscreen(maps, tasks);
    }

    /** Review R2: an app that comes forward without a touch, such as a reverse camera. */
    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void anotherAppComingForwardDuringATapAboveTheVideoAppLeavesMapsInItsPanel() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        whileLayingOut(maps);
        appSwitches(maps).onTaskCreated(42, new ComponentName("com.android.camera", "Camera"));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("No frozen frame stays over that app", 0, frozenFrames());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsWaitsInItsPanel(maps, tasks);
    }

    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void anotherAppComingForwardBeforeTheFrozenFrameStartsLeavesMapsInItsPanel() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        while (frozenFrames() == 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        appSwitches(maps).onTaskCreated(42, new ComponentName("com.android.camera", "Camera"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(List.of(), events);
        assertMapsWaitsInItsPanel(maps, tasks);
    }

    /** The video app finished on its own: Wend shows again, and so does Maps in its panel. */
    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void wendShowingAgainDuringATapAboveTheVideoAppReturnsMapsToItsPanel() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        whileLayingOut(maps);
        maps.resume();
        hostSurfaceCreated(maps);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsWaitsInItsPanel(maps, tasks);
    }

    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void homeDuringATapAboveTheVideoAppReturnsMapsToItsPanel() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        whileLayingOut(maps);
        maps.resume();
        maps.home(maps::returnFromFloating);
        hostSurfaceCreated(maps);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsWaitsInItsPanel(maps, tasks);
    }

    /** Above the live video, Wend only stops for another app: the takeover ends with its frozen frame. */
    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void anotherAppStoppingWendDuringATapAboveTheLiveVideoRemovesTheFrozenFrame() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        hostSurfaceCreated(maps);
        assertTrue(maps.floatAboveVideo(FloatingPlacement.Corner.BOTTOM_RIGHT, listener(),
                LiveSurfaceWindow.Entry.HELD_AT_CARD));
        commitFrames();
        maps.leavePanel();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        maps.openFullscreenFromFloating(takeover());
        whileLayingOut(maps);
        hostSurfaceDestroyed(maps);
        maps.suspend();
        assertEquals("No frozen frame stays over that app", 0, frozenFrames());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsWaitsInItsPanel(maps, tasks);
    }

    /** The dock or the card opens the video app on the main display, and Maps floats above it at rest. */
    private void floatAboveTheVideoApp(EmbeddedMaps maps) throws ReflectiveOperationException {
        maps.suspend();
        assertTrue(maps.floatAboveVideoApp(FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        commitFrames();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400 + LiveSurfaceWindow.ENTRY_TAP_GRACE_MS));
        assertTrue("Maps floats in its corner", floating(maps).floating());
    }

    /** Until the frozen frame covers the screen and Maps lays out, as Takeover.covered says. */
    private void untilCovered() {
        // Maps lays out at full size behind its held window (up to 900 ms), then grows (280 ms).
        for (int i = 0; i < 300 && !events.contains("covered"); i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        }
        assertEquals(List.of("covered"), events);
        assertEquals(1, frozenFrames());
    }

    /** Partway through Maps' layout at full size, held in its floating window before it grows. */
    private void whileLayingOut(EmbeddedMaps maps) throws ReflectiveOperationException {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals("Nothing is covered yet", List.of(), events);
        assertTrue("Maps holds in its corner", floating(maps).floating());
    }

    /** Feedback: floating Maps grew to its panel's size, centered, and only later to full screen. */
    @Config(qualifiers = SCREEN, shadows = {Screenshots.class, CompositedWindows.class})
    @Test public void aTapLaysMapsOutAtFullSizeBeforeItGrowsThere() throws Exception {
        FakeTasks tasks = new FakeTasks(host);
        EmbeddedMaps maps = readyMaps(tasks);
        tasks.maps = new MapsTask.Task(7, panelDisplay(maps), 1);
        maps.resume();
        floatAboveTheVideoApp(maps);
        maps.openFullscreenFromFloating(takeover());
        android.graphics.Point size = new android.graphics.Point();
        host.getSystemService(DisplayManager.class).getDisplay(panelDisplay(maps)).getRealSize(size);
        assertEquals("Its display takes the screen's size at the tap", new android.graphics.Point(1280, 720), size);
        assertTrue("and it holds in its corner meanwhile", floating(maps).floating());
        // Laid out at a new size, the view's SurfaceView, which is the display's root surface,
        // re-placed it around the held window (fixture emulator, above the live video).
        assertEquals("Its view keeps the panel's layout", -1, activityView(maps).getLayoutParams().width);
        untilCovered();
        assertFalse("It has grown to the whole display", floating(maps).floating());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertMapsFullscreen(maps, tasks);
    }

    private void assertMapsFullscreen(EmbeddedMaps maps, FakeTasks tasks) throws ReflectiveOperationException {
        assertEquals("The existing Maps task moved to the main display once",
                List.of("move to 0"), tasks.calls);
        assertEquals(List.of("covered", "revealed"), events);
        assertFalse("Nothing floats", floating(maps).active());
        assertEquals("No frozen frame is left", 0, frozenFrames());
        maps.release();
    }

    private void assertMapsWaitsInItsPanel(EmbeddedMaps maps, FakeTasks tasks) throws ReflectiveOperationException {
        assertEquals("Maps never moved", List.of(), tasks.calls);
        assertFalse("Nothing floats", floating(maps).active());
        assertEquals("No frozen frame is left", 0, frozenFrames());
        assertNull("Maps waits in its panel, with no message", panelMessage(maps));
        android.graphics.Point size = new android.graphics.Point();
        host.getSystemService(DisplayManager.class).getDisplay(panelDisplay(maps)).getRealSize(size);
        assertEquals("Its display has the panel's size again", new android.graphics.Point(833, 720), size);
        maps.release();
    }

    private MapPanel.Takeover takeover() {
        return new MapPanel.Takeover() {
            @Override public void covered() { events.add("covered"); }
            @Override public void revealed() { events.add("revealed"); }
            @Override public void failed() { events.add("failed"); }
        };
    }

    /** Android draws, and then commits, the first frame of every window waiting for one. */
    private void commitFrames() throws ReflectiveOperationException {
        shadowOf(Looper.getMainLooper()).idle();
        Method capture = ViewTreeObserver.class.getDeclaredMethod("captureFrameCommitCallbacks");
        capture.setAccessible(true);
        for (View window : windows()) {
            Object callbacks = capture.invoke(window.getViewTreeObserver());
            if (!(callbacks instanceof List<?>)) continue;
            for (Object callback : (List<?>) callbacks) {
                if (callback instanceof Runnable) ((Runnable) callback).run();
            }
        }
    }

    private int frozenFrames() {
        int count = 0;
        for (View window : windows()) {
            if (window.getLayoutParams() instanceof WindowManager.LayoutParams && "Wend map transition"
                    .contentEquals(((WindowManager.LayoutParams) window.getLayoutParams()).getTitle())) count++;
        }
        return count;
    }

    private List<View> windows() {
        ShadowWindowManagerImpl manager = Shadow.extract(host.getSystemService(WindowManager.class));
        return new ArrayList<>(manager.getViews());
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
            @Override public void fullscreen() { events.add("fullscreen"); }
            @Override public void closed() { events.add("closed"); }
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

        @Override void showHost() { calls.add("show Wend"); }
    }

    /**
     * The panel as on the platform-signed head unit, laid out at 70% of 1280 x 720, with its
     * ActivityView's display and container surface created as Android 10 creates them.
     */
    private EmbeddedMaps readyMaps(FakeTasks tasks) throws ReflectiveOperationException {
        ShadowProcess.setUid(1000);
        EmbeddedMaps maps = new EmbeddedMaps(host, tasks, () -> resumesGuidance);
        FrameLayout content = new FrameLayout(host);
        content.addView(maps, new FrameLayout.LayoutParams(833, 720));
        host.setContentView(content);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("The panel is laid out in Wend's window", maps.isLaidOut() && maps.isAttachedToWindow());
        ActivityView view = activityView(maps);
        Field display = ActivityView.class.getDeclaredField("mVirtualDisplay");
        display.setAccessible(true);
        display.set(view, host.getSystemService(DisplayManager.class)
                .createVirtualDisplay("ActivityViewVirtualDisplay", 833, 720, 240, null, 0));
        Field root = ActivityView.class.getDeclaredField("mRootSurfaceControl");
        root.setAccessible(true);
        root.set(view, new SurfaceControl.Builder().setName("Wend test map display").build());
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

    /** Whether Wend turned on its accessibility service that presses Maps' Start. */
    private boolean guidanceResumeRequested() {
        String enabled = Settings.Secure.getString(host.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(host.getPackageName() + "/" + GuidanceResume.class.getName());
    }

    /** Maps' ongoing guidance notification, as it posts it while navigating. */
    private StatusBarNotification guidance() {
        Notification notification = new Notification.Builder(host, "1_foreground_1")
                .setSmallIcon(Icon.createWithBitmap(Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)))
                .setCategory(Notification.CATEGORY_NAVIGATION).setOngoing(true)
                .setContentTitle("0 ft").setContentText("CA-20 W").build();
        return new StatusBarNotification(EmbeddedMaps.PACKAGE, EmbeddedMaps.PACKAGE, 1, null, 10111, 0, 0,
                notification, Process.myUserHandle(), 0);
    }

    /** The part of Maps' display its floating window shows. */
    private static RectF floatingSource(EmbeddedMaps maps) throws ReflectiveOperationException {
        Field running = LiveSurfaceWindow.class.getDeclaredField("running");
        running.setAccessible(true);
        Object window = running.get(floating(maps));
        assertNotNull("Maps floats", window);
        Field source = window.getClass().getDeclaredField("floatingSource");
        source.setAccessible(true);
        return (RectF) source.get(window);
    }

    /** Google Maps is installed, so Wend can ask Android to open it. */
    private void installMaps() {
        ResolveInfo resolved = new ResolveInfo();
        resolved.activityInfo = new ActivityInfo();
        resolved.activityInfo.packageName = EmbeddedMaps.PACKAGE;
        resolved.activityInfo.name = "com.google.android.maps.MapsActivity";
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(EmbeddedMaps.PACKAGE);
        shadowOf(host.getPackageManager()).addResolveInfoForIntent(launcher, resolved);
    }

    /** Wend asked Android to open Maps, once. */
    private void assertReopened(String message) {
        Intent started = shadowOf(host).getNextStartedActivity();
        assertNotNull(message, started);
        assertEquals(EmbeddedMaps.PACKAGE, started.getComponent().getPackageName());
        assertNull("Only once", shadowOf(host).getNextStartedActivity());
    }

    /** The callback Android calls for each task it destroys, however that came about. */
    private static TaskStackListener taskRemovals(EmbeddedMaps maps) throws ReflectiveOperationException {
        Field watcher = EmbeddedMaps.class.getDeclaredField("taskRemovals");
        watcher.setAccessible(true);
        Object value = watcher.get(maps);
        assertTrue("Maps watches for its task being destroyed", value instanceof TaskRemovalWatcher);
        Field registration = TaskRemovalWatcher.class.getDeclaredField("registration");
        registration.setAccessible(true);
        Object listener = registration.get(value);
        assertTrue(listener instanceof TaskStackListener);
        return (TaskStackListener) listener;
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

    /** Wend's window has a surface again, so its panel can show Maps. */
    private static void hostSurfaceCreated(EmbeddedMaps maps) throws ReflectiveOperationException {
        EmbeddedDisplayLifecycle lifecycle = lifecycle(maps);
        Field holder = EmbeddedDisplayLifecycle.class.getDeclaredField("holder");
        holder.setAccessible(true);
        lifecycle.surfaceCreated((SurfaceHolder) holder.get(lifecycle));
    }

    /** Wend's window is hidden, as Wend stops. */
    private static void hostSurfaceDestroyed(EmbeddedMaps maps) throws ReflectiveOperationException {
        EmbeddedDisplayLifecycle lifecycle = lifecycle(maps);
        Field holder = EmbeddedDisplayLifecycle.class.getDeclaredField("holder");
        holder.setAccessible(true);
        lifecycle.surfaceDestroyed((SurfaceHolder) holder.get(lifecycle));
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

    /** Windows with a compositor surface, so a floating window can present Maps' display. */
    @Implements(className = "android.view.ViewRootImpl", isInAndroidSdk = false)
    public static class CompositedWindows extends ShadowViewRootImpl {
        private SurfaceControl surface;

        @Implementation protected SurfaceControl getSurfaceControl() {
            if (surface == null) surface = new SurfaceControl.Builder().setName("Wend test window").build();
            return surface;
        }
    }
}
