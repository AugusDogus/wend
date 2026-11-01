package android.view;

/** Compile-only Android 10 signatures. Android supplies the implementation; never package this. */
public class RemoteAnimationAdapter {
    public RemoteAnimationAdapter(IRemoteAnimationRunner runner, long duration, long delay) { }
    public RemoteAnimationAdapter(IRemoteAnimationRunner runner, long duration, long delay, boolean snapshot) { }
}
