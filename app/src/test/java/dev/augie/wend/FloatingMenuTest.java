package dev.augie.wend;

import android.graphics.Rect;
import android.media.session.PlaybackState;
import dev.augie.wend.FloatingMenu.Control;
import dev.augie.wend.FloatingMenu.Playback;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class FloatingMenuTest {
    /** The floating video at rest in the bottom right corner, at the head unit's 1.5 density. */
    private final Rect video = new Rect(824, 453, 1256, 696);
    private static final float DENSITY = 1.5f;

    @Test public void mapsOffersFullscreenAndCloseAndAVideoAlsoPlayback() {
        assertEquals(List.of(Control.FULLSCREEN, Control.CLOSE), FloatingMenu.controls(Playback.NONE));
        for (Playback media : new Playback[]{Playback.PLAYING, Playback.PAUSED, Playback.ENDED}) {
            assertEquals(List.of(Control.PLAY_PAUSE, Control.FULLSCREEN, Control.CLOSE), FloatingMenu.controls(media));
        }
    }

    @Test public void controlsSitWhereAndroid10PutsThem() {
        // 48 dp targets: close in the top right corner, fullscreen in the middle, playback below it.
        assertEquals(new Rect(1184, 453, 1256, 525), FloatingMenu.target(Control.CLOSE, video, DENSITY));
        assertEquals(new Rect(1004, 538, 1076, 610), FloatingMenu.target(Control.FULLSCREEN, video, DENSITY));
        assertEquals(new Rect(1004, 624, 1076, 696), FloatingMenu.target(Control.PLAY_PAUSE, video, DENSITY));
    }

    @Test public void everyControlFitsInsideTheSmallestWindowWithoutOverlapping() {
        // The 288 x 162 dp video is the shortest floating window.
        List<Control> all = FloatingMenu.controls(Playback.PLAYING);
        for (Control control : all) {
            Rect target = FloatingMenu.target(control, video, DENSITY);
            assertTrue(control + " inside", video.contains(target));
            for (Control other : all) {
                if (other != control) {
                    assertFalse(control + " and " + other, Rect.intersects(target, FloatingMenu.target(other, video, DENSITY)));
                }
            }
        }
    }

    @Test public void aTouchFindsTheControlUnderItOnlyAmongThoseShown() {
        assertEquals(Control.CLOSE, FloatingMenu.at(1250, 460, video, Playback.NONE, DENSITY));
        assertEquals(Control.FULLSCREEN, FloatingMenu.at(1040, 574, video, Playback.NONE, DENSITY));
        assertNull("Maps has no playback control", FloatingMenu.at(1040, 660, video, Playback.NONE, DENSITY));
        assertEquals(Control.PLAY_PAUSE, FloatingMenu.at(1040, 660, video, Playback.PAUSED, DENSITY));
        assertNull("The dimmed window around the controls", FloatingMenu.at(880, 480, video, Playback.PAUSED, DENSITY));
    }

    @Test public void playbackFollowsTheSessionsState() {
        for (int state : new int[]{PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING}) {
            assertEquals("state " + state, Playback.PLAYING, Playback.of(playback(state)));
        }
        assertEquals(Playback.PAUSED, Playback.of(playback(PlaybackState.STATE_PAUSED)));
        // Stopped, failed, or reset: YouTube's stopped, up-next, or ad screen.
        for (int state : new int[]{PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE,
                PlaybackState.STATE_ERROR}) {
            assertEquals("state " + state, Playback.ENDED, Playback.of(playback(state)));
        }
        assertEquals("A session without playback plays nothing", Playback.ENDED, Playback.of(null));
    }

    @Test public void thePlaybackControlOffersTheOtherState() {
        assertEquals(Ui.Icon.PAUSE, Playback.PLAYING.icon());
        assertEquals(Ui.Icon.PLAY, Playback.PAUSED.icon());
        assertEquals(Ui.Icon.PLAY, Playback.ENDED.icon());
    }

    @Test public void theControlsStayUpOnlyWhileTheVideoHasEnded() {
        // An ended video offers its play control until it plays again; otherwise they fade.
        assertTrue(Playback.ENDED.keepsControls());
        for (Playback other : new Playback[]{Playback.NONE, Playback.PLAYING, Playback.PAUSED}) {
            assertFalse(other.toString(), other.keepsControls());
        }
    }

    private static PlaybackState playback(int state) {
        return new PlaybackState.Builder().setState(state, 0, state == PlaybackState.STATE_PLAYING ? 1 : 0).build();
    }
}
