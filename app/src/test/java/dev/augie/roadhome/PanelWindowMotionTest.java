package dev.augie.roadhome;

import android.graphics.Rect;
import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PanelWindowMotionTest {
    @Test public void mapExpansionOnlyMovesAndCropsWithoutScalingItsControls() {
        PanelWindowMotion motion = new PanelWindowMotion(447, 0, 833, 720,
                new RectF(0, 0, 833, 720), 1280, 720);
        Rect crop = new Rect();
        for (int i = 0; i <= 20; i++) {
            float progress = i / 20f;
            assertEquals(1f, motion.scale(progress), 0f);
            assertEquals(0f, motion.y(progress), 0f);
            motion.crop(progress, crop);
            assertEquals(720, crop.height());
            assertEquals(1280f, motion.x(progress) + crop.width(), .51f);
        }
    }

    @Test public void fullscreenVideoStartsExactlyInThePreviewAndEndsAtDisplayBounds() {
        PanelWindowMotion motion = new PanelWindowMotion(109, 111, 320, 180,
                new RectF(0, 0, 1280, 720), 1280, 720);
        assertEquals(.25f, motion.scale(0), .0001f);
        assertEquals(109f, motion.x(0), .0001f);
        assertEquals(111f, motion.y(0), .0001f);
        assertEquals(1f, motion.scale(1), .0001f);
        assertEquals(0f, motion.x(1), .0001f);
        assertEquals(0f, motion.y(1), .0001f);
        Rect crop = new Rect();
        motion.crop(1, crop);
        assertEquals(new Rect(0, 0, 1280, 720), crop);
    }

    @Test public void watchPageVideoOffsetsDoNotShiftTheInitialPreview() {
        RectF video = new RectF(20, 84, 820, 534);
        PanelWindowMotion motion = new PanelWindowMotion(109, 111, 320, 180, video, 1280, 720);
        assertEquals(109f, motion.x(0) + video.left * motion.scale(0), .0001f);
        assertEquals(111f, motion.y(0) + video.top * motion.scale(0), .0001f);
        Rect crop = new Rect();
        motion.crop(0, crop);
        assertEquals(new Rect(20, 84, 820, 534), crop);
        motion.crop(1, crop);
        assertEquals(new Rect(0, 0, 1280, 720), crop);
    }

    @Test public void portraitVideoStaysCenteredWithItsOriginalAspectRatio() {
        RectF video = new RectF(400, 0, 805, 720);
        PanelWindowMotion motion = new PanelWindowMotion(900, 200, 320, 180, video, 1280, 720);
        assertEquals(1060f, motion.x(0) + (video.left + video.width() / 2) * motion.scale(0), .0001f);
        assertEquals(200f, motion.y(0), .0001f);
        for (int i = 0; i <= 10; i++) {
            float scale = motion.scale(i / 10f);
            assertEquals(9f / 16, video.width() * scale / (video.height() * scale), .0001f);
        }
    }

    @Test public void cropContinuouslyRevealsSurroundingAppWithoutOvershootingDisplay() {
        PanelWindowMotion motion = new PanelWindowMotion(109, 111, 320, 180,
                new RectF(20, 84, 820, 534), 1280, 720);
        Rect previous = new Rect();
        motion.crop(0, previous);
        for (int i = 1; i <= 20; i++) {
            Rect next = new Rect();
            motion.crop(i / 20f, next);
            assertTrue(next.contains(previous));
            assertTrue(new Rect(0, 0, 1280, 720).contains(next));
            previous = next;
        }
    }
}
