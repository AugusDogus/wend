package dev.augie.videoprobe;
import android.view.Surface;
import dev.augie.videoprobe.IRouteCallback;
interface IPlaybackBridge {
    void start(in Surface original, IRouteCallback callback);
    void pause();
    void attach(in Surface destination, IBinder owner, IRouteCallback callback);
    void detach(IBinder owner);
    void stop();
}
