package dev.augie.wend;

import android.app.ActivityView;
import android.graphics.RectF;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
    private final TaskFrontWatcher appSwitches = new TaskFrontWatcher("WendVideo", this::appMovedToFront);
    private final Runnable presentationEnded;
    private final TextView status;
    /** Covers the card whenever its live video is not shown there. */
    private final VideoCardCover cover;
    private final View tapTarget;
    private final Point displaySize = new Point();
    private final ExecutorService geometryReader = Executors.newSingleThreadExecutor();
    private final VideoStills stills;
    /** The video the cover's still belongs to. */
    private String stillTitle;
    /** A sheet or fullscreen Maps covers the card, so its pixels are not the video's. */
    private boolean covered;
    private final Runnable inspect = this::inspectSurface;
    private boolean hostActive;
    private VideoCardState presentation = VideoCardState.HIDDEN;
    private int generation;
    private long preparationStarted;
    private VideoSurface.Bounds previousBounds;

    /**
     * @param open opens the video app, as a tap anywhere on the card does
     * @param presentationEnded runs whenever a fullscreen or floating presentation has finished
     */
    EmbeddedVideo(Activity context, Runnable open, Runnable presentationEnded) {
        super(context);
        host = context;
        this.presentationEnded = presentationEnded;
        window = new LiveSurfaceWindow(context, WINDOW_TITLE, "WendVideo");
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(displaySize);
        setBackgroundColor(android.graphics.Color.BLACK);
        setClipChildren(true);
        cover = new VideoCardCover(context);
        addView(cover, new LayoutParams(-1, -1));
        status = Ui.text(context, "", 14, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(android.graphics.Color.BLACK);
        status.setVisibility(GONE);
        addView(status, new LayoutParams(-1, -1));
        // One large target over everything the card shows. Being visible above the player, it also
        // takes the card out of ActivityView's tap-exclude region, so Wend keeps these touches.
        tapTarget = new View(context);
        tapTarget.setBackground(Ui.ripple(context, 0));
        tapTarget.setContentDescription("Open video");
        tapTarget.setOnClickListener(view -> open.run());
        addView(tapTarget, new LayoutParams(-1, -1));
        stills = new VideoStills(geometryReader, new VideoStills.Card() {
            @Override public Rect liveArea() { return liveCard(); }
            @Override public int rotation() { return host.getWindowManager().getDefaultDisplay().getRotation(); }
            @Override public void kept(Bitmap still) { cover.keep(still); }
        });
        setVisibility(GONE);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int height = Math.round(MeasureSpec.getSize(widthSpec) * 9f / 16f);
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    /**
     * @param control what the card promises over its picture while the live video is not there
     * @param title identifies the video; a still of another one is dropped
     */
    void update(VideoCardState presentation, VideoCardCover.Control control, CharSequence title, Bitmap artwork) {
        this.presentation = presentation;
        String video = title == null ? null : title.toString();
        if (!TextUtils.equals(video, stillTitle)) {
            stillTitle = video;
            stills.interrupt();
            cover.keep(null);
        }
        cover.show(control, artwork);
        if (window.active()) {
            // A paused floating video keeps its last frame. A cleared or ended session removes it,
            // as does stopped playback (playbackEnded).
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

    /**
     * Playback stopped, failed, or was reset rather than paused. The player then draws its own
     * stopped, up-next, or ad screen, which must not float over Maps; the card keeps the title.
     * @param state completes "the video ..." in the log
     */
    void playbackEnded(String state) {
        if (window.floating()) window.dismiss("the video " + state + ", not paused");
    }

    /** Wend is visible again, by Home or because the app covering it left. */
    void resume() {
        hostActive = true;
        covered = false;
        window.hostShown();
        if (window.active()) {
            // A video floating above Maps returns to its card. A whole video above Wend
            // stays until Home: Wend also shows behind it when Maps leaves for its panel.
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
        // Once Wend's window can show the card again.
        if (playerLifecycle != null) playerLifecycle.whenHostReady(window::collapse);
        else window.collapse();
    }

    boolean presenting() { return window.active(); }

    /**
     * Wend is about to draw over the card, with a sheet, or to leave it for fullscreen Maps:
     * no still is taken from it until {@link #cardShown}.
     */
    void cardCovered() {
        covered = true;
        stills.interrupt();
    }

    /** Nothing of Wend's covers the card any more. */
    void cardShown() { covered = false; }

    /** The card's screen area while it shows live video that nothing of Wend's covers, else null. */
    private Rect liveCard() {
        if (!hostActive || covered || window.active() || state != State.SHOWN || presentation != VideoCardState.PLAYING
                || cover.getVisibility() == VISIBLE || tapTarget.isPressed() || !isShown()) return null;
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + getWidth(), location[1] + getHeight());
    }

    void suspend() {
        hostActive = false;
        // Floating above the app that replaced Wend: keep the window and keep playing.
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
        if (window.active()) {
            // The card it leaves keeps the last still rather than an empty black slot.
            cover.setVisibility(VISIBLE);
            postDelayed(inspect, 300);
        }
        return true;
    }

    /**
     * Keeps live, playing video in a small window above Maps as Maps covers Wend.
     * The task, display, and decoder are untouched.
     *
     * @return the card's screen area the video left, or null to keep today's behavior
     */
    Rect floatAboveMaps(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener) {
        if (window.active() || state != State.SHOWN || playerSurface == null || previousBounds == null
                || presentation != VideoCardState.PLAYING || !hostActive) return null;
        if (playerLifecycle == null) {
            Log.w("WendVideo", "Floating video unavailable without the video display lifecycle; keeping it in its card");
            return null;
        }
        try { appSwitches.start(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendVideo", "Floating video unavailable: app switches cannot be observed; keeping it in its card", error);
            return null;
        }
        cancelPreparation();
        // Before Wend stops: its hidden window must not power off the playing display.
        playerLifecycle.presentElsewhere();
        try {
            window.openFloating(this, playerSurface, source(previousBounds), displaySize, floatingPlacement(),
                    corner, LiveSurfaceWindow.Entry.FROM_CARD, listener, this::presentationRestored);
        } catch (RuntimeException error) {
            // Runs inside the Maps expansion, which must open fullscreen regardless.
            Log.e("WendVideo", "Could not create the floating video window; Maps opens fullscreen"
                    + " and the video stays in its card", error);
        }
        if (!window.active()) {
            appSwitches.stop();
            playerLifecycle.returnToHost();
            return null;
        }
        Log.i("WendVideo", "Floating live video above Maps in the " + corner + " corner");
        cover.setVisibility(VISIBLE);
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

    /**
     * Fullscreen Maps now covers the whole fullscreen video: hide it, ready to {@link #emergeAboveMaps}.
     * @param corner where it floats, or null to return it to its card hidden as before
     */
    void yieldToMaps(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener) {
        if (!window.active()) return;
        if (corner == null || presentation != VideoCardState.PLAYING || playerLifecycle == null) {
            window.cancel();
            return;
        }
        try { appSwitches.start(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendVideo", "Floating video unavailable: app switches cannot be observed; it returns to its card", error);
            window.cancel();
            return;
        }
        // Before Wend stops: its hidden window must not power off the playing display.
        playerLifecycle.presentElsewhere();
        if (!window.hideForCorner(floatingPlacement(), corner, listener)) {
            appSwitches.stop();
            window.cancel();
            return;
        }
        Log.i("WendVideo", "Hiding the live video under fullscreen Maps; it floats in the " + corner + " corner next");
    }

    /** The video hidden by {@link #yieldToMaps} appears floating in its corner. */
    void emergeAboveMaps() { window.emerge(); }

    private void presentationRestored() {
        appSwitches.stop();
        if (playerLifecycle != null) playerLifecycle.returnToHost();
        cancelPreparation();
        if (hostActive && presentation == VideoCardState.PLAYING) {
            // Back in the card live, as it arrived: the still would show an older frame over it.
            if (state == State.SHOWN && playerSurface != null && playerSurface.showsPreview()) {
                cover.setVisibility(GONE);
            }
            inspectSurface();
        } else {
            if (state == State.SHOWN) state = State.READY;
            coverPlayer();
            status.setVisibility(GONE);
            setVisibility(presentation == VideoCardState.HIDDEN ? GONE : VISIBLE);
        }
        presentationEnded.run();
    }

    private void appMovedToFront(String packageName, int displayId) {
        // Also while a tapped floating video grows whole, so Wend is not then brought
        // forward over the app that just came to the front.
        if (!window.overAnotherApp()) return;
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
                releaseDisplay();
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
        stills.interrupt();
        removeCallbacks(inspect);
        if (state == State.PREPARING) state = State.READY;
    }

    private void coverPlayer() {
        boolean wasLive = cover.getVisibility() != VISIBLE;
        cover.setVisibility(VISIBLE);
        if (player != null) player.setClipBounds(new Rect());
        if (playerSurface == null) return;
        if (!wasLive || !hostActive) { playerSurface.hide(); return; }
        // Hidden once the cover is drawn over it: hidden first, the card showed black in between.
        ActivityViewSurface covering = playerSurface;
        getViewTreeObserver().registerFrameCommitCallback(() -> {
            if (covering == playerSurface && cover.getVisibility() == VISIBLE) covering.hide();
        });
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
                    Log.w("WendVideo", "Video display lifecycle unavailable; video cannot float above Maps", error);
                }
                addView(player, 0, new LayoutParams(displaySize.x, displaySize.y));
                player.setCallback(new ActivityView.StateCallback() {
                    @Override public void onActivityViewReady(ActivityView view) {
                        try {
                            // The card keeps its own touches: the preview is scaled, and a tap opens the video.
                            playerSurface = new ActivityViewSurface(view, ActivityViewSurface.PreviewInput.KEPT);
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
                        stills.interrupt();
                        cover.setVisibility(VISIBLE);
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

    /** Geometry is followed while Wend shows the card or a live window shows the video. */
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
            cover.setVisibility(GONE);
            stills.start();
            if (state != State.SHOWN) {
                state = State.SHOWN;
                status.setVisibility(View.GONE);
                Log.i("Wend", "Showing YouTube video surface without PiP: " + bounds.left + ","
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
        status.setVisibility(VISIBLE);
        Log.e("Wend", "Could not embed the playing YouTube video surface", error);
    }

    /** ActivityView refuses to release a display it never created, before its first surface. */
    private void releaseDisplay() {
        if (player.getVirtualDisplayId() != android.view.Display.INVALID_DISPLAY) player.release();
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
        stills.release();
        cover.release();
        geometryReader.shutdownNow();
        if (player != null) {
            player.setCallback(null);
            releaseLifecycle();
            releaseDisplay();
            playerSurface = null;
        }
        state = State.CLOSED;
    }
}
