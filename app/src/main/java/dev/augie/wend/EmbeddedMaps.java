package dev.augie.wend;

import android.app.ActivityView;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Owns the Android 10 map display and its task transitions, without a root shell. */
final class EmbeddedMaps extends MapPanel {
    static final String PACKAGE = BuildConfig.MAPS_PACKAGE;
    private enum State { WAITING, READY, CLOSED }
    private State state = State.WAITING;
    private ActivityView activityView;
    private EmbeddedDisplayLifecycle displayLifecycle;
    private EmbeddedMapMotion motion;
    private final MapsTask tasks;
    private final MapTransition transition;
    /** Live Maps floating above the fullscreen video. Its display keeps the panel's size. */
    private final LiveSurfaceWindow floating;
    private final NavigationNotifications.Listener turns;
    /** Whether floating Maps' crop leaves room for the next-turn card; chosen as it starts floating. */
    private boolean cropUnderTurnCard;
    private static final String CLOSED = "Maps was closed. Tap Open Maps to bring it back.";
    /** Android files a crash as the app dies, up to this long before or after its task is reported closing. */
    private static final int CRASHED_BEFORE_MS = 5_000, CRASH_FILED_MS = 1_000;
    private final ExecutorService cardReader = Executors.newSingleThreadExecutor();
    private final MapsCardEdge mapsCard;
    /** The row just below Maps' own card in its display while a window floats under Wend's card, else 0. */
    private int mapsCardBottom;
    /**
     * Watches app switches exactly while Maps floats above the video app that Wend opened on
     * the main display, rather than above the live video Wend presents itself.
     */
    private TaskFrontWatcher overVideoApp;
    /** Maps' process dying is reported only as its task destroyed; see {@link #taskRemoved}. */
    private final TaskRemovalWatcher taskRemovals = new TaskRemovalWatcher("WendMap", this::taskRemoved);
    /**
     * Maps that dies again this soon after Wend reopened it cannot stay up, as when memory is short:
     * reopening it each time would loop, so the panel offers Open Maps instead.
     */
    private static final long REOPEN_AGAIN_MS = 30_000;
    /** When Wend last reopened Maps because its process had died, in uptime ms, or null if never. */
    private Long reopenedAt;
    private final BooleanSupplier resumesGuidance;
    /** Guidance that ended this long before Maps' process died was over: Maps had arrived, or the driver stopped it. */
    private static final long GUIDING_MS = 5_000;
    /** Maps' process died while it guided and the setting is on: its next opening asks for Start to be pressed. */
    private boolean resumeGuidance;
    private final Point screen = new Point();
    /** Maps leaves display 0 beneath the fullscreen video once Wend has drawn behind it. */
    private boolean returningBeneath;
    private final Runnable moveBeneath = this::moveBeneathVideo;
    /** Floats Maps once it has returned beneath the fullscreen video and laid out in its panel. */
    private Runnable pendingFloat;
    private boolean panelFrameReady;
    private MapFrame panelFrame;
    /**
     * A tap on floating Maps that is taking the screen from the video, from its frozen frame until
     * Maps moves or the takeover is called off.
     */
    private static final class ScreenTakeover {
        /** What floating Maps was above when it was tapped, which decides what Wend's stop means. */
        enum Above {
            /** The live video Wend presents whole above itself: Wend stops only for another app. */
            LIVE_VIDEO,
            /**
             * The video app Wend opened on the main display: Wend is hidden, or Android stops
             * it at any moment, such as once the video app idles, and its window may still be there.
             */
            VIDEO_APP
        }

        final Takeover callbacks;
        final Above above;
        /** The size Maps' display was given to lay out at before it grows: the screen's, unless that failed. */
        final Point display;

        ScreenTakeover(Takeover callbacks, Above above, Point display) {
            this.callbacks = callbacks;
            this.above = above;
            this.display = display;
        }
    }

    private ScreenTakeover takeover;
    private MapFrame takeoverFrame;
    /**
     * How long Maps, held still in its floating window, may take to lay out at the full screen size
     * before it grows and moves as it is. Moved earlier, Maps lays out a second time on the main
     * display: real Maps in guidance took 0.6 to 0.9 s here on the loaded emulator, and a 700 ms
     * cap made those swaps slower.
     */
    private static final int TAKEOVER_LAYOUT_MS = 900;
    /**
     * How long Maps may take to lay out in its panel before it floats as it is: until then the
     * fullscreen video shows no navigation, and floating Maps finishes its layout live.
     */
    private static final int PANEL_LAYOUT_MS = 500;
    private final Runnable takeoverDeadline = () -> {
        Log.w("WendMap", "Maps did not lay out at the full screen size within " + TAKEOVER_LAYOUT_MS
                + " ms; growing it as it is");
        if (takeover != null) growToFullscreen(takeover);
    };
    private final Runnable panelFrameDeadline = () -> {
        Log.w("WendMap", "Maps did not lay out at its panel size within " + PANEL_LAYOUT_MS
                + " ms; floating it as it is");
        panelFrameReady();
    };
    private boolean hostActive;
    private boolean launching;
    private boolean pictureInPictureChecked;
    private final LinearLayout status;
    private final TextView message;
    private View topCorner;
    private View bottomCorner;

