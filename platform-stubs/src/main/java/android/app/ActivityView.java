package android.app;

import android.content.Context;
import android.view.ViewGroup;

/** Compile-only signatures from AOSP Android 10. Never included in the APK. */
public class ActivityView extends ViewGroup {
    public ActivityView(Context context) { super(context); }
    public void setCallback(StateCallback callback) { throw new UnsupportedOperationException("compile-only"); }
    public void startActivity(PendingIntent intent) { throw new UnsupportedOperationException("compile-only"); }
    public void release() { throw new UnsupportedOperationException("compile-only"); }
    public int getVirtualDisplayId() { throw new UnsupportedOperationException("compile-only"); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { }

    public abstract static class StateCallback {
        public abstract void onActivityViewReady(ActivityView view);
        public abstract void onActivityViewDestroyed(ActivityView view);
        public void onTaskRemovalStarted(int taskId) { }
        public void onTaskMovedToFront(int taskId) { }
        public void onTaskCreated(int taskId, android.content.ComponentName componentName) { }
    }
}
