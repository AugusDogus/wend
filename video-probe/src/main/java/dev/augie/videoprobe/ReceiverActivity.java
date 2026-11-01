package dev.augie.videoprobe;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.LinearLayout;

public final class ReceiverActivity extends ProbeActivity implements SurfaceHolder.Callback {
    private SurfaceView destination;
    private IBinder lease;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        destination = new SurfaceView(this);
        destination.getHolder().addCallback(this);
        content.addView(destination, 0, new LinearLayout.LayoutParams(-1, Math.round(220 * getResources().getDisplayMetrics().density)));
        button("Attach already-playing video", this::attach);
        button("Return output to source", () -> { disconnectOutput(); status.setText("Output returned to source."); });
        button("Back to source app", this::finish);
    }
    private void attach() {
        if (bridge == null || !destination.getHolder().getSurface().isValid()) {
            status.setText("Waiting for source connection and receiver surface."); return;
        }
        if (lease == null) lease = new Binder();
        command(service -> service.attach(destination.getHolder().getSurface(), lease, callback));
    }
    @Override void connected() { status.setText("Ready. Attach only succeeds if the source is already playing."); }
    @Override void disconnectOutput() {
        if (lease != null && bridge != null) command(service -> service.detach(lease));
        lease = null;
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { disconnectOutput(); }
}
