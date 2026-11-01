package dev.augie.roadhome;

import android.app.ActivityView;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.SurfaceControl;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Keeps the Android 10 map display awake while its host window is hidden. */
final class MapDisplayLifecycle implements SurfaceHolder.Callback {
    private final ActivityView view;
    private final SurfaceHolder.Callback original;
    private final Field rootSurface;
    private final Method clearInput;

    MapDisplayLifecycle(ActivityView view) throws ReflectiveOperationException {
        this.view = view;
        Field callback = ActivityView.class.getDeclaredField("mSurfaceCallback");
        callback.setAccessible(true);
        Object value = callback.get(view);
        if (!(value instanceof SurfaceHolder.Callback) || !(view.getChildAt(0) instanceof SurfaceView)) {
            throw new IllegalStateException("Android 10 ActivityView surface callbacks are unavailable");
        }
        original = (SurfaceHolder.Callback) value;
        rootSurface = ActivityView.class.getDeclaredField("mRootSurfaceControl");
        rootSurface.setAccessible(true);
        clearInput = ActivityView.class.getDeclaredMethod("cleanTapExcludeRegion");
        clearInput.setAccessible(true);
        SurfaceHolder holder = ((SurfaceView) view.getChildAt(0)).getHolder();
        holder.removeCallback(original);
        holder.addCallback(this);
    }

    // EmbeddedMaps gates this helper to Android 10 and platform UID 1000,
    // which is exempt from application hidden-API restrictions.
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    void retainTasksOnRemoval() throws ReflectiveOperationException {
        Field field = ActivityView.class.getDeclaredField("mVirtualDisplay");
        field.setAccessible(true);
        Object value = field.get(view);
        if (!(value instanceof VirtualDisplay)) throw new IllegalStateException("Map display is not initialized");
        VirtualDisplay originalDisplay = (VirtualDisplay) value;
        DisplayManager manager = view.getContext().getSystemService(DisplayManager.class);
        if (manager == null) throw new IllegalStateException("Android display service is unavailable");
        // Stock ActivityView destroys every task when its owner process exits.
        // Without that flag, Android returns tasks to the main display during
        // APK replacement, preserving the running Maps activity and route.
        VirtualDisplay replacement = manager.createVirtualDisplay("Road Home Maps",
                view.getWidth(), view.getHeight(), view.getResources().getDisplayMetrics().densityDpi,
                null, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        if (replacement == null) throw new IllegalStateException("Could not create the persistent map display");
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object session = global.getMethod("getWindowSession").invoke(null);
            Method getWindow = View.class.getDeclaredMethod("getWindow");
            getWindow.setAccessible(true);
            Object window = getWindow.invoke(view);
            int displayId = replacement.getDisplay().getDisplayId();
            Class.forName("android.view.IWindowSession").getMethod("reparentDisplayContent",
                    Class.forName("android.view.IWindow"), SurfaceControl.class, int.class)
                    .invoke(session, window, surface(), displayId);
            // Keep WindowManager's normal synchronization between display metrics
            // and configuration changes while the map panel resizes.
            field.set(view, replacement);
        } catch (ReflectiveOperationException | RuntimeException error) {
            replacement.release();
            throw error;
        }
        originalDisplay.release();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        original.surfaceCreated(holder);
        if (view.getVirtualDisplayId() == Display.INVALID_DISPLAY) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setAlpha(surface(), 1).apply();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        original.surfaceChanged(holder, format, width, height);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (view.getVirtualDisplayId() == Display.INVALID_DISPLAY) return;
        // ActivityView's original callback powers off the display. That pauses
        // Maps and discards its rendered window. Hide the container instead,
        // retaining the map task and buffers until the host surface returns.
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setAlpha(surface(), 0).apply();
            // Keep the IME transform too. Sending null while Maps remains
            // resumed triggers Android 10 InputMethodManager's null-Matrix bug.
            clearInput.invoke(view);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not suspend Maps input geometry", error);
        }
    }

    void release() {
        SurfaceHolder holder = ((SurfaceView) view.getChildAt(0)).getHolder();
        holder.removeCallback(this);
        holder.addCallback(original);
    }

    private SurfaceControl surface() {
        try {
            Object value = rootSurface.get(view);
            if (value instanceof SurfaceControl) return (SurfaceControl) value;
            throw new IllegalStateException("Map display has no container surface");
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Could not access the map display container", error);
        }
    }
}