    /** @param resumesGuidance the "resume after Maps restarts" setting, read when Maps' process dies */
    EmbeddedMaps(Activity context, BooleanSupplier resumesGuidance) {
        this(context, new MapsTask(context), resumesGuidance);
    }

    EmbeddedMaps(Activity context, MapsTask tasks, BooleanSupplier resumesGuidance) {
        super(context);
        this.tasks = tasks;
        this.resumesGuidance = resumesGuidance;
        transition = new MapTransition(context, this);
        floating = new LiveSurfaceWindow(context, BuildConfig.APPLICATION_ID + " live map", "WendMap");
        mapsCard = new MapsCardEdge(cardReader, context.getResources().getDisplayMetrics().density,
                this::topOfDisplay, this::mapsCardMeasured);
        turns = this::turnCardChanged;
        floating.turnCard(NavigationNotifications.current());
        NavigationNotifications.listen(turns);
        context.getWindowManager().getDefaultDisplay().getRealSize(screen);
        setBackground(Ui.surface(context, Ui.PANEL, 10));
        status = new LinearLayout(context);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setGravity(Gravity.CENTER);
        int padding = Ui.dp(context, 24);
        status.setPadding(padding, padding, padding, padding);
        // Opaque: without Maps, its display below shows only the background color Maps last reported,
        // which ActivityView keeps (white after a fresh start), and the message could not be read on it.
        status.setBackgroundColor(Ui.PANEL);
        message = Ui.text(context, "Opening Google Maps…", 20, Ui.TEXT);
        message.setGravity(Gravity.CENTER);
        status.addView(message);
        TextView retry = Ui.action(context, "Open Maps", this::launch);
        LinearLayout.LayoutParams retryLayout = new LinearLayout.LayoutParams(Ui.dp(context, 160), Ui.dp(context, 52));
        retryLayout.topMargin = Ui.dp(context, 20);
        status.addView(retry, retryLayout);
        addView(status, new LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT != 29 || Process.myUid() != 1000) {
            showError("This version needs your Android 10 head unit and its matching system signature.");
            return;
        }
        try {
            activityView = new ActivityView(context);
            displayLifecycle = new EmbeddedDisplayLifecycle(activityView,
                    EmbeddedDisplayLifecycle.WhenHidden.STAY_AWAKE);
            addView(activityView, 0, new LayoutParams(-1, -1));
            try { motion = new EmbeddedMapMotion(context, this, activityView); }
            catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("Wend", "Live map transitions unavailable; using the covered task handoff", error);
            }
            activityView.setCallback(new ActivityView.StateCallback() {
                @Override public void onActivityViewReady(ActivityView view) {
                    try {
                        displayLifecycle.retainTasksOnRemoval();
                        state = State.READY;
                        watchTaskRemovals();
                        if (hostActive) launch();
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        Log.e("Wend", "Could not initialize the persistent map display", error);
                        showError("The map display could not start. Open Maps from Apps while the display issue is checked.");
                    }
                }
                @Override public void onActivityViewDestroyed(ActivityView view) {
                    state = State.CLOSED;
                }
                @Override public void onTaskRemovalStarted(int taskId) {
                    launching = false;
                    long at = System.currentTimeMillis();
                    post(() -> {
                        if (state != State.READY) return;
                        try {
                            if (tasks.find() == null) mapsClosed(at);
                        } catch (ReflectiveOperationException | RuntimeException error) {
                            Log.e("Wend", "Could not check Maps after task removal", error);
                            showError("Maps task status could not be checked. Tap Open Maps to retry.");
                        }
                    });
                }
                @Override public void onTaskCreated(int taskId, ComponentName component) {
                    if (component != null && PACKAGE.equals(component.getPackageName())) {
                        tasks.remember(taskId);
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.e("Wend", "ActivityView creation failed", error);
            showError("The map panel could not start. Open Maps from Apps while the display issue is checked.");
        }
    }

    /** Guidance began, ended, or reached its next step: floating Maps shows the card, and its map makes room. */
    private void turnCardChanged(NavigationCard card) {
        floating.turnCard(card);
        boolean under = card != null;
        // A tap taking the screen holds the window still and grows it from the crop it has.
        if (under == cropUnderTurnCard || takeover != null || !floating.active()) return;
        cropUnderTurnCard = under;
        watchMapsCard();
        floating.floatingSource(floatingCrop());
    }

    /** Maps' own card is measured while a window floats with Wend's card: the window's map starts below it. */
    private void watchMapsCard() {
        if (floating.active() && cropUnderTurnCard) { mapsCard.start(); return; }
        mapsCard.stop();
        mapsCardBottom = 0;
    }

    private MapsCardEdge.Read topOfDisplay(int rows) throws ReflectiveOperationException {
        if (activityView == null) return null;
        ActivityViewSurface surface = new ActivityViewSurface(activityView, ActivityViewSurface.PreviewInput.FORWARDED);
        Rect top = new Rect(0, 0, getWidth(), Math.min(rows, getHeight()));
        return () -> surface.still(top);
    }

    private void mapsCardMeasured(int bottom) {
        // A tap taking the screen holds the window still and grows it from the crop it has.
        if (bottom == mapsCardBottom || takeover != null || !floating.active()) return;
        mapsCardBottom = bottom;
        floating.floatingSource(floatingCrop());
    }

    /**
     * Android said Maps' last task is closing: the driver closed Maps, or Maps crashed. A crashed
     * Maps reopens like one whose process was killed ({@link #taskRemoved}); a closed one stays
     * closed behind its message.
     *
     * @param at when Android said so, in wall-clock ms
     */
    private void mapsClosed(long at) {
        if (mapsCrashed(at)) return;
        showError(CLOSED);
        // Android files a crash on another thread, which can finish after the task is gone.
        postDelayed(() -> {
            if (state != State.READY || status.getVisibility() != View.VISIBLE
                    || !CLOSED.contentEquals(message.getText()) || !mapsCrashed(at)
                    || !mapsGone("after its crash was filed")) return;
            status.setVisibility(View.GONE);
            if (reopensDead("after it crashed")) launch();
        }, CRASH_FILED_MS);
    }

    private boolean mapsCrashed(long closedAt) {
        try {
            DropBoxManager dropBox = (DropBoxManager) getContext().getSystemService(Context.DROPBOX_SERVICE);
            return dropBox != null && AppCrashes.since(dropBox, PACKAGE, closedAt - CRASHED_BEFORE_MS);
        } catch (RuntimeException error) {
            Log.w("WendMap", "Could not read Android's crash log to tell whether Maps crashed or was closed."
                    + " Treating it as closed: its panel offers Open Maps, which also restores its route", error);
            return false;
        }
    }

    private void watchTaskRemovals() {
        try { taskRemovals.start(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Could not watch for Maps' task being destroyed. If Maps' process dies, its"
                    + " panel stays empty until Home is pressed, which reopens Maps", error);
        }
    }

    /**
     * Android destroyed a task. If Maps now has none, its display, in its panel or floating, is
     * empty: black, or whatever color Maps last reported. Its empty floating window closes.
     *
     * <p>If no message says Maps closed, its process died (killed, or out of memory): Android 10
     * reports that no other way. Maps then reopens in its panel: now if Wend shows, otherwise when
     * it next does ({@link #resume}).
     */
    private void taskRemoved(int taskId) {
        // A launch has no task yet.
        if (state != State.READY || launching || !mapsGone("after task " + taskId + " was destroyed")) return;
        cancelTakeover();
        cancelPendingFloat();
        // A message already says Maps closed and offers Open Maps: nothing reopens under it.
        boolean reopen = status.getVisibility() != View.VISIBLE
                && reopensDead("after task " + taskId + " was destroyed, so its process died");
        // The end of the float asks for Maps in its panel, unless a message stays (floatingEnded).
        if (floating.active()) floating.dismiss("Maps is gone");
        else if (reopen) launch();
    }

    /**
     * Maps is gone without the driver closing it. Unless it also went less than
     * {@link #REOPEN_AGAIN_MS} after Wend last reopened it, it is to reopen, and to resume its
     * guidance if it guided and the setting allows.
     *
     * @param how what happened, for the log
     * @return whether to reopen it; if not, a message offers Open Maps
     */
    private boolean reopensDead(String how) {
        long now = SystemClock.uptimeMillis();
        boolean again = reopenedAt != null && now - reopenedAt < REOPEN_AGAIN_MS;
        Log.w("WendMap", "Maps has no task left " + how + ". "
                + (again ? "Wend reopened it " + (now - reopenedAt) + " ms ago, so the panel offers Open Maps"
                        + " instead of reopening it again"
                        : hostActive ? "Reopening it in its panel" : "It reopens in its panel when Wend next shows"));
        if (again) showError("Maps keeps closing. Tap Open Maps to try again.");
        else reopenedAt = now;
        // Asked as Maps next opens (launchNew), which is on Home if Wend is hidden now.
        resumeGuidance = resumesGuidance.getAsBoolean() && NavigationNotifications.guidedWithin(GUIDING_MS);
        return !again;
    }

    /** @return whether Maps has no task, so only opening it again shows it; false if that cannot be checked */
    private boolean mapsGone(String when) {
        try { return tasks.find() == null; }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Could not check for Maps' task " + when + "; if Maps is gone, its panel stays"
                    + " empty until Home is pressed, which reopens Maps", error);
            return false;
        }
    }

    void launch() {
        if (!hostActive || state != State.READY || activityView == null || floating.active()) return;
        if (motion != null && motion.pending()) return;
        cancelPendingTransition();
        if (launching) return;
        openOnDisplay(activityView.getVirtualDisplayId(), INTO_PANEL);
    }

    /** Maps returning to its panel covers nothing of Wend. */
    private static final Covering INTO_PANEL = new Covering() {
        @Override public Rect starting() { return null; }
        @Override public void abandoned() { }
    };

    void resume() {
        hostActive = true;
        // Wend shows again: a tap taking the screen is called off with its frozen frame.
        cancelTakeover();
        // The video app left, or failed to open: Maps returns from above it to its panel.
        if (overVideoApp != null) { returnFromFloating(); return; }
        if (returningBeneath) {
            // Wend now covers fullscreen Maps under the video. Move Maps after Wend's
            // next frame, once Maps has been hidden behind it and the panel is laid out.
            getViewTreeObserver().registerFrameCommitCallback(() -> post(moveBeneath));
            postDelayed(moveBeneath, 300);
            invalidate();
            return;
        }
        if (motion != null && motion.expanding()) cancelPendingTransition();
        launch();
        floatWhenReady();
    }
    void suspend() {
        hostActive = false;
        // Above the video app Wend opened, Wend's stop is expected whenever Android
        // delivers it, also halfway through a tap taking the screen: Maps keeps floating, or keeps
        // going fullscreen, until another app comes to the front.
        if (overVideoApp != null
                || takeover != null && takeover.above == ScreenTakeover.Above.VIDEO_APP) return;
        cancelPendingTransition();
        cancelPendingFloat();
        cancelTakeover();
        // Another app replaced Wend and the video below; Maps waits hidden in its panel.
        floating.cancel();
    }

    @Override void home(Runnable then) {
        cancelPendingFloat();
        // Home wins over a takeover still behind its frozen frame, and over a covered move that
        // has not run yet; resume() then asks for Maps in its panel again.
        cancelTakeover();
        if (transition.active()) {
            transition.cancel();
            launching = false;
        }
        if (!floating.active()) { resume(); then.run(); return; }
        hostActive = true;
        // Lands over its panel, still above the fullscreen video, which then shrinks away below it.
        floating.returnOverCard(then);
    }

    @Override boolean floatAboveVideo(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener,
            LiveSurfaceWindow.Entry entry) {
        if (!hostActive || !canFloat() || !openFloating(corner, listener, entry)) return false;
        Log.i("WendMap", "Floating live Maps above the video in the " + corner + " corner");
        return true;
    }

    @Override boolean floatAboveVideoApp(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener) {
        if (!canFloat()) return false;
        TaskFrontWatcher appSwitches = new TaskFrontWatcher("WendMap", this::appMovedToFront);
        try { appSwitches.start(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Floating Maps unavailable: app switches cannot be observed, so it could"
                    + " stay above another app; Maps stays in its panel", error);
            return false;
        }
        overVideoApp = appSwitches;
        // Moves to its corner as the video app opens; the end of the float stops the watcher.
        if (!openFloating(corner, listener, LiveSurfaceWindow.Entry.FROM_CARD)) {
            stopWatchingApps();
            return false;
        }
        Log.i("WendMap", "Floating live Maps above the video app in the " + corner + " corner");
        return true;
    }

    private boolean canFloat() {
        return activityView != null && displayLifecycle != null && state == State.READY && !launching
                && !floating.active() && status.getVisibility() != View.VISIBLE && isLaidOut()
                && !(motion != null && motion.pending()) && !transition.pending();
    }

    /** Lifts Maps out of its panel, if it is there, into the floating window. */
    private boolean openFloating(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener,
            LiveSurfaceWindow.Entry entry) {
        try {
            MapsTask.Task existing = tasks.find();
            if (existing == null || existing.displayId != activityView.getVirtualDisplayId()) return false;
            ActivityViewSurface surface = new ActivityViewSurface(activityView,
                    ActivityViewSurface.PreviewInput.FORWARDED);
            // Before anything can hide Wend: its surface changes must not move this root.
            displayLifecycle.presentElsewhere();
            cropUnderTurnCard = NavigationNotifications.current() != null;
            floating.openFloating(this, surface, new RectF(0, 0, getWidth(), getHeight()), floatingCrop(),
                    new Point(getWidth(), getHeight()), floatingPlacement(), corner, entry, listener,
                    this::floatingEnded);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Floating Maps unavailable; Maps stays in its panel under the video", error);
            floating.cancel();
        }
        if (!floating.active()) {
            displayLifecycle.returnToHost();
            return false;
        }
        watchMapsCard();
        return true;
    }

    private void appMovedToFront(String packageName, int displayId) {
        if (overVideoApp == null) return;
        // Wend showing again returns Maps to its panel (resume); Maps and the video app are its own.
        if (packageName.equals(getContext().getPackageName()) || packageName.equals(PACKAGE)
                || packageName.equals(EmbeddedVideo.PACKAGE)) return;
        // A tap taking the screen is called off with its frozen frame: Maps never moves over that app.
        cancelTakeover();
        floating.dismiss(packageName + " came to the front on display " + displayId);
    }

    private void stopWatchingApps() {
        if (overVideoApp == null) return;
        overVideoApp.stop();
        overVideoApp = null;
    }

    @Override void leavePanel() { floating.leaveCard(); }

    @Override void returnFromFloating() {
        // Wend may still be starting, after Home or as the video app leaves: Maps stays
        // floating until Wend's window can show its panel again.
        if (displayLifecycle != null) displayLifecycle.whenHostReady(floating::collapse);
        else floating.collapse();
    }

    @Override void returnBeneathVideo(Runnable ready) {
        cancelPendingTransition();
        cancelPendingFloat();
        if (activityView == null || state != State.READY) return;
        pendingFloat = ready;
        returningBeneath = true;
        if (hostActive) { moveBeneathVideo(); return; }
        // Wend first, as on Home, all hidden by the video. Maps then leaves display 0 from
        // behind it, and Android recreates its windows at the panel's size. Moved while still
        // visible, Maps had to resize them in place, and the fixture's map SurfaceView sometimes
        // kept its fullscreen-sized buffer in the panel.
        try { tasks.showHost(); }
        catch (RuntimeException error) {
            Log.w("WendMap", "Could not bring Wend forward first; moving Maps beneath the video now", error);
            moveBeneathVideo();
        }
    }

    private void moveBeneathVideo() {
        if (!returningBeneath) return;
        returningBeneath = false;
        removeCallbacks(moveBeneath);
        int panelDisplay = activityView.getVirtualDisplayId();
        try {
            MapsTask.Task existing = tasks.find();
            if (existing == null) {
                Log.w("WendMap", "No Maps task to return beneath the video; Maps does not float,"
                        + " and the panel says Maps was closed and offers Open Maps");
                showError(CLOSED);
                cancelPendingFloat();
                return;
            }
            // No motion: the video covers the whole screen.
            if (existing.displayId != panelDisplay) tasks.move(existing, panelDisplay);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("WendMap", "Could not return Maps beneath the fullscreen video to display " + panelDisplay, error);
            showError("Maps could not be restored. Open Maps from Recents to recover your route.");
            cancelPendingFloat();
            return;
        }
        launch();
        if (pendingFloat == null) return;
        panelFrame = new MapFrame(getWidth(), () -> post(this::panelFrameReady));
        postDelayed(panelFrameDeadline, PANEL_LAYOUT_MS);
    }

    @Override void openFullscreenFromFloating(Takeover next) {
        if (activityView == null || takeover != null || !floating.floating()) return;
        // Feedback: Maps grew to its panel's size, centered, and only then, after a frozen frame,
        // to full screen. Now the floating window holds still while Maps lays out at the full
        // screen size behind it, as the dock expansion does first, then grows straight to full
        // screen with Maps already in its fullscreen layout. Moved at its panel size, Android 10
        // left Wend resumed and visible behind fullscreen Maps, so Home had nothing to animate.
        RectF shown = floatingCrop();
        try {
            floating.freeze(new ActivityViewSurface(activityView, ActivityViewSurface.PreviewInput.FORWARDED)
                    .still(new Rect(Math.round(shown.left), Math.round(shown.top),
                            Math.round(shown.right), Math.round(shown.bottom))));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Could not hold floating Maps still while it lays out at full screen size;"
                    + " its relayout shows in the floating window until it grows", error);
        }
        ScreenTakeover started = new ScreenTakeover(next, overVideoApp != null
                ? ScreenTakeover.Above.VIDEO_APP : ScreenTakeover.Above.LIVE_VIDEO, layOutFullscreen());
        takeover = started;
        takeoverFrame = new MapFrame(started.display.x, () -> post(() -> growToFullscreen(started)));
        postDelayed(takeoverDeadline, TAKEOVER_LAYOUT_MS);
    }

