package dev.augie.roadhome;

import android.app.Activity;
import android.widget.FrameLayout;

/** The Maps pane's lifecycle and fullscreen actions, independent of window hosting. */
abstract class MapPanel extends FrameLayout {
    MapPanel(Activity host) { super(host); }
    abstract void resume();
    abstract void suspend();
    abstract void launch();
    abstract void openFullscreen();
    abstract void setInnerCorners(boolean mapsOnRight);
    abstract void release();
}
