package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import java.util.Collections;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowMediaSessionManager;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MediaPanelTest {
    @Test public void pausedVideoCardRemainsWhileAnotherAppPlaysMusic() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaSession music = new MediaSession(activity, "music");
            MediaSession video = new MediaSession(activity, "video");
            MediaController musicController = paused(music, "com.spotify.music");
            MediaController videoController = paused(video, EmbeddedVideo.PACKAGE);
            Shadows.shadowOf(musicController).setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1).build());
            Shadows.shadowOf(videoController).setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Paused video").build());
            ShadowMediaSessionManager sessions = Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class));
            sessions.addController(musicController);
            sessions.addController(videoController);
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { });
            try {
                panel.start();
                assertEquals("com.spotify.music", panel.musicPackage());
                assertEquals(View.VISIBLE, panel.getChildAt(0).getVisibility());
                assertEquals(PlaybackState.STATE_PAUSED, videoController.getPlaybackState().getState());
            } finally {
                panel.stop(); panel.release(); video.release(); music.release();
            }
        }
    }

    @Test public void pausedVideoSelectedAfterMetadataArrivesDisplacesIdleMusic() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaSession music = new MediaSession(activity, "music");
            MediaSession video = new MediaSession(activity, "video");
            MediaController musicController = paused(music, "com.spotify.music");
            MediaController videoController = paused(video, EmbeddedVideo.PACKAGE);
            ShadowMediaSessionManager sessions = Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class));
            sessions.addController(musicController);
            sessions.addController(videoController);
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { });
            try {
                panel.start();
                assertEquals("com.spotify.music", panel.musicPackage());
                // The active-session list is unchanged. Only the previously unselected
                // video's metadata changes, as when a paused video is selected.
                Shadows.shadowOf(videoController).executeOnMetadataChanged(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "Newly selected video").build());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(EmbeddedVideo.PACKAGE, panel.musicPackage());
                assertEquals(View.VISIBLE, panel.getChildAt(0).getVisibility());
                assertEquals(PlaybackState.STATE_PAUSED, videoController.getPlaybackState().getState());
                Shadows.shadowOf(videoController).executeOnMetadataChanged(null);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("com.spotify.music", panel.musicPackage());
                assertEquals(View.GONE, panel.getChildAt(0).getVisibility());
                assertEquals(1, Shadows.shadowOf(musicController).getCallbacks().size());
                assertEquals(1, Shadows.shadowOf(videoController).getCallbacks().size());
                panel.stop();
                assertEquals(0, Shadows.shadowOf(musicController).getCallbacks().size());
                assertEquals(0, Shadows.shadowOf(videoController).getCallbacks().size());
            } finally {
                panel.stop(); panel.release(); video.release(); music.release();
            }
        }
    }

    @Test public void inactivePausedVideoSurvivesHomeUntilItsSelectionIsCleared() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaSession session = new MediaSession(activity, "video");
            MediaController controller = paused(session, EmbeddedVideo.PACKAGE);
            Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Selected video").build());
            ActivityManager.RunningTaskInfo task = new ActivityManager.RunningTaskInfo();
            task.baseActivity = new ComponentName(EmbeddedVideo.PACKAGE, "PlayerActivity");
            Shadows.shadowOf(activity.getSystemService(ActivityManager.class)).setTasks(Collections.singletonList(task));
            ShadowMediaSessionManager sessions = Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class));
            sessions.addController(controller);
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { });
            try {
                panel.start();
                panel.stop();
                sessions.clearControllers();
                panel.start();
                assertEquals(EmbeddedVideo.PACKAGE, panel.musicPackage());
                assertEquals(View.VISIBLE, panel.getChildAt(0).getVisibility());
                Shadows.shadowOf(controller).executeOnMetadataChanged(null);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(View.GONE, panel.getChildAt(0).getVisibility());
                panel.stop();
                Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "Another selected video").build());
                sessions.addController(controller);
                panel.start();
                sessions.clearControllers();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(View.VISIBLE, panel.getChildAt(0).getVisibility());
                Shadows.shadowOf(controller).executeOnSessionDestroyed();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(View.GONE, panel.getChildAt(0).getVisibility());
                panel.stop();
                sessions.addController(controller);
                panel.start();
                panel.stop();
                sessions.clearControllers();
                Shadows.shadowOf(activity.getSystemService(ActivityManager.class)).setTasks(Collections.emptyList());
                panel.start();
                assertEquals(View.GONE, panel.getChildAt(0).getVisibility());
            } finally {
                panel.stop(); panel.release(); session.release();
            }
        }
    }

    private static MediaController paused(MediaSession session, String packageName) {
        MediaController controller = session.getController();
        Shadows.shadowOf(controller).setPackageName(packageName);
        Shadows.shadowOf(controller).setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0, 0).build());
        return controller;
    }
}
