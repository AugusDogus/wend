package dev.augie.wend;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;
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
    /** How a screenshot is sent: JPEG at this quality keeps a 1280x720 screen near 100 KiB. */
    private static final int JPEG_QUALITY = 80;

    private final Context context;

    CarControls(Context context) { this.context = context; }

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
