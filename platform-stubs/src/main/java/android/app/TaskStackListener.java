package android.app;

/**
 * Compile-only Android 10 signatures. At runtime this is the framework class, an
 * ITaskStackListener.Stub Binder; never package this stub.
 */
public abstract class TaskStackListener extends android.os.Binder {
    public void onTaskCreated(int taskId, android.content.ComponentName componentName) throws android.os.RemoteException { }
    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) throws android.os.RemoteException { }
}
