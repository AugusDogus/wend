package dev.augie.roadhome;

import android.app.Activity;
import android.graphics.Rect;
import android.widget.FrameLayout;

/** The Maps pane's lifecycle and fullscreen actions, independent of window hosting. */
abstract class MapPanel extends FrameLayout {
    /** Called once, when fullscreen Maps visibly starts covering Road Home. */
    interface Covering {
        /**
         * @return the screen area whose live content leaves Road Home now, such as a floating
         *     video's card, to blank in any frozen transition frame; null if nothing leaves
         */
        Rect starting();
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
    /** @param covering never runs if the request fails first or is canceled */
    abstract void openFullscreen(Covering covering);
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

    /** Maps lifted with {@link LiveSurfaceWindow.Entry#HELD_AT_CARD} now moves to its corner. */
    void leavePanel() { }

    /** The fullscreen video below floating Maps has gone: Maps returns to its panel. */
    void returnFromFloating() { }

    /**
     * A fullscreen video now hides fullscreen Maps: Maps returns to its panel's display beneath it.
     * @param ready if not null, runs once Maps has laid out at the panel's size and Road Home shows
     */
    void returnBeneathVideo(Runnable ready) { }

    /** Floating Maps was tapped: it takes the screen from the fullscreen video below it. */
    void openFullscreenFromFloating(Takeover takeover) { takeover.failed(); }
}
