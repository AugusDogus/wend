package dev.augie.wend;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Presses Maps' own Start button once, after Wend reopened a Maps whose process died while it
 * guided. Maps then restores its route preview by itself but waits for that press, and its guidance
 * notification names no destination Wend could hand back to it. Pressing another app's button is
 * what Android's accessibility services are for, so this is one: Wend turns it on only while it
 * waits for the button (as the system user it may), it hears only Maps, and it turns itself off.
 */
public final class GuidanceResume extends AccessibilityService {
    private static final String TAG = "WendNav";
    /** How long a reopened Maps may take to show its route preview; it took up to 10 s on the emulator. */
    private static final long WAIT_MS = 30_000;
    private static final long LOOK_EVERY_MS = 500;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** When to stop waiting for Maps' Start, in uptime ms, or null if no press is wanted. Main thread only. */
    private static Long until;
    /** The connected service, or null. Main thread only. */
    private static GuidanceResume connected;

    private final Runnable look = this::look;

    /** Asks for Maps' Start button to be pressed once it shows, within {@link #WAIT_MS}. */
    static void request(Context context) {
        Context app = context.getApplicationContext();
        String service = new ComponentName(app, GuidanceResume.class).flattenToString();
        try {
            ContentResolver resolver = app.getContentResolver();
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    enabled(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), service));
        } catch (RuntimeException error) {
            Log.w(TAG, "Guidance is not resumed: Wend could not turn on its accessibility service that presses"
                    + " Maps' Start. Maps is open at its route; press Start there", error);
            return;
        }
        until = SystemClock.uptimeMillis() + WAIT_MS;
        Log.i(TAG, "Waiting up to " + WAIT_MS + " ms for the reopened Maps to show Start, to resume its guidance");
        if (connected != null) connected.look();
        // If Android never connects the service, nothing else ends the wait.
        main.postDelayed(() -> giveUpUnconnected(app, service), WAIT_MS + LOOK_EVERY_MS);
    }

    private static void giveUpUnconnected(Context app, String service) {
        if (until == null || connected != null || SystemClock.uptimeMillis() < until) return;
        until = null;
        Log.w(TAG, "Guidance was not resumed: Android did not connect Wend's accessibility service within "
                + WAIT_MS + " ms. Maps is open; press Start there");
        try {
            ContentResolver resolver = app.getContentResolver();
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    disabled(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), service));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not turn Wend's accessibility service off again; it turns itself off when it connects", error);
        }
    }

    /** @return Android's colon-separated list of enabled accessibility services, with {@code service} in it once */
    static String enabled(String services, String service) {
        List<String> all = split(services);
        if (!all.contains(service)) all.add(service);
        return TextUtils.join(":", all);
    }

    /** @return the list without {@code service} */
    static String disabled(String services, String service) {
        List<String> all = split(services);
        all.remove(service);
        return TextUtils.join(":", all);
    }

    private static List<String> split(String services) {
        return TextUtils.isEmpty(services) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(services.split(":")));
    }

    /**
     * @return the button of {@code mapsPackage} under {@code root} that is named exactly
     *     {@code label} and can be pressed now, or null. Maps names its Start button by content
     *     description and puts the text on a child view.
     */
    static AccessibilityNodeInfo startButton(AccessibilityNodeInfo root, String mapsPackage, CharSequence label) {
        if (root == null || !TextUtils.equals(mapsPackage, root.getPackageName())) return null;
        if (TextUtils.equals("android.widget.Button", root.getClassName())
                && (TextUtils.equals(label, root.getContentDescription()) || TextUtils.equals(label, root.getText()))) {
            return root.isEnabled() && root.isVisibleToUser() ? root : null;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = startButton(root.getChild(i), mapsPackage, label);
            if (found != null) return found;
        }
        return null;
    }

    @Override protected void onServiceConnected() {
        connected = this;
        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = new String[]{EmbeddedMaps.PACKAGE};
        setServiceInfo(info);
        look();
    }

    // Maps' events only keep Android's copy of its views current for look(), which polls: a search per
    // event would walk Maps' whole tree many times a second while its map draws.
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        main.removeCallbacks(look);
        if (connected == this) connected = null;
        return super.onUnbind(intent);
    }

    private void look() {
        main.removeCallbacks(look);
        Long deadline = until;
        // Turned on by hand, or already done: it is only for the moment after Maps reopens.
        if (deadline == null) { disableSelf(); return; }
        AccessibilityNodeInfo start = find();
        if (start != null) {
            until = null;
            if (start.performAction(AccessibilityNodeInfo.ACTION_CLICK)) Log.i(TAG, "Pressed Start in the reopened Maps to resume its guidance");
            else Log.w(TAG, "Guidance was not resumed: Maps did not take the press on its Start button. Maps is open at its route; press Start there");
            disableSelf();
        } else if (SystemClock.uptimeMillis() >= deadline) {
            until = null;
            Log.w(TAG, "Guidance was not resumed: the reopened Maps showed no Start button within " + WAIT_MS
                    + " ms, so it did not restore its route. Maps is open; start navigation there");
            disableSelf();
        } else main.postDelayed(look, LOOK_EVERY_MS);
    }

    /** Maps' Start button, in its panel or fullscreen, or null while Maps does not show one. */
    private AccessibilityNodeInfo find() {
        CharSequence label = getString(R.string.maps_start_button);
        AccessibilityNodeInfo found = startButton(getRootInActiveWindow(), EmbeddedMaps.PACKAGE, label);
        if (found != null) return found;
        for (AccessibilityWindowInfo window : getWindows()) {
            found = startButton(window.getRoot(), EmbeddedMaps.PACKAGE, label);
            if (found != null) return found;
        }
        return null;
    }
}
