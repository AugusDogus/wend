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

    MapPanel(Activity host) { super(host); }
    abstract void resume();
    abstract void suspend();
    abstract void launch();
    /** @param covering never runs if the request fails first or is canceled */
    abstract void openFullscreen(Covering covering);
    abstract void setInnerCorners(boolean mapsOnRight);
    abstract void release();
}
