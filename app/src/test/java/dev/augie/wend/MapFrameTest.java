package dev.augie.wend;

import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MapFrameTest {
    private static final String MAPS = "com.google.android.apps.maps";
    private static final String FIXTURE = "dev.augie.fixture.maps";
    private static final String TEST_APP = "test.maps";
    private static final int PANEL = 833;
    private static final int SCREEN = 1280;

    /**
     * Each query is a SurfaceFlinger dump, which can take hundreds of milliseconds while Android
     * applies a display change. Buffers at the new size are already on screen, so one query that
     * shows them is enough.
     */
    @Test public void reportsMapsReadyOnTheFirstQueryThatShowsItsNewSize() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(1);
        try (MapFrame frame = new MapFrame(SCREEN, width -> queries.incrementAndGet() >= 2, ready::countDown)) {
            assertTrue("Maps was never reported ready", ready.await(5, TimeUnit.SECONDS));
        }
        assertEquals(2, queries.get());
    }

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

    @Test public void acceptsNewContentInsideTheExistingFreeformShadowBuffer() throws IOException {
        assertTrue(shadowReady(1400, 1280, 1280));
        assertTrue(shadowReady(953, 833, 833));
        assertFalse(shadowReady(1400, 833, 833));
    }

    /**
     * Real Google Maps in live guidance on the Android 10 emulator, 70% panel (833 px) and full
     * screen (1280 px). Only Maps' layers are kept from {@code dumpsys SurfaceFlinger --proto}.
     */
    @Test public void readsRealMapsInGuidanceAtThePanelAndFullScreenSizes() throws IOException {
        assertTrue(captured("guidance-panel.pb", PANEL));
        assertFalse(captured("guidance-panel.pb", SCREEN));
        assertFalse("The window has resized, the map has not drawn at the new size yet",
                captured("guidance-window-resized-map-not.pb", SCREEN));
        assertTrue(captured("guidance-fullscreen-on-panel-display.pb", SCREEN));
        assertFalse("Moved into the panel, Maps has not laid out at its size yet",
                captured("guidance-moved-to-panel-before-relayout.pb", PANEL));
    }

    /**
     * After its first return to the panel, real Maps keeps its earlier map surface, still holding
     * a full-screen buffer, beside the map surface it draws now. Only the newer one shows Maps.
     */
    @Test public void aStaleFullScreenMapSurfaceNeverMakesMapsLookReady() throws IOException {
        assertTrue(captured("guidance-panel-with-stale-fullscreen-map.pb", PANEL));
        assertFalse(captured("guidance-panel-with-stale-fullscreen-map.pb", SCREEN));
        assertFalse("The live map is still panel-sized under the full-screen window",
                captured("guidance-fullscreen-window-with-panel-map-and-stale-fullscreen-map.pb", SCREEN));
        assertTrue(captured("guidance-fullscreen-with-stale-fullscreen-map.pb", SCREEN));
    }

    /** The emulator's map fixture, which draws its map into one SurfaceView as Maps does. */
    @Test public void readsTheMapFixtureAtThePanelAndFullScreenSizes() throws IOException {
        assertTrue(captured("fixture-panel.pb", PANEL, FIXTURE));
        assertFalse(captured("fixture-panel.pb", SCREEN, FIXTURE));
        assertTrue("Below the status bar the map is shorter, but full width",
                captured("fixture-fullscreen.pb", SCREEN, FIXTURE));
        assertFalse(captured("fixture-fullscreen.pb", PANEL, FIXTURE));
    }

    @Test public void onlyAMapSurfaceInsideTheMapsWindowCounts() throws IOException {
        ProtoOutputStream proto = new ProtoOutputStream();
        layer(proto, 10, 1, TEST_APP + "/MapsActivity#0", 1280, 0, 0, 0);
        // Same name, but under another layer than the Maps window.
        layer(proto, 11, 99, "SurfaceView - " + TEST_APP + "/MapsActivity#0", 1280, 0, 0, 0);
        assertFalse(ready(proto, 1280));
    }

    private static boolean captured(String name, int width) throws IOException {
        return captured(name, width, MAPS);
    }

    private static boolean captured(String name, int width, String packageName) throws IOException {
        try (InputStream stream = MapFrameTest.class.getResourceAsStream("mapframe/" + name)) {
            assertNotNull("Missing captured layers " + name, stream);
            return MapFrame.ready(new ProtoInputStream(stream), width, packageName);
        }
    }

    private static boolean shadowReady(int windowWidth, int tileWidth, int target) throws IOException {
        ProtoOutputStream proto = new ProtoOutputStream();
        positionedLayer(proto, 10, 1, TEST_APP + "/MapsActivity#0", windowWidth, -60);
        positionedLayer(proto, 11, 10, "SurfaceView - " + TEST_APP + "/MapsActivity#0", tileWidth, 0);
        return MapFrame.ready(new ProtoInputStream(new ByteArrayInputStream(proto.getBytes())), target, TEST_APP);
    }

    private static void positionedLayer(ProtoOutputStream proto, int id, int parent, String name, int width, float x) {
        long layer = proto.start(0x20b00000001L);
        proto.write(0x10500000001L, id);
        proto.write(0x10900000002L, name);
        long position = proto.start(0x10b0000000bL);
        proto.write(0x10200000001L, x);
        proto.end(position);
        proto.write(0x10500000019L, parent);
        long buffer = proto.start(0x10b0000001bL);
        proto.write(0x10d00000001L, width);
        proto.end(buffer);
        proto.end(layer);
    }

    private static boolean ready(int windowWidth, int tileWidth, int flags, int queued) throws IOException {
        ProtoOutputStream proto = new ProtoOutputStream();
        layer(proto, 10, 1, TEST_APP + "/MapsActivity#0", windowWidth, 0, 0, 0);
        layer(proto, 11, 10, "SurfaceView - " + TEST_APP + "/MapsActivity#0", tileWidth, flags, queued, 0);
        return ready(proto, 1280);
    }

    private static boolean ready(ProtoOutputStream proto, int width) throws IOException {
        return MapFrame.ready(new ProtoInputStream(new ByteArrayInputStream(proto.getBytes())), width, TEST_APP);
    }

    private static void layer(ProtoOutputStream proto, int id, int parent, String name, int width, int flags,
            int queued, float x) {
        long layer = proto.start(0x20b00000001L);
        proto.write(0x10500000001L, id);
        proto.write(0x10900000002L, name);
        long position = proto.start(0x10b0000000bL);
        proto.write(0x10200000001L, x);
        proto.end(position);
        proto.write(0x10d00000016L, flags);
        proto.write(0x10500000019L, parent);
        long buffer = proto.start(0x10b0000001bL);
        proto.write(0x10d00000001L, width);
        proto.end(buffer);
        proto.write(0x1050000001cL, queued);
        proto.end(layer);
    }
}
