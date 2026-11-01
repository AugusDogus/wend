package dev.augie.videoprobe;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.widget.LinearLayout;

public final class SourceActivity extends ProbeActivity implements TextureView.SurfaceTextureListener {
    private TextureView preview;
    private SurfaceTexture retainedTexture;
    private Surface source;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preview = new TextureView(this); preview.setSurfaceTextureListener(this);
        content.addView(preview, 0, new LinearLayout.LayoutParams(-1, Math.round(220 * getResources().getDisplayMetrics().density)));
        button("Play test video from beginning", () -> {
            if (source == null || !source.isValid()) { status.setText("Preview is not ready yet."); return; }
            startForegroundService(serviceIntent());
            command(service -> service.start(source, callback));
        });
        button("Pause", () -> command(service -> { service.pause(); status.setText("Paused. The receiver must not start playback."); }));
        button("Open receiver", () -> startActivity(new Intent().setComponent(new ComponentName(RECEIVER, ReceiverActivity.class.getName()))));
        button("Stop test decoder", () -> command(service -> { service.stop(); status.setText("Test stopped."); }));
    }
    @Override void connected() { status.setText("Press Play to start the local test video. Receiver attachment never starts it."); }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (retainedTexture == null) {
            retainedTexture = texture; source = new Surface(texture);
        } else if (retainedTexture != texture) {
            preview.setSurfaceTexture(retainedTexture);
        }
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { return false; }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
    @Override protected void onDestroy() {
        // Finishing the source owns the end of the test, including any retained preview queue.
        if (isFinishing()) {
            stopService(serviceIntent());
            if (source != null) source.release();
            if (retainedTexture != null) retainedTexture.release();
        }
        super.onDestroy();
    }
}
