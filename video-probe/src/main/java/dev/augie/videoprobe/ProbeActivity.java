package dev.augie.videoprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

abstract class ProbeActivity extends Activity implements ServiceConnection {
    static final String SOURCE = "dev.augie.videoprobe.source";
    static final String RECEIVER = "dev.augie.videoprobe.receiver";
    LinearLayout content;
    TextView status;
    IPlaybackBridge bridge;
    private boolean bound;
    interface Command { void run(IPlaybackBridge bridge) throws RemoteException; }
    final IRouteCallback callback = new IRouteCallback.Stub() {
        @Override public void onResult(boolean success, String message) {
            runOnUiThread(() -> { if (!isFinishing()) status.setText(message); });
        }
    };
    static Intent serviceIntent() {
        return new Intent().setComponent(new ComponentName(SOURCE, DecoderService.class.getName()));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        status = new TextView(this); status.setText("Connecting to source decoder…");
        content.addView(status); setContentView(content);
    }
    void button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run());
        content.addView(button);
    }
    void command(Command command) {
        if (bridge == null) { status.setText("Source service is disconnected. Install both probe APKs and reopen."); return; }
        try { command.run(bridge); }
        catch (RemoteException error) { status.setText("Source decoder disconnected: " + error.getMessage()); }
    }
    @Override protected void onStart() {
        super.onStart(); bound = bindService(serviceIntent(), this, BIND_AUTO_CREATE);
        if (!bound) status.setText("Source app is not installed. Install both probe APKs first.");
    }
    @Override protected void onStop() {
        disconnectOutput();
        if (bound) { unbindService(this); bound = false; }
        bridge = null; super.onStop();
    }
    void connected() { }
    void disconnectOutput() { }
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        bridge = IPlaybackBridge.Stub.asInterface(binder); connected();
    }
    @Override public void onServiceDisconnected(ComponentName name) {
        bridge = null; status.setText("Source decoder disconnected. Return to its app to restart the test.");
    }
}
