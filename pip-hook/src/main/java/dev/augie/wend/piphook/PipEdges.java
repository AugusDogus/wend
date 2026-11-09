package dev.augie.wend.piphook;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceControl;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Runs in system_server. Cuts the PiP's app off at the PiP's bounds and draws the PiP's shadow
 * itself.
 *
 * <p>Android 10 grows the pinned stack by a margin on each side, and the app's window surface
 * reaches into it, so the app can draw its own shadow there. But SurfaceFlinger keeps showing
 * SurfaceView layers an app has removed (YouTube removes its player's layer each time it moves
 * between displays; the layer stays, frozen, behind its window), and in the margin one showed as a
 * thick blurred border around the PiP. So each task in the pinned stack is cropped to the PiP's
 * bounds, which cuts the app's shadow too, and a shadow drawn here sits in the margin under it.
 */
final class PipEdges {
    /** Shadows by pinned TaskStack; guarded by the class. */
    private static final Map<Object, Shadow> shadows = new WeakHashMap<>();
    /** Tasks this crops; guarded by the class. */
    private static final Set<Object> cropped = Collections.newSetFromMap(new WeakHashMap<>());

    private PipEdges() { }

    static void install(ClassLoader loader) {
        Class<?> taskStack = XposedHelpers.findClass("com.android.server.wm.TaskStack", loader);
        // Android sizes the stack here on every bounds or configuration change, including each
        // step of the PiP's bounds animation and a change into or out of PiP.
        XposedHelpers.findAndHookMethod(taskStack, "updateSurfaceSize", SurfaceControl.Transaction.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    update(param.thisObject, (SurfaceControl.Transaction) param.args[0]);
                } catch (Throwable error) {
                    XposedBridge.log(PipHook.TAG + ": could not fit the PiP's edges to its bounds: " + error);
                }
            }
        });
        XposedHelpers.findAndHookMethod(taskStack, "removeImmediately", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Shadow shadow;
                synchronized (PipEdges.class) { shadow = shadows.remove(param.thisObject); }
                if (shadow != null) shadow.remove();
            }
        });
        // A task joining the pinned stack after Android sized it (as the PiP starts), or leaving it
        // for another stack (as into a Wend panel).
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.Task", loader), "onParentChanged", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object task = param.thisObject;
                    SurfaceControl.Transaction t = (SurfaceControl.Transaction) XposedHelpers.callMethod(task, "getPendingTransaction");
                    Object stack = XposedHelpers.getObjectField(task, "mStack");
                    if (stack != null && (boolean) XposedHelpers.callMethod(stack, "inPinnedWindowingMode")) update(stack, t);
                    else uncrop(task, t);
                } catch (Throwable error) {
                    XposedBridge.log(PipHook.TAG + ": could not fit a task moving into or out of the PiP: " + error);
                }
            }
        });
        XposedBridge.log(PipHook.TAG + ": pip edges hook installed");
    }

    private static void update(Object stack, SurfaceControl.Transaction t) {
        List<?> tasks = (List<?>) XposedHelpers.getObjectField(stack, "mChildren");
        if (!(boolean) XposedHelpers.callMethod(stack, "inPinnedWindowingMode")) {
            Shadow shadow;
            synchronized (PipEdges.class) { shadow = shadows.remove(stack); }
            if (shadow != null) shadow.remove();
            for (Object task : tasks) uncrop(task, t);
            return;
        }
        SurfaceControl surface = (SurfaceControl) XposedHelpers.getObjectField(stack, "mSurfaceControl");
        if (surface == null) return;
        Rect bounds = (Rect) XposedHelpers.callMethod(stack, "getDisplayedBounds");
        int margin = (int) XposedHelpers.callMethod(stack, "getStackOutset");
        // The stack's surface starts the margin above and left of its bounds; its tasks start there too.
        Rect content = new Rect(margin, margin, margin + bounds.width(), margin + bounds.height());
        for (Object task : tasks) {
            SurfaceControl taskSurface = (SurfaceControl) XposedHelpers.callMethod(task, "getSurfaceControl");
            if (taskSurface == null) continue;
            XposedHelpers.callMethod(t, "setWindowCrop", taskSurface, new Rect(content));
            synchronized (PipEdges.class) { cropped.add(task); }
        }
        Shadow shadow;
        synchronized (PipEdges.class) {
            shadow = shadows.get(stack);
            if (shadow == null && margin > 0) {
                shadow = Shadow.make(stack, margin);
                if (shadow != null) shadows.put(stack, shadow);
            }
        }
        if (shadow != null) shadow.place(t, bounds.width(), bounds.height());
    }

    private static void uncrop(Object task, SurfaceControl.Transaction t) {
        synchronized (PipEdges.class) { if (!cropped.remove(task)) return; }
        SurfaceControl taskSurface = (SurfaceControl) XposedHelpers.callMethod(task, "getSurfaceControl");
        // An empty crop is no crop.
        if (taskSurface != null) XposedHelpers.callMethod(t, "setWindowCrop", taskSurface, new Rect());
    }

    /**
     * A soft shadow around the PiP, in the stack's margin, under its task. It is cut from one
     * picture of a shadowed square into four corners and four one-pixel edges stretched along the
     * PiP's sides, so the blur keeps its width as the PiP resizes. Only its empty container is made
     * on the window manager's thread; the parts are drawn on {@link MotionThread} (about 15 ms that
     * would otherwise hold the window manager as the PiP starts) and show from the next placement.
     */
    private static final class Shadow {
        /** How dark the shadow is next to the PiP; close to Android's own 5dp PiP elevation. */
        private static final int ALPHA = 0x50;
        /** The shadow picture for each margin; guarded by {@link PipEdges}. */
        private static final Map<Integer, Bitmap> pictures = new java.util.HashMap<>();

        private final SurfaceControl container;
        private final int margin;
        /**
         * In order: top-left, top-right, bottom-left, bottom-right corners; top, bottom, left,
         * right edges. Null until drawn. This and the fields below are guarded by {@link PipEdges}.
         */
        private SurfaceControl[] parts;
        private int width;
        private int height;
        private boolean removed;

        private Shadow(SurfaceControl container, int margin) {
            this.container = container;
            this.margin = margin;
        }

        /** @return null if Android could not make it; nothing is left behind then */
        static Shadow make(Object stack, int margin) {
            try {
                SurfaceControl.Builder builder = (SurfaceControl.Builder) XposedHelpers.callMethod(stack, "makeChildSurface", (Object) null);
                XposedHelpers.callMethod(builder, "setContainerLayer");
                SurfaceControl container = builder.setName("WendPipShadow").build();
                new SurfaceControl.Transaction().setLayer(container, Integer.MIN_VALUE).setVisibility(container, true).apply();
                Shadow shadow = new Shadow(container, margin);
                MotionThread.get().post(shadow::draw);
                return shadow;
            } catch (Throwable error) {
                XposedBridge.log(PipHook.TAG + ": could not make the PiP's shadow; the PiP shows without one: " + error);
                return null;
            }
        }

        private void draw() {
            int m = margin;
            // Where each part comes from in the picture: the shadowed square spans m..5m of 6m.
            Rect[] from = {
                    new Rect(0, 0, 2 * m, 2 * m), new Rect(4 * m, 0, 6 * m, 2 * m),
                    new Rect(0, 4 * m, 2 * m, 6 * m), new Rect(4 * m, 4 * m, 6 * m, 6 * m),
                    new Rect(3 * m, 0, 3 * m + 1, 2 * m), new Rect(3 * m, 4 * m, 3 * m + 1, 6 * m),
                    new Rect(0, 3 * m, 2 * m, 3 * m + 1), new Rect(4 * m, 3 * m, 6 * m, 3 * m + 1),
            };
            SurfaceControl[] drawn = new SurfaceControl[from.length];
            try {
                Bitmap picture = picture(m);
                for (int i = 0; i < from.length; i++) {
                    drawn[i] = new SurfaceControl.Builder().setParent(container).setName("WendPipShadowPart")
                            .setBufferSize(from[i].width(), from[i].height()).setFormat(PixelFormat.TRANSLUCENT).build();
                    Surface surface = new Surface(drawn[i]);
                    try {
                        Canvas canvas = surface.lockCanvas(null);
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                        canvas.drawBitmap(picture, -from[i].left, -from[i].top, null);
                        surface.unlockCanvasAndPost(canvas);
                    } finally {
                        surface.release();
                    }
                }
            } catch (Throwable error) {
                XposedBridge.log(PipHook.TAG + ": could not draw the PiP's shadow; the PiP shows without one: " + error);
                release(drawn);
                return;
            }
            synchronized (PipEdges.class) {
                if (removed) {
                    release(drawn);
                    return;
                }
                parts = drawn;
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                for (SurfaceControl part : drawn) t.setVisibility(part, true);
                placeParts(t);
                t.apply();
            }
        }

        /** A square from m to 5m in a 6m picture, with a soft shadow falling slightly down. */
        private static Bitmap picture(int m) {
            synchronized (PipEdges.class) {
                Bitmap picture = pictures.get(m);
                if (picture != null) return picture;
                picture = Bitmap.createBitmap(6 * m, 6 * m, Bitmap.Config.ARGB_8888);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.argb(ALPHA, 0, 0, 0));
                paint.setMaskFilter(new BlurMaskFilter(0.6f * m, BlurMaskFilter.Blur.NORMAL));
                float drop = 0.2f * m;
                new Canvas(picture).drawRect(m, m + drop, 5 * m, 5 * m + drop, paint);
                pictures.put(m, picture);
                return picture;
            }
        }

        /** Fits the shadow around a {@code width} x {@code height} PiP whose top left is at (margin, margin). */
        void place(SurfaceControl.Transaction t, int width, int height) {
            synchronized (PipEdges.class) {
                this.width = width;
                this.height = height;
                if (parts != null) placeParts(t);
            }
        }

        /** Guarded by {@link PipEdges}. */
        private void placeParts(SurfaceControl.Transaction t) {
            int m = margin;
            float across = Math.max(0, width - 2 * m);
            float down = Math.max(0, height - 2 * m);
            float[][] at = {
                    {0, 0, 1, 1}, {width, 0, 1, 1}, {0, height, 1, 1}, {width, height, 1, 1},
                    {2 * m, 0, across, 1}, {2 * m, height, across, 1},
                    {0, 2 * m, 1, down}, {width, 2 * m, 1, down},
            };
            for (int i = 0; i < parts.length; i++) {
                XposedHelpers.callMethod(t, "setPosition", parts[i], at[i][0], at[i][1]);
                XposedHelpers.callMethod(t, "setMatrix", parts[i], at[i][2], 0f, 0f, at[i][3]);
            }
        }

        void remove() {
            SurfaceControl[] drawn;
            synchronized (PipEdges.class) {
                removed = true;
                drawn = parts;
                parts = null;
            }
            try {
                new SurfaceControl.Transaction().reparent(container, null).apply();
                container.release();
            } catch (Throwable error) {
                XposedBridge.log(PipHook.TAG + ": could not remove the PiP's shadow: " + error);
            }
            release(drawn);
        }

        private static void release(SurfaceControl[] drawn) {
            if (drawn == null) return;
            for (SurfaceControl part : drawn) if (part != null) part.release();
        }
    }
}
