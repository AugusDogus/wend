package dev.augie.wend;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Refuses overlays built against another ROM's private framework resource IDs. */
final class WindowDecoration {
    private static final String PACKAGE = "dev.augie.wend.windowdecor";
    private WindowDecoration() { }

    static void enable(Context context) throws IOException, ReflectiveOperationException {
        if (!context.getPackageManager().hasSystemFeature("android.software.freeform_window_management")
                && android.provider.Settings.Global.getInt(context.getContentResolver(), "enable_freeform_support", 0) == 0) {
            throw new IOException("Enable freeform windows in Developer options, then restart Android");
        }
        ApplicationInfo overlay;
        try { overlay = context.getPackageManager().getApplicationInfo(PACKAGE, PackageManager.GET_META_DATA); }
        catch (PackageManager.NameNotFoundException error) {
            throw new IOException("Install the matching window-decoration APK first", error);
        }
        String expected = overlay.metaData == null ? null : overlay.metaData.getString("frameworkSha256");
        if (expected == null || !expected.equals(frameworkDigest())) {
            throw new IOException("Rebuild window decoration using this device's framework-res.apk");
        }
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "overlay");
        if (!(binder instanceof IBinder)) throw new IOException("Android's overlay service is unavailable");
        Object service = Class.forName("android.content.om.IOverlayManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object result = Class.forName("android.content.om.IOverlayManager")
                .getMethod("setEnabled", String.class, boolean.class, int.class)
                .invoke(service, PACKAGE, true, 0);
        if (!Boolean.TRUE.equals(result)) throw new IOException("Android could not enable window decoration");
    }

    private static String frameworkDigest() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream("/system/framework/framework-res.apk")) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}
