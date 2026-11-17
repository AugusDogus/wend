package dev.augie.wend;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Process;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class CarControlsTest {
    private final Application context = RuntimeEnvironment.getApplication();
    private final List<Integer> pressed = new ArrayList<>();
    private final CarControls car = new CarControls(context, pressed::add);
    private final McpProtocol protocol = new McpProtocol(new AgentTools(context, car));

    private JSONObject callTool(String name, JSONObject arguments) throws Exception {
        JSONObject asked = new JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call")
                .put("params", new JSONObject().put("name", name).put("arguments", arguments));
        return protocol.answer(asked.toString()).getJSONObject("result");
    }

    private static String text(JSONObject result) throws Exception {
        return result.getJSONArray("content").getJSONObject(0).getString("text");
    }

    private static LauncherApp app(String packageName, String label) {
        return new LauncherApp(new ComponentName(packageName, packageName + ".Main"), Process.myUserHandle(), label, null);
    }

    private static final List<LauncherApp> APPS = Arrays.asList(app("com.spotify.music", "Spotify"),
            app("com.google.android.youtube", "YouTube"), app("com.google.android.apps.youtube.music", "YouTube Music"),
            app("com.android.settings", "Settings"));

    @Test public void goHomeBringsWendsHomeScreenToTheFront() throws Exception {
        assertFalse(callTool("go_home", new JSONObject()).getBoolean("isError"));
        Intent home = shadowOf(context).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), home.getComponent().getClassName());
        assertTrue(home.hasCategory(Intent.CATEGORY_HOME));
        assertNull("Home opens no app", home.getStringExtra(CarControls.OPEN_APP));
    }

    @Test public void goBackPressesBack() throws Exception {
        assertFalse(callTool("go_back", new JSONObject()).getBoolean("isError"));
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_BACK), pressed);
    }

    @Test public void listAppsNamesEachAppAndItsPackage() throws Exception {
        JSONArray apps = new JSONObject(car.apps(APPS).text).getJSONArray("apps");
        assertEquals(4, apps.length());
        assertEquals("Spotify", apps.getJSONObject(0).getString("name"));
        assertEquals("com.spotify.music", apps.getJSONObject(0).getString("package"));
    }

    @Test public void appsAreRefusedUntilWendHasLoadedThem() throws Exception {
        assertTrue(car.apps(null).error);
        assertTrue(car.openApp("Spotify", null).error);
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test public void openAppOpensItThroughWendsHomeScreen() {
        assertFalse(car.openApp("spotify", APPS).error);
        Intent open = shadowOf(context).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), open.getComponent().getClassName());
        assertEquals("com.spotify.music", open.getStringExtra(CarControls.OPEN_APP));
    }

    @Test public void openAppPrefersTheAppNamedExactlyOverThoseWhoseNameHoldsIt() {
        assertFalse(car.openApp("YouTube", APPS).error);
        assertEquals("com.google.android.youtube", shadowOf(context).getNextStartedActivity().getStringExtra(CarControls.OPEN_APP));
    }

    @Test public void openAppByPartOfTwoNamesOpensNothingAndNamesThem() {
        AgentTools.Result result = car.openApp("tube", APPS);
        assertTrue(result.error);
        assertTrue(result.text, result.text.contains("com.google.android.apps.youtube.music"));
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test public void openAppByPackageOpensThatApp() {
        assertFalse(car.openApp("com.google.android.apps.youtube.music", APPS).error);
        assertEquals("com.google.android.apps.youtube.music",
                shadowOf(context).getNextStartedActivity().getStringExtra(CarControls.OPEN_APP));
    }

    @Test public void openAppOfNoSuchAppOpensNothing() {
        assertTrue(car.openApp("Waze", APPS).error);
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test public void screenStatusNamesTheAppInFront() throws Exception {
        ActivityManager.RunningTaskInfo maps = new ActivityManager.RunningTaskInfo();
        maps.topActivity = new ComponentName(EmbeddedMaps.PACKAGE, "com.google.android.maps.MapsActivity");
        shadowOf(context.getSystemService(ActivityManager.class)).setTasks(Arrays.asList(maps));
        JSONObject status = new JSONObject(text(callTool("screen_status", new JSONObject())));
        assertEquals(EmbeddedMaps.PACKAGE, status.getString("package"));
        assertFalse(status.getBoolean("home"));
    }

    /** A session of {@code packageName} in {@code state}, playing {@code title}. */
    private MediaController session(String packageName, int state, String title) {
        MediaSession session = new MediaSession(context, packageName);
        MediaController controller = session.getController();
        Shadows.shadowOf(controller).setPackageName(packageName);
        Shadows.shadowOf(controller).setPlaybackState(new PlaybackState.Builder().setState(state, 61_000, 1).build());
        Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000).build());
        Shadows.shadowOf(context.getSystemService(MediaSessionManager.class)).addController(controller);
        return controller;
    }

    @Test public void mediaStatusListsTheSessionPlayingFirst() throws Exception {
        session("com.google.android.youtube", PlaybackState.STATE_PAUSED, "A video");
        session("com.spotify.music", PlaybackState.STATE_PLAYING, "A song");
        JSONArray sessions = new JSONObject(text(callTool("media_status", new JSONObject()))).getJSONArray("sessions");
        assertEquals(2, sessions.length());
        JSONObject playing = sessions.getJSONObject(0);
        assertEquals("com.spotify.music", playing.getString("package"));
        assertEquals("playing", playing.getString("state"));
        assertEquals("A song", playing.getString("title"));
        assertEquals(200, playing.getInt("duration_seconds"));
        assertEquals(61, playing.getInt("position_seconds"));
        assertEquals("paused", sessions.getJSONObject(1).getString("state"));
    }

    @Test public void mediaControlActsOnTheSessionPlayingUnlessToldAnother() throws Exception {
        session("com.google.android.youtube", PlaybackState.STATE_PAUSED, "A video");
        session("com.spotify.music", PlaybackState.STATE_PLAYING, "A song");
        // Robolectric's sessions hear no transport controls, so this checks which app and action Wend chose.
        assertTrue(text(callTool("media_control", new JSONObject().put("action", "toggle"))).startsWith("Asked com.spotify.music to pause."));
        assertTrue(text(callTool("media_control", new JSONObject().put("action", "next"))).startsWith("Asked com.spotify.music to next."));
        assertTrue(text(callTool("media_control", new JSONObject().put("action", "play").put("app", "com.google.android.youtube")))
                .startsWith("Asked com.google.android.youtube to play."));
    }

    @Test public void mediaControlWithoutSessionsOrWithAnUnknownActionDoesNothing() throws Exception {
        assertTrue(callTool("media_control", new JSONObject().put("action", "play")).getBoolean("isError"));
        session("com.spotify.music", PlaybackState.STATE_PLAYING, "A song");
        JSONObject rewind = callTool("media_control", new JSONObject().put("action", "rewind"));
        assertTrue(rewind.getBoolean("isError"));
        assertTrue(text(rewind), text(rewind).contains("play, pause, toggle, next or previous"));
    }

    @Test public void volumeReadsAndSetsTheMediaVolume() throws Exception {
        AudioManager audio = context.getSystemService(AudioManager.class);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        JSONObject set = new JSONObject(text(callTool("volume", new JSONObject().put("percent", 50))));
        assertEquals(Math.round(max / 2f), audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertEquals(max, set.getInt("max"));
        JSONObject read = new JSONObject(text(callTool("volume", new JSONObject())));
        assertEquals(set.getInt("level"), read.getInt("level"));
    }

    @Test public void volumeRefusesALevelOutOfRangeOrAnUnknownChange() throws Exception {
        AudioManager audio = context.getSystemService(AudioManager.class);
        int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        assertTrue(callTool("volume", new JSONObject().put("percent", 120)).getBoolean("isError"));
        assertTrue(callTool("volume", new JSONObject().put("change", "louder")).getBoolean("isError"));
        assertEquals(before, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    private Intent sent(String action) {
        Intent found = null;
        for (Intent intent : shadowOf(context).getBroadcastIntents()) if (action.equals(intent.getAction())) found = intent;
        return found;
    }

    @Test public void onTheHeadUnitVolumeSetsTheUnitsOwnVolume() throws Exception {
        HeadUnit.settleMs = 0;
        FakeMcu mcu = new FakeMcu();
        mcu.parameters.put("cfg_maxvolume", "30");
        mcu.parameters.put("av_mute", "false");
        android.provider.Settings.System.putInt(context.getContentResolver(), HeadUnit.VOLUME_SETTING, 12);
        CarControls unit = new CarControls(context, pressed::add, mcu);
        JSONObject read = new JSONObject(unit.volume(null, "").text);
        assertEquals(40, read.getInt("percent"));
        assertEquals(30, read.getInt("max"));
        assertFalse(unit.volume(50, "").error);
        assertEquals(15, sent(HeadUnit.VOLUME_SET).getIntExtra("level", -1));
        assertFalse(unit.volume(null, "down").error);
        assertEquals("sub", sent(HeadUnit.VOLUME_SET).getStringExtra("type"));
    }

    @Test public void onTheHeadUnitMutePressesTheMuteKeyOnlyToChangeIt() throws Exception {
        HeadUnit.settleMs = 0;
        FakeMcu mcu = new FakeMcu();
        mcu.parameters.put("av_mute", "true");
        CarControls unit = new CarControls(context, pressed::add, mcu);
        assertFalse(unit.volume(null, "mute").error);
        assertNull("Already muted", sent(HeadUnit.KEY_DOWN));
        assertFalse(unit.volume(null, "unmute").error);
        assertEquals(HeadUnit.KEY_MUTE, sent(HeadUnit.KEY_DOWN).getIntExtra("keyCode", -1));
    }
}
