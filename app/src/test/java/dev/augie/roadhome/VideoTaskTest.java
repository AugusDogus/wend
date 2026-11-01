package dev.augie.roadhome;

import android.app.ActivityOptions;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class VideoTaskTest {
    @Test public void fullscreenReturnKeepsTheRememberedVideoInsteadOfAnotherTask() {
        assertEquals(25, VideoTask.selectId(Arrays.asList(28, 25), 25));
    }

    @Test public void anExistingVideoIsAdoptedWithoutLaunchingAnotherTask() {
        assertEquals(25, VideoTask.selectId(Collections.singletonList(25), -1));
    }

    @Test(expected = IllegalStateException.class)
    public void ambiguousVideosDoNotLaunchAnotherCopy() {
        VideoTask.selectId(Arrays.asList(28, 25), -1);
    }

    @Test public void embeddingRestoresFullscreenInsteadOfMovingAPinnedStack() throws Exception {
        ActivityOptions options = VideoTask.fullscreenOptions(7);
        assertEquals(7, options.getLaunchDisplayId());
        assertEquals(1, ActivityOptions.class.getMethod("getLaunchWindowingMode").invoke(options));
    }
}
