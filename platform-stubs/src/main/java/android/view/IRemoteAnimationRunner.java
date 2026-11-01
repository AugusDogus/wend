package android.view;

/** Compile-only Android 10 signatures. Android supplies the implementation; never package this. */
public interface IRemoteAnimationRunner extends android.os.IInterface {
    void onAnimationStart(RemoteAnimationTarget[] apps, IRemoteAnimationFinishedCallback callback) throws android.os.RemoteException;
    void onAnimationCancelled() throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IRemoteAnimationRunner {
        public android.os.IBinder asBinder() { throw new UnsupportedOperationException("compile-only"); }
    }
}
