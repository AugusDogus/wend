package dev.augie.roadhome;

import android.app.ActivityView;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Owns one Android 10 virtual display. No polling, signature bypass, or root shell. */
final class EmbeddedMaps extends FrameLayout {
    static final String PACKAGE = BuildConfig.MAPS_PACKAGE;
    private enum State { WAITING, READY, CLOSED }
    private State state = State.WAITING;
    private ActivityView activityView;
    private MapDisplayLifecycle displayLifecycle;
    private final MapsTask tasks;
    private boolean hostActive;
    private boolean launching;
    private final LinearLayout status;
    private final TextView message;
    private View topCorner;
    private View bottomCorner;

    EmbeddedMaps(Activity context) {
        super(context);
        tasks = new MapsTask(context);
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
            displayLifecycle = new MapDisplayLifecycle(activityView);
            addView(activityView, 0, new LayoutParams(-1, -1));
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
                        launching = false;
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.e("RoadHome", "ActivityView creation failed", error);
            showError("The map panel could not start. Open Maps from Apps while the display issue is checked.");
        }
    }

    void launch() {
        if (!hostActive || state != State.READY || activityView == null || launching) return;
        openOnDisplay(activityView.getVirtualDisplayId());
    }

    void resume() { hostActive = true; launch(); }
    void suspend() { hostActive = false; }

    void openFullscreen() { openOnDisplay(0); }

    private void openOnDisplay(int displayId) {
        if (launching) return;
        try {
            MapsTask.Task existing = tasks.find();
            if (existing != null) {
                if (existing.displayId != displayId || existing.windowingMode != 1 || displayId == 0) tasks.move(existing, displayId);
                status.setVisibility(View.GONE);
                return;
            }
            launchNew(displayId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("RoadHome", "Could not reuse Maps task on display " + displayId, error);
            showError("Maps could not be restored. Existing tasks were left intact. Open Maps from Recents to recover your route.");
        }
    }

    private void launchNew(int displayId) {
        Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (intent == null) {
            showError("Google Maps is not installed. Install Maps, then tap Open Maps.");
            return;
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
                activityView.startActivity(pending);
            }
            status.setVisibility(View.GONE);
            Log.i("RoadHome", "Started Maps because no running Maps task exists, display " + displayId);
        } catch (RuntimeException error) {
            launching = false;
            Log.e("RoadHome", "Could not launch Google Maps in ActivityView", error);
            showError("Maps could not open in this panel. Tap Open Maps to retry, or open it from Apps.");
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
