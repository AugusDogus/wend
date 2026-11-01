package android.view;

/** Compile-only Android 10 signatures. Android supplies the implementation; never package this. */
public class RemoteAnimationTarget {
    public int taskId, mode;
    public SurfaceControl leash, startLeash;
    public android.graphics.Point position;
    public android.graphics.Rect sourceContainerBounds, startBounds;
}
