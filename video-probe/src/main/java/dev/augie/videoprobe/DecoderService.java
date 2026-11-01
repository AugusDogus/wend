package dev.augie.videoprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Test decoder, kept in the source app's process throughout a cross-app surface handoff. */
public final class DecoderService extends Service {
    private final HandlerThread thread = new HandlerThread("VideoProbe-decoder");
    private Handler worker;
    private MediaCodec codec;
    private MediaExtractor extractor;
    private Surface original;
    private Surface borrowed;
    private OutputRoute<Surface> route;
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private boolean playing;
    private long epochNanos;
    private boolean inputEnded;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final Runnable pump = this::decode;
    private final IPlaybackBridge.Stub bridge = new IPlaybackBridge.Stub() {
        @Override public void start(Surface surface, IRouteCallback callback) {
            worker.post(() -> startDecoder(surface, callback));
        }
        @Override public void pause() { worker.post(() -> { playing = false; worker.removeCallbacks(pump); }); }
        @Override public void attach(Surface surface, IBinder lease, IRouteCallback callback) {
            worker.post(() -> attachSurface(surface, lease, callback));
        }
        @Override public void detach(IBinder lease) { worker.post(() -> detachSurface(lease)); }
        @Override public void stop() { worker.post(() -> { stopDecoder(); stopSelf(); }); }
    };

    @Override public void onCreate() {
        super.onCreate(); thread.start(); worker = new Handler(thread.getLooper());
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("probe", "Decoder test", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "probe").setContentTitle("Video decoder test")
                .setContentText("Stop the test from the source app when finished")
                .setSmallIcon(android.R.drawable.ic_media_play).build());
    }
    @Override public IBinder onBind(Intent intent) { return bridge; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public void onDestroy() {
        worker.post(() -> { stopDecoder(); thread.quitSafely(); });
        super.onDestroy();
    }

    private void startDecoder(Surface surface, IRouteCallback callback) {
        stopDecoder();
        if (surface == null || !surface.isValid()) {
            if (surface != null) surface.release();
            reply(callback, false, "Source surface is unavailable. Reopen the source app.");
            return;
        }
        original = surface;
        try (AssetFileDescriptor asset = getAssets().openFd("test-video.mp4")) {
            extractor = new MediaExtractor();
            extractor.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { track = i; break; }
            }
            if (track < 0) throw new IOException("Test asset has no video track");
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("Video track has no MIME type");
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, original, null, 0);
            codec.start();
            route = new OutputRoute<>(original, target -> codec.setOutputSurface(target), () -> playing);
            playing = true; inputEnded = false; epochNanos = System.nanoTime();
            worker.post(pump);
            reply(callback, true, "Playing in source app. Open the receiver to transfer this decoder's output.");
        } catch (IOException | RuntimeException error) {
            Log.e("VideoProbe", "Could not start test decoder", error);
            stopDecoder(); reply(callback, false, "Decoder start failed: " + error.getMessage());
        }
    }

    private void decode() {
        if (!playing || codec == null || extractor == null) return;
        try {
            int input = inputEnded ? -1 : codec.dequeueInputBuffer(0);
            if (input >= 0) {
                ByteBuffer buffer = codec.getInputBuffer(input);
                if (buffer == null) throw new IllegalStateException("Decoder returned no input buffer");
                int bytes = extractor.readSampleData(buffer, 0);
                codec.queueInputBuffer(input, 0, Math.max(0, bytes), bytes < 0 ? 0 : extractor.getSampleTime(),
                        bytes < 0 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                if (bytes >= 0) extractor.advance();
                else inputEnded = true;
            }
            int output = codec.dequeueOutputBuffer(info, 0);
            if (output >= 0) {
                boolean ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (ended) {
                    codec.releaseOutputBuffer(output, false);
                    codec.flush(); extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    epochNanos = System.nanoTime(); inputEnded = false;
                } else codec.releaseOutputBuffer(output, epochNanos + info.presentationTimeUs * 1000);
            }
            worker.postDelayed(pump, 10);
        } catch (RuntimeException error) {
            Log.e("VideoProbe", "Decode failed; stopping test", error);
            stopDecoder();
        }
    }

    private void attachSurface(Surface surface, IBinder lease, IRouteCallback callback) {
        if (surface == null || !surface.isValid() || lease == null || route == null) {
            if (surface != null) surface.release();
            reply(callback, false, "No active decoder or valid destination. Start playback in the source app first.");
            return;
        }
        IBinder.DeathRecipient nextDeath = () -> worker.post(() -> detachSurface(lease));
        try { lease.linkToDeath(nextDeath, 0); }
        catch (RemoteException error) {
            surface.release(); reply(callback, false, "Receiver closed before attachment."); return;
        }
        OutputRoute.Result result = route.attach(lease, surface);
        if (result != OutputRoute.Result.ATTACHED) {
            lease.unlinkToDeath(nextDeath, 0); surface.release();
            reply(callback, false, result == OutputRoute.Result.NOT_PLAYING
                    ? "Playback is paused. Attachment did not start playback."
                    : "Decoder rejected the destination surface. Original routing is retained.");
            return;
        }
        releaseBorrowed();
        borrowed = surface; owner = lease; death = nextDeath;
        Log.i("VideoProbe", "Decoder output redirected without restarting or seeking");
        reply(callback, true, "Receiving the existing decoder output. No PiP or task resize.");
    }

    private void detachSurface(IBinder lease) {
        if (route == null || lease == null) return;
        try {
            if (route.detach(lease)) { releaseBorrowed(); Log.i("VideoProbe", "Restored original decoder output"); }
        } catch (RuntimeException error) {
            Log.e("VideoProbe", "Original surface cannot be restored; stopping test decoder", error);
            stopDecoder();
        }
    }

    private void releaseBorrowed() {
        if (owner != null && death != null) owner.unlinkToDeath(death, 0);
        if (borrowed != null) borrowed.release();
        borrowed = null; owner = null; death = null;
    }
    private void stopDecoder() {
        playing = false; worker.removeCallbacks(pump);
        if (codec != null) { codec.release(); codec = null; }
        if (extractor != null) { extractor.release(); extractor = null; }
        releaseBorrowed();
        if (original != null) { original.release(); original = null; }
        route = null;
    }
    private static void reply(IRouteCallback callback, boolean success, String message) {
        if (callback == null) return;
        try { callback.onResult(success, message); }
        catch (RemoteException error) { Log.w("VideoProbe", "Receiver disconnected before route result", error); }
    }
}
