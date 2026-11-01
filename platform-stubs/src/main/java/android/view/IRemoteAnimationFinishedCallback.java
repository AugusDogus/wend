package android.view;

/** Compile-only Android 10 signatures. Android supplies the implementation; never package this. */
public interface IRemoteAnimationFinishedCallback extends android.os.IInterface {
    void onAnimationFinished() throws android.os.RemoteException;
}
