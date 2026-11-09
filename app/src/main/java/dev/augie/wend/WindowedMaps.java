package dev.augie.wend;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Opt-in Android 10 host for a real bounded task on the physical display. */
final class WindowedMaps extends MapPanel {
    private final MapsTask tasks;
    private final Activity host;
    private final LiveMapsAnimation animation;
    private final MapsHomeAnimation returning;
    private final MapWindowCorners corners;
    private final TextView status;
    private boolean ready;
    private boolean active;
    private boolean mapsOnRight = true;
    private final Rect lastBounds = new Rect();
    private int lastTask = -1;
    private boolean needsForeground = true;

    WindowedMaps(Activity host) {
        super(host);
        this.host = host;
        tasks = new MapsTask(host);
        animation = new LiveMapsAnimation(host, this);
        returning = new MapsHomeAnimation(host, this);
        corners = new MapWindowCorners(host, this);
        status = Ui.text(host, "Opening Maps…", 18, Ui.TEXT);
        status.setGravity(Gravity.CENTER);
        status.setPadding(24, 24, 24, 24);
        status.setOnClickListener(view -> launch());
        addView(status, new LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT != 29 || Process.myUid() != 1000) {
            showError("Window-based Maps needs Android 10 and the matching system signature. Use the standard build on this device.");
            return;
        }
        try {
            WindowDecoration.enable(host);
            animation.initialize();
            returning.initialize();
            ready = true;
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException error) {
            Log.e("WendLive", "Native Maps setup failed", error);
            showError("Window-based Maps setup failed: " + error.getMessage() + ". Existing Maps data is preserved. Use the standard build or correct the window setup.");
        }
    }

    @Override void resume() { active = true; animation.cancel(); post(this::launch); }
    @Override void suspend() {
        active = false;
        needsForeground = true;
        corners.hide();
        returning.cancel();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed && active) post(this::launch);
    }

    @Override void launch() {
        if (!active || !ready || getWidth() == 0 || returning.pending()) return;
        // Home can arrive through onNewIntent without another onStart.
        animation.cancel();
        try {
            int[] point = new int[2]; getLocationOnScreen(point);
            Rect bounds = new Rect(point[0], point[1], point[0] + getWidth(), point[1] + getHeight());
            MapsTask.Task task = tasks.find();
            if (task != null) {
                // onStart, onNewIntent and onLayout may all request the same restoration.
                // Restarting an already placed task interrupts Maps' SurfaceView handoff.
                if (!needsForeground && task.id == lastTask && task.windowingMode == 5
                        && task.displayId == 0 && bounds.equals(lastBounds)) return;
                if (task.windowingMode == 1) returning.move(task.id, bounds, () -> restore(task, bounds));
                else restore(task, bounds);
                return;
            }
            Intent intent = host.getPackageManager().getLaunchIntentForPackage(EmbeddedMaps.PACKAGE);
            if (intent == null) { showError("Install Google Maps, then tap here to retry."); return; }
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(bounds); options.setLaunchDisplayId(0);
            ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(options, 5);
            host.startActivity(intent, options.toBundle());
            status.setVisibility(View.GONE);
            corners.show(mapsOnRight);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("WendLive", "Could not open bounded Maps", error);
            showError("Maps could not open in the panel. Open Maps from Recents to recover your route, or tap here to retry.");
        }
    }

    private void restore(MapsTask.Task task, Rect bounds) {
        if (!active) return;
        try {
            tasks.bounded(task, bounds);
            lastTask = task.id;
            lastBounds.set(bounds);
            needsForeground = false;
            status.setVisibility(View.GONE);
            corners.show(mapsOnRight);
        } catch (ReflectiveOperationException | RuntimeException error) {
            returning.cancel();
            Log.e("WendLive", "Could not bound Maps task " + task.id, error);
            showError("Maps could not return to the panel. Open Maps from Recents to recover your route.");
        }
    }

    // The archived windowed experiment keeps video in its card; covering and native PiP never run.
    @Override void openFullscreen(Covering covering, boolean allowCoveredAutoPip) {
        if (!ready) return;
        returning.cancel();
        animation.cancel();
        try {
            MapsTask.Task task = tasks.find();
            if (task == null) { launch(); return; }
            if (task.windowingMode == 1 && task.displayId == 0) {
                corners.hide();
                tasks.move(task, 0);
                return;
            }
            animation.expand(task.id, () -> {
                corners.hide();
                try { tasks.fullscreen(task); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    animation.cancel();
                    Log.e("WendLive", "Could not expand Maps task " + task.id, error);
                    showError("Maps could not expand. Open it from Recents to recover your route.");
                }
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("WendLive", "Could not find Maps for fullscreen", error);
            showError("Maps task status could not be read. Open Maps from Recents to recover your route.");
        }
    }

    @Override void setInnerCorners(boolean right) { mapsOnRight = right; }
    @Override void release() {
        active = false;
        returning.release(); animation.release(); corners.hide();
    }
    private void showError(String message) { status.setText(message); status.setVisibility(View.VISIBLE); }
}
