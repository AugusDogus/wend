package dev.augie.roadhome;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.proto.ProtoInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Reads geometry, never pixels, from Android 10's SurfaceFlinger layer proto. */
final class VideoSurface {
    // AOSP frameworks/native/services/surfaceflinger/layerproto/layers.proto, Android 10.
    private static final long MESSAGE = 0x10b00000000L;
    private static final long INTEGER = 0x10500000000L;
    private static final long FLOAT = 0x10200000000L;
    private static final long BOOLEAN = 0x10800000000L;
    private static final long STRING = 0x10900000000L;

    static final class Bounds {
        final float left, top, width, height;
        final int layerId;
        final long frame;
        Bounds(float left, float top, float width, float height, int layerId, long frame) {
            this.left = left; this.top = top; this.width = width; this.height = height;
            this.layerId = layerId; this.frame = frame;
        }

        boolean advances(Bounds previous) {
            return previous != null && layerId == previous.layerId && frame > previous.frame;
        }
    }

    static final class Layer {
        int id, parent = -1, flags, transformType, width, height;
        String name = "";
        float x, y, sx = 1, sy = 1, crossX, crossY;
        boolean buffered, protectedContent;
        long frame;
    }

    private VideoSurface() { }

    static Bounds read(int displayWidth, int displayHeight) throws IOException, ReflectiveOperationException {
        return read(displayWidth, displayHeight, false);
    }

    static Bounds read(int displayWidth, int displayHeight, boolean presenting) throws IOException, ReflectiveOperationException {
        Object service = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "SurfaceFlinger");
        if (!(service instanceof IBinder)) throw new IOException("SurfaceFlinger service unavailable");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor input = pipe[0]; ParcelFileDescriptor output = pipe[1]) {
            ((IBinder) service).dumpAsync(output.getFileDescriptor(), new String[]{"--proto"});
            output.close();
            try (ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                return find(readLayers(new ProtoInputStream(stream)), displayWidth, displayHeight, presenting);
            }
        } catch (android.os.RemoteException error) {
            throw new IOException("SurfaceFlinger layer query failed", error);
        }
    }

    private static Map<Integer, Layer> readLayers(ProtoInputStream proto) throws IOException {
        Map<Integer, Layer> layers = new HashMap<>();
        int field;
        while ((field = proto.nextField()) != -1) {
            if (field != 1) continue;
            long token = proto.start(0x20b00000001L);
            Layer layer = new Layer();
            while ((field = proto.nextField()) != -1) {
                switch (field) {
                    case 1: layer.id = proto.readInt(INTEGER | field); break;
                    case 2: layer.name = proto.readString(STRING | field); break;
                    case 11: readPosition(proto, layer); break;
                    case 13: readSize(proto, layer); break;
                    case 22: layer.flags = proto.readInt(0x10d00000000L | field); break;
                    case 23: readTransform(proto, layer); break;
                    case 25: layer.parent = proto.readInt(INTEGER | field); break;
                    case 27: layer.buffered = true; break;
                    case 36: layer.protectedContent = proto.readBoolean(BOOLEAN | field); break;
                    case 37: layer.frame = proto.readLong(0x10400000025L); break;
                    default: break;
                }
            }
            proto.end(token);
            layers.put(layer.id, layer);
        }
        return layers;
    }

    private static void readPosition(ProtoInputStream proto, Layer layer) throws IOException {
        long token = proto.start(MESSAGE | 11);
        int field;
        while ((field = proto.nextField()) != -1) {
            if (field == 1) layer.x = proto.readFloat(FLOAT | field);
            if (field == 2) layer.y = proto.readFloat(FLOAT | field);
        }
        proto.end(token);
    }

    private static void readSize(ProtoInputStream proto, Layer layer) throws IOException {
        long token = proto.start(MESSAGE | 13);
        int field;
        while ((field = proto.nextField()) != -1) {
            if (field == 1) layer.width = proto.readInt(INTEGER | field);
            if (field == 2) layer.height = proto.readInt(INTEGER | field);
        }
        proto.end(token);
    }

    private static void readTransform(ProtoInputStream proto, Layer layer) throws IOException {
        long token = proto.start(MESSAGE | 23);
        int field;
        while ((field = proto.nextField()) != -1) {
            switch (field) {
                case 1: layer.sx = proto.readFloat(FLOAT | field); break;
                case 2: layer.crossX = proto.readFloat(FLOAT | field); break;
                case 3: layer.crossY = proto.readFloat(FLOAT | field); break;
                case 4: layer.sy = proto.readFloat(FLOAT | field); break;
                case 5: layer.transformType = proto.readInt(INTEGER | field); break;
                default: break;
            }
        }
        proto.end(token);
    }

    static Bounds find(Map<Integer, Layer> layers, int displayWidth, int displayHeight) {
        return find(layers, displayWidth, displayHeight, false);
    }

    static Bounds find(Map<Integer, Layer> layers, int displayWidth, int displayHeight, boolean presenting) {
        Bounds largest = null;
        for (Layer video : layers.values()) {
            if (!video.name.startsWith("SurfaceView - " + EmbeddedVideo.PACKAGE + "/")
                    || !video.buffered || !usable(video)) continue;
            Layer root = layers.get(video.parent);
            int depth = 0;
            while (root != null && depth++ < layers.size() && usable(root)
                    && !root.name.startsWith("ActivityViewVirtualDisplay#")) {
                root = layers.get(root.parent);
            }
            if (root == null || !usable(root) || !root.name.startsWith("ActivityViewVirtualDisplay#")) continue;
            // Require this virtual display to be hosted by Road Home, not some other app.
            Layer host = layers.get(root.parent);
            if (host == null || (!host.name.startsWith("SurfaceView - " + BuildConfig.APPLICATION_ID + "/")
                    && !(presenting && host.name.startsWith(LiveVideoWindow.TITLE + "#")))) continue;
            Bounds bounds = new Bounds((video.x - root.x) / root.sx, (video.y - root.y) / root.sy,
                    video.width * video.sx / root.sx, video.height * video.sy / root.sy, video.id, video.frame);
            if (!Float.isFinite(bounds.left) || !Float.isFinite(bounds.top)
                    || !Float.isFinite(bounds.width) || !Float.isFinite(bounds.height)
                    || bounds.width < 100 || bounds.height < 50 || bounds.left < -1 || bounds.top < -1
                    || bounds.left + bounds.width > displayWidth + 1
                    || bounds.top + bounds.height > displayHeight + 1) continue;
            float area = bounds.width * bounds.height;
            if (largest == null || area > largest.width * largest.height
                    || (area == largest.width * largest.height && bounds.layerId > largest.layerId)) largest = bounds;
        }
        return largest;
    }

    private static boolean usable(Layer layer) {
        return !layer.protectedContent && (layer.flags & 0x81) == 0
                && layer.crossX == 0 && layer.crossY == 0 && (layer.transformType & 2) == 0
                && layer.sx > 0 && layer.sy > 0;
    }
}
