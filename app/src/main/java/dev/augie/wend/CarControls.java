package dev.augie.wend;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.view.Display;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What an agent may do with the car's head unit itself, besides Maps' guidance ({@link AgentTools}).
 * Each call returns what the agent hears.
 */
final class CarControls {
    /** Presses a key as the driver would; off the main thread. */
    interface Keys { void press(int keyCode); }

    /** Asks Wend's home screen to open the app this extra names, as its dock and drawer do. */
    static final String OPEN_APP = "dev.augie.wend.OPEN_APP";
    /** How a screenshot is sent: JPEG at this quality keeps a 1280x720 screen near 100 KiB. */
    private static final int JPEG_QUALITY = 80;

    private static final String NO_APPS = "Wend has not loaded the car's apps yet, or could not; try again in a few seconds.";
    private final Context context;
    private final Keys keys;

    CarControls(Context context) {
        // Injecting keys needs INJECT_EVENTS, which Wend holds as a platform-signed app.
        this(context, code -> new Instrumentation().sendKeyDownUpSync(code));
    }

    CarControls(Context context, Keys keys) {
        this.context = context;
        this.keys = keys;
    }

    /** Brings Wend's home screen to the front, as the Home button does. */
    AgentTools.Result home() {
        context.startActivity(homeIntent());
        return AgentTools.Result.ok("Wend's home screen is in front.");
    }

    /** Presses Back for whatever is in front. Off the main thread: the key waits for its delivery. */
    AgentTools.Result back() {
        try {
            keys.press(KeyEvent.KEYCODE_BACK);
        } catch (SecurityException | IllegalStateException failed) {
            return AgentTools.Result.failed("Android did not take the Back key: " + failed.getMessage() + ". Nothing changed.");
        }
        return AgentTools.Result.ok("Pressed Back. Call screen_status to see what is in front now.");
    }

    /** @param apps the apps Wend's drawer lists, null while it still loads them; main thread */
    AgentTools.Result apps(List<LauncherApp> apps) throws JSONException {
        if (apps == null) return AgentTools.Result.failed(NO_APPS);
        JSONArray listed = new JSONArray();
        for (LauncherApp app : apps) {
            if (!AppCatalog.canPin(app)) continue;
            listed.put(new JSONObject().put("name", app.label).put("package", app.key.packageName));
        }
        return AgentTools.Result.ok(new JSONObject().put("apps", listed).toString());
    }

    /**
     * Opens the app {@code asked} names through Wend's home screen, as a tap in its drawer does:
     * Maps and the video app come back in their own tasks.
     *
     * @param asked an app's package, its name, or part of its name
     * @param apps the apps Wend's drawer lists, null while it still loads them
     */
    AgentTools.Result openApp(String asked, List<LauncherApp> apps) {
        if (asked.isEmpty()) return AgentTools.Result.failed("open_app needs an app: its name or package, from list_apps.");
        if (apps == null) return AgentTools.Result.failed(NO_APPS);
        List<LauncherApp> matches = match(asked, apps);
        if (matches.isEmpty()) {
            return AgentTools.Result.failed("No app in the car is named \"" + asked + "\"; list_apps names them. Nothing opened.");
        }
        if (matches.size() > 1) {
            List<String> names = new ArrayList<>();
            for (LauncherApp app : matches) names.add(app.label + " (" + app.key.packageName + ")");
            return AgentTools.Result.failed("\"" + asked + "\" names more than one app: " + String.join(", ", names)
                    + ". Pass one's package. Nothing opened.");
        }
        LauncherApp app = matches.get(0);
        context.startActivity(homeIntent().putExtra(OPEN_APP, app.key.packageName));
        return AgentTools.Result.ok("Opened " + app.label + ".");
    }

    /** @return the one app whose package is {@code asked}, else those named it, else those whose name holds it */
    private static List<LauncherApp> match(String asked, List<LauncherApp> apps) {
        List<LauncherApp> named = new ArrayList<>(), partly = new ArrayList<>();
        String lower = asked.toLowerCase(Locale.ROOT);
        for (LauncherApp app : apps) {
            if (!AppCatalog.canPin(app)) continue;
            if (app.key.packageName.equals(asked)) return java.util.Collections.singletonList(app);
            String label = app.label.toLowerCase(Locale.ROOT);
            if (label.equals(lower)) named.add(app);
            else if (label.contains(lower)) partly.add(app);
        }
        return named.isEmpty() ? partly : named;
    }

