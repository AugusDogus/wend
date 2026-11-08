package dev.augie.roadhome;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Limits a full-screen overlay window's touches to one rectangle so the app below keeps the
 * rest. Uses Android 10's hidden internal-insets listener, as system floating windows do.
 */
final class OverlayTouchRegion {
    private static final int TOUCHABLE_INSETS_FRAME = 0;
    private static final int TOUCHABLE_INSETS_REGION = 3;
    private final View view;
    private final Object listener;
    private final Method add;
    private final Method remove;
    private final Method setTouchableInsets;
    private final Field touchableRegion;
    private final Rect bounds = new Rect();
    private boolean whole = true;

    OverlayTouchRegion(View view) throws ReflectiveOperationException {
        this.view = view;
        Class<?> type = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
        Class<?> info = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
        add = ViewTreeObserver.class.getMethod("addOnComputeInternalInsetsListener", type);
        remove = ViewTreeObserver.class.getMethod("removeOnComputeInternalInsetsListener", type);
        setTouchableInsets = info.getMethod("setTouchableInsets", int.class);
        touchableRegion = info.getField("touchableRegion");
        listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onComputeInternalInsets": compute(args[0]); return null;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "Road Home overlay touch region " + (whole ? "whole window" : bounds);
                default: throw new UnsupportedOperationException("Unexpected insets listener call " + method);
            }
        });
        add.invoke(view.getViewTreeObserver(), listener);
    }

    /** The whole window takes touches, as an ordinary overlay does. */
    void whole() { whole = true; view.invalidate(); }

    /** Only {@code area} takes touches; an empty area passes every touch through. */
    void only(Rect area) {
        whole = false;
        bounds.set(area);
        // ViewRootImpl recomputes and sends internal insets on its next traversal.
        view.invalidate();
    }

    void release() {
        try { remove.invoke(view.getViewTreeObserver(), listener); }
        catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not remove the overlay touch region listener", error);
        }
    }

    private void compute(Object insets) throws IllegalAccessException, InvocationTargetException {
        setTouchableInsets.invoke(insets, whole ? TOUCHABLE_INSETS_FRAME : TOUCHABLE_INSETS_REGION);
        Object region = touchableRegion.get(insets);
        if (!(region instanceof Region)) throw new IllegalStateException("Internal insets have no touch region");
        if (whole) ((Region) region).setEmpty();
        else ((Region) region).set(bounds);
    }
}
