package dev.augie.wend;

import android.graphics.Rect;
import android.media.session.PlaybackState;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The controls a tap shows over a floating window, laid out as Android 10's PiP menu: close in the
 * top right corner, fullscreen in the middle, and the media action below it.
 */
final class FloatingMenu {
    enum Control { PLAY_PAUSE, FULLSCREEN, CLOSE }

    /** What the window's media session is doing, or {@link #NONE} for a window without one, such as Maps. */
    enum Playback {
        NONE, PLAYING, PAUSED,
        /** Stopped, failed, or reset: the player shows its own stopped, up-next, or ad screen. */
        ENDED;

        static Playback of(PlaybackState state) {
            if (state == null) return ENDED;
            switch (state.getState()) {
                case PlaybackState.STATE_PLAYING:
                case PlaybackState.STATE_BUFFERING:
                case PlaybackState.STATE_CONNECTING:
                    return PLAYING;
                case PlaybackState.STATE_STOPPED:
                case PlaybackState.STATE_NONE:
                case PlaybackState.STATE_ERROR:
                    return ENDED;
                default:
                    // Paused, or moving through the video: its frame stays.
                    return PAUSED;
            }
        }

        /** The playback control offers the other state. */
        Ui.Icon icon() { return this == PLAYING ? Ui.Icon.PAUSE : Ui.Icon.PLAY; }

        /** An ended video keeps offering play, rather than fading to a stopped screen. */
        boolean keepsControls() { return this == ENDED; }
    }

    private static final int TARGET_DP = 48;

    private FloatingMenu() { }

    static List<Control> controls(Playback playback) {
        return playback == Playback.NONE ? Collections.unmodifiableList(Arrays.asList(Control.FULLSCREEN, Control.CLOSE))
                : Collections.unmodifiableList(Arrays.asList(Control.PLAY_PAUSE, Control.FULLSCREEN, Control.CLOSE));
    }

    /** The control's touch target, in screen px, over a window at {@code window}. */
    static Rect target(Control control, Rect window, float density) {
        int size = Math.round(TARGET_DP * density);
        int centerX = (window.left + window.right) / 2;
        int centerY = (window.top + window.bottom) / 2;
        switch (control) {
            case CLOSE: return new Rect(window.right - size, window.top, window.right, window.top + size);
            case FULLSCREEN: return new Rect(centerX - size / 2, centerY - size / 2, centerX + size / 2, centerY + size / 2);
            default: return new Rect(centerX - size / 2, window.bottom - size, centerX + size / 2, window.bottom);
        }
    }

    /** The shown control under a touch at screen point {@code x}, {@code y}, or null. */
    static Control at(float x, float y, Rect window, Playback playback, float density) {
        for (Control control : controls(playback)) {
            if (target(control, window, density).contains(Math.round(x), Math.round(y))) return control;
        }
        return null;
    }
}
