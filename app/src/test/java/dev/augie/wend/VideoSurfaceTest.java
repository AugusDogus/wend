package dev.augie.wend;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class VideoSurfaceTest {
    private static final class Scene {
        final Map<Integer, VideoSurface.Layer> layers = new HashMap<>();
        final VideoSurface.Layer host = layer(1, -1, "SurfaceView - " + BuildConfig.APPLICATION_ID + "/MainActivity#0");
        final VideoSurface.Layer root = layer(2, 1, "ActivityViewVirtualDisplay#0");
        final VideoSurface.Layer window = layer(3, 2, "YouTube window");
        final VideoSurface.Layer video = layer(4, 3, "SurfaceView - " + EmbeddedVideo.PACKAGE + "/MainActivity#0");
        Scene() {
            root.x = 109; root.y = 111;
            video.x = 109; video.y = 183;
            video.width = 832; video.height = 468; video.buffered = true;
        }
        private VideoSurface.Layer layer(int id, int parent, String name) {
            VideoSurface.Layer layer = new VideoSurface.Layer();
            layer.id = id; layer.parent = parent; layer.name = name;
            layers.put(id, layer);
            return layer;
        }
        VideoSurface.Bounds find() { return VideoSurface.find(layers, 1280, 720); }
    }

    @Test public void locatesVideoWithinHostedDisplay() {
        VideoSurface.Bounds bounds = new Scene().find();
        assertNotNull(bounds);
        assertEquals(0, bounds.left, .01);
        assertEquals(72, bounds.top, .01);
        assertEquals(832, bounds.width, .01);
        assertEquals(468, bounds.height, .01);
    }

    @Test public void removesLauncherScaleAndTranslationFromVideoCoordinates() {
        Scene scene = new Scene();
        scene.root.sx = .4f; scene.root.sy = .4f;
        scene.video.sx = .4f; scene.video.sy = .4f;
        scene.video.y = scene.root.y + 72 * .4f;
        VideoSurface.Bounds bounds = scene.find();
        assertNotNull(bounds);
        assertEquals(72, bounds.top, .01);
        assertEquals(832, bounds.width, .01);
    }

    @Test public void rejectsMainDisplayAndOtherLaunchers() {
        Scene scene = new Scene();
        scene.root.name = "Display Root#0";
        assertNull(scene.find());
        scene.root.name = "ActivityViewVirtualDisplay#0";
        scene.host.name = "SurfaceView - another.app/Activity#0";
        assertNull(scene.find());
    }

    @Test public void tracksWatchPageGeometryOnlyInsideOurActivePresentation() {
        Scene scene = new Scene();
        scene.host.name = EmbeddedVideo.WINDOW_TITLE + "#0";
        scene.root.x = 0; scene.root.y = 0;
        scene.video.x = 438; scene.video.y = 0;
        scene.video.sx = 405f / 1280; scene.video.sy = 228f / 720;
        scene.video.width = 1280; scene.video.height = 720;
        assertNull(scene.find());
        VideoSurface.Bounds bounds = VideoSurface.find(scene.layers, 1280, 720, true);
        assertNotNull(bounds);
        assertEquals(438, bounds.left, .01);
        assertEquals(405, bounds.width, .01);
        assertEquals(228, bounds.height, .01);
        scene.host.name = "another.app live video#0";
        assertNull(VideoSurface.find(scene.layers, 1280, 720, true));
    }

    @Test public void rejectsHiddenSecureAndProtectedVideoOrAncestors() {
        Scene scene = new Scene();
        scene.video.flags = 1;
        assertNull(scene.find());
        scene.video.flags = 0x80;
        assertNull(scene.find());
        scene.video.flags = 0;
        scene.video.protectedContent = true;
        assertNull(scene.find());
        scene.video.protectedContent = false;
        scene.window.flags = 0x80;
        assertNull(scene.find());
    }

    @Test public void rejectsMissingBufferRotationAndOutOfBoundsVideo() {
        Scene scene = new Scene();
        scene.video.buffered = false;
        assertNull(scene.find());
        scene.video.buffered = true;
        scene.video.crossX = 1;
        assertNull(scene.find());
        scene.video.crossX = 0;
        scene.video.x = 1000;
        assertNull(scene.find());
    }

    @Test public void rejectsParentCycles() {
        Scene scene = new Scene();
        scene.window.parent = scene.video.id;
        assertNull(scene.find());
    }

    @Test public void retainedDecoderBufferDoesNotCountAsPlayingUntilItsFramesAdvance() {
        Scene scene = new Scene();
        scene.video.frame = 50;
        VideoSurface.Bounds previous = scene.find();
        assertFalse(scene.find().advances(previous));
        scene.video.frame = 51;
        assertTrue(scene.find().advances(previous));
        scene.video.id = 5;
        assertFalse(scene.find().advances(previous));
    }

    @Test public void replacementDecoderWinsOverRetainedBufferAtTheSameSize() {
        Scene scene = new Scene();
        VideoSurface.Layer replacement = scene.layer(5, scene.video.parent, scene.video.name);
        replacement.x = scene.video.x; replacement.y = scene.video.y;
        replacement.width = scene.video.width; replacement.height = scene.video.height;
        replacement.buffered = true;
        replacement.frame = 1;
        assertEquals(5, scene.find().layerId);
    }
}
