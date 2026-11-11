package dev.augie.wend;

import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies transactions to surfaces under a window in the frame that window draws next, as Android
 * 10's SurfaceView keeps its surface in step with its window. Without it a surface moved by a
 * transaction runs a frame or more ahead of what the window draws for it in the same move.
 */
final class OverlayFrames {
    private static final String TAG = "Wend";
    private final Object viewRoot;
    private final Surface windowSurface;
    private final Method register;
    private final Class<?> callbackType;
    private final Method defer;
    /** Transactions waiting for a frame of the window, oldest first; guarded by this. */
    private final List<Waiting> waiting = new ArrayList<>();
    /** The number of transactions ever handed over to wait; guarded by this. */
    private long handedOver;

    private static final class Waiting {
        final long number;
        final SurfaceControl.Transaction transaction;
        final SurfaceControl[] layers;

        Waiting(long number, SurfaceControl.Transaction transaction, SurfaceControl[] layers) {
            this.number = number;
            this.transaction = transaction;
            this.layers = layers;
        }
    }

    private OverlayFrames(Object viewRoot, Surface windowSurface, Method register, Class<?> callbackType, Method defer) {
        this.viewRoot = viewRoot;
        this.windowSurface = windowSurface;
        this.register = register;
        this.callbackType = callbackType;
        this.defer = defer;
    }

    /**
     * @return the frames of {@code window}, which must be attached; or null if they cannot be
     *     waited for (drawn without hardware acceleration, or Android's internals differ)
     */
    static OverlayFrames of(View window) {
        if (!window.isHardwareAccelerated()) return null;
        try {
            Object viewRoot = View.class.getMethod("getViewRootImpl").invoke(window);
            if (viewRoot == null) return null;
            Class<?> rootType = Class.forName("android.view.ViewRootImpl");
            Field surface = rootType.getField("mSurface");
            Class<?> callbackType = Class.forName("android.graphics.HardwareRenderer$FrameDrawingCallback");
            Method register = rootType.getMethod("registerRtFrameCallback", callbackType);
            Method defer = SurfaceControl.Transaction.class.getMethod("deferTransactionUntilSurface",
                    SurfaceControl.class, Surface.class, long.class);
            return new OverlayFrames(viewRoot, (Surface) surface.get(viewRoot), register, callbackType, defer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w(TAG, "Cannot wait for the floating window's frames; its surface moves at once, and what"
                    + " the window draws over it can trail a drag by a frame", error);
            return null;
        }
    }

    /** @return whether a transaction still waits for the window's next frame */
    synchronized boolean waiting() { return !waiting.isEmpty(); }

    /**
     * Applies and closes {@code transaction}, which moves {@code layers}, as the window shows the
     * frame it draws next. The caller makes it draw one. If that cannot be arranged it applies now.
     */
    void applyWithNextFrame(SurfaceControl.Transaction transaction, SurfaceControl... layers) {
        long number;
        synchronized (this) {
            number = ++handedOver;
            waiting.add(new Waiting(number, transaction, layers));
        }
        try {
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            // A frame recorded before this transaction came does not take it.
                            case "onFrameDraw": applyWith((long) args[0], number); return null;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            default: return "Wend overlay frame callback";
                        }
                    });
            register.invoke(viewRoot, callback);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Could not wait for the floating window's next frame; moving its surface at once", error);
            applyNow();
        }
    }

    /** Applies whatever still waits, at once: before the surfaces change in any other way. */
    synchronized void applyNow() { applyWith(0, Long.MAX_VALUE); }

    /**
     * Applies the transactions handed over up to number {@code last}, in order: on the window's
     * render thread for the frame it draws now, or at once for a frame of 0.
     */
    private synchronized void applyWith(long frame, long last) {
        while (!waiting.isEmpty() && waiting.get(0).number <= last) {
            Waiting next = waiting.remove(0);
            try {
                if (frame > 0 && windowSurface.isValid()) {
                    for (SurfaceControl layer : next.layers) {
                        if (layer.isValid()) defer.invoke(next.transaction, layer, windowSurface, frame);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "Could not tie the floating surface to frame " + frame + "; moving it at once", error);
            }
            try { next.transaction.apply(); }
            catch (RuntimeException error) { Log.e(TAG, "Could not move the floating surface", error); }
            finally { next.transaction.close(); }
        }
    }
}
