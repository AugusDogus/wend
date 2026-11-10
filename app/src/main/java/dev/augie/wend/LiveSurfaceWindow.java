package dev.augie.wend;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;

/**
 * Presents one owned, live ActivityView display outside its card: whole above Wend, or in a
 * floating corner window above another app. Only compositor geometry changes: it never moves,
 * resizes, or restarts the display or its task.
 */
final class LiveSurfaceWindow {
    interface Listener {
        /** A drag released the floating window, which now rests in {@code corner}. */
        void settled(FloatingPlacement.Corner corner);
        /** A double tap, or the fullscreen control: the window's app takes the screen. */
        void fullscreen();
        /** The close control, or a drag or fling off the bottom edge: the window is closing. */
        void closed();
    }

    /** How a floating window first appears. */
    enum Entry {
        /** Moves from its card to its corner at once. */
        FROM_CARD,
        /** Lifts out of its card without moving, and stays until {@link #leaveCard}. */
        HELD_AT_CARD,
        /** Its card is already hidden: it grows and fades in at its corner. */
        EMERGING
    }

    private enum Phase { WAITING, MOVING, RESTING, DRAGGING, CLOSED }
    private enum Target { CARD, CORNER, WHOLE, HIDDEN, GONE }
    private static final int MOTION_MS = 280;
    private static final int EMERGE_MS = 220;
    private static final int FADE_MS = 160;
    private static final int READY_MS = 700;
    /**
     * After a floating window first comes to rest, a tap this soon was aimed at what it covered,
     * such as a second tap on the dock's Maps button where the video has just arrived.
     */
    static final int ENTRY_TAP_GRACE_MS = 500;
    private static final float EMERGE_SCALE = .9f;
    /** A shown menu fades after this long, as Android 10's PiP menu does after about 3 s. */
    static final int MENU_MS = 3000;
    /** A downward release this fast, in dp/s, from the bottom corners is a fling that closes the window. */
    private static final int DISMISS_FLING_DP = 500;
    private final Activity host;
    private final String title;
    private final String tag;
    private final WindowManager windows;
    /** The next turn drawn across the bottom of a floating Maps window, or null. */
    private NavigationCard turnCard;
    private final Point screen = new Point();
    private final BroadcastReceiver home = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())
                    || !"homekey".equals(intent.getStringExtra("reason"))) return;
            // Android 10 sends Home to the last touched virtual display, which cannot
            // host Home. Route that system notification to the physical Home task.
            // Start before animating: this synchronous Binder call can occupy several
            // animation frames. MainActivity's Home intent starts the live return.
            host.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClass(host, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        }
    };
    private boolean routingHome;
    private Running running;
    /** What the presented display's media session does, and how its playback control acts. */
    private FloatingMenu.Playback playback = FloatingMenu.Playback.NONE;
    private Runnable togglePlayback = () -> { };

    /**
     * @param title the overlay window's title, which also names its compositor layer
     * @param tag the log tag for this display's presentation
     */
    LiveSurfaceWindow(Activity host, String title, String tag) {
        this.host = host;
        this.title = title;
        this.tag = tag;
        windows = host.getSystemService(WindowManager.class);
        host.getWindowManager().getDefaultDisplay().getRealSize(screen);
    }

    boolean active() { return running != null; }

    /**
     * Presenting above another app, so it keeps going while Wend is stopped: floating in its
     * corner, entering or leaving it, or hidden until it emerges there.
     */
    boolean floating() { return running != null && running.placement != null && running.target != Target.WHOLE; }

    /**
     * Presented over another app rather than opened above Wend: floating, or growing whole
     * after a tap while Wend is still hidden. An unrelated app coming to the front ends it.
     */
    boolean overAnotherApp() { return running != null && running.placement != null; }

    /** Wend is visible again. Android's own Home routing applies unless a display is whole. */
    void hostShown() { if (running == null || running.target != Target.WHOLE) stopRoutingHome(); }

    void release() {
        cancel();
        stopRoutingHome();
    }

    private void routeHome() {
        if (routingHome) return;
        host.registerReceiver(home, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        routingHome = true;
    }

    private void stopRoutingHome() {
        if (!routingHome) return;
        host.unregisterReceiver(home);
        routingHome = false;
    }

    /**
     * Expands the card until the whole display shows above Wend, then gives it touches.
     * @param display the presented display's size; it is centered unscaled on the screen
     * @param fallback opens the app another way if the whole display cannot be shown; never
     *     runs once {@link #hideForCorner} has made it a floating window
     * @param reached runs once the whole display shows and receives touches
     */
    void open(View card, ActivityViewSurface surface, RectF source, Point display,
            Runnable fallback, Runnable restored, Runnable reached) {
        start(new Running(card, surface, source, display, null, null, fallback, restored), reached);
    }

    /** Moves the card into a small window that floats above the app replacing Wend. */
    void openFloating(View card, ActivityViewSurface surface, RectF source, Point display,
            FloatingPlacement placement, FloatingPlacement.Corner corner, Entry entry,
            Listener listener, Runnable restored) {
        Running next = new Running(card, surface, source, display, entry, listener, () -> { }, restored);
        next.floatIn(placement, corner, listener);
        start(next, null);
    }

    /**
     * As {@link #openFloating(View, ActivityViewSurface, RectF, Point, FloatingPlacement,
     * FloatingPlacement.Corner, Entry, Listener, Runnable)}, showing only {@code floatingSource} of
     * the display while it floats, such as Maps' map without its controls, and all of
     * {@code source} in its card.
     */
    void openFloating(View card, ActivityViewSurface surface, RectF source, RectF floatingSource,
            Point display, FloatingPlacement placement, FloatingPlacement.Corner corner, Entry entry,
            Listener listener, Runnable restored) {
        Running next = new Running(card, surface, source, display, entry, listener, () -> { }, restored);
        next.floatingSource = new RectF(floatingSource);
        next.floatIn(placement, corner, listener);
        start(next, null);
    }

    /** Draws {@code card}, the next turn, across the bottom of the window while it floats; null removes it. */
    void turnCard(NavigationCard card) {
        turnCard = card;
        if (running != null) running.overlay.turnCard(card);
    }

    private void start(Running next, Runnable reached) {
        if (active()) return;
        if (windows == null || !next.card.isLaidOut()) { next.fallback.run(); return; }
        running = next;
        next.overlay.turnCard(turnCard);
        next.reached = reached;
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(screen.x, screen.y,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setTitle(title);
            next.overlay.setSystemUiVisibility(host.getWindow().getDecorView().getSystemUiVisibility());
            windows.addView(next.overlay, params);
            if (next.placement == null) routeHome();
            else next.takeTouches();
            next.overlay.getViewTreeObserver().registerFrameCommitCallback(next::attach);
            next.overlay.invalidate();
            next.overlay.postDelayed(next.deadline, READY_MS);
        } catch (ReflectiveOperationException | RuntimeException error) { next.fail(error); }
    }

    /** A window held at its card ({@link Entry#HELD_AT_CARD}) now moves to its corner. */
    void leaveCard() { if (running != null) running.leaveCard(); }

    /**
     * A floating window grows until its whole display shows, then receives touches.
     * @param reached runs once the whole display shows
     */
    void expand(Runnable reached) { if (running != null) running.whole(reached, true); }

    /**
     * A floating window grows until its whole display shows, still without touches, so another
     * presentation can take over from that frame.
     */
    void focus(Runnable reached) { if (running != null) running.whole(reached, false); }

    /**
     * Holds a floating window on {@code still}, a frame of what it shows now, while its display
     * changes behind it, until it grows with {@link #focus(RectF, Point, Runnable)} or leaves its corner.
     * It takes no gestures meanwhile.
     */
    void freeze(Bitmap still) { if (running != null) running.freeze(still); }

    /**
     * Its display has been resized to {@code display}, unseen behind {@link #freeze}: the floating
     * window shows {@code floatingSource} of it, then grows straight to the whole display, centered,
     * still without touches.
     * @param reached runs once the whole display shows
     */
    void focus(RectF floatingSource, Point display, Runnable reached) {
        if (running != null) running.growResized(floatingSource, display, reached);
    }

    /**
     * Something else now covers the whole display: hide it, ready to {@link #emerge} in a
     * floating corner window above the app replacing Wend.
     * @return false if it is not showing whole, or cannot float
     */
    boolean hideForCorner(FloatingPlacement placement, FloatingPlacement.Corner corner, Listener listener) {
        return running != null && running.hideForCorner(placement, corner, listener);
    }

    /** A window hidden by {@link #hideForCorner} grows and fades in at its corner. */
    void emerge() { if (running != null) running.emerge(); }

    /** Returns to the card in Wend's window, which must be visible again. */
    void collapse() { if (running != null) running.collapse(); }

    /**
     * Moves over its card and stays drawn there, above windows that still cover the card, until
     * {@link #collapse} hands it back to the card.
     * @param arrived runs once it is drawn over its card, or at once if it already returned
     */
    void returnOverCard(Runnable arrived) {
        if (running == null) arrived.run();
        else running.returnOverCard(arrived);
    }

    /**
     * Fades out without returning to the card, as when the session ends or another app opens.
     * @param reason completes "Closing ... because ..." in the log
     */
    void dismiss(String reason) { if (running != null) running.dismiss(reason); }

    void cancel() { if (running != null) running.finish(false); }

    /** Whether a floating window shows its controls. */
    boolean controlsShown() { return running != null && running.overlay.menuShown(); }

    /**
     * The presented display's media session changed: its controls offer play or pause, and a
     * floating video that has ended keeps offering play until it plays again.
     * @param toggle plays or pauses it, as the control shows
     */
    void playback(FloatingMenu.Playback state, Runnable toggle) {
        FloatingMenu.Playback was = playback;
        playback = state;
        togglePlayback = toggle;
        if (running != null) running.playbackChanged(was);
    }

    void updateSource(RectF source) { if (running != null) running.updateSource(source); }

    /**
     * From now on a floating window shows {@code source} of its display, such as Maps' map under a
     * next-turn card that came or went: at once where it rests, otherwise as it next rests.
     */
    void floatingSource(RectF source) { if (running != null) running.floatingSource(source); }

    private final class Running {
        final FloatingChrome overlay = new FloatingChrome(host);
        final float density = host.getResources().getDisplayMetrics().density;
        final Runnable hideMenu = () -> menu(false);
        final View card;
        final ActivityViewSurface surface;
        /** The whole display centered on screen, and its input; a resized display changes both. */
        SurfacePlacement whole;
        Rect wholeInput;
        final Entry entry;
        final Runnable fallback, restored;
        final float radius;
        final Rect crop = new Rect();
        final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        final TimeInterpolator motion = AnimationUtils.loadInterpolator(host, R.interpolator.live_window_motion);
        final TimeInterpolator easeOut = AnimationUtils.loadInterpolator(host, R.interpolator.window_ease_out);
        final Runnable deadline = () -> fail(new IllegalStateException(
                "Live window was not ready within " + READY_MS + " ms"));
        /** The display region shown in its card and, unless {@link #floatingSource} is set, while it floats. */
        RectF source;
        /** The region shown while it floats, if it differs from its card's. */
        RectF floatingSource;
        /** {@link #floatingSource} changed during a motion that ends at rest in the corner. */
        boolean floatedChanged;
        Runnable reached;
        /** Runs once it is drawn over its card, or as it is handed back to the card. */
        Runnable overCard;
        FloatingPlacement placement;
        FloatingPlacement.Corner corner;
        Listener listener;
        FloatingDrag drag;
        OverlayTouchRegion touches;
        FloatingOutline outline;
        Phase phase = Phase.WAITING;
        Target target;
        Target origin;
        SurfacePlacement current;
        float currentRadius, currentOutline, currentAlpha = 1;
        boolean leaveWhenAttached;
        /** A released window travels to its corner and takes touches where it is drawn. */
        boolean snapping;
        /** Where a window closed by a drag off the bottom edge leaves to, or null to fade in place. */
        SurfacePlacement away;

        Running(View card, ActivityViewSurface surface, RectF source, Point display, Entry entry,
                Listener listener, Runnable fallback, Runnable restored) {
            this.card = card;
            this.surface = surface;
            this.source = new RectF(source);
            this.entry = entry;
            this.listener = listener;
            this.fallback = fallback;
            this.restored = restored;
            whole = SurfacePlacement.centered(display.x, display.y, screen.x, screen.y);
            wholeInput = new Rect(0, 0, display.x, display.y);
            radius = Ui.dp(host, 12);
        }

        /** Floating geometry and gestures, set when it first floats. */
        void floatIn(FloatingPlacement placement, FloatingPlacement.Corner corner, Listener listener) {
            this.placement = placement;
            this.corner = corner;
            this.listener = listener;
            drag = new FloatingDrag(placement, ViewConfiguration.get(host).getScaledTouchSlop(),
                    DISMISS_FLING_DP * density, new FloatingDrag.Listener() {
                        @Override public void moved(Rect bounds) { dragged(bounds); }
                        @Override public void released(Rect bounds, FloatingPlacement.Corner next) {
                            overlay.dismissTarget(false);
                            snap(next);
                        }
                        @Override public void dismissed(Rect bounds) { closeDragged(bounds); }
                        @Override public boolean pressed(float x, float y) { return press(x, y); }
                        @Override public void tapped() { if (resting()) menu(!overlay.menuShown()); }
                        @Override public void doubleTapped() { if (resting()) fullscreen(); }
                    });
        }

        /** Touches outside the floating window reach the app below it; none reach it yet. */
        void takeTouches() throws ReflectiveOperationException {
            touches = new OverlayTouchRegion(overlay);
            touches.only(new Rect());
            overlay.setOnTouchListener((view, event) -> touch(event));
        }

        void attach() {
            if (phase != Phase.WAITING) return;
            overlay.removeCallbacks(deadline);
            try {
                if (placement != null) createOutline();
                boolean emerging = entry == Entry.EMERGING;
                current = emerging ? emergeFrom() : SurfacePlacement.fit(source, cardOnScreen());
                currentRadius = emerging ? radius : 0;
                currentOutline = 0;
                currentAlpha = emerging ? 0 : 1;
                surface.attach(overlay, current, currentRadius, currentAlpha);
                target = emerging ? Target.HIDDEN : Target.CARD;
                phase = Phase.RESTING;
                if (placement == null) whole(reached, true);
                else if (emerging) emerge();
                else if (entry == Entry.HELD_AT_CARD) { if (leaveWhenAttached) leaveCard(); }
                else animateTo(Target.CORNER, MOTION_MS, motion, this::entered);
            } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
        }

        void createOutline() {
            if (outline != null) return;
            try {
                outline = new FloatingOutline(ActivityViewSurface.windowSurface(overlay));
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(tag, "Floating window outline unavailable; showing the display without it", error);
            }
        }

        Rect cardOnScreen() {
            int[] location = new int[2];
            card.getLocationOnScreen(location);
            return new Rect(location[0], location[1], location[0] + card.getWidth(), location[1] + card.getHeight());
        }

        RectF floated() { return floatingSource != null ? floatingSource : source; }

        SurfacePlacement emergeFrom() { return SurfacePlacement.fit(floated(), placement.rest(corner)).scaled(EMERGE_SCALE); }

        SurfacePlacement placementFor(Target next) {
            switch (next) {
                case CARD: return SurfacePlacement.fit(source, cardOnScreen());
                case CORNER: return SurfacePlacement.fit(floated(), placement.rest(corner));
                case WHOLE: return whole;
                case GONE: return away != null ? away : current;
                default: return current;
            }
        }

        void animateTo(Target next, int duration, TimeInterpolator interpolator, Runnable end) {
            // A failed placement just before this closed the window; nothing is left to move.
            if (phase == Phase.CLOSED) return;
            if (next != Target.CORNER) {
                // Its controls belong to the window resting in its corner.
                menu(false);
                if (drag != null) drag.cancelTap();
            }
            // A held still only gives way to the display growing behind it.
            if (next == Target.WHOLE) overlay.fadeStill();
            else overlay.still(null, null, 0);
            // Reversing a motion part way only travels back the distance already covered.
            float covered = phase == Phase.MOVING && origin == next ? animator.getAnimatedFraction() : 1;
            stopAnimator();
            origin = target;
            target = next;
            phase = Phase.MOVING;
            SurfacePlacement from = current;
            SurfacePlacement to = placementFor(next);
            boolean fading = next == Target.GONE || next == Target.HIDDEN;
            float fromRadius = currentRadius, toRadius = next == Target.CORNER ? radius : fading ? currentRadius : 0;
            float fromOutline = currentOutline, toOutline = next == Target.CORNER ? 1 : 0;
            float fromAlpha = currentAlpha, toAlpha = fading ? 0 : 1;
            animator.setDuration(Math.max(1, Math.round(duration * covered)));
            animator.setInterpolator(interpolator);
            animator.addUpdateListener(value -> {
                float f = (float) value.getAnimatedValue();
                show(SurfacePlacement.lerp(from, to, f), lerp(fromRadius, toRadius, f),
                        lerp(fromOutline, toOutline, f), lerp(fromAlpha, toAlpha, f));
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (phase != Phase.MOVING) return;
                    phase = Phase.RESTING;
                    end.run();
                }
            });
            animator.start();
        }

        void show(SurfacePlacement placed, float cornerRadius, float outlineOpacity, float alpha) {
            if (phase == Phase.CLOSED || phase == Phase.WAITING) return;
            current = placed;
            currentRadius = cornerRadius;
            currentOutline = outlineOpacity;
            currentAlpha = alpha;
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                surface.place(transaction, placed, cornerRadius, alpha);
                if (outline != null) outline.place(transaction, placed.visible(), cornerRadius, outlineOpacity * alpha);
                transaction.apply();
            } catch (ReflectiveOperationException | RuntimeException error) { fail(error); return; }
            // The turn card belongs to the floating window: it shows as the window reaches its corner.
            overlay.frame(drawn(), cornerRadius, outlineOpacity * alpha);
            if (snapping) touches.only(drawn());
        }

        /** Where the window is drawn now, in screen pixels. */
        Rect drawn() {
            RectF visible = current.visible();
            return new Rect(Math.round(visible.left), Math.round(visible.top),
                    Math.round(visible.right), Math.round(visible.bottom));
        }

        void stopAnimator() {
            snapping = false;
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
        }

        void rest() {
            snapping = false;
            if (floatedChanged) showFloated();
            touches.only(placement.rest(corner));
            if (playback.keepsControls()) menu(true);
        }

        /** Its entry motion has settled: it takes touches, but not yet taps. */
        void entered() {
            rest();
            drag.ignoreTapsUntil(SystemClock.uptimeMillis() + ENTRY_TAP_GRACE_MS);
        }

        void leaveCard() {
            if (entry != Entry.HELD_AT_CARD || phase == Phase.CLOSED) return;
            if (phase == Phase.WAITING) { leaveWhenAttached = true; return; }
            if (target != Target.CARD || phase != Phase.RESTING) return;
            animateTo(Target.CORNER, MOTION_MS, motion, this::entered);
        }

        void whole(Runnable then, boolean interactive) {
            if (phase == Phase.CLOSED || phase == Phase.WAITING || target == Target.WHOLE) return;
            if (placement != null && target != Target.CORNER && target != Target.CARD) return;
            // While it grows, touches go to whatever the window still uncovers.
            if (touches != null) {
                if (interactive) touches.whole();
                else touches.only(new Rect());
            }
            animateTo(Target.WHOLE, MOTION_MS, motion, () -> {
                if (interactive) {
                    // Its input portal must sit above this window, which otherwise takes every touch.
                    surface.layer(ActivityViewSurface.ABOVE_WINDOW);
                    surface.input(wholeInput);
                    // Touches now reach the presented display, which then receives the Home key.
                    routeHome();
                }
                if (then != null) then.run();
            });
        }

        void freeze(Bitmap still) {
            if (!resting()) return;
            menu(false);
            overlay.still(still, placement.rest(corner), radius);
        }

        void growResized(RectF nextSource, Point display, Runnable reached) {
            if (phase == Phase.CLOSED || phase == Phase.WAITING || placement == null || target != Target.CORNER) return;
            floatingSource = new RectF(nextSource);
            whole = SurfacePlacement.centered(display.x, display.y, screen.x, screen.y);
            wholeInput = new Rect(0, 0, display.x, display.y);
            // The resized display fills the corner first, under the still, then grows from there.
            stopAnimator();
            phase = Phase.RESTING;
            show(placementFor(Target.CORNER), radius, 1, 1);
            whole(reached, false);
        }

        boolean hideForCorner(FloatingPlacement next, FloatingPlacement.Corner nextCorner, Listener nextListener) {
            if (phase == Phase.CLOSED || phase == Phase.WAITING || target != Target.WHOLE) return false;
            try {
                if (touches == null) takeTouches();
                floatIn(next, nextCorner, nextListener);
                createOutline();
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(tag, "Cannot float this display; it is still shown whole", error);
                return false;
            }
            stopAnimator();
            touches.only(new Rect());
            surface.input(new Rect());
            // Below the window again, where its floating controls draw over it.
            surface.layer(ActivityViewSurface.BELOW_WINDOW);
            // The tap that covered it was on the main display, where Home works again.
            stopRoutingHome();
            origin = target;
            target = Target.HIDDEN;
            phase = Phase.RESTING;
            show(current, currentRadius, 0, 0);
            return phase != Phase.CLOSED;
        }

        void emerge() {
            if (phase != Phase.RESTING || target != Target.HIDDEN) return;
            show(emergeFrom(), radius, 0, 0);
            animateTo(Target.CORNER, EMERGE_MS, easeOut, this::entered);
        }

        boolean touch(MotionEvent event) {
            if (target != Target.CORNER || phase == Phase.WAITING || phase == Phase.CLOSED) return false;
            // Held still while it is about to grow: its gestures are over.
            if (overlay.holdsStill()) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && phase == Phase.MOVING) {
                // Catch the window mid-snap; the drag starts where it is drawn now.
                stopAnimator();
                phase = Phase.RESTING;
            }
            return drag.handle(event, drawn());
        }

        void dragged(Rect bounds) {
            phase = Phase.DRAGGING;
            // As Android 10's PiP: the menu goes, and the dismiss target shows where to drop it.
            menu(false);
            overlay.dismissTarget(true);
            show(SurfacePlacement.fit(floated(), bounds), radius, 1, 1);
        }

        boolean resting() { return target == Target.CORNER && phase == Phase.RESTING; }

        /** Shows the menu over the resting window, fading it after {@link #MENU_MS}; or hides it. */
        void menu(boolean shown) {
            overlay.removeCallbacks(hideMenu);
            if (!shown) { overlay.menu(false, null, radius, true); return; }
            overlay.menu(true, placement.rest(corner), radius, true);
            overlay.playback(playback);
            if (!playback.keepsControls()) overlay.postDelayed(hideMenu, MENU_MS);
        }

        void playbackChanged(FloatingMenu.Playback was) {
            overlay.playback(playback);
            if (!resting()) return;
            // Shown, its fade restarts or stops; an ended video offers play until it plays again.
            if (overlay.menuShown() || playback.keepsControls() && !was.keepsControls()) menu(true);
        }

        /** A tap on a shown control acts at once. */
        boolean press(float x, float y) {
            if (!resting() || !overlay.menuShown()) return false;
            FloatingMenu.Control control = FloatingMenu.at(x, y, placement.rest(corner),
                    playback, density);
            if (control == null) return false;
            switch (control) {
                case FULLSCREEN: fullscreen(); break;
                case CLOSE: close(null); break;
                default:
                    togglePlayback.run();
                    // Its icon follows the session; the menu stays a while longer.
                    menu(true);
                    break;
            }
            return true;
        }

        void fullscreen() {
            menu(false);
            listener.fullscreen();
        }

        void closeDragged(Rect bounds) {
            overlay.dismissTarget(false);
            // It leaves downward from where it was dropped, as Android 10's PiP does.
            close(SurfacePlacement.fit(floated(), placement.below(bounds)));
        }

        /** @param to where it leaves to as it fades, or null to fade where it is */
        void close(SurfacePlacement to) {
            if (phase == Phase.CLOSED || target == Target.GONE) return;
            away = to;
            listener.closed();
            dismiss("the driver closed it");
        }

        void snap(FloatingPlacement.Corner next) {
            corner = next;
            animateTo(Target.CORNER, MOTION_MS, motion, this::rest);
            // On its way, a touch where it is drawn catches it instead of reaching the app below.
            snapping = true;
            touches.only(drawn());
            listener.settled(next);
        }

        void collapse() {
            // A dismissal already in progress wins: its session ended or another app took over.
            if (phase == Phase.CLOSED || target == Target.GONE || target == Target.CARD && phase == Phase.MOVING) return;
            // Not yet drawn elsewhere, still at its card, or hidden: the card shows it directly.
            if (phase == Phase.WAITING || target == Target.CARD || target == Target.HIDDEN) { finish(false); return; }
            surface.input(new Rect());
            if (touches != null) touches.whole();
            animateTo(Target.CARD, MOTION_MS, motion, () -> finish(false));
        }

        void returnOverCard(Runnable arrived) {
            if (phase == Phase.CLOSED || target == Target.GONE) { arrived.run(); return; }
            // Not yet drawn elsewhere or hidden: nothing to keep above the card.
            if (phase == Phase.WAITING || target == Target.HIDDEN) { finish(false); arrived.run(); return; }
            if (target == Target.CARD && phase == Phase.RESTING) { arrived.run(); return; }
            // A repeated request, such as Home delivered twice, joins the motion under way.
            overCard = arrived;
            if (target == Target.CARD) return;
            surface.input(new Rect());
            if (touches != null) touches.only(new Rect());
            animateTo(Target.CARD, MOTION_MS, motion, this::arrivedOverCard);
        }

        void arrivedOverCard() {
            Runnable then = overCard;
            overCard = null;
            if (then != null) then.run();
        }

        void dismiss(String reason) {
            if (phase == Phase.CLOSED || target == Target.GONE) return;
            Log.i(tag, "Closing the live window because " + reason);
            if (phase == Phase.WAITING || target == Target.HIDDEN) { finish(true); return; }
            surface.input(new Rect());
            if (touches != null) touches.only(new Rect());
            animateTo(Target.GONE, FADE_MS, motion, () -> finish(true));
        }

        void floatingSource(RectF next) {
            // A window that floats what its card shows has no part of its own.
            if (floatingSource == null || floatingSource.equals(next)) return;
            floatingSource = new RectF(next);
            // A drag shows it with its next move, and a motion in flight as it comes to rest.
            if (resting()) showFloated();
            else floatedChanged = true;
        }

        void showFloated() {
            floatedChanged = false;
            show(SurfacePlacement.fit(floated(), placement.rest(corner)), radius, 1, 1);
        }

        void updateSource(RectF next) {
            if (phase != Phase.RESTING) return;
            source = new RectF(next);
            if (target == Target.CORNER) show(SurfacePlacement.fit(floated(), placement.rest(corner)), radius, 1, 1);
        }

        void fail(Exception error) {
            if (phase == Phase.CLOSED) return;
            Log.w(tag, placement == null
                    ? "Live whole-display presentation unavailable; opening the existing task"
                    : "Floating window unavailable; the display returns to its card when Wend is shown", error);
            finish(false);
            // Once floated, opening the task directly would move it fullscreen over the other app.
            if (placement == null) fallback.run();
        }

        void finish(boolean hidden) {
            if (phase == Phase.CLOSED) return;
            boolean attached = phase != Phase.WAITING;
            phase = Phase.CLOSED;
            overlay.removeCallbacks(deadline);
            // A dismissal leaves Wend hidden, and the last touch may have been on the
            // presented display, where Home does nothing. Keep routing until Wend shows.
            if (!hidden) stopRoutingHome();
            stopAnimator();
            overlay.removeCallbacks(hideMenu);
            overlay.release();
            if (drag != null) drag.cancelTap();
            if (touches != null) {
                try { touches.release(); }
                catch (RuntimeException error) { Log.e(tag, "Could not release the floating window touch area", error); }
            }
            if (attached) restoreSurface(hidden);
            if (outline != null) outline.release();
            if (overlay.isAttachedToWindow()) windows.removeViewImmediate(overlay);
            running = null;
            restored.run();
            arrivedOverCard();
        }

        void restoreSurface(boolean hidden) {
            try {
                if (!hidden && surface.hostAvailable()) {
                    SurfacePlacement preview = SurfacePlacement.fit(source,
                            new Rect(0, 0, card.getWidth(), card.getHeight()));
                    preview.crop(crop);
                    surface.restorePreview(preview.scale, preview.x, preview.y, crop);
                } else surface.restoreHidden();
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e(tag, "Could not return the live surface to its card; it stays hidden until"
                        + " Wend shows it again", error);
            }
        }
    }

    private static float lerp(float from, float to, float fraction) { return from + (to - from) * fraction; }
}
