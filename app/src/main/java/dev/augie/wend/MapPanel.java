package dev.augie.wend;

import android.app.Activity;
import android.graphics.Rect;
import android.widget.FrameLayout;

/** The Maps pane's lifecycle and fullscreen actions, independent of window hosting. */
abstract class MapPanel extends FrameLayout {
    /** What happens around Maps as it opens fullscreen over Wend. */
    interface Covering {
        /**
         * Called once, when fullscreen Maps visibly starts covering Wend.
         * @return the screen area whose live content leaves Wend now, such as a floating
         *     video's card, to blank in any frozen transition frame; null if nothing leaves
         */
        Rect starting();
        /**
         * Maps could not move fullscreen after {@link #starting} ran: Wend stays visible
         * with Maps in its panel, so whatever left Wend returns to it.
         */
        void abandoned();
        /** Maps' task has just moved to the main display, fullscreen and on top. */
        default void moved(int taskId) { }
    }

    /** What happens around floating Maps as a tap brings it fullscreen over the fullscreen video. */
    interface Takeover {
        /** A frozen frame covers the screen; Maps moves to the main display next. */
        void covered();
        /** Fullscreen Maps shows, with nothing frozen left above it. */
        void revealed();
        /** Maps could not go fullscreen. It is back in its panel, which explains the error. */
        void failed();
    }

    MapPanel(Activity host) { super(host); }
    abstract void resume();
    abstract void suspend();
    abstract void launch();
    /** @param covering never starts if the request fails first or is canceled */
    void openFullscreen(Covering covering) { openFullscreen(covering, false); }
    /**
     * @param covering never starts if the request fails first or is canceled
     * @param allowCoveredAutoPip lets the app Maps covers enter its own native PiP on the task
     *     switch (Maps covering a floated video under {@code SYSTEM_PIP}); gated by that app's op
     */
    abstract void openFullscreen(Covering covering, boolean allowCoveredAutoPip);
    abstract void setInnerCorners(boolean mapsOnRight);
    abstract void release();

    /** Home: any floating Maps returns to its panel first, then {@code then} runs. */
    void home(Runnable then) { resume(); then.run(); }

    /**
     * Lifts live Maps out of its panel into a small window above a fullscreen video.
     * @return false, leaving Maps where it is, when this host cannot float it now
     */
    boolean floatAboveVideo(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener,
            LiveSurfaceWindow.Entry entry) { return false; }

    /**
     * Lifts live Maps out of its panel into a small window above the video app, which Wend is
     * opening on the main display itself. It stays while Wend is stopped, leaves when another
     * app comes to the front, and returns to its panel when Wend shows again.
     * @return false, leaving Maps where it is, when this host cannot float it now
     */
    boolean floatAboveVideoApp(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener) {
        return false;
    }

    /** Maps lifted with {@link LiveSurfaceWindow.Entry#HELD_AT_CARD} now moves to its corner. */
    void leavePanel() { }

    /** The video below floating Maps has gone: Maps returns to its panel once Wend shows it. */
    void returnFromFloating() { }

    /**
     * A fullscreen video now hides fullscreen Maps: Maps returns to its panel's display beneath it.
     * @param ready if not null, runs once Maps has laid out at the panel's size and Wend shows
     */
    void returnBeneathVideo(Runnable ready) { }

    /**
     * Makes Maps the fullscreen app on the main display and allows its PICTURE_IN_PICTURE app-op,
     * so Maps enters its own native navigation PiP once Wend opens the video over it.
     * @return false, leaving Maps where it is, when this host cannot float it now
     */
    boolean beginNativePipFloat() { return false; }

    /**
     * Asks Maps, navigating in its panel, to enter its own navigation PiP straight from the panel
     * ({@link PanelPip}), allowing its PICTURE_IN_PICTURE app-op for the float.
     * @param result told once, on the main thread; {@code stayed} leaves Maps and its op as they were
     */
    void floatFromPanelIntoPip(PanelPip.Result result) { result.stayed("this map panel cannot float Maps"); }

    /**
     * Moves Maps fullscreen on the main display over a video that has just gone fullscreen, allowing
     * that video's own native PiP on the task switch. A direct move, so it survives Wend stopping.
     * @return false, leaving Maps where it is, when this host cannot cover now
     */
    boolean coverVideoNativePip() { return false; }

    /** Floating Maps was tapped: it takes the screen from the fullscreen video below it. */
    void openFullscreenFromFloating(Takeover takeover) { takeover.failed(); }
}
