package dev.augie.wend;

import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SystemPipTest {
    private static final int MAPS = 7;
    private static final int VIDEO = 9;
    private static final int WEND = 3;

    /** The driver expanded the PiP: its task is now the fullscreen app on the main display. */
    @Test public void anExpandedPipShowsFullscreenOnTheMainDisplay() {
        List<SystemPip.Stack> stacks = List.of(
                stack(0, SystemPip.FULLSCREEN, true, VIDEO),
                stack(0, SystemPip.FULLSCREEN, false, MAPS),
                stack(0, SystemPip.FULLSCREEN, false, WEND));
        assertTrue(SystemPip.showsFullscreen(stacks, VIDEO));
        assertFalse("Maps is behind the video", SystemPip.showsFullscreen(stacks, MAPS));
    }

    /** Close or a fling moves the PiP task to the bottom of the main display, hidden. */
    @Test public void aDismissedPipIsNotShownFullscreen() {
        List<SystemPip.Stack> stacks = List.of(
                stack(0, SystemPip.FULLSCREEN, true, MAPS),
                stack(0, SystemPip.FULLSCREEN, false, WEND),
                stack(0, SystemPip.FULLSCREEN, false, VIDEO));
        assertFalse(SystemPip.showsFullscreen(stacks, VIDEO));
    }

    /** Home returns a PiP task to Wend's own display, which is not the main display. */
    @Test public void aPipWendReturnedToItsPanelIsNotShownFullscreen() {
        List<SystemPip.Stack> stacks = List.of(
                stack(0, SystemPip.FULLSCREEN, true, WEND),
                stack(5, SystemPip.FULLSCREEN, true, MAPS));
        assertFalse(SystemPip.showsFullscreen(stacks, MAPS));
    }

    /** Another PiP above the expanded app does not hide it. */
    @Test public void thePinnedStackAboveDoesNotCount() {
        List<SystemPip.Stack> stacks = List.of(
                stack(0, SystemPip.PINNED, true, VIDEO),
                stack(0, SystemPip.FULLSCREEN, true, MAPS));
        assertTrue(SystemPip.showsFullscreen(stacks, MAPS));
        assertFalse(SystemPip.showsFullscreen(stacks, VIDEO));
        assertEquals(VIDEO, SystemPip.pinnedTask(stacks));
    }

    @Test public void noPinnedStackHasNoPinnedTask() {
        assertEquals(-1, SystemPip.pinnedTask(List.of(stack(0, SystemPip.FULLSCREEN, true, MAPS))));
    }

    /** The video in its card, on Wend's display, joins fullscreen Maps' stack and is pinned from there. */
    @Test public void aTaskOnWendsDisplayIsPinnedFromTheStackOfTheAppItFloatsAbove() {
        List<SystemPip.Stack> stacks = List.of(
                stack(0, SystemPip.FULLSCREEN, true, MAPS),
                stack(0, SystemPip.FULLSCREEN, false, WEND),
                stack(5, SystemPip.FULLSCREEN, true, VIDEO));
        SystemPip.Plan plan = SystemPip.plan(stacks, VIDEO, MAPS);
        assertEquals(100 + MAPS, plan.intoStack);
        assertEquals(100 + MAPS, plan.pinStack);
    }

    /** After the video's PiP expands, Maps is the top task of its own stack behind it. */
    @Test public void aTaskOnTopOfItsStackOnTheMainDisplayIsPinnedWhereItIs() {
        List<SystemPip.Stack> stacks = List.of(
                stack(0, SystemPip.FULLSCREEN, true, VIDEO),
                stack(0, SystemPip.FULLSCREEN, false, MAPS));
        SystemPip.Plan plan = SystemPip.plan(stacks, MAPS, VIDEO);
        assertEquals(-1, plan.intoStack);
        assertEquals(100 + MAPS, plan.pinStack);
    }

    @Test public void aPinnedTaskNeedsNoPlan() {
        assertNull(SystemPip.plan(List.of(stack(0, SystemPip.PINNED, true, MAPS)), MAPS, VIDEO));
    }

    /** Never pinned above Wend's own panel display: SystemUI shows PiP on the main display only. */
    @Test public void aTaskIsNotPinnedAboveAnAppOutsideTheMainDisplay() {
        List<SystemPip.Stack> stacks = List.of(
                stack(0, SystemPip.FULLSCREEN, true, WEND),
                stack(5, SystemPip.FULLSCREEN, true, MAPS),
                stack(6, SystemPip.FULLSCREEN, true, VIDEO));
        assertThrows(IllegalStateException.class, () -> SystemPip.plan(stacks, VIDEO, MAPS));
        assertThrows(IllegalStateException.class, () -> SystemPip.plan(stacks, 42, MAPS));
    }

    @Test public void theWatcherReportsWhichTaskWasUnpinnedOnTheMainThread() throws Exception {
        List<String> events = new ArrayList<>();
        SystemPip.Watcher watcher = new SystemPip.Watcher((packageName, taskId) ->
                events.add("unpinned " + packageName + " " + taskId));
        android.app.TaskStackListener android = watcher.listener();
        android.onActivityPinned("com.google.android.youtube", 0, VIDEO, 12);
        android.onActivityUnpinned();
        assertEquals("Callbacks wait for the main thread", List.of(), events);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("unpinned com.google.android.youtube " + VIDEO), events);
        // Android can report an unpin without a pin Wend saw, such as one from before it started.
        android.onActivityUnpinned();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, events.size());
    }

    private static SystemPip.Stack stack(int displayId, int windowingMode, boolean visible, int task) {
        return new SystemPip.Stack(100 + task, displayId, windowingMode, visible, new int[]{task});
    }
}
