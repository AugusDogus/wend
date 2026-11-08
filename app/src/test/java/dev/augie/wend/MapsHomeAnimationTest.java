package dev.augie.wend;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import java.time.Duration;
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
public class MapsHomeAnimationTest {
    private MapsHomeAnimation create() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        // An unlaid-out panel bypasses capture but keeps the actual request/deadline logic.
        return new MapsHomeAnimation(host, new View(host));
    }

    @Test public void missingRemoteCallbackRestoresExactlyOnce() {
        MapsHomeAnimation animation = create();
        List<String> moved = new ArrayList<>();
        animation.move(1, new Rect(447, 36, 1280, 720), () -> moved.add("maps"));
        assertTrue(animation.pending());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));
        assertEquals(List.of("maps"), moved);
        assertFalse(animation.pending());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(1, moved.size());
    }

    @Test public void leavingHomeCancelsTheQueuedTaskMove() {
        MapsHomeAnimation animation = create();
        List<String> moved = new ArrayList<>();
        animation.move(1, new Rect(447, 36, 1280, 720), () -> moved.add("maps"));
        animation.cancel();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(moved.isEmpty());
        assertFalse(animation.pending());
    }

    @Test public void aNewDestinationReplacesTheOldDeadline() {
        MapsHomeAnimation animation = create();
        List<String> moved = new ArrayList<>();
        animation.move(1, new Rect(447, 36, 1280, 720), () -> moved.add("right"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        animation.move(1, new Rect(91, 36, 804, 720), () -> moved.add("left"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(moved.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(List.of("left"), moved);
    }

    @Test public void disabledAnimationsRestoreImmediatelyWithoutADeadline() throws Exception {
        Method scale = ValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
        scale.invoke(null, 0f);
        try {
            MapsHomeAnimation animation = create();
            List<String> moved = new ArrayList<>();
            animation.move(1, new Rect(447, 36, 1280, 720), () -> moved.add("maps"));
            assertEquals(List.of("maps"), moved);
            assertFalse(animation.pending());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(1, moved.size());
        } finally { scale.invoke(null, 1f); }
    }
}
