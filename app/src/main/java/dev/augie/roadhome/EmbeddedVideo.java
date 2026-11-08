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
    /** Names the live video's overlay window, whose compositor layer VideoSurface trusts. */
    static final String WINDOW_TITLE = BuildConfig.APPLICATION_ID + " live video";
    private static final String PLAYER_UNAVAILABLE = "Player unavailable. Use Open YouTube below.";
    private enum State { EMPTY, WAITING, READY, PREPARING, SHOWN, FAILED, CLOSED }
    private State state = State.EMPTY;
    private ActivityView player;
    private ActivityViewSurface playerSurface;
    private EmbeddedDisplayLifecycle playerLifecycle;
    private final Activity host;
    private final LiveSurfaceWindow window;
    private final TaskFrontWatcher appSwitches = new TaskFrontWatcher(this::appMovedToFront);
    private final Runnable presentationEnded;
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

    /** @param presentationEnded runs whenever a fullscreen or floating presentation has finished */
    EmbeddedVideo(Activity context, Runnable presentationEnded) {
        super(context);
        host = context;
        this.presentationEnded = presentationEnded;
        window = new LiveSurfaceWindow(context, WINDOW_TITLE, "RoadHomeVideo");
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
        if (window.active()) {
            // A paused floating video keeps its last frame; only a cleared or ended session removes it.
            if (window.floating() && presentation == VideoCardState.HIDDEN) {
                window.dismiss("its media session ended or cleared its title");
            }
            return;
        }
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

    /** Road Home is visible again, by Home or because the app covering it left. */
    void resume() {
        hostActive = true;
        window.hostShown();
        if (window.active()) {
            // A video floating above Maps returns to its card. A whole video above Road Home
            // stays until Home: Road Home also shows behind it when Maps leaves for its panel.
            if (window.floating()) returnToCard();
            return;
        }
        maybeLaunch();
    }

    /** Home returns any presentation to the card. */
    void home() {
        hostActive = true;
        window.hostShown();
        if (window.active()) returnToCard();
        else maybeLaunch();
    }

    private void returnToCard() {
        // Once Road Home's window can show the card again.
        if (playerLifecycle != null) playerLifecycle.whenHostReady(window::collapse);
        else window.collapse();
    }

    boolean presenting() { return window.active(); }

    void suspend() {
        hostActive = false;
        // Floating above the app that replaced Road Home: keep the window and keep playing.
        if (window.floating()) return;
        window.cancel();
        cancelPreparation();
        if (state == State.SHOWN) state = State.READY;
        coverPlayer();
    }

    boolean floating() { return window.floating(); }

    /** @param reached runs once the video shows whole and receives touches */
    boolean openFullscreen(Runnable open, Runnable reached) {
        if (window.active()) return true;
        if (state != State.SHOWN || playerSurface == null || previousBounds == null
                || presentation != VideoCardState.PLAYING || !hostActive) return false;
        cancelPreparation();
        window.open(this, playerSurface, source(previousBounds), displaySize, open,
                this::presentationRestored, reached);
        if (window.active()) postDelayed(inspect, 300);
        return true;
    }

    /**
     * Keeps live, playing video in a small window above Maps as Maps covers Road Home.
     * The task, display, and decoder are untouched.
     *
     * @return the card's screen area the video left, or null to keep today's behavior
     */
    Rect floatAboveMaps(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener) {
        if (window.active() || state != State.SHOWN || playerSurface == null || previousBounds == null
                || presentation != VideoCardState.PLAYING || !hostActive) return null;
        if (playerLifecycle == null) {
            Log.w("RoadHomeVideo", "Floating video unavailable without the video display lifecycle; keeping it in its card");
            return null;
        }
        try { appSwitches.start(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("RoadHomeVideo", "Floating video unavailable: app switches cannot be observed; keeping it in its card", error);
            return null;
        }
        cancelPreparation();
        // Before Road Home stops: its hidden window must not power off the playing display.
        playerLifecycle.presentElsewhere();
        try {
            window.openFloating(this, playerSurface, source(previousBounds), displaySize, floatingPlacement(),
                    corner, LiveSurfaceWindow.Entry.FROM_CARD, listener, this::presentationRestored);
        } catch (RuntimeException error) {
            // Runs inside the Maps expansion, which must open fullscreen regardless.
            Log.e("RoadHomeVideo", "Could not create the floating video window; Maps opens fullscreen"
                    + " and the video stays in its card", error);
        }
        if (!window.active()) {
            appSwitches.stop();
            playerLifecycle.returnToHost();
            return null;
        }
        Log.i("RoadHomeVideo", "Floating live video above Maps in the " + corner + " corner");
        postDelayed(inspect, 500);
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + getWidth(), location[1] + getHeight());
    }

    /**
     * The floating video grows to fullscreen above Maps, with its own input.
     * @param reached runs once it covers the screen
     */
    void expand(Runnable reached) { window.expand(reached); }

    /** 288 x 162 dp in a corner of the screen below the status bar, which fullscreen Maps keeps. */
    private FloatingPlacement floatingPlacement() {
        int statusBar = host.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int top = statusBar == 0 ? 0 : host.getResources().getDimensionPixelSize(statusBar);
        return new FloatingPlacement(new Rect(0, top, displaySize.x, displaySize.y),
                Ui.dp(host, FloatingPlacement.VIDEO_WIDTH_DP), Ui.dp(host, FloatingPlacement.VIDEO_HEIGHT_DP),
                Ui.dp(host, 16));
    }

    private void presentationRestored() {
        appSwitches.stop();
        if (playerLifecycle != null) playerLifecycle.returnToHost();
        cancelPreparation();
        if (hostActive && presentation == VideoCardState.PLAYING) inspectSurface();
        else {
            if (state == State.SHOWN) state = State.READY;
            coverPlayer();
            status.setVisibility(GONE);
            setVisibility(presentation == VideoCardState.HIDDEN ? GONE : VISIBLE);
        }
        presentationEnded.run();
    }

    private void appMovedToFront(String packageName, int displayId) {
        if (!window.floating()) return;
        if (packageName.equals(host.getPackageName()) || packageName.equals(EmbeddedMaps.PACKAGE)) return;
        if (packageName.equals(PACKAGE) && player != null && displayId == player.getVirtualDisplayId()) return;
        window.dismiss(packageName + " came to the front on display " + displayId);
    }

    private static RectF source(VideoSurface.Bounds bounds) {
        return new RectF(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height);
    }

    void fullscreenOpened() {
        // Run after the expansion restores its surface parent. The task is already on display 0.
        // Removing the now-empty virtual display clears Android 10's stale input-window entry.
        post(() -> {
            if (hostActive || state == State.CLOSED || window.active()) return;
            cancelPreparation();
            if (player != null) {
                player.setCallback(null);
                releaseLifecycle();
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
        if (window.active()) return;
        if (!hostActive || presentation != VideoCardState.PLAYING) return;
        if (state == State.EMPTY) {
            state = State.WAITING;
            try {
                player = new ActivityView(getContext());
                player.setPivotX(0);
                player.setPivotY(0);
                player.setClipBounds(new Rect());
                try {
                    playerLifecycle = new EmbeddedDisplayLifecycle(player, EmbeddedDisplayLifecycle.WhenHidden.POWER_OFF);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Log.w("RoadHomeVideo", "Video display lifecycle unavailable; video cannot float above Maps", error);
                }
                addView(player, 0, new LayoutParams(displaySize.x, displaySize.y));
                player.setCallback(new ActivityView.StateCallback() {
                    @Override public void onActivityViewReady(ActivityView view) {
                        try {
                            playerSurface = new ActivityViewSurface(view);
                            playerSurface.hide();
                            geometryReader.execute(() -> {
                                try {
                                    VideoDisplayPolicy.fixOrientation(view.getVirtualDisplayId());
                                    post(() -> {
                                        if (player != view || state != State.WAITING) return;
                                        state = State.READY;
                                        maybeLaunch();
                                    });
                                } catch (IOException | RuntimeException error) {
                                    post(() -> { if (player == view && state == State.WAITING) fail(error); });
                                }
                            });
                        } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
                    }
                    @Override public void onActivityViewDestroyed(ActivityView view) {
                        window.cancel();
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

    /** Geometry is followed while Road Home shows the card or a live window shows the video. */
    private boolean observing() { return hostActive || window.active(); }

    private void inspectSurface() {
        if (!observing() || (state != State.PREPARING && state != State.SHOWN)) return;
        int requestGeneration = generation;
        boolean presenting = window.active();
        geometryReader.execute(() -> {
            try {
                VideoSurface.Bounds bounds = VideoSurface.read(displaySize.x, displaySize.y, presenting);
                post(() -> {
                    if (requestGeneration != generation || !observing()) return;
                    if (window.active()) {
                        if (bounds != null) {
                            previousBounds = bounds;
                            window.updateSource(source(bounds));
                        }
                        postDelayed(inspect, window.floating() ? 500 : 300);
                        return;
                    }
                    applyBounds(bounds);
                });
            } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError error) {
                post(() -> {
                    if (requestGeneration == generation && observing()) fail(error);
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
            // The View remains unscaled. Its clip is in card coordinates; the
            // source-video crop belongs only to the transformed compositor root.
            player.setClipBounds(new Rect(0, 0, getWidth(), getHeight()));
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
        window.cancel();
        coverPlayer();
        status.setText(PLAYER_UNAVAILABLE);
        Log.e("RoadHome", "Could not embed the playing YouTube video surface", error);
    }

    private void releaseLifecycle() {
        if (playerLifecycle != null) playerLifecycle.release();
        playerLifecycle = null;
    }

    void release() {
        hostActive = false;
        window.release();
        appSwitches.stop();
        cancelPreparation();
        geometryReader.shutdownNow();
        if (player != null) {
            player.setCallback(null);
            releaseLifecycle();
            player.release();
            playerSurface = null;
        }
        state = State.CLOSED;
    }
}