    /** Maps has laid out at the full screen size, or its deadline passed: it grows straight there. */
    private void growToFullscreen(ScreenTakeover started) {
        if (takeover != started) return;
        stopWaitingForTakeover();
        Point display = started.display;
        floating.focus(FloatingMapCrop.of(display.x, display.y, getResources().getDisplayMetrics().density, cropUnderTurnCard, mapsCardBottom),
                display, () -> {
                    if (takeover != started) return;
                    // A frozen frame of exactly what Maps shows now covers the task move. Read from
                    // Maps' own layers, as the screen can still be a frame short of the growth's end.
                    Rect whole = new Rect(0, 0, display.x, display.y);
                    RectF at = SurfacePlacement.centered(display.x, display.y, screen.x, screen.y).visible();
                    MapTransition.Still maps = new MapTransition.Still(() -> new ActivityViewSurface(activityView,
                            ActivityViewSurface.PreviewInput.FORWARDED).still(whole), new Rect(Math.round(at.left),
                            Math.round(at.top), Math.round(at.right), Math.round(at.bottom)));
                    transition.holdThenCrossfade(maps, () -> {
                        if (takeover != started) return;
                        // Under the frozen frame the video steps aside, then Maps moves as laid out.
                        started.callbacks.covered();
                        moveTakeover();
                    }, started.callbacks::revealed);
                });
    }

