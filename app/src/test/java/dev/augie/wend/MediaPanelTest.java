package dev.augie.wend;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.util.Collections;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowMediaSessionManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MediaPanelTest {
    @Test public void pausedVideoCardRemainsWhileAnotherAppPlaysMusic() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaSession music = new MediaSession(activity, "music");
            MediaSession video = new MediaSession(activity, "video");
            MediaController musicController = paused(music, BuildConfig.MUSIC_PACKAGE);
            MediaController videoController = paused(video, EmbeddedVideo.PACKAGE);
            Shadows.shadowOf(musicController).setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1).build());
            Shadows.shadowOf(videoController).setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Paused video").build());
            ShadowMediaSessionManager sessions = Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class));
            sessions.addController(musicController);
            sessions.addController(videoController);
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { }, () -> { });
            try {
                panel.start();
                assertEquals(BuildConfig.MUSIC_PACKAGE, panel.musicPackage());
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
            MediaController musicController = paused(music, BuildConfig.MUSIC_PACKAGE);
            MediaController videoController = paused(video, EmbeddedVideo.PACKAGE);
            ShadowMediaSessionManager sessions = Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class));
            sessions.addController(musicController);
            sessions.addController(videoController);
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { }, () -> { });
            try {
                panel.start();
                assertEquals(BuildConfig.MUSIC_PACKAGE, panel.musicPackage());
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
                assertEquals(BuildConfig.MUSIC_PACKAGE, panel.musicPackage());
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
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { }, () -> { });
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

    /** Wend can be destroyed before the card's player display gets its first surface. */
    @Test public void aPlayingVideoCardReleasesBeforeItsPlayerDisplayExists() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaSession session = new MediaSession(activity, "video");
            MediaController controller = session.getController();
            Shadows.shadowOf(controller).setPackageName(EmbeddedVideo.PACKAGE);
            Shadows.shadowOf(controller).setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1).build());
            Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Playing video").build());
            Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class)).addController(controller);
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { }, () -> { });
            try {
                panel.start();
                panel.stop();
                panel.release();
            } finally {
                session.release();
            }
        }
    }

    /**
     * Device report RH-5: the whole card opens the video app, as its Open action does, whether the
     * video is paused or playing. None of the card's touches go to YouTube's scaled-down player.
     */
    @Test public void aTapAnywhereOnTheVideoCardOpensTheVideo() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaSession session = new MediaSession(activity, "video");
            MediaController controller = paused(session, EmbeddedVideo.PACKAGE);
            Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Selected video").build());
            Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class)).addController(controller);
            List<String> opened = new ArrayList<>();
            MediaPanel panel = new MediaPanel(activity, () -> opened.add("music"), () -> opened.add("video"), () -> { });
            activity.setContentView(panel, new ViewGroup.LayoutParams(357, 720));
            try {
                panel.start();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                View card = panel.getChildAt(0);
                assertEquals(View.VISIBLE, card.getVisibility());
                tapCorners(card);
                assertEquals(List.of("video", "video", "video"), opened);
                // Live, the card holds the embedded player beneath; the tap is still the card's.
                Shadows.shadowOf(controller).executeOnPlaybackStateChanged(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0, 1).build());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                opened.clear();
                tapCorners(card);
                assertEquals(List.of("video", "video", "video"), opened);
            } finally {
                panel.stop(); panel.release(); session.release();
            }
        }
    }

    private static void tapCorners(View card) {
        assertTrue("The card is laid out", card.getWidth() > 0 && card.getHeight() > 0);
        tap(card, 2, 2);
        tap(card, card.getWidth() / 2f, card.getHeight() / 2f);
        tap(card, card.getWidth() - 2, card.getHeight() - 2);
    }

    private static void tap(View view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent release = MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0);
        try {
            view.dispatchTouchEvent(press);
            view.dispatchTouchEvent(release);
        } finally {
            press.recycle();
            release.recycle();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Device reports RH-4 and BUG-3: YouTube 21.43 supplies no artwork, and the card was a black box. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test public void aPausedVideoWithoutArtworkNeverShowsABlackCard() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaSession session = new MediaSession(activity, "video");
            MediaController controller = paused(session, EmbeddedVideo.PACKAGE);
            Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Paused video").build());
            Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class)).addController(controller);
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { }, () -> { });
            activity.setContentView(panel, new ViewGroup.LayoutParams(357, 720));
            try {
                panel.start();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                View card = panel.getChildAt(0);
                Bitmap drawn = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
                card.draw(new Canvas(drawn));
                assertNotEquals(Color.BLACK, drawn.getPixel(4, drawn.getHeight() - 4));
                // A 48 dp control at the center, not a 16 px glyph: 30 px from center is still inside it.
                assertNotEquals(drawn.getPixel(4, drawn.getHeight() - 4),
                        drawn.getPixel(drawn.getWidth() / 2 + 30, drawn.getHeight() / 2));
            } finally {
                panel.stop(); panel.release(); session.release();
            }
        }
    }

    /**
     * Device report BUG-5 (real apps): after Home, YouTube stopped and deactivated its session
     * (STATE_STOPPED, only ACTION_PLAY_FROM_URI), and the card's still kept a play control that
     * nothing could act on. Tapping the card still opens the video.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test public void aVideoWhoseSessionCannotPlayShowsNoPlayControlAndStillOpensOnATap() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaSession session = new MediaSession(activity, "video");
            MediaController controller = session.getController();
            Shadows.shadowOf(controller).setPackageName(EmbeddedVideo.PACKAGE);
            Shadows.shadowOf(controller).setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0, 0)
                    .setActions(PlaybackState.ACTION_PLAY_FROM_URI).build());
            Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Stopped video").build());
            Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class)).addController(controller);
            List<String> opened = new ArrayList<>();
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> opened.add("video"), () -> { });
            activity.setContentView(panel, new ViewGroup.LayoutParams(357, 720));
            try {
                panel.start();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                View card = panel.getChildAt(0);
                assertEquals(View.VISIBLE, card.getVisibility());
                Bitmap drawn = draw(card);
                int surface = drawn.getPixel(4, drawn.getHeight() - 4);
                assertEquals("No play control", surface, drawn.getPixel(drawn.getWidth() / 2 + 30, drawn.getHeight() / 2));
                assertEquals("No play glyph", surface, drawn.getPixel(drawn.getWidth() / 2, drawn.getHeight() / 2));
                tapCorners(card);
                assertEquals(List.of("video", "video", "video"), opened);
                // The session can play again, as a paused one can.
                Shadows.shadowOf(controller).executeOnPlaybackStateChanged(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PAUSED, 0, 0).setActions(PLAYABLE).build());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                drawn = draw(card);
                assertNotEquals("The play control is back", surface,
                        drawn.getPixel(drawn.getWidth() / 2 + 30, drawn.getHeight() / 2));
            } finally {
                panel.stop(); panel.release(); session.release();
            }
        }
    }

    /** Design feedback (fixtures): "Choose something in your musi…" at the 70% layout, 357 px wide. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test public void theEmptyPanelSubtitleFitsTheNarrowestPanel() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaPanel panel = new MediaPanel(activity, () -> { }, () -> { }, () -> { });
            activity.setContentView(panel, new ViewGroup.LayoutParams(357, 720));
            try {
                panel.start();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                android.widget.TextView subtitle = (android.widget.TextView) panel.getChildAt(3);
                assertEquals("Nothing playing", ((android.widget.TextView) panel.getChildAt(2)).getText().toString());
                assertTrue("Laid out", subtitle.getLayout() != null);
                assertEquals("\"" + subtitle.getText() + "\" is not cut off", 0, subtitle.getLayout().getEllipsisCount(0));
            } finally {
                panel.stop(); panel.release();
            }
        }
    }

    private static Bitmap draw(View view) {
        Bitmap drawn = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(drawn));
        return drawn;
    }

    /** The empty-panel fallback names the chosen music app, not the build default. */
    @Test public void theEmptyFallbackUsesTheChosenMusicApp() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            MediaPanel panel = new MediaPanel(activity, () -> "com.example.player", () -> { }, () -> { }, () -> { });
            try {
                panel.start();
                assertEquals("com.example.player", panel.musicPackage());
            } finally {
                panel.stop(); panel.release();
            }
        }
    }

    /** Among paused sessions the tie-break prefers the chosen music app, even listed last. */
    @Test public void thePausedSessionTieBreakFollowsTheChosenMusicApp() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = host.get();
            String chosen = "com.example.player";
            MediaSession chosenSession = new MediaSession(activity, "chosen");
            MediaSession otherSession = new MediaSession(activity, "other");
            MediaController chosenController = paused(chosenSession, chosen);
            MediaController otherController = paused(otherSession, "com.example.other");
            ShadowMediaSessionManager sessions = Shadows.shadowOf(activity.getSystemService(MediaSessionManager.class));
            // The non-music app is listed first, so only the tie-break can prefer the chosen app.
            sessions.addController(otherController);
            sessions.addController(chosenController);
            MediaPanel panel = new MediaPanel(activity, () -> chosen, () -> { }, () -> { }, () -> { });
            try {
                panel.start();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(chosen, panel.musicPackage());
            } finally {
                panel.stop(); panel.release(); chosenSession.release(); otherSession.release();
            }
        }
    }

    /** What a paused session offers, as the video fixture's (518) and YouTube's paused sessions do. */
    private static final long PLAYABLE = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE;

    private static MediaController paused(MediaSession session, String packageName) {
        MediaController controller = session.getController();
        Shadows.shadowOf(controller).setPackageName(packageName);
        Shadows.shadowOf(controller).setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0, 0).setActions(PLAYABLE).build());
        return controller;
    }
}
