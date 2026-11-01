package dev.augie.roadhome;

import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MapFrameTest {
    @Test public void waitsForBothWindowAndTileBuffersToResize() throws IOException {
        assertFalse(ready(833, 833, 0, 0));
        assertFalse(ready(1280, 833, 0, 0));
        assertTrue(ready(1280, 1280, 0, 0));
    }

    @Test public void hiddenAndSecureBuffersAreNotReady() throws IOException {
        assertFalse(ready(1280, 1280, 1, 0));
        assertFalse(ready(1280, 1280, 128, 0));
    }

    @Test public void normalQueuedAnimationFramesDoNotBlockAResizedWindow() throws IOException {
        assertTrue(ready(1280, 1280, 0, 1));
    }

    private static boolean ready(int windowWidth, int tileWidth, int flags, int queued) throws IOException {
        ProtoOutputStream proto = new ProtoOutputStream();
        layer(proto, EmbeddedMaps.PACKAGE + "/MapsActivity#0", windowWidth, 0, 0);
        layer(proto, "SurfaceView - " + EmbeddedMaps.PACKAGE + "/MapsActivity#0", tileWidth, flags, queued);
        return MapFrame.ready(new ProtoInputStream(new ByteArrayInputStream(proto.getBytes())), 1280);
    }

    private static void layer(ProtoOutputStream proto, String name, int width, int flags, int queued) {
        long layer = proto.start(0x20b00000001L);
        proto.write(0x10900000002L, name);
        proto.write(0x10d00000016L, flags);
        long buffer = proto.start(0x10b0000001bL);
        proto.write(0x10d00000001L, width);
        proto.end(buffer);
        proto.write(0x1050000001cL, queued);
        proto.end(layer);
    }
}
