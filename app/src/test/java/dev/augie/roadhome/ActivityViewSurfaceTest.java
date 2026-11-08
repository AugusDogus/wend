package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityView;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ActivityViewSurfaceTest {
    private final Activity host = Robolectric.buildActivity(Activity.class).setup().get();

    /**
     * Device report RH-5: the video card shows YouTube scaled down, and Android 10 offsets a scaled
     * portal's touch region, so forwarded card touches landed elsewhere in YouTube and a strip of
     * the map beside the card went to YouTube. The card keeps its touches; Maps' unscaled panel
     * still forwards them.
     */
    @Test public void aScaledVideoPreviewForwardsNoTouchesWhileMapsInItsPanelDoes() throws Exception {
        Rect decoder = new Rect(16, 122, 496, 392);
        ActivityViewSurface video = surface(ActivityViewSurface.PreviewInput.KEPT);
        video.show(320 / 480f, -10.7f, -81.3f, decoder);
        assertTrue("The video card's portal takes no touches", portalRegion(video).isEmpty());
        video.restorePreview(320 / 480f, -10.7f, -81.3f, decoder);
        assertTrue("Nor after returning from fullscreen", portalRegion(video).isEmpty());
        // Fullscreen input is unchanged: the whole display, unscaled.
        video.input(new Rect(0, 0, 1280, 720));
        assertEquals(new Rect(0, 0, 1280, 720), portalRegion(video).getBounds());

        Rect panel = new Rect(0, 0, 833, 720);
        ActivityViewSurface maps = surface(ActivityViewSurface.PreviewInput.FORWARDED);
        maps.show(panel);
        assertEquals(panel, portalRegion(maps).getBounds());
        maps.restorePreview(1, 0, 0, panel);
        assertEquals(panel, portalRegion(maps).getBounds());
    }

    private ActivityViewSurface surface(ActivityViewSurface.PreviewInput input) throws ReflectiveOperationException {
        ActivityView view = new ActivityView(host);
        // What ActivityView holds once its SurfaceView is created.
        Field root = ActivityView.class.getDeclaredField("mRootSurfaceControl");
        root.setAccessible(true);
        root.set(view, new SurfaceControl.Builder().setName("Road Home test display").build());
        // And the card's own surface, which a preview returns to.
        Field parent = ActivityView.class.getDeclaredField("mSurfaceView");
        parent.setAccessible(true);
        Field card = SurfaceView.class.getDeclaredField("mSurfaceControl");
        card.setAccessible(true);
        card.set(parent.get(view), new SurfaceControl.Builder().setName("Road Home test card").build());
        return new ActivityViewSurface(view, input);
    }

    private static Region portalRegion(ActivityViewSurface surface) throws ReflectiveOperationException {
        Field field = ActivityViewSurface.class.getDeclaredField("touchableRegion");
        field.setAccessible(true);
        return (Region) field.get(surface);
    }
}
