package dev.augie.wend;

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

/**
 * Decides what an Android 10 ActivityView display does while Wend's window is hidden.
 * The stock callback powers the display off, which stops the embedded task rendering.
 */
final class EmbeddedDisplayLifecycle implements SurfaceHolder.Callback {
    enum WhenHidden {
        /** Hide the container but keep the display on, retaining the task's rendered window. */
        STAY_AWAKE,
        /** Stock ActivityView behavior: power the display off until the host returns. */
        POWER_OFF
    }

    private final ActivityView view;
    private final WhenHidden whenHidden;
    private final SurfaceHolder holder;
    private final SurfaceHolder.Callback original;
    private final Field rootSurface;
    private final Method clearInput;
    private boolean hostSurface;
    private boolean applied;
    private boolean elsewhere;
    private Runnable hostReady;

    EmbeddedDisplayLifecycle(ActivityView view, WhenHidden whenHidden) throws ReflectiveOperationException {
        this.view = view;
        this.whenHidden = whenHidden;
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
        holder = ((SurfaceView) view.getChildAt(0)).getHolder();
        hostSurface = holder.getSurface() != null && holder.getSurface().isValid();
        applied = hostSurface;
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
        VirtualDisplay replacement = manager.createVirtualDisplay("Wend Maps",
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

    /**
     * Resizes the display while Wend's window is hidden: its layout, which otherwise sizes
     * the display, then runs no more. ActivityView sizes it to its view again when the window's
     * surface returns.
     * @return false, changing nothing, while Wend's window has a surface
     */
    boolean resizeWhileHidden(int width, int height) throws ReflectiveOperationException {
        if (hostSurface) return false;
        resize(width, height);
        return true;
    }

    /**
     * Resizes the display now, whether or not Wend's window has a surface. ActivityView sizes
     * the display to its view whenever that surface changes, so the caller gives the view the same
     * size.
     */
    void resize(int width, int height) throws ReflectiveOperationException {
        Field field = ActivityView.class.getDeclaredField("mVirtualDisplay");
        field.setAccessible(true);
        Object display = field.get(view);
        if (!(display instanceof VirtualDisplay)) throw new IllegalStateException("Map display is not initialized");
        ((VirtualDisplay) display).resize(width, height, view.getResources().getDisplayMetrics().densityDpi);
    }

    /**
     * Another window now parents the display's root surface. Until {@link #returnToHost},
     * host surface changes neither reparent, hide, nor power off the display.
     */
    void presentElsewhere() { elsewhere = true; }

    /** Runs once Wend's window has a surface again, immediately if it already has one. */
    void whenHostReady(Runnable ready) {
        hostReady = null;
        if (hostSurface) ready.run();
        else hostReady = ready;
    }

    /**
     * Applies the host surface state from while the display was presented elsewhere. A present
     * host is always re-announced: its surface and tap-exclude region may have been recreated.
     */
    void returnToHost() {
        if (!elsewhere) return;
        elsewhere = false;
        hostReady = null;
        if (hostSurface) created();
        else if (applied) destroyed();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        hostSurface = true;
        if (!elsewhere) created();
        Runnable ready = hostReady;
        hostReady = null;
        if (ready != null) ready.run();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        original.surfaceChanged(holder, format, width, height);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        hostSurface = false;
        if (!elsewhere) { destroyed(); return; }
        // The display keeps rendering into the other window; only the host's input geometry goes.
        clearInput();
    }

    private void created() {
        applied = true;
        original.surfaceCreated(holder);
        if (whenHidden != WhenHidden.STAY_AWAKE || view.getVirtualDisplayId() == Display.INVALID_DISPLAY) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setAlpha(surface(), 1).apply();
        }
    }

    private void destroyed() {
        applied = false;
        if (whenHidden == WhenHidden.POWER_OFF) { original.surfaceDestroyed(holder); return; }
        if (view.getVirtualDisplayId() == Display.INVALID_DISPLAY) return;
        // ActivityView's original callback powers off the display. That pauses
        // Maps and discards its rendered window. Hide the container instead,
        // retaining the map task and buffers until the host surface returns.
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setAlpha(surface(), 0).apply();
        }
        // Keep the IME transform too. Sending null while Maps remains
        // resumed triggers Android 10 InputMethodManager's null-Matrix bug.
        clearInput();
    }

    private void clearInput() {
        try { clearInput.invoke(view); }
        catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not suspend embedded display input geometry", error);
        }
    }

    void release() {
        hostReady = null;
        holder.removeCallback(this);
        holder.addCallback(original);
    }

    private SurfaceControl surface() {
        try {
            Object value = rootSurface.get(view);
            if (value instanceof SurfaceControl) return (SurfaceControl) value;
            throw new IllegalStateException("Embedded display has no container surface");
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Could not access the embedded display container", error);
        }
    }
}
