package dev.augie.wend;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Method;
import java.time.Duration;
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
public class SurfaceExpansionTest {
    private final Activity host = Robolectric.buildActivity(Activity.class).setup().get();
    private final View card = new View(host);
    private final SurfaceExpansion expansion = new SurfaceExpansion(host);
    private final List<String> opened = new ArrayList<>();

    private void open(String name) {
        // No real compositor surface: exercise cancellation and bounded fallback before attachment.
        expansion.open(card, null, new RectF(0, 0, 1280, 720), 1280, 720,
                () -> opened.add(name));
    }

    @Test public void leavingHomeBeforeSurfaceReadinessNeverLaunchesLater() {
        card.layout(0, 0, 320, 180);
        open("video");
        assertTrue(expansion.active());
        expansion.cancel();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(opened.isEmpty());
        assertFalse(expansion.active());
    }

    @Test public void repeatedOpenAndMissingFrameOnlyLaunchOnce() {
        card.layout(0, 0, 320, 180);
        open("first");
        open("second");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of("first"), opened);
        assertFalse(expansion.active());
    }

    @Test public void disabledAnimationsOpenWithoutTouchingTheCompositor() throws Exception {
        card.layout(0, 0, 320, 180);
        Method scale = ValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
        scale.invoke(null, 0f);
        try {
            open("video");
            assertEquals(List.of("video"), opened);
            assertFalse(expansion.active());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(1, opened.size());
        } finally { scale.invoke(null, 1f); }
    }
}
