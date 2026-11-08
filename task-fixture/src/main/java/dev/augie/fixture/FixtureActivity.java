package dev.augie.fixture;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;

/** Offline fixtures only. No Google app code, network requests, or real navigation. */
public final class FixtureActivity extends Activity implements SurfaceHolder.Callback {
    private final String instance = Long.toHexString(System.nanoTime());
    private TextView status;
    private TextView heading;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean selected;
    private boolean ready;
    private boolean route;
    private boolean autoPip;
    private int focusChange;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 12);
        heading = new TextView(this);
        heading.setTextSize(24);
        heading.setText(BuildConfig.VIDEO ? "Emulator video fixture" : "Emulator map fixture");
        root.addView(heading);
        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);
        if (BuildConfig.VIDEO) setupVideo(root);
        else button(root, "Mark route active", () -> { route = true; update(); });
        button(root, "Toggle automatic PiP", () -> { autoPip = !autoPip; update(); });
        button(root, "Return to Wend", () -> startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).setPackage("dev.augie.wend.emulator")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        if (BuildConfig.VIDEO) {
            root.setBackgroundColor(0xff172033);
            setContentView(root);
        } else setContentView(withMapSurface(root));
        update();
        Log.i("RoadFixture", "created " + getPackageName() + " task=" + getTaskId() + " instance=" + instance);
    }

    // Google Maps draws tiles into a SurfaceView. Hiding it exercises the launcher's slow path,
    // where map buffers never report the new size.
    private FrameLayout withMapSurface(LinearLayout controls) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff124c48);
        SurfaceView map = new SurfaceView(this);
        map.getHolder().addCallback(new MapSurface());
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        button(controls, "Toggle map surface", () -> {
            map.setVisibility(map.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            Log.i("RoadFixture", "map surface visible=" + (map.getVisibility() == View.VISIBLE));
        });
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2));
        return root;
    }

    private void setupVideo(LinearLayout root) {
        audio = getSystemService(AudioManager.class);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                    focusChange = change;
                    Log.i("RoadFixture", "audio focus=" + change);
                    if (change < 0) pause();
                    update();
                }).build();
        session = new MediaSession(this, "Wend emulator video");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
        });
        session.setActive(true);
        player = new MediaPlayer();
        player.setAudioAttributes(attributes);
        player.setLooping(true);
        player.setOnPreparedListener(value -> { ready = true; update(); });
        player.setOnErrorListener((value, what, extra) -> {
            Log.e("RoadFixture", "MediaPlayer error " + what + "/" + extra);
            status.setText("Fixture decoder failed: " + what + "/" + extra);
            return true;
        });
        try (android.content.res.AssetFileDescriptor asset = getAssets().openFd("test.mp4")) {
            player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            player.prepareAsync();
        } catch (IOException error) { throw new IllegalStateException("Fixture video asset could not load", error); }
        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new LinearLayout.LayoutParams(480, 270));
        button(root, "Select paused video", () -> { selected = true; metadata(); pause(); });
        button(root, "Play selected video", this::play);
        button(root, "Pause video", this::pause);
        button(root, "Clear selected video", () -> { pause(); selected = false; session.setMetadata(null); update(); });
    }

    private void metadata() {
        Bitmap artwork = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(artwork);
        canvas.drawColor(0xff4169a1);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE); paint.setTextSize(38);
        canvas.drawText("Paused test video", 80, 190, paint);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Offline video lifecycle test")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Local test pattern and quiet tone")
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork).build());
    }

    private void play() {
        if (!ready) return;
        selected = true;
        metadata();
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) player.start();
        update();
    }

    private void pause() {
        if (ready && player.isPlaying()) player.pause();
        update();
    }

    private void update() {
        boolean playing = ready && player != null && player.isPlaying();
        status.setText("Task " + getTaskId() + " / instance " + instance
                + " / display " + getWindowManager().getDefaultDisplay().getDisplayId()
                + "\nRoute " + route + " / selected " + selected + " / playing " + playing
                + " / automatic PiP " + autoPip + " / focus " + focusChange);
        if (session != null) session.setPlaybackState(new PlaybackState.Builder()
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        ready ? player.getCurrentPosition() : 0, playing ? 1 : 0)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE).build());
    }

    private void button(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(view -> action.run());
        root.addView(button, new LinearLayout.LayoutParams(-1, 48));
    }

    @Override protected void onUserLeaveHint() {
        if (autoPip && (route || (ready && player.isPlaying()))) {
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
        }
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // ActivityThread finishes updating the display after this callback returns.
        status.post(this::update);
    }
    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration configuration) {
        super.onPictureInPictureModeChanged(pip, configuration);
        Log.i("RoadFixture", "PiP=" + pip + " task=" + getTaskId());
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { player.setDisplay(holder); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { player.setDisplay(null); }
    @Override protected void onDestroy() {
        if (player != null) player.release();
        if (session != null) session.release();
        if (audio != null) audio.abandonAudioFocusRequest(focus);
        super.onDestroy();
    }
}
