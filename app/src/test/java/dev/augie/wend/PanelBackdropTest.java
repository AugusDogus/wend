package dev.augie.wend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.SurfaceView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PanelBackdropTest {
    /** The panel's surface comes and goes with Wend's window, as when another app covers it. */
    @Test public void thePanelHasItsColorUnderItWheneverItHasASurface() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        SurfaceView panel = new SurfaceView(host);
        PanelBackdrop backdrop = PanelBackdrop.under(panel, Ui.PANEL);
        assertFalse(backdrop.shown());
        backdrop.surfaceCreated(panel.getHolder());
        assertTrue(backdrop.shown());
        backdrop.surfaceDestroyed(panel.getHolder());
        assertFalse(backdrop.shown());
        backdrop.surfaceCreated(panel.getHolder());
        assertTrue(backdrop.shown());
    }
}
