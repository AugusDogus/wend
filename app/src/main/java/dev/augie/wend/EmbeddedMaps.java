package dev.augie.wend;

import android.app.ActivityView;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    /**
     * Watches app switches exactly while Maps floats above the video app that Wend opened on
     * the main display, rather than above the live video Wend presents itself.
     */
    private TaskFrontWatcher overVideoApp;
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

        ScreenTakeover(Takeover callbacks, Above above) {
            this.callbacks = callbacks;
            this.above = above;
        }
    }

    private ScreenTakeover takeover;
    private MapFrame takeoverFrame;
    /**
     * How long Maps, behind a frozen frame, may take to lay out at the full screen size before it
     * moves as it is. Moved earlier, Maps lays out a second time on the main display, which holds
     * the frozen frame longer, not shorter: real Maps in guidance took 0.6 to 0.9 s here on the
     * loaded emulator, and a 700 ms cap made those swaps slower.
     */
    private static final int TAKEOVER_LAYOUT_MS = 900;
    /**
     * How long Maps may take to lay out in its panel before it floats as it is: until then the
     * fullscreen video shows no navigation, and floating Maps finishes its layout live.
     */
    private static final int PANEL_LAYOUT_MS = 500;
    private final Runnable takeoverDeadline = () -> {
        Log.w("WendMap", "Maps did not lay out at the full screen size within " + TAKEOVER_LAYOUT_MS
                + " ms; moving it as it is");
        moveTakeover();
    };
    private final Runnable panelFrameDeadline = () -> {
        Log.w("WendMap", "Maps did not lay out at its panel size within " + PANEL_LAYOUT_MS
                + " ms; floating it as it is");
        panelFrameReady();
    };
    private boolean hostActive;
    private boolean launching;
    /**
     * Maps' PICTURE_IN_PICTURE app-op. Held at ignore so fullscreen Maps never shrinks itself into
     * a PiP window on plain Home; allowed only while Wend deliberately floats Maps, so Maps renders
     * its own compact navigation PiP card.
     */
    private final PipAppOp pipOp = new PipAppOp(PACKAGE);
    /**
     * True while Maps is deliberately floated into, or resting in, its own native PiP on display 0
     * for Case B. Wend coming forward (such as the Home-stack reorder that startActivityFromRecents
     * triggers as the video is placed and covers Maps) must then NOT re-adopt Maps into its panel:
     * that startActivityFromRecents back onto the virtual display is exactly what pulls Maps out of
     * PiP. Home clears it and re-adopts Maps from its PiP into the panel.
     */
    private boolean nativePipFloating;
    private final LinearLayout status;
    private final TextView message;
    private View topCorner;
    private View bottomCorner;

    EmbeddedMaps(Activity context) { this(context, new MapsTask(context)); }

    EmbeddedMaps(Activity context, MapsTask tasks) {
        super(context);
        this.tasks = tasks;
        transition = new MapTransition(context, this);
        floating = new LiveSurfaceWindow(context, BuildConfig.APPLICATION_ID + " live map", "WendMap");
        context.getWindowManager().getDefaultDisplay().getRealSize(screen);
        setBackground(Ui.surface(context, Ui.PANEL, 10));
        status = new LinearLayout(context);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setGravity(Gravity.CENTER);
        int padding = Ui.dp(context, 24);
        status.setPadding(padding, padding, padding, padding);
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
            // Wherever Maps does not cover its panel (floating out into its PiP, redrawing on its way
            // back), the panel shows Wend's color rather than Android's black.
            ActivityViewSurface.paintBackground(activityView, Ui.PANEL);
            try { motion = new EmbeddedMapMotion(context, this, activityView); }
            catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("Wend", "Live map transitions unavailable; using the covered task handoff", error);
            }
            activityView.setCallback(new ActivityView.StateCallback() {
                @Override public void onActivityViewReady(ActivityView view) {
                    try {
                        displayLifecycle.retainTasksOnRemoval();
                        state = State.READY;
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
                    post(() -> {
                        if (state != State.READY) return;
                        try {
                            if (tasks.find() == null) showError("Maps was closed. Tap Open Maps to bring it back.");
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
        // Floating into or resting in its own native PiP on display 0: never re-adopt Maps here,
        // or the adoption's startActivityFromRecents pulls it straight back out of its PiP.
        if (nativePipFloating) return;
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
        // While Maps floats in its own native PiP, Wend stopping under the fullscreen video is
        // expected; Maps stays in its PiP until Home re-adopts it.
        if (overVideoApp != null || nativePipFloating
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
        // Maps is in its own native PiP: stop floating and re-adopt it from the PiP into the panel.
        // openOnDisplay leaves the PiP for the panel directly and restores Maps' op to resting.
        if (nativePipFloating) {
            nativePipFloating = false;
            hostActive = true;
            launch();
            then.run();
            return;
        }
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

    @Override boolean beginNativePipFloat() {
        if (activityView == null || state != State.READY) return false;
        try {
            MapsTask.Task existing = tasks.find();
            if (existing == null) {
                Log.w("WendPip", "No Maps task to float as its own navigation picture-in-picture");
                return false;
            }
            pipOp.allowForFloat(getContext());
            // Bring Maps fullscreen on display 0 with a foreground activity launch, never with
            // startActivityFromRecents. A recents restore resumes Maps without re-arming its own
            // auto-PiP, so the covering task switch never makes it pin (verified on-device); a
            // launch re-arms it and still shows the ongoing route. The video Wend then brings over
            // it backgrounds Maps, which enters its own nav PiP card because its op is now allowed.
            if (existing.displayId != 0 || existing.windowingMode != SystemPip.FULLSCREEN) {
                launchOnMainDisplay();
            }
            launching = false;
            // Wend coming forward while the video is placed and covers Maps must not re-adopt it.
            nativePipFloating = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("WendPip", "Could not move Maps to the main display for its own picture-in-picture;"
                    + " Maps and its route stay where they were, and Home returns Maps to its panel", error);
            nativePipFloating = false;
            restorePipOp();
            return false;
        }
    }

    @Override void floatFromPanelIntoPip(MapsPanelPip.Result result) {
        try {
            MapsTask.Task existing = activityView == null || state != State.READY ? null : tasks.find();
            if (existing == null || existing.displayId != activityView.getVirtualDisplayId()) {
                result.stayed("Maps is not in its panel");
                return;
            }
            pipOp.allowForFloat(getContext());
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("WendPip", "Could not prepare Maps to float from its panel; Maps and its route stay in the panel", error);
            result.stayed("Maps could not be prepared to float: " + error);
            return;
        }
        // From here Maps leaves its panel on its own: Wend stopping, resuming, or seeing the panel
        // empty must not re-adopt it, or the adoption pulls Maps straight back out of its PiP.
        nativePipFloating = true;
        MapsPanelPip.request(getContext(), new MapsPanelPip.Result() {
            @Override public void entered() { result.entered(); }
            @Override public void stayed(String why) {
                nativePipFloating = false;
                restorePipOp();
                result.stayed(why);
            }
        });
    }

    /**
     * Brings the existing Maps task fullscreen on display 0 with a foreground launch (reusing the
     * task, so the ongoing route stays), which re-arms Maps' own auto-PiP. Unlike
     * {@code startActivityFromRecents} it does not reorder the Home stack, so Wend does not come
     * forward and try to re-adopt Maps.
     */
    private void launchOnMainDisplay() {
        Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (intent == null) {
            throw new IllegalStateException("Google Maps has no launch intent to float as its own picture-in-picture");
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(0);
        getContext().startActivity(intent, options.toBundle());
    }

    @Override boolean coverVideoNativePip() {
        if (activityView == null || state != State.READY) return false;
        // A direct, synchronous move, issued before the fullscreen video stops Wend: the live
        // expansion would be cancelled by suspend. Maps' op stays ignore so Maps never pins itself;
        // allowing auto-PiP on this move lets the video it covers enter its own player PiP.
        restMapsOp();
        try {
            MapsTask.Task existing = tasks.find();
            if (existing == null) return launchNew(0);
            if (existing.displayId != 0 || existing.windowingMode != SystemPip.FULLSCREEN) {
                tasks.move(existing, 0, false);
            }
            launching = false;
            status.setVisibility(View.GONE);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("WendMap", "Could not open Maps fullscreen over the floating video; existing tasks"
                    + " were left intact. Open Maps from Recents to recover your route.", error);
            showError("Maps could not open fullscreen. Open Maps from Recents to recover your route.");
            return false;
        }
    }

    private void restMapsOp() {
        try { pipOp.holdResting(getContext()); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("Wend", "Could not hold Maps' picture-in-picture app-op at ignore. Maps and its route"
                    + " are unaffected, but Home can briefly show the small PiP window before Maps returns"
                    + " to the panel. Turn it off in Settings > Apps > Special app access >"
                    + " Picture-in-picture.", error);
        }
    }

    private void restorePipOp() {
        try { pipOp.restore(getContext()); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("Wend", "Could not restore Maps' picture-in-picture app-op to ignore after its float."
                    + " Turn it off in Settings > Apps > Special app access > Picture-in-picture if Maps"
                    + " flashes a PiP on Home.", error);
        }
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
            floating.openFloating(this, surface, new RectF(0, 0, getWidth(), getHeight()),
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
                showError("Maps was closed. Tap Open Maps to bring it back.");
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
        if (activityView == null || takeover != null) return;
        // Grows live, never past pixel scale one, to its full-height panel display centered on
        // screen. A held frame then covers the task move, and crossfades to fullscreen Maps.
        floating.focus(() -> {
            if (takeover != null) return;
            ScreenTakeover started = new ScreenTakeover(next, overVideoApp != null
                    ? ScreenTakeover.Above.VIDEO_APP : ScreenTakeover.Above.LIVE_VIDEO);
            takeover = started;
            // Only Maps stays in the frozen frame, black around it: a frozen video beside it reads
            // as a stall. Read from Maps' own layers, as the screen can still be a frame short of
            // the growth's end and showed the video at Maps' edges.
            RectF centered = SurfacePlacement.centered(getWidth(), getHeight(), screen.x, screen.y).visible();
            Rect panel = new Rect(0, 0, getWidth(), getHeight());
            MapTransition.Still maps = new MapTransition.Still(() -> new ActivityViewSurface(activityView,
                    ActivityViewSurface.PreviewInput.FORWARDED).still(panel), new Rect(Math.round(centered.left),
                    Math.round(centered.top), Math.round(centered.right), Math.round(centered.bottom)));
            transition.holdThenCrossfade(maps, () -> {
                if (takeover != started) return;
                // Under the frozen frame: the video steps aside, and Maps lays out at the full
                // screen size on its own display first, as the dock expansion does. Moved at its
                // panel size, Android 10 left Wend resumed and visible behind fullscreen
                // Maps, so Home later had nothing to animate.
                next.covered();
                if (started.above == ScreenTakeover.Above.VIDEO_APP) growAboveVideoApp();
                else {
                    // Maps leaves its window for its panel, where Wend's layout sizes it.
                    floating.cancel();
                    activityView.setLayoutParams(new LayoutParams(screen.x, screen.y));
                }
                takeoverFrame = new MapFrame(screen.x, () -> post(this::moveTakeover));
                postDelayed(takeoverDeadline, TAKEOVER_LAYOUT_MS);
            }, next::revealed);
        });
    }

    /**
     * Above the video app, Maps stays in its floating window, under the frozen frame, until it
     * moves. Returned to its panel, it would show nowhere while Wend is stopped, and a display
     * shown nowhere is missing from SurfaceFlinger's layers, where Maps is seen laying out. Wend
     * may be stopped and lay out nothing, or still have its window (the video app moved off
     * Wend's display left it resumed), so the display and its view both take the full size.
     */
    private void growAboveVideoApp() {
        activityView.setLayoutParams(new LayoutParams(screen.x, screen.y));
        if (displayLifecycle == null) return;
        try { displayLifecycle.resize(screen.x, screen.y); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Could not grow Maps' display to " + screen.x + "x" + screen.y
                    + "; Maps moves at its panel size once its deadline passes and lays out fullscreen after", error);
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
            // Maps' display, kept in the floating window above the video app, now has no task.
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

    private void restorePanelSize() {
        if (activityView != null && (activityView.getLayoutParams().width != -1 || activityView.getLayoutParams().height != -1)) {
            activityView.setLayoutParams(new LayoutParams(-1, -1));
        }
        if (displayLifecycle == null) return;
        try { displayLifecycle.resizeWhileHidden(getWidth(), getHeight()); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Could not return Maps' hidden display to its panel size " + getWidth() + "x"
                    + getHeight() + "; Wend's layout does when it shows the panel again", error);
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

    /** The floating video's visual weight, in the panel's shape, anywhere on Wend's fullscreen. */
    private FloatingPlacement floatingPlacement() {
        Point size = FloatingPlacement.matchingArea(Ui.dp(getContext(), FloatingPlacement.VIDEO_WIDTH_DP),
                Ui.dp(getContext(), FloatingPlacement.VIDEO_HEIGHT_DP), getWidth() / (float) getHeight());
        return new FloatingPlacement(new Rect(0, 0, screen.x, screen.y), size.x, size.y, Ui.dp(getContext(), 16));
    }

    private void floatingEnded() {
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

    @Override void openFullscreen(Covering covering, boolean allowCoveredAutoPip) {
        cancelPendingTransition();
        openOnDisplay(0, covering, allowCoveredAutoPip);
    }

    private void openOnDisplay(int displayId, Covering request) { openOnDisplay(displayId, request, false); }

    /**
     * @param request is told once, as Maps visibly starts covering Wend on display 0, and
     *     told again if Maps then fails to move
     * @param allowCoveredAutoPip lets the app Maps covers on display 0 enter its own native PiP
     *     (its op gates whether it actually does); never set for a panel return
     */
    private void openOnDisplay(int displayId, Covering request, boolean allowCoveredAutoPip) {
        if (launching) return;
        // Hold Maps' op at ignore so it never pins itself on plain Home; a deliberate float keeps
        // it allowed (holdResting is then a no-op) until Maps is back in its panel.
        restMapsOp();
        boolean suppressAutoPip = !(displayId == 0 && allowCoveredAutoPip);
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
                            tasks.move(existing, displayId, suppressAutoPip);
                            if (displayId == 0) covering.moved(existing.id);
                            else { restorePipOp(); revealSurface(); }
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
                    // From Android's PiP there is no fullscreen Maps window to animate: Home would wait
                    // for one with the PiP still above Wend. It leaves the PiP for its panel at once.
                    if (existing.windowingMode == SystemPip.PINNED) move.run();
                    else if (motion != null) {
                        if (displayId == 0) motion.expand(move, covering);
                        else motion.returnHome(existing.id, move);
                    } else transition.move(displayId == 0, () -> {
                        move.run();
                        if (displayId == 0) transition.conceal(covering.starting());
                    });
                } else {
                    if (existing.windowingMode != 1 || displayId == 0) tasks.move(existing, displayId, suppressAutoPip);
                    if (displayId == 0) { covering.moved(existing.id); covering.starting(); }
                    if (displayId != 0) { restorePipOp(); revealSurface(); }
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

        @Override public void moved(int taskId) { if (state != State.ABANDONED) covering.moved(taskId); }
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
        cancelPendingFloat();
        cancelTakeover();
        nativePipFloating = false;
        // Never leave Maps' PiP op allowed once Wend is gone.
        restorePipOp();
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
