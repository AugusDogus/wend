package dev.augie.roadhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.util.Locale;

/**
 * A full-width glass sheet in its own window. It slides up over the launcher and follows a
 * downward drag from its header, or from content already scrolled to the top. Releasing past
 * a quarter of its height or flinging down dismisses it; otherwise it springs back. The close
 * button, a backdrop tap, Back and Home all use the same exit.
 */
final class BottomSheet {
    interface Listener {
        /** The window is gone; reset transient state such as a search query. */
        void onDismissed();

        /** Back was pressed; return true to handle it, for example by leaving a sub-page. */
        default boolean onBack() { return false; }

        /** The sheet started sliding away or is being removed; the launcher shows next. */
        default void onClosing() { }
    }

    private enum Phase { HIDDEN, CAPTURING, OPENING, OPEN, DRAGGING, SETTLING, CLOSING }

    // Vaul's drawer curve: a strong ease-out that settles softly.
    private static final TimeInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0, 1);
    private static final int ENTER_MS = 300;
    private static final int EXIT_MS = 200;
    private static final int MIN_EXIT_MS = 120;
    private static final int SETTLE_MS = 240;
    private static final int FROST_MS = 150;
    // A warm capture takes under 10 ms. Never hold the sheet back for a slow one.
    private static final long CAPTURE_DEADLINE_MS = 64;
    private static final int TOP_GAP_DP = 24;
    private static final int CORNER_DP = 16;
    private static final int HANDLE_ZONE_DP = 20;
    private static final int SCRIM = 0x66000000;

    private final Activity activity;
    private final String name;
    private final Listener listener;
    private final FrostedBackdrop backdrop;
    private final SheetDrag drag;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    /** Everything above and behind the sheet: the blurred capture under a dim. */
    private final ImageView scrim;
    private final SheetPanel panel;
    private final SheetGlass glass;
    private final int gap;
    private final int displayWidth;
    private final float travel;
    private final Runnable captureDeadline = () -> enter("the capture deadline passed");
    private Phase phase = Phase.HIDDEN;
    private ValueAnimator motion;
    private ValueAnimator frosting;
    /** Identifies the current opening, so a late capture from an earlier one is ignored. */
    private Object opening;
    private FrostedBackdrop.Capture capture;
    /** The capture was taken before the slide began, so it cannot contain the sheet. */
    private boolean cleanCapture;
    private long requestedAt;
    private float offset;
    private float dragOrigin;

    /** {@code backdrop} may be shared by several sheets; its owner releases it. */
    BottomSheet(Activity activity, FrostedBackdrop backdrop, SheetGlass.Density density, String name,
            View header, View body, Listener listener) {
        this.activity = activity;
        this.backdrop = backdrop;
        this.name = name;
        this.listener = listener;
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        drag = new SheetDrag(metrics.density);
        gap = Ui.dp(activity, TOP_GAP_DP);
        travel = metrics.heightPixels - gap;
        displayWidth = metrics.widthPixels;
        dialog = new Dialog(activity) {
            @Override public void onBackPressed() { if (!listener.onBack()) close(); }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Android delivers this asynchronously; it only matters if the system dismisses the dialog.
        dialog.setOnDismissListener(ignored -> reset());
        FrameLayout root = new FrameLayout(activity);
        scrim = new ImageView(activity);
        scrim.setScaleType(ImageView.ScaleType.MATRIX);
        scrim.setForeground(new ColorDrawable(SCRIM));
        scrim.setOnClickListener(v -> close());
        scrim.setContentDescription("Close " + name.toLowerCase(Locale.ROOT));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        panel = new SheetPanel(activity, body, new SheetPanel.Callback() {
            @Override public boolean onDragStart() { return startDrag(); }
            @Override public void onDrag(float distance) { setOffset(drag.follow(dragOrigin + distance)); }
            @Override public void onDragEnd(float velocity) { finishDrag(velocity); }
        });
        glass = new SheetGlass(metrics.widthPixels, metrics.heightPixels, density);
        panel.setBackground(glass);
        int corner = Ui.dp(activity, CORNER_DP);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        panel.setClipToOutline(true);
        // The panel extends below the screen so upward overdrag never reveals its bottom edge.
        int extension = Ui.dp(activity, (int) SheetDrag.OVERDRAG_DP + 8);
        panel.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), extension);
        panel.addView(handle(), new LinearLayout.LayoutParams(-1, Ui.dp(activity, HANDLE_ZONE_DP)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 48)));
        LinearLayout.LayoutParams bodyLayout = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyLayout.topMargin = Ui.dp(activity, 8);
        panel.addView(body, bodyLayout);
        FrameLayout.LayoutParams panelLayout = new FrameLayout.LayoutParams(-1, -1, Gravity.TOP);
        panelLayout.topMargin = gap;
        panelLayout.bottomMargin = -extension;
        root.addView(panel, panelLayout);
        dialog.setContentView(root);
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
        setOffset(travel);
    }

    void show() {
        if (phase != Phase.HIDDEN) return;
        Object current = new Object();
        opening = current;
        requestedAt = SystemClock.uptimeMillis();
        cancel(frosting);
        glass.setBackdrop(null);
        scrim.setImageDrawable(null);
        setFrost(0);
        setOffset(travel);
        phase = Phase.CAPTURING;
        // The window opens now with the sheet below the screen and a clear scrim, so the
        // capture can only see the launcher. The slide starts once the pixels are taken.
        capture = backdrop.capture(activity, new FrostedBackdrop.Listener() {
            @Override public void onCaptured() { if (opening == current) enter("the backdrop was captured"); }
            @Override public void onBlurred(Bitmap bitmap) { if (opening == current) frost(bitmap); }
            @Override public void onUnavailable() { if (opening == current) enter("the backdrop is unavailable"); }
        });
        main.postDelayed(captureDeadline, CAPTURE_DEADLINE_MS);
        showWindow();
        // A new window's first draw can hold the main thread for 50 ms or more, delaying the
        // onCaptured callback. A capture that is already taken by then can start the slide.
        panel.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                panel.getViewTreeObserver().removeOnPreDrawListener(this);
                if (opening == current && capture.isTaken()) enter("the backdrop was captured before the first frame");
                return true;
            }
        });
    }

    /** Slides the sheet away, then removes its window. */
    void close() {
        switch (phase) {
            case HIDDEN: case CLOSING: return;
            case CAPTURING: dismiss(); return;
            default: break;
        }
        hideKeyboard();
        listener.onClosing();
        long duration = Math.max(MIN_EXIT_MS, Math.round(EXIT_MS * (1 - offset / travel)));
        animateTo(travel, duration, Phase.CLOSING, this::dismiss);
    }

    /** Removes the sheet immediately, for example when an app opens over it. */
    void dismiss() {
        if (phase != Phase.HIDDEN && phase != Phase.CLOSING) listener.onClosing();
        if (dialog.isShowing()) dialog.dismiss();
        reset();
    }

    void release() { dismiss(); }

    private void showWindow() {
        if (BuildConfig.WINDOWED_MAPS && dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(0);
        window.setLayout(-1, -1);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void enter(String reason) {
        if (phase != Phase.CAPTURING) return;
        main.removeCallbacks(captureDeadline);
        cleanCapture = capture.isTaken();
        Log.i("RoadHome", name + " started opening " + (SystemClock.uptimeMillis() - requestedAt)
                + " ms after the request because " + reason);
        animateTo(0, ENTER_MS, Phase.OPENING, () -> phase = Phase.OPEN);
    }

    private void frost(Bitmap bitmap) {
        if (!cleanCapture) {
            Log.i("RoadHome", name + " backdrop capture finished after the slide began; "
                    + "keeping plain glass so the blur cannot include the sheet");
            return;
        }
        glass.setBackdrop(bitmap);
        scrim.setImageBitmap(bitmap);
        Matrix scale = new Matrix();
        float factor = displayWidth / (float) bitmap.getWidth();
        scale.setScale(factor, factor);
        scrim.setImageMatrix(scale);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setFrost(1);
            return;
        }
        frosting = ValueAnimator.ofFloat(0, 1);
        frosting.setDuration(FROST_MS);
        frosting.addUpdateListener(animation -> setFrost((float) animation.getAnimatedValue()));
        frosting.start();
    }

    private void setFrost(float value) {
        glass.setFrost(value);
        scrim.setImageAlpha(Math.round(255 * value));
    }

    private boolean startDrag() {
        if (phase == Phase.CLOSING || phase == Phase.CAPTURING || phase == Phase.HIDDEN) return false;
        cancel(motion);
        phase = Phase.DRAGGING;
        dragOrigin = offset;
        return true;
    }

    private void finishDrag(float velocity) {
        if (phase != Phase.DRAGGING) return;
        SheetDrag.Release decision = drag.release(offset, travel, velocity);
        float density = activity.getResources().getDisplayMetrics().density;
        Log.i("RoadHome", name + " released " + Math.round(100 * offset / travel) + "% down at "
                + Math.round(velocity / density) + " dp/s: " + decision);
        if (decision == SheetDrag.Release.DISMISS) {
            close();
        } else {
            animateTo(0, SETTLE_MS, Phase.SETTLING, () -> phase = Phase.OPEN);
        }
    }

    private void animateTo(float target, long duration, Phase during, Runnable done) {
        cancel(motion);
        phase = during;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setOffset(target);
            done.run();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(offset, target);
        animator.setDuration(duration);
        animator.setInterpolator(EASE);
        animator.addUpdateListener(animation -> setOffset((float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;
            @Override public void onAnimationCancel(Animator animation) { canceled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (motion == animation) motion = null;
                if (!canceled) done.run();
            }
        });
        motion = animator;
        animator.start();
    }

    private void setOffset(float value) {
        offset = value;
        panel.setTranslationY(value);
        scrim.setAlpha(SheetDrag.openness(value, travel));
        glass.setScreenTop(gap + value);
    }

    private void reset() {
        if (phase == Phase.HIDDEN) return;
        main.removeCallbacks(captureDeadline);
        cancel(motion);
        cancel(frosting);
        opening = null;
        phase = Phase.HIDDEN;
        setOffset(travel);
        listener.onDismissed();
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(panel.getWindowToken(), 0);
    }

    private View handle() {
        FrameLayout zone = new FrameLayout(activity);
        View pill = new View(activity);
        pill.setBackground(Ui.surface(activity, 0x66FFFFFF, 2));
        zone.addView(pill, new FrameLayout.LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 4), Gravity.CENTER));
        zone.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return zone;
    }

    private static void cancel(Animator animator) { if (animator != null) animator.cancel(); }
}
