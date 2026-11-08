package dev.augie.roadhome;

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
    private final Point screen = new Point();
    /** Maps leaves display 0 beneath the fullscreen video once Road Home has drawn behind it. */
    private boolean returningBeneath;
    private final Runnable moveBeneath = this::moveBeneathVideo;
    /** Floats Maps once it has returned beneath the fullscreen video and laid out in its panel. */
    private Runnable pendingFloat;
    private boolean panelFrameReady;
    private MapFrame panelFrame;
    /** A tap on floating Maps that is taking the screen from the video, until Maps moves. */
    private Takeover takeover;
    private MapFrame takeoverFrame;
    private final Runnable takeoverDeadline = () -> {
        Log.w("RoadHomeMap", "Maps did not lay out at the full screen size within 900 ms; moving it as it is");
        moveTakeover();
    };
    private final Runnable panelFrameDeadline = () -> {
        Log.w("RoadHomeMap", "Maps did not lay out at its panel size within 900 ms; floating it as it is");
        panelFrameReady();
    };
    private boolean hostActive;
    private boolean launching;
    private boolean pictureInPictureChecked;
    private final LinearLayout status;
    private final TextView message;
    private View topCorner;
    private View bottomCorner;

    EmbeddedMaps(Activity context) { this(context, new MapsTask(context)); }

    EmbeddedMaps(Activity context, MapsTask tasks) {
        super(context);
        this.tasks = tasks;
        transition = new MapTransition(context, this);
        floating = new LiveSurfaceWindow(context, BuildConfig.APPLICATION_ID + " live map", "RoadHomeMap");
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
            try { motion = new EmbeddedMapMotion(context, this, activityView); }
            catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("RoadHome", "Live map transitions unavailable; using the covered task handoff", error);
            }
            activityView.setCallback(new ActivityView.StateCallback() {
                @Override public void onActivityViewReady(ActivityView view) {
                    try {
                        displayLifecycle.retainTasksOnRemoval();
                        state = State.READY;
                        if (hostActive) launch();
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        Log.e("RoadHome", "Could not initialize the persistent map display", error);
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
                            Log.e("RoadHome", "Could not check Maps after task removal", error);
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
            Log.e("RoadHome", "ActivityView creation failed", error);
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

    /** Maps returning to its panel covers nothing of Road Home. */
    private static final Covering INTO_PANEL = new Covering() {
        @Override public Rect starting() { return null; }
        @Override public void abandoned() { }
    };

    void resume() {
        hostActive = true;
        if (returningBeneath) {
            // Road Home now covers fullscreen Maps under the video. Move Maps after Road Home's
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
        cancelPendingTransition();
        cancelPendingFloat();
        cancelTakeover();
        // Another app replaced Road Home and the video below; Maps waits hidden in its panel.
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
        if (activityView == null || displayLifecycle == null || state != State.READY || !hostActive
                || launching || floating.active() || status.getVisibility() == View.VISIBLE || !isLaidOut()
                || (motion != null && motion.pending()) || transition.pending()) return false;
        try {
            MapsTask.Task existing = tasks.find();
            if (existing == null || existing.displayId != activityView.getVirtualDisplayId()) return false;
            ActivityViewSurface surface = new ActivityViewSurface(activityView);
            // Before anything can hide Road Home: its surface changes must not move this root.
            displayLifecycle.presentElsewhere();
            floating.openFloating(this, surface, new RectF(0, 0, getWidth(), getHeight()),
                    new Point(getWidth(), getHeight()), floatingPlacement(), corner, entry, listener,
                    this::floatingEnded);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("RoadHomeMap", "Floating Maps unavailable; Maps stays in its panel under the video", error);
            floating.cancel();
        }
        if (!floating.active()) {
            displayLifecycle.returnToHost();
            return false;
        }
        Log.i("RoadHomeMap", "Floating live Maps above the video in the " + corner + " corner");
        return true;
    }

    @Override void leavePanel() { floating.leaveCard(); }

    @Override void returnFromFloating() { floating.collapse(); }

    @Override void returnBeneathVideo(Runnable ready) {
        cancelPendingTransition();
        cancelPendingFloat();
        if (activityView == null || state != State.READY) return;
        pendingFloat = ready;
        returningBeneath = true;
        if (hostActive) { moveBeneathVideo(); return; }
        // Road Home first, as on Home, all hidden by the video. Maps then leaves display 0 from
        // behind it, and Android recreates its windows at the panel's size. Moved while still
        // visible, Maps had to resize them in place, and the fixture's map SurfaceView sometimes
        // kept its fullscreen-sized buffer in the panel.
        try { tasks.showHost(); }
        catch (RuntimeException error) {
            Log.w("RoadHomeMap", "Could not bring Road Home forward first; moving Maps beneath the video now", error);
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
                Log.w("RoadHomeMap", "No Maps task to return beneath the video; Maps does not float,"
                        + " and the panel says Maps was closed and offers Open Maps");
                showError("Maps was closed. Tap Open Maps to bring it back.");
                cancelPendingFloat();
                return;
            }
            // No motion: the video covers the whole screen.
            if (existing.displayId != panelDisplay) tasks.move(existing, panelDisplay);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("RoadHomeMap", "Could not return Maps beneath the fullscreen video to display " + panelDisplay, error);
            showError("Maps could not be restored. Open Maps from Recents to recover your route.");
            cancelPendingFloat();
            return;
        }
        launch();
        if (pendingFloat == null) return;
        panelFrame = new MapFrame(getWidth(), () -> post(this::panelFrameReady));
        postDelayed(panelFrameDeadline, 900);
    }

    @Override void openFullscreenFromFloating(Takeover next) {
        if (activityView == null || takeover != null) return;
        // Grows live, never past pixel scale one, to its full-height panel display centered on
        // screen. A held frame then covers the task move, and crossfades to fullscreen Maps.
        floating.focus(() -> transition.holdThenCrossfade(() -> {
            // Under the frozen frame: Maps leaves its window for its panel, the video steps aside,
            // and Maps lays out at the full screen size on its own display first, as the dock
            // expansion does. Moved at its panel size, Android 10 left Road Home resumed and
            // visible behind fullscreen Maps, so Home later had nothing to animate.
            floating.cancel();
            takeover = next;
            next.covered();
            activityView.setLayoutParams(new LayoutParams(screen.x, screen.y));
            takeoverFrame = new MapFrame(screen.x, () -> post(this::moveTakeover));
            postDelayed(takeoverDeadline, 900);
        }, next::revealed));
    }

    private void moveTakeover() {
        Takeover moving = takeover;
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
            Log.e("RoadHomeMap", "Could not open floating Maps fullscreen on display 0", error);
            showError("Maps could not open fullscreen. Tap Open Maps; existing tasks were left intact.");
            moving.failed();
        } finally {
            post(this::restorePanelSize);
        }
    }

    private void cancelTakeover() {
        if (takeover == null) return;
        takeover = null;
        stopWaitingForTakeover();
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

    /** The floating video's visual weight, in the panel's shape, anywhere on Road Home's fullscreen. */
    private FloatingPlacement floatingPlacement() {
        Point size = FloatingPlacement.matchingArea(Ui.dp(getContext(), FloatingPlacement.VIDEO_WIDTH_DP),
                Ui.dp(getContext(), FloatingPlacement.VIDEO_HEIGHT_DP), getWidth() / (float) getHeight());
        return new FloatingPlacement(new Rect(0, 0, screen.x, screen.y), size.x, size.y, Ui.dp(getContext(), 16));
    }

    private void floatingEnded() {
        if (displayLifecycle != null) displayLifecycle.returnToHost();
        if (hostActive) {
            try {
                // The same panel geometry and input every return from fullscreen applies. Maps
                // only floats without a message, so any message now, such as Maps having
                // closed while it floated, stays with its Open Maps button.
                revealSurface();
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e("RoadHomeMap", "Could not return floating Maps to its panel input and geometry", error);
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

    @Override void openFullscreen(Covering covering) { cancelPendingTransition(); openOnDisplay(0, covering); }

    /**
     * @param request is told once, as Maps visibly starts covering Road Home on display 0, and
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
                            Log.e("RoadHome", "Could not move Maps task " + existing.id + " from display "
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
            Log.e("RoadHome", "Could not reuse Maps task on display " + displayId, error);
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
            Log.e("RoadHome", "Could not turn off system picture-in-picture for " + PACKAGE
                    + ". Maps and its route are unaffected, but Home can briefly show the small PiP"
                    + " window before Maps returns to the panel. Turn it off in Settings > Apps >"
                    + " Special app access > Picture-in-picture.", error);
        }
    }

    private void revealSurface() throws ReflectiveOperationException {
        if (activityView != null) new ActivityViewSurface(activityView).show(
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
            Log.e("RoadHomeMap", "Could not fit Maps to its resized panel (" + getWidth() + "x" + getHeight()
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
            Log.i("RoadHome", "Requested Maps launch after no task was found, display " + displayId);
            return true;
        } catch (RuntimeException error) {
            launching = false;
            Log.e("RoadHome", "Could not launch Google Maps in ActivityView", error);
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
                Log.e("RoadHome", "Could not return Maps to the main display before launcher destruction", error);
            }
        }
        activityView.setCallback(null);
        if (displayLifecycle != null) displayLifecycle.release();
        if (activityView.getVirtualDisplayId() != Display.INVALID_DISPLAY) activityView.release();
        state = State.CLOSED;
    }
}