    /**
     * Maps stays in its floating window, behind its still, until it moves: returned to its panel
     * above the video app, it would show nowhere while Wend is stopped, and a display shown
     * nowhere is missing from SurfaceFlinger's layers, where Maps is seen laying out. Wend may be
     * stopped and lay out nothing, or still have its window (above the live video, or when the
     * video app moved off Wend's display left it resumed), so the display itself is resized.
     * Its view keeps the panel's size: the display's root surface is the view's SurfaceView
     * surface, and laying that out at a new size re-places it at the view's own geometry, which
     * showed a larger, unscaled Maps around the held still on the fixture emulator.
     */
    private Point layOutFullscreen() {
        Point panel = new Point(getWidth(), getHeight());
        if (displayLifecycle == null) return panel;
        try {
            displayLifecycle.resize(screen.x, screen.y);
            return screen;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Could not grow Maps' display to " + screen.x + "x" + screen.y
                    + "; Maps grows and moves at its panel size once its deadline passes and lays out"
                    + " fullscreen after", error);
            return panel;
        }
    }

    private void moveTakeover() {
        ScreenTakeover moving = takeover;
        if (moving == null) return;
        stopWaitingForTakeover();
        takeover = null;
        try {
            MapsTask.Task existing = tasks.find();
            if (existing == null) throw new IllegalStateException("No Maps task is running");
            tasks.move(existing, 0);
            transition.settleHeld();
        } catch (ReflectiveOperationException | RuntimeException error) {
            transition.cancel();
            Log.e("WendMap", "Could not open floating Maps fullscreen on display 0", error);
            showError("Maps could not open fullscreen. Tap Open Maps; existing tasks were left intact.");
            moving.callbacks.failed();
        } finally {
            // Maps' display, kept in the floating window until it moved, now has no task.
            floating.cancel();
            post(this::restorePanelSize);
        }
    }

    private void cancelTakeover() {
        if (takeover == null) return;
        takeover = null;
        stopWaitingForTakeover();
        // Its frozen frame never stays above whatever called it off.
        transition.cancel();
        restorePanelSize();
    }

    private void stopWaitingForTakeover() {
        removeCallbacks(takeoverDeadline);
        if (takeoverFrame != null) takeoverFrame.close();
        takeoverFrame = null;
    }

    /** Maps' display, resized directly for a takeover, takes its panel's size again, as its view has. */
    private void restorePanelSize() {
        if (displayLifecycle == null) return;
        try { displayLifecycle.resize(getWidth(), getHeight()); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Could not return Maps' display to its panel size " + getWidth() + "x"
                    + getHeight() + "; the panel may show it cropped until Wend's window is recreated", error);
        }
    }

    private void panelFrameReady() {
        stopWaitingForPanelFrame();
        if (pendingFloat == null) return;
        panelFrameReady = true;
        floatWhenReady();
    }

    private void floatWhenReady() {
        if (pendingFloat == null || !panelFrameReady || !hostActive) return;
        Runnable ready = pendingFloat;
        pendingFloat = null;
        panelFrameReady = false;
        ready.run();
    }

    private void cancelPendingFloat() {
        returningBeneath = false;
        removeCallbacks(moveBeneath);
        pendingFloat = null;
        panelFrameReady = false;
        stopWaitingForPanelFrame();
    }

    private void stopWaitingForPanelFrame() {
        removeCallbacks(panelFrameDeadline);
        if (panelFrame != null) panelFrame.close();
        panelFrame = null;
    }

    /** The map alone, in portrait, from the panel display Maps keeps while it floats. */
    private RectF floatingCrop() {
        return FloatingMapCrop.of(getWidth(), getHeight(), getResources().getDisplayMetrics().density, cropUnderTurnCard, mapsCardBottom);
    }

    /** The floating video's visual weight, in the map crop's shape, anywhere on Wend's fullscreen. */
    private FloatingPlacement floatingPlacement() {
        RectF crop = floatingCrop();
        Point size = FloatingPlacement.matchingArea(Ui.dp(getContext(), FloatingPlacement.VIDEO_WIDTH_DP),
                Ui.dp(getContext(), FloatingPlacement.VIDEO_HEIGHT_DP), crop.width() / crop.height());
        return new FloatingPlacement(new Rect(0, 0, screen.x, screen.y), size.x, size.y, Ui.dp(getContext(), 16));
    }

    private void floatingEnded() {
        watchMapsCard();
        stopWatchingApps();
        if (displayLifecycle != null) displayLifecycle.returnToHost();
        if (hostActive) {
            try {
                // The same panel geometry and input every return from fullscreen applies. Maps
                // only floats without a message, so any message now, such as Maps having
                // closed while it floated, stays with its Open Maps button.
                revealSurface();
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e("WendMap", "Could not return floating Maps to its panel input and geometry", error);
                showError("Maps could not return to this panel. Tap Open Maps; your route is kept.");
            }
            // Maps' process can die while it floats (see taskRemoved). Every other return to the
            // panel asks for Maps there; this one did not, and the panel stayed black after Home.
            if (status.getVisibility() != View.VISIBLE && mapsGone("as its floating window returned")) launch();
        }
    }

    private void cancelPendingTransition() {
        if (motion != null && motion.pending()) {
            motion.cancel();
            launching = false;
        }
        if (!transition.pending()) return;
        transition.cancel();
        launching = false;
    }

    @Override void openFullscreen(Covering covering) { cancelPendingTransition(); openOnDisplay(0, covering); }

    /**
     * @param request is told once, as Maps visibly starts covering Wend on display 0, and
     *     told again if Maps then fails to move
     */
    private void openOnDisplay(int displayId, Covering request) {
        if (launching) return;
        preventSystemPictureInPicture();
        CoverOnce covering = new CoverOnce(request);
        try {
            MapsTask.Task existing = tasks.find();
            if (existing != null) {
                if (existing.displayId != displayId) {
                    launching = true;
                    Runnable move = () -> {
                        launching = false;
                        if (state == State.CLOSED) { covering.abandoned(); return; }
                        try {
                            tasks.move(existing, displayId);
                            if (displayId != 0) revealSurface();
                            status.setVisibility(View.GONE);
                        } catch (ReflectiveOperationException | RuntimeException error) {
                            transition.cancel();
                            Log.e("Wend", "Could not move Maps task " + existing.id + " from display "
                                    + existing.displayId + " to " + displayId + "; Maps stays where it was"
                                    + " and the panel offers Open Maps", error);
                            showError("Maps could not be restored. Open Maps from Recents to recover your route.");
                            covering.abandoned();
                        }
                    };
                    if (motion != null) {
                        if (displayId == 0) motion.expand(move, covering);
                        else motion.returnHome(existing.id, move);
                    } else transition.move(displayId == 0, () -> {
                        move.run();
                        if (displayId == 0) transition.conceal(covering.starting());
                    });
                } else {
                    if (existing.windowingMode != 1 || displayId == 0) tasks.move(existing, displayId);
                    if (displayId == 0) covering.starting();
                    if (displayId != 0) revealSurface();
                    status.setVisibility(View.GONE);
                }
                return;
            }
            if (launchNew(displayId) && displayId == 0) covering.starting();
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("Wend", "Could not reuse Maps task on display " + displayId, error);
            showError("Maps could not be restored. Existing tasks were left intact. Open Maps from Recents to recover your route.");
        }
    }

    // Home from fullscreen Maps calls its onUserLeaveHint, and navigation apps then enter
    // PiP before this panel can reclaim the task, flashing the small system window.
    private void preventSystemPictureInPicture() {
        if (pictureInPictureChecked) return;
        try {
            // An uninstalled Maps is checked again on the next launch request.
            pictureInPictureChecked = MapsPictureInPicture.disable(getContext(), PACKAGE)
                    != MapsPictureInPicture.Outcome.NOT_INSTALLED;
        } catch (ReflectiveOperationException | RuntimeException error) {
            pictureInPictureChecked = true;
            Log.e("Wend", "Could not turn off system picture-in-picture for " + PACKAGE
                    + ". Maps and its route are unaffected, but Home can briefly show the small PiP"
                    + " window before Maps returns to the panel. Turn it off in Settings > Apps >"
                    + " Special app access > Picture-in-picture.", error);
        }
    }

    private void revealSurface() throws ReflectiveOperationException {
        if (activityView != null) new ActivityViewSurface(activityView, ActivityViewSurface.PreviewInput.FORWARDED).show(
                new Rect(0, 0, getWidth(), getHeight()));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // A new map width from Settings: Maps' root keeps the previous crop and input region,
        // showing a black, untouchable strip, until it is fitted to the panel again.
        if (oldWidth != 0 && oldHeight != 0) post(this::fitToResizedPanel);
    }

    private void fitToResizedPanel() {
        if (!hostActive || state != State.READY || activityView == null || launching || floating.active()
                || takeover != null || transition.active() || (motion != null && motion.pending())) return;
        try {
            MapsTask.Task existing = tasks.find();
            if (existing != null && existing.displayId == activityView.getVirtualDisplayId()) revealSurface();
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("WendMap", "Could not fit Maps to its resized panel (" + getWidth() + "x" + getHeight()
                    + "); part of the panel may stay black until Home shows Maps again", error);
        }
    }

    /** @return whether Android accepted the launch request */
    private boolean launchNew(int displayId) {
        Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (intent == null) {
            showError("Google Maps is not installed. Install Maps, then tap Open Maps.");
            return false;
        }
        try {
            if (displayId == 0) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(0);
                getContext().startActivity(intent, options.toBundle());
            } else if (activityView != null) {
                launching = true;
                PendingIntent pending = PendingIntent.getActivity(getContext(), 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                try {
                    activityView.startActivity(pending);
                } finally {
                    // PendingIntent.send returns after the launch request. Android can reuse
                    // a task missed by find(), in which case onTaskCreated never arrives.
                    // Waiting for that callback would disable both Home restoration and the dock.
                    launching = false;
                }
            }
            status.setVisibility(View.GONE);
            Log.i("Wend", "Requested Maps launch after no task was found, display " + displayId);
            if (resumeGuidance) {
                resumeGuidance = false;
                GuidanceResume.request(getContext());
            }
            return true;
        } catch (RuntimeException error) {
            launching = false;
            Log.e("Wend", "Could not launch Google Maps in ActivityView", error);
            showError("Maps could not open in this panel. Tap Open Maps to retry, or open it from Apps.");
            return false;
        }
    }

    /**
     * Starts covering at most once and never after the move failed; undoes it if the move fails
     * after it started, as when the live expansion floats the video before the task moves.
     */
    static final class CoverOnce implements Covering {
        private enum State { WAITING, COVERING, ABANDONED }
        private final Covering covering;
        private State state = State.WAITING;

        CoverOnce(Covering covering) { this.covering = covering; }

        @Override public Rect starting() {
            if (state != State.WAITING) return null;
            state = State.COVERING;
            return covering.starting();
        }

        @Override public void abandoned() {
            State was = state;
            state = State.ABANDONED;
            if (was == State.COVERING) covering.abandoned();
        }
    }

    void setInnerCorners(boolean mapsOnRight) {
        float radius = Ui.dp(getContext(), 20);
        GradientDrawable background = Ui.surface(getContext(), Ui.PANEL, 0);
        background.setCornerRadii(mapsOnRight
                ? new float[]{radius, radius, 0, 0, 0, 0, radius, radius}
                : new float[]{0, 0, radius, radius, radius, radius, 0, 0});
        setBackground(background);
        if (topCorner != null) removeView(topCorner);
        if (bottomCorner != null) removeView(bottomCorner);
        topCorner = addCorner(mapsOnRight ? MapCornerMask.Corner.TOP_LEFT : MapCornerMask.Corner.TOP_RIGHT);
        bottomCorner = addCorner(mapsOnRight ? MapCornerMask.Corner.BOTTOM_LEFT : MapCornerMask.Corner.BOTTOM_RIGHT);
    }

    private View addCorner(MapCornerMask.Corner corner) {
        View mask = new MapCornerMask(getContext(), corner);
        int size = Ui.dp(getContext(), 20);
        addView(mask, new LayoutParams(size, size, corner.gravity));
        return mask;
    }

    private void showError(String text) {
        message.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    void release() {
        taskRemovals.stop();
        NavigationNotifications.stop(turns);
        mapsCard.stop();
        cardReader.shutdownNow();
        cancelPendingFloat();
        cancelTakeover();
        floating.release();
        if (motion != null) motion.release();
        transition.cancel();
        if (activityView == null || state == State.CLOSED) return;
        if (state == State.READY) {
            try {
                MapsTask.Task existing = tasks.find();
                if (existing != null && existing.displayId == activityView.getVirtualDisplayId()) {
                    tasks.move(existing, 0);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e("Wend", "Could not return Maps to the main display before launcher destruction", error);
            }
        }
        activityView.setCallback(null);
        if (displayLifecycle != null) displayLifecycle.release();
        if (activityView.getVirtualDisplayId() != Display.INVALID_DISPLAY) activityView.release();
        state = State.CLOSED;
    }
}
