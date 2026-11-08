package dev.augie.roadhome;

import android.app.ActivityView;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Process;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Android 10 ActivityView's own container surface, never a foreign app's surface. Not final only
 * so JVM tests can attach it to a window that has no compositor surface.
 */
class ActivityViewSurface {
    /** Where touches on the display's preview in Road Home's window go. */
    enum PreviewInput {
        /**
         * To the display's app through the bounded input portal, as for Maps in its panel. Only
         * right for an unscaled preview: Android 10 scales a portal's touch region and then offsets
         * it by its scaled frame, so a scaled preview's region misses it.
         */
        FORWARDED,
        /** None reach the app; a view of Road Home's above the preview takes them, as on the video card. */
        KEPT
    }

    private final SurfaceControl surface;
    private final PreviewInput previewInput;
    private final SurfaceView parent;
    private final Method crop;
    private final Method matrix;
    private final Method position;
    private final Method reparent;
    private final Method surfaceControl;
    private final Method setInput;
    /** Hidden Android 10 SurfaceFlinger corner radius; null leaves square corners. */
    private final Method cornerRadius;
    private final Object portal;
    private final Region touchableRegion;
    private final Rect cropped = new Rect();

    ActivityViewSurface(ActivityView view, PreviewInput previewInput) throws ReflectiveOperationException {
        this.previewInput = previewInput;
        Field field = ActivityView.class.getDeclaredField("mRootSurfaceControl");
        field.setAccessible(true);
        Object value = field.get(view);
        if (!(value instanceof SurfaceControl)) {
            throw new IllegalStateException("ActivityView has no initialized container surface");
        }
        surface = (SurfaceControl) value;
        Field parentField = ActivityView.class.getDeclaredField("mSurfaceView");
        parentField.setAccessible(true);
        Object parentValue = parentField.get(view);
        if (!(parentValue instanceof SurfaceView)) {
            throw new IllegalStateException("ActivityView has no surface parent");
        }
        parent = (SurfaceView) parentValue;
        crop = SurfaceControl.Transaction.class.getMethod("setWindowCrop", SurfaceControl.class, Rect.class);
        matrix = SurfaceControl.Transaction.class.getMethod("setMatrix", SurfaceControl.class,
                float.class, float.class, float.class, float.class);
        position = SurfaceControl.Transaction.class.getMethod("setPosition", SurfaceControl.class, float.class, float.class);
        reparent = SurfaceControl.Transaction.class.getMethod("reparent", SurfaceControl.class, SurfaceControl.class);
        surfaceControl = SurfaceView.class.getMethod("getSurfaceControl");
        cornerRadius = optionalCornerRadius();
        Class<?> handle = Class.forName("android.view.InputWindowHandle");
        portal = handle.getConstructor(Class.forName("android.view.InputApplicationHandle"),
                Class.forName("android.view.IWindow"), int.class).newInstance(null, null, -1);
        // Match Android 10 DisplayContent.createPortalWindowHandle, but limit its
        // touch region to the visible video. Otherwise the full-size portal steals Maps taps.
        handle.getField("name").set(portal, "Road Home video portal");
        handle.getField("token").set(portal, new Binder());
        handle.getField("layoutParamsFlags").setInt(portal, WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        handle.getField("scaleFactor").setFloat(portal, 1);
        handle.getField("ownerPid").setInt(portal, Process.myPid());
        handle.getField("ownerUid").setInt(portal, Process.myUid());
        handle.getField("portalToDisplayId").setInt(portal, view.getVirtualDisplayId());
        Object region = handle.getField("touchableRegion").get(portal);
        if (!(region instanceof Region)) throw new IllegalStateException("Video input portal has no touch region");
        touchableRegion = (Region) region;
        setInput = SurfaceControl.Transaction.class.getMethod("setInputWindowInfo", SurfaceControl.class, handle);
    }

    private static Method optionalCornerRadius() {
        try {
            return SurfaceControl.Transaction.class.getMethod("setCornerRadius", SurfaceControl.class, float.class);
        } catch (NoSuchMethodException error) {
            Log.w("RoadHomeSurface", "SurfaceFlinger corner radius is unavailable; floating video keeps square corners", error);
            return null;
        }
    }

    /** The compositor surface of the window that hosts {@code view}. */
    static SurfaceControl windowSurface(View view) throws ReflectiveOperationException {
        Object root = View.class.getMethod("getViewRootImpl").invoke(view);
        Object target = Class.forName("android.view.ViewRootImpl").getMethod("getSurfaceControl").invoke(root);
        if (!(target instanceof SurfaceControl) || !((SurfaceControl) target).isValid()) {
            throw new IllegalStateException("Overlay window has no compositor surface yet");
        }
        return (SurfaceControl) target;
    }

    /** Temporarily puts our live display above Maps; disables its input portal during motion. */
    void attach(View destination, float scale, float x, float y, Rect bounds)
            throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            reparent.invoke(transaction, surface, windowSurface(destination));
            SurfaceControl.Transaction.class.getMethod("setLayer", SurfaceControl.class, int.class)
                    .invoke(transaction, surface, 1);
            touchableRegion.setEmpty();
            updateInput(transaction);
            transform(transaction, scale, x, y, bounds);
            transaction.setAlpha(surface, 1).apply();
        }
    }

    /** Puts our live display in another window at {@code placement}, without input, in one frame. */
    void attach(View destination, SurfacePlacement placement, float radius, float alpha)
            throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            reparent.invoke(transaction, surface, windowSurface(destination));
            transaction.setLayer(surface, 1);
            touchableRegion.setEmpty();
            updateInput(transaction);
            place(transaction, placement, radius, alpha);
            transaction.apply();
        }
    }

    void transform(float scale, float x, float y, Rect bounds) throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transform(transaction, scale, x, y, bounds);
            transaction.setAlpha(surface, 1).apply();
        }
    }

    /** Queues a placement in a caller's transaction so other layers can move in the same frame. */
    void place(SurfaceControl.Transaction transaction, SurfacePlacement placement, float radius, float alpha)
            throws ReflectiveOperationException {
        placement.crop(cropped);
        transform(transaction, placement.scale, placement.x, placement.y, cropped);
        // SurfaceFlinger rounds the crop in layer space, so the radius is unscaled here.
        round(transaction, radius / placement.scale);
        transaction.setAlpha(surface, alpha);
    }

    private void transform(SurfaceControl.Transaction transaction, float scale, float x, float y, Rect bounds)
            throws ReflectiveOperationException {
        matrix.invoke(transaction, surface, scale, 0f, 0f, scale);
        position.invoke(transaction, surface, x, y);
        crop.invoke(transaction, surface, bounds);
    }

    private void round(SurfaceControl.Transaction transaction, float radius) throws ReflectiveOperationException {
        if (cornerRadius != null) cornerRadius.invoke(transaction, surface, radius);
    }

    void restore() throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            reparent(transaction, parent);
            SurfaceControl.Transaction.class.getMethod("setLayer", SurfaceControl.class, int.class)
                    .invoke(transaction, surface, 0);
            matrix.invoke(transaction, surface, 1f, 0f, 0f, 1f);
            position.invoke(transaction, surface, 0f, 0f);
            round(transaction, 0);
            transaction.setAlpha(surface, 0).apply();
        }
    }

    void restorePreview(float scale, float x, float y, Rect bounds) throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            reparent(transaction, parent);
            transaction.setLayer(surface, 0);
            transform(transaction, scale, x, y, bounds);
            round(transaction, 0);
            previewInput(bounds);
            updateInput(transaction);
            transaction.setAlpha(surface, 1).apply();
        }
    }

    /** Whether Road Home's window currently has the card surface a preview can return to. */
    boolean hostAvailable() throws ReflectiveOperationException {
        Object target = surfaceControl.invoke(parent);
        return target instanceof SurfaceControl && ((SurfaceControl) target).isValid();
    }

    /**
     * Hides the display without input. It returns to the card surface when Road Home has one;
     * otherwise ActivityView reparents it when that surface is next created.
     */
    void restoreHidden() throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            if (hostAvailable()) {
                reparent(transaction, parent);
                transaction.setLayer(surface, 0);
            }
            round(transaction, 0);
            touchableRegion.setEmpty();
            updateInput(transaction);
            transaction.setAlpha(surface, 0).apply();
        }
    }

    void input(Rect bounds) {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            touchableRegion.set(bounds);
            updateInput(transaction);
            transaction.apply();
        }
    }

    private void reparent(SurfaceControl.Transaction transaction, SurfaceView destination)
            throws ReflectiveOperationException {
        Object target = surfaceControl.invoke(destination);
        if (!(target instanceof SurfaceControl)) throw new IllegalStateException("Video animation surface unavailable");
        reparent.invoke(transaction, surface, target);
    }

    void hide() {
        // Alpha keeps layer geometry inspectable while preparation is covered.
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            touchableRegion.setEmpty();
            updateInput(transaction);
            transaction.setAlpha(surface, 0).apply();
        }
    }

    void show(Rect bounds) throws ReflectiveOperationException {
        show(1, 0, 0, bounds);
    }

    /** Reveal geometry and pixels together, without waiting for a separate View render frame. */
    void show(float scale, float x, float y, Rect bounds) throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transform(transaction, scale, x, y, bounds);
            previewInput(bounds);
            updateInput(transaction);
            transaction.setAlpha(surface, 1).apply();
        }
    }

    /** @param bounds the shown part of the display, in the display's own coordinates */
    private void previewInput(Rect bounds) {
        if (previewInput == PreviewInput.FORWARDED) touchableRegion.set(bounds);
        else touchableRegion.setEmpty();
    }

    private void updateInput(SurfaceControl.Transaction transaction) {
        try {
            setInput.invoke(transaction, surface, portal);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not constrain the video input portal to its card", error);
        }
    }
}