    private Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Names the app in front on the car's screen. */
    @SuppressWarnings("deprecation")
    AgentTools.Result screenStatus() throws JSONException {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningTaskInfo> tasks = manager == null ? null : manager.getRunningTasks(1);
        if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) {
            return AgentTools.Result.failed("Android did not say which app is in front.");
        }
        String app = tasks.get(0).topActivity.getPackageName();
        JSONObject status = new JSONObject().put("app", label(app)).put("package", app)
                .put("home", context.getPackageName().equals(app));
        return AgentTools.Result.ok(status.toString());
    }

    /** Takes a picture of the car's whole screen. Off the main thread. */
    AgentTools.Result screenshot() {
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows == null) return AgentTools.Result.failed("Android gave Wend no window service, so it cannot see the screen.");
        Display display = windows.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        Bitmap shot;
        try {
            // Android 10's overload, which READ_FRAME_BUFFER lets Wend call; it leaves out secure layers.
            Object result = SurfaceControl.class.getDeclaredMethod("screenshot", Rect.class, int.class, int.class, int.class)
                    .invoke(null, new Rect(0, 0, size.x, size.y), size.x, size.y, display.getRotation());
            if (!(result instanceof Bitmap)) return AgentTools.Result.failed("Android took no screenshot; try again.");
            shot = (Bitmap) result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            return AgentTools.Result.failed("Wend could not take a screenshot: " + failed + ". It needs Android 10 and"
                    + " Wend's platform signature.");
        }
        try {
            Bitmap drawable = shot.getConfig() == Bitmap.Config.HARDWARE ? shot.copy(Bitmap.Config.ARGB_8888, false) : shot;
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            drawable.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg);
            if (drawable != shot) drawable.recycle();
            return AgentTools.Result.image("The car's screen, " + size.x + "x" + size.y + ".", jpeg.toByteArray(), "image/jpeg");
        } finally { shot.recycle(); }
    }

    /** Lists what plays or played lately in the car, the one media_control acts on first. */
    AgentTools.Result mediaStatus() throws JSONException {
        List<MediaController> sessions = sessions();
        JSONArray listed = new JSONArray();
        for (MediaController session : sessions) listed.put(describe(session));
        return AgentTools.Result.ok(new JSONObject().put("sessions", listed).toString());
    }

    /**
     * @param action play, pause, toggle, next or previous
     * @param asked the app to control, by package or name; empty for the one playing, or else the
     *     latest
     */
    AgentTools.Result mediaControl(String action, String asked) throws JSONException {
        List<MediaController> sessions = sessions();
        MediaController target = null;
        for (MediaController session : sessions) {
            if (asked.isEmpty() || session.getPackageName().equals(asked) || label(session.getPackageName()).equalsIgnoreCase(asked)) {
                target = session;
                break;
            }
        }
        if (target == null) {
            return AgentTools.Result.failed(asked.isEmpty() ? "Nothing plays or played lately in the car; open a media app first."
                    : "\"" + asked + "\" has no media session now; media_status lists those that do. Nothing changed.");
        }
        JSONObject before = describe(target);
        MediaController.TransportControls controls = target.getTransportControls();
        if (action.equals("toggle")) action = playing(target) ? "pause" : "play";
        switch (action) {
            case "play": controls.play(); break;
            case "pause": controls.pause(); break;
            case "next": controls.skipToNext(); break;
            case "previous": controls.skipToPrevious(); break;
            default:
                return AgentTools.Result.failed("media_control takes play, pause, toggle, next or previous, not \"" + action + "\".");
        }
        return AgentTools.Result.ok("Asked " + label(target.getPackageName()) + " to " + action + ". Before, it was: " + before);
    }

    /** @return the active sessions, the one playing first, else in Android's order, the latest first */
    private List<MediaController> sessions() {
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        List<MediaController> active = manager == null ? new ArrayList<>() : new ArrayList<>(manager.getActiveSessions(null));
        List<MediaController> ordered = new ArrayList<>();
        for (MediaController session : active) if (playing(session)) ordered.add(session);
        for (MediaController session : active) if (!playing(session)) ordered.add(session);
        return ordered;
    }

    private JSONObject describe(MediaController session) throws JSONException {
        JSONObject described = new JSONObject().put("app", label(session.getPackageName())).put("package", session.getPackageName());
        PlaybackState state = session.getPlaybackState();
        described.put("state", state == null ? "none" : stateName(state.getState()));
        MediaMetadata metadata = session.getMetadata();
        if (metadata != null) {
            put(described, "title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            put(described, "artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            put(described, "album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (duration > 0) described.put("duration_seconds", duration / 1000);
        }
        if (state != null && state.getPosition() >= 0) described.put("position_seconds", position(state) / 1000);
        return described;
    }

    /** @return where playback is now, in ms: the session reports where it was when its state last changed */
    private static long position(PlaybackState state) {
        if (state.getState() != PlaybackState.STATE_PLAYING || state.getLastPositionUpdateTime() <= 0) return state.getPosition();
        long since = android.os.SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
        return state.getPosition() + (long) (since * state.getPlaybackSpeed());
    }

    private static void put(JSONObject object, String name, String value) throws JSONException {
        if (value != null && !value.isEmpty()) object.put(name, value);
    }

    private static boolean playing(MediaController session) {
        PlaybackState state = session.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private static String stateName(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_BUFFERING: case PlaybackState.STATE_CONNECTING: return "buffering";
            case PlaybackState.STATE_ERROR: return "error";
            default: return "none";
        }
    }

    private String label(String packageName) {
        PackageManager packages = context.getPackageManager();
        try {
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            return packages.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException gone) {
            return packageName;
        }
    }
}
