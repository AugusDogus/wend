package dev.augie.roadhome;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.util.proto.ProtoInputStream;
import java.io.IOException;

/** Waits for Maps' window and tile buffers at the new width, never reading pixels. */
final class MapFrame implements AutoCloseable {
    interface Listener { void onReady(); }
    private volatile boolean closed;
    private volatile ParcelFileDescriptor reading;

    MapFrame(int width, Listener listener) {
        Thread worker = new Thread(() -> {
            long deadline = SystemClock.uptimeMillis() + 900;
            int settled = 0;
            try {
                while (!closed && SystemClock.uptimeMillis() < deadline) {
                    settled = ready(width) ? settled + 1 : 0;
                    if (settled >= 3) {
                        if (!closed) listener.onReady();
                        return;
                    }
                    Thread.sleep(40);
                }
            } catch (IOException | ReflectiveOperationException | RuntimeException error) {
                if (!closed) Log.w("RoadHome", "Maps frame readiness unavailable; using bounded transition deadline", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, "RoadHome-map-frame");
        worker.start();
    }

    private boolean ready(int width) throws IOException, ReflectiveOperationException {
        Object service = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "SurfaceFlinger");
        if (!(service instanceof IBinder)) throw new IOException("SurfaceFlinger unavailable");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor input = pipe[0]; ParcelFileDescriptor output = pipe[1]) {
            reading = input;
            if (closed) return false;
            ((IBinder) service).dumpAsync(output.getFileDescriptor(), new String[]{"--proto"});
            output.close();
            try (ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                return ready(new ProtoInputStream(stream), width);
            }
        } catch (android.os.RemoteException error) {
            throw new IOException("Maps frame query failed", error);
        } finally {
            reading = null;
        }
    }

    static boolean ready(ProtoInputStream proto, int width) throws IOException {
        int windowWidth = 0;
        float windowX = 0, tilesX = 0;
        boolean tiles = false;
        int field;
        while ((field = proto.nextField()) != -1) {
            if (field != 1) continue;
            long layer = proto.start(0x20b00000001L);
            String name = "";
            int flags = 0, bufferWidth = 0;
            float positionX = 0;
            boolean protectedContent = false;
            while ((field = proto.nextField()) != -1) {
                switch (field) {
                    case 2: name = proto.readString(0x10900000002L); break;
                    case 11:
                        long position = proto.start(0x10b0000000bL);
                        while ((field = proto.nextField()) != -1) {
                            if (field == 1) positionX = proto.readFloat(0x10200000001L);
                        }
                        proto.end(position);
                        break;
                    case 22: flags = proto.readInt(0x10d00000016L); break;
                    case 27:
                        long buffer = proto.start(0x10b0000001bL);
                        int component;
                        while ((component = proto.nextField()) != -1) {
                            if (component == 1) bufferWidth = proto.readInt(0x10d00000001L);
                        }
                        proto.end(buffer);
                        break;
                    case 36: protectedContent = proto.readBoolean(0x10800000024L); break;
                    default: break;
                }
            }
            proto.end(layer);
            if (bufferWidth == 0 || (flags & 0x81) != 0 || protectedContent) continue;
            if (name.startsWith(EmbeddedMaps.PACKAGE + "/")) {
                windowWidth = bufferWidth; windowX = positionX;
            }
            if (name.startsWith("SurfaceView - " + EmbeddedMaps.PACKAGE + "/") && bufferWidth == width) {
                tiles = true; tilesX = positionX;
            }
        }
        if (!tiles) return false;
        if (windowWidth == width) return true;
        // Freeform decor retains symmetric shadow insets while its content has already
        // resized. Compare content width, rather than waiting for the shadow buffer to vanish.
        float inset = tilesX - windowX;
        return inset > 0
                && Math.abs(windowWidth - width - 2 * inset) < 0.5f;
    }

    @Override public void close() {
        closed = true;
        ParcelFileDescriptor input = reading;
        if (input != null) {
            try { input.close(); }
            catch (IOException error) { Log.w("RoadHome", "Could not close Maps frame query", error); }
        }
    }
}
