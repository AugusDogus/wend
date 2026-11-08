package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.ArrayList;

// Created in code with its required navigation action, never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
final class MediaPanel extends LinearLayout {
    private final MediaSessionManager sessions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView artwork;
    private final EmbeddedVideo video;
    private final TextView title;
    private final TextView artist;
    private final TextView openApp;
    private final ImageButton previous;
    private final ImageButton play;
    private final ImageButton next;
    private MediaController controller;
    private MediaController videoController;
    private boolean started;
    private final MediaSessionSubscriptions subscriptions = new MediaSessionSubscriptions(handler, this::refresh);
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener = this::select;

    MediaPanel(Activity context, Runnable openMusic) {
        super(context);
        sessions = context.getSystemService(MediaSessionManager.class);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        int padding = Ui.dp(context, 12);
        setPadding(padding, padding, padding, padding);
        video = new EmbeddedVideo(context, this::videoPresentationEnded);
        LayoutParams videoLayout = new LayoutParams(-1, -2);
        videoLayout.bottomMargin = Ui.dp(context, 16);
        addView(video, videoLayout);
        artwork = new ImageView(context);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackground(Ui.surface(context, Ui.BACKGROUND, 10));
        artwork.setClipToOutline(true);
        artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams imageLayout = new LayoutParams(-1, Ui.dp(context, 160));
        imageLayout.bottomMargin = Ui.dp(context, 16);
        addView(artwork, imageLayout);
        title = Ui.text(context, "Nothing playing", 21, Ui.TEXT);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        addView(title, new LayoutParams(-1, -2));
        artist = Ui.text(context, "Choose something in Spotify", 14, Ui.MUTED);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams artistLayout = new LayoutParams(-1, Ui.dp(context, 28));
        artistLayout.topMargin = Ui.dp(context, 6);
        addView(artist, artistLayout);
        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        previous = Ui.iconButton(context, Ui.Icon.PREVIOUS, "Previous track", () -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        play = Ui.iconButton(context, Ui.Icon.PLAY, "Play", () -> {
            if (controller == null) return;
            if (isPlaying(controller.getPlaybackState())) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        next = Ui.iconButton(context, Ui.Icon.NEXT, "Next track", () -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
        controls.addView(previous, new LayoutParams(0, Ui.dp(context, 56), 1));
        controls.addView(play, new LayoutParams(0, Ui.dp(context, 56), 1));
        controls.addView(next, new LayoutParams(0, Ui.dp(context, 56), 1));
        addView(controls, new LayoutParams(-1, Ui.dp(context, 64)));
        openApp = Ui.action(context, "Open Spotify", openMusic);
        openApp.setSingleLine(true);
        openApp.setEllipsize(TextUtils.TruncateAt.END);
        addView(openApp, new LayoutParams(-1, Ui.dp(context, 48)));
        render();
    }

    String musicPackage() { return controller == null ? "com.spotify.music" : controller.getPackageName(); }
    boolean openVideoFullscreen(Runnable open) { return video.openFullscreen(open); }
    void resumeVideo() { video.resume(); }
    void suspendVideo() { video.suspend(); }
    void videoOpened() { video.fullscreenOpened(); }

    /** Keeps a playing video visible above fullscreen Maps; returns the card it left, or null. */
    Rect floatVideo(FloatingPlacement.Corner corner, LiveSurfaceWindow.Listener listener) {
        return video.floatAboveMaps(corner, listener);
    }
    void expandVideo() { video.expand(); }

    void start() {
        started = true;
        if (sessions == null) {
            title.setText(R.string.media_service_unavailable);
            artist.setText(R.string.open_music_directly);
            return;
        }
        try {
            sessions.addOnActiveSessionsChangedListener(sessionListener, null, handler);
            refresh();
            video.resume();
        } catch (SecurityException error) {
            Log.e("RoadHome", "MEDIA_CONTENT_CONTROL permission was not granted", error);
            title.setText(R.string.media_controls_unavailable);
            artist.setText(R.string.open_music_directly);
        }
    }

    void stop() {
        started = false;
        video.suspend();
        // A floating video still needs its session's pause, title, and end updates.
        if (!video.floating()) unsubscribe();
    }

    private void videoPresentationEnded() { if (!started) unsubscribe(); }

    private void unsubscribe() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionListener);
        subscriptions.clear();
        bind(null);
    }

    void release() { videoController = null; video.release(); }

    private void refresh() {
        if (sessions == null) return;
        try { select(sessions.getActiveSessions(null)); }
        catch (SecurityException error) {
            Log.e("RoadHome", "Cannot read active media sessions", error);
            bind(null);
            title.setText(R.string.media_controls_unavailable);
        }
    }

    private void select(List<MediaController> active) {
        List<MediaController> observed = active == null ? new ArrayList<>() : new ArrayList<>(active);
        // YouTube deactivates a paused session when backgrounded, although its
        // task still owns the selected video. Keep reading that session's live
        // metadata until its title clears, it is destroyed, or its task closes.
        if (videoController != null && !subscriptions.isDestroyed(videoController)
                && hasVideoTask() && observed.stream().noneMatch(candidate ->
                        EmbeddedVideo.PACKAGE.equals(candidate.getPackageName()))) {
            observed.add(videoController);
        }
        subscriptions.update(observed);
        MediaController selected = null;
        MediaController selectedVideo = null;
        MediaController playing = null;
        if (!observed.isEmpty()) {
            for (MediaController candidate : observed) {
                if (playing == null && isPlaying(candidate.getPlaybackState())) playing = candidate;
                MediaMetadata metadata = candidate.getMetadata();
                CharSequence videoTitle = metadata == null ? null : metadata.getDescription().getTitle();
                if (VideoCardState.fromMedia(candidate.getPackageName(), videoTitle, false)
                        == VideoCardState.THUMBNAIL) selectedVideo = candidate;
                if (selected == null || candidate.getPackageName().equals("com.spotify.music")) selected = candidate;
            }
        }
        // Audio controls follow playback; a selected paused video keeps its card even
        // when another app owns those controls.
        if (playing != null) selected = playing;
        else if (selectedVideo != null) selected = selectedVideo;
        videoController = selectedVideo;
        bind(selected);
    }

    @SuppressWarnings("deprecation")
    private boolean hasVideoTask() {
        ActivityManager manager = getContext().getSystemService(ActivityManager.class);
        if (manager == null) return false;
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(Integer.MAX_VALUE)) {
            if (task.baseActivity != null && EmbeddedVideo.PACKAGE.equals(task.baseActivity.getPackageName())) return true;
        }
        return false;
    }

    private void bind(MediaController selected) {
        if (controller != null && selected != null
                && controller.getSessionToken().equals(selected.getSessionToken())) {
            render();
            return;
        }
        controller = selected;
        render();
    }

    private void render() {
        PlaybackState playback = controller == null ? null : controller.getPlaybackState();
        boolean playing = isPlaying(playback);
        boolean youTube = controller != null && EmbeddedVideo.PACKAGE.equals(controller.getPackageName());
        MediaMetadata metadata = controller == null ? null : controller.getMetadata();
        MediaDescription description = metadata == null ? null : metadata.getDescription();
        CharSequence track = description == null ? null : description.getTitle();
        CharSequence subtitle = description == null ? null : description.getSubtitle();
        title.setText(TextUtils.isEmpty(track) ? "Nothing playing" : track);
        artist.setText(TextUtils.isEmpty(subtitle) ? "Choose something in your music app" : subtitle);
        Bitmap bitmap = description == null ? null : description.getIconBitmap();
        if (bitmap == null && metadata != null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        MediaMetadata videoMetadata = videoController == null ? null : videoController.getMetadata();
        MediaDescription videoDescription = videoMetadata == null ? null : videoMetadata.getDescription();
        PlaybackState videoPlayback = videoController == null ? null : videoController.getPlaybackState();
        VideoCardState videoState = VideoCardState.fromMedia(
                videoController == null ? null : videoController.getPackageName(),
                videoDescription == null ? null : videoDescription.getTitle(),
                videoPlayback != null && videoPlayback.getState() == PlaybackState.STATE_PLAYING);
        Bitmap videoArt = videoDescription == null ? null : videoDescription.getIconBitmap();
        if (videoArt == null && videoMetadata != null) videoArt = videoMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        video.update(videoState, videoArt);
        if (bitmap == null || youTube || videoState != VideoCardState.HIDDEN) {
            artwork.setVisibility(GONE);
            artwork.setImageDrawable(null);
        } else {
            artwork.setVisibility(VISIBLE);
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setBackground(Ui.surface(getContext(), Ui.BACKGROUND, 10));
            artwork.setImageBitmap(bitmap);
        }
        PackageManager pm = getContext().getPackageManager();
        String label;
        try { label = pm.getApplicationLabel(pm.getApplicationInfo(musicPackage(), 0)).toString(); }
        catch (PackageManager.NameNotFoundException error) { label = "media app"; }
        openApp.setText("Open " + label);
        play.setImageDrawable(new Ui.Symbol(playing ? Ui.Icon.PAUSE : Ui.Icon.PLAY, Ui.TEXT));
        play.setContentDescription(playing ? "Pause" : "Play");
        long actions = playback == null ? 0 : playback.getActions();
        enable(play, (actions & (PlaybackState.ACTION_PLAY_PAUSE
                | (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY))) != 0);
        enable(previous, (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        enable(next, (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
    }

    private static boolean isPlaying(PlaybackState state) {
        return state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING
                || state.getState() == PlaybackState.STATE_CONNECTING);
    }

    private static void enable(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1 : 0.3f);
    }
}
