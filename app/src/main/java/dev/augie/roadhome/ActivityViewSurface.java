package dev.augie.roadhome;

import android.app.ActivityView;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Process;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Android 10 ActivityView's own container surface, never a foreign app's surface. */
final class ActivityViewSurface {
    private final SurfaceControl surface;
    private final SurfaceView parent;
    private final Method crop;
    private final Method matrix;
    private final Method position;
    private final Method reparent;
    private final Method surfaceControl;
    private final Method setInput;
    private final Object portal;
    private final Region touchableRegion;

    ActivityViewSurface(ActivityView view) throws ReflectiveOperationException {
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

    /** Temporarily puts our live display above Maps; disables its input portal during motion. */
    void attach(View destination, float scale, float x, float y, Rect bounds)
            throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            Object root = View.class.getMethod("getViewRootImpl").invoke(destination);
            Object target = Class.forName("android.view.ViewRootImpl").getMethod("getSurfaceControl").invoke(root);
            if (!(target instanceof SurfaceControl)) throw new IllegalStateException("Video overlay has no surface");
            reparent.invoke(transaction, surface, target);
            SurfaceControl.Transaction.class.getMethod("setLayer", SurfaceControl.class, int.class)
                    .invoke(transaction, surface, 1);
            touchableRegion.setEmpty();
            updateInput(transaction);
            transform(transaction, scale, x, y, bounds);
            transaction.setAlpha(surface, 1).apply();
        }
    }

    void transform(float scale, float x, float y, Rect bounds) throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transform(transaction, scale, x, y, bounds);
            transaction.setAlpha(surface, 1).apply();
        }
    }

    private void transform(SurfaceControl.Transaction transaction, float scale, float x, float y, Rect bounds)
            throws ReflectiveOperationException {
        matrix.invoke(transaction, surface, scale, 0f, 0f, scale);
        position.invoke(transaction, surface, x, y);
        crop.invoke(transaction, surface, bounds);
    }

    void restore() throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            reparent(transaction, parent);
            SurfaceControl.Transaction.class.getMethod("setLayer", SurfaceControl.class, int.class)
                    .invoke(transaction, surface, 0);
            matrix.invoke(transaction, surface, 1f, 0f, 0f, 1f);
            position.invoke(transaction, surface, 0f, 0f);
            transaction.setAlpha(surface, 0).apply();
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
            touchableRegion.set(bounds);
            updateInput(transaction);
            transaction.setAlpha(surface, 1).apply();
        }
    }

    private void updateInput(SurfaceControl.Transaction transaction) {
        try {
            setInput.invoke(transaction, surface, portal);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not constrain the video input portal to its card", error);
        }
    }
}
