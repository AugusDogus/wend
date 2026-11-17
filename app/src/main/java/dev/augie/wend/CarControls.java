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
import android.view.Display;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What an agent may do with the car's head unit itself, besides Maps' guidance ({@link AgentTools}).
 * Each call returns what the agent hears.
 */
final class CarControls {
    /** Presses a key as the driver would; off the main thread. */
    interface Keys { void press(int keyCode); }

    /** How a screenshot is sent: JPEG at this quality keeps a 1280x720 screen near 100 KiB. */
    private static final int JPEG_QUALITY = 80;

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
