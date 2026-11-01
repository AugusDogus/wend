package dev.augie.roadhome;

import android.app.ActivityView;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Process;
import android.view.SurfaceControl;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Android 10 ActivityView's own container surface, never a foreign app's surface. */
final class ActivityViewSurface {
    private final SurfaceControl surface;
    private final Method crop;
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
        crop = SurfaceControl.Transaction.class.getMethod("setWindowCrop", SurfaceControl.class, Rect.class);
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

    void hide() {
        // Alpha keeps layer geometry inspectable while preparation is covered.
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            touchableRegion.setEmpty();
            updateInput(transaction);
            transaction.setAlpha(surface, 0).apply();
        }
    }

    void show(Rect bounds) throws ReflectiveOperationException {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            crop.invoke(transaction, surface, bounds);
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
