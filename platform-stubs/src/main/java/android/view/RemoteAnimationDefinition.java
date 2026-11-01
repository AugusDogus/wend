package android.view;

/** Compile-only Android 10 signatures. Android supplies the implementation; never package this. */
public class RemoteAnimationDefinition {
    public void addRemoteAnimation(int transition, RemoteAnimationAdapter adapter) { }
    public void addRemoteAnimation(int transition, int activityType, RemoteAnimationAdapter adapter) { }
}
