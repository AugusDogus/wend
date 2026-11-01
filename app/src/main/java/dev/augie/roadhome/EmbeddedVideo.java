package dev.augie.roadhome;

import android.app.ActivityView;
import android.graphics.RectF;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps YouTube at normal display size and exposes only its decoder surface's region. */
final class EmbeddedVideo extends FrameLayout {
    static final String PACKAGE = BuildConfig.VIDEO_PACKAGE;
    private static final String PLAYER_UNAVAILABLE = "Player unavailable. Use Open YouTube below.";
    private enum State { EMPTY, WAITING, READY, PREPARING, SHOWN, FAILED, CLOSED }
    private State state = State.EMPTY;
    private ActivityView player;
    private ActivityViewSurface playerSurface;
    private final Activity host;
    private final SurfaceExpansion expansion;
    private final TextView status;
    private final ImageView thumbnail;
    private final Point displaySize = new Point();
    private final ExecutorService geometryReader = Executors.newSingleThreadExecutor();
    private final Runnable inspect = this::inspectSurface;
    private boolean hostActive;
    private VideoCardState presentation = VideoCardState.HIDDEN;
    private int generation;
    private long preparationStarted;
    private VideoSurface.Bounds previousBounds;

    EmbeddedVideo(Activity context) {
        super(context);
        host = context;
        expansion = new SurfaceExpansion(context);
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(displaySize);
        setBackgroundColor(android.graphics.Color.BLACK);
        setClipChildren(true);
        thumbnail = new ImageView(context);
        thumbnail.setBackgroundColor(android.graphics.Color.BLACK);
        thumbnail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(thumbnail, new LayoutParams(-1, -1));
        status = Ui.text(context, "", 14, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(android.graphics.Color.BLACK);
        addView(status, new LayoutParams(-1, -1));
        setVisibility(GONE);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int height = Math.round(MeasureSpec.getSize(widthSpec) * 9f / 16f);
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    void update(VideoCardState presentation, Bitmap artwork) {
        this.presentation = presentation;
        if (artwork != null) {
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImageBitmap(artwork);
        } else {
            thumbnail.setScaleType(ImageView.ScaleType.CENTER);
            thumbnail.setImageDrawable(new Ui.Symbol(Ui.Icon.PLAY, Ui.MUTED));
        }
        thumbnail.setVisibility(presentation == VideoCardState.THUMBNAIL ? VISIBLE : GONE);
        if (expansion.active()) return;
        if (presentation != VideoCardState.PLAYING) {
            cancelPreparation();
            if (state == State.SHOWN) state = State.READY;
            coverPlayer();
            status.setVisibility(GONE);
        } else if (state == State.FAILED) {
            status.setText(PLAYER_UNAVAILABLE);
            status.setVisibility(VISIBLE);
        }
        setVisibility(presentation == VideoCardState.HIDDEN ? GONE : VISIBLE);
        maybeLaunch();
    }

    void resume() {
        hostActive = true;
        if (expansion.active()) {
            expansion.cancel();
            state = State.READY;
            if (presentation != VideoCardState.PLAYING) {
                coverPlayer();
                status.setVisibility(GONE);
                setVisibility(presentation == VideoCardState.HIDDEN ? GONE : VISIBLE);
            }
        }
        maybeLaunch();
    }

    void suspend() {
        hostActive = false;
        expansion.cancel();
        cancelPreparation();
        if (state == State.SHOWN) state = State.READY;
        if (!expansion.handingOff()) coverPlayer();
    }

    boolean openFullscreen(Runnable open) {
        if (expansion.active()) return true;
        if (state != State.SHOWN || playerSurface == null || previousBounds == null
                || presentation != VideoCardState.PLAYING || !hostActive) return false;
        cancelPreparation();
        RectF source = new RectF(previousBounds.left, previousBounds.top,
                previousBounds.left + previousBounds.width, previousBounds.top + previousBounds.height);
        expansion.open(this, playerSurface, source, displaySize.x, displaySize.y, () -> {
            state = State.READY;
            open.run();
            // If Android rejects the launch, restore the preview instead of leaving a hidden surface.
            post(() -> { if (hostActive) maybeLaunch(); });
        });
        return true;
    }

    void fullscreenOpened() {
        // Run after the expansion restores its surface parent. The task is already on display 0.
        // Removing the now-empty virtual display clears Android 10's stale input-window entry.
        post(() -> {
            if (hostActive || state == State.CLOSED) return;
            cancelPreparation();
            if (player != null) {
                player.setCallback(null);
                player.release();
                removeView(player);
                player = null;
                playerSurface = null;
            }
            previousBounds = null;
            state = State.EMPTY;
        });
    }

    private void cancelPreparation() {
        generation++;
        removeCallbacks(inspect);
        if (state == State.PREPARING) state = State.READY;
    }

    private void coverPlayer() {
        status.setText("");
        status.setVisibility(VISIBLE);
        if (player != null) player.setClipBounds(new Rect());
        if (playerSurface != null) playerSurface.hide();
    }

    private void maybeLaunch() {
        if (expansion.active()) return;
        if (!hostActive || presentation != VideoCardState.PLAYING) return;
        if (state == State.EMPTY) {
            state = State.WAITING;
            try {
                player = new ActivityView(getContext());
                player.setPivotX(0);
                player.setPivotY(0);
                player.setClipBounds(new Rect());
                addView(player, 0, new LayoutParams(displaySize.x, displaySize.y));
                player.setCallback(new ActivityView.StateCallback() {
                    @Override public void onActivityViewReady(ActivityView view) {
                        try {
                            playerSurface = new ActivityViewSurface(view);
                            playerSurface.hide();
                            state = State.READY;
                            maybeLaunch();
                        } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
                    }
                    @Override public void onActivityViewDestroyed(ActivityView view) {
                        expansion.cancel();
                        playerSurface = null;
                        state = State.CLOSED;
                    }
                });
            } catch (RuntimeException | LinkageError error) { fail(error); }
            return;
        }
        if (state != State.READY || player == null) return;
        try {
            coverPlayer();
            state = State.PREPARING;
            preparationStarted = SystemClock.uptimeMillis();
            previousBounds = null;
            generation++;
            if (!VideoTask.embed(host, player.getVirtualDisplayId())) {
                fail(new IllegalStateException("Playing YouTube task was not found on the main display"));
                return;
            }
            inspectSurface();
        } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
    }

    private void inspectSurface() {
        if (!hostActive || (state != State.PREPARING && state != State.SHOWN)) return;
        int requestGeneration = generation;
        geometryReader.execute(() -> {
            try {
                VideoSurface.Bounds bounds = VideoSurface.read(displaySize.x, displaySize.y);
                post(() -> {
                    if (requestGeneration != generation || !hostActive) return;
                    applyBounds(bounds);
                });
            } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError error) {
                post(() -> {
                    if (requestGeneration == generation && hostActive) fail(error);
                });
            }
        });
    }

    private void applyBounds(VideoSurface.Bounds bounds) {
        if (player == null || playerSurface == null || (state != State.PREPARING && state != State.SHOWN)) return;
        if (bounds != null && sameBounds(bounds, previousBounds)
                && (state == State.SHOWN || bounds.advances(previousBounds))) {
            float scale = Math.min(getWidth() / bounds.width, getHeight() / bounds.height);
            float x = (getWidth() - bounds.width * scale) / 2 - bounds.left * scale;
            float y = (getHeight() - bounds.height * scale) / 2 - bounds.top * scale;
            Rect crop = new Rect(Math.round(bounds.left), Math.round(bounds.top),
                    Math.round(bounds.left + bounds.width), Math.round(bounds.top + bounds.height));
            player.setClipBounds(crop);
            try {
                // Scaling the View and revealing the surface use different frame schedules.
                // Commit scale, crop, position, input, and visibility atomically on the root.
                playerSurface.show(scale, x, y, crop);
            } catch (ReflectiveOperationException | RuntimeException error) {
                fail(error);
                return;
            }
            if (state != State.SHOWN) {
                state = State.SHOWN;
                status.setVisibility(View.GONE);
                Log.i("RoadHome", "Showing YouTube video surface without PiP: " + bounds.left + ","
                        + bounds.top + " " + bounds.width + "x" + bounds.height);
            }
        } else {
            coverPlayer();
            if (state == State.SHOWN) {
                state = State.PREPARING;
                preparationStarted = SystemClock.uptimeMillis();
            }
            if (SystemClock.uptimeMillis() - preparationStarted > 3000) {
                fail(new IllegalStateException("No stable YouTube video surface found on our display within 3000 ms"));
                return;
            }
        }
        previousBounds = bounds;
        postDelayed(inspect, state == State.SHOWN ? 500 : 100);
    }

    private static boolean sameBounds(VideoSurface.Bounds a, VideoSurface.Bounds b) {
        return b != null && Math.abs(a.left - b.left) < 1 && Math.abs(a.top - b.top) < 1
                && Math.abs(a.width - b.width) < 1 && Math.abs(a.height - b.height) < 1;
    }

    private void fail(Throwable error) {
        cancelPreparation();
        state = State.FAILED;
        coverPlayer();
        status.setText(PLAYER_UNAVAILABLE);
        Log.e("RoadHome", "Could not embed the playing YouTube video surface", error);
    }

    void release() {
        hostActive = false;
        expansion.cancel();
        cancelPreparation();
        geometryReader.shutdownNow();
        if (player != null) {
            player.setCallback(null);
            player.release();
            playerSurface = null;
        }
        state = State.CLOSED;
    }
}
