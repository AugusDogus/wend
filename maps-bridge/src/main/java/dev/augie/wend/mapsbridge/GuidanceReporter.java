package dev.augie.wend.mapsbridge;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.os.Bundle;
import android.os.UserHandle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs inside Google Maps. Hands Wend each guidance notification Maps builds (category
 * {@code navigation}: the turn's arrow, distance, road and the trip's time), as Maps posts it, and
 * tells Wend when Maps takes it down. Android delivers no notification of an app whose
 * notifications are turned off, so on such a head unit this is how Wend learns the next turn.
 * Maps' own calls go on unchanged.
 *
 * <p>Each is a call of {@code guidance} on Wend's provider, with the notification as the extra
 * {@code notification}, or without it once guidance ended.
 */
final class GuidanceReporter {
    private static final String NOTIFICATION = "notification";
    /** {@code Service.STOP_FOREGROUND_REMOVE}. */
    private static final int REMOVE = 1;
    private final TripReporter reporter;
    /** The guidance notification Maps shows now, and its id and tag; null while it shows none. */
    private Notification shown;
    private int id;
    private String tag;
    /** The service that holds it as its foreground notification, if one does. */
    private Object holder;

    private GuidanceReporter(TripReporter reporter) { this.reporter = reporter; }

    static GuidanceReporter install(TripReporter reporter) {
        GuidanceReporter guidance = new GuidanceReporter(reporter);
        XposedHelpers.findAndHookMethod(NotificationManager.class, "notifyAsUser", String.class, int.class,
                Notification.class, UserHandle.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        guidance.posted((String) param.args[0], (int) param.args[1], (Notification) param.args[2], null);
                    }
                });
        XposedBridge.hookAllMethods(Service.class, "startForeground", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length >= 2 && param.args[1] instanceof Notification) {
                    guidance.posted(null, (int) param.args[0], (Notification) param.args[1], param.thisObject);
                }
            }
        });
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancelAsUser", String.class, int.class,
                UserHandle.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        guidance.cancelled((String) param.args[0], (int) param.args[1]);
                    }
                });
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancelAll", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { guidance.ended(); }
        });
        XposedBridge.hookAllMethods(Service.class, "stopForeground", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object how = param.args.length == 1 ? param.args[0] : null;
                boolean removes = Boolean.TRUE.equals(how) || how instanceof Integer && ((int) how & REMOVE) != 0;
                if (removes) guidance.stopped(param.thisObject);
            }
        });
        return guidance;
    }

    private synchronized void posted(String tag, int id, Notification notification, Object service) {
        if (notification == null) return;
        if (!Notification.CATEGORY_NAVIGATION.equals(notification.category)) {
            // The same notification, no longer guidance.
            if (shown != null && id == this.id && equal(tag, this.tag)) ended();
            return;
        }
        // Maps may change the one it handed over.
        shown = notification.clone();
        this.id = id;
        this.tag = tag;
        if (service != null) holder = service;
        report();
    }

    private synchronized void cancelled(String tag, int id) {
        if (shown != null && id == this.id && equal(tag, this.tag)) ended();
    }

    private synchronized void stopped(Object service) {
        if (shown != null && service == holder) ended();
    }

    private synchronized void ended() {
        if (shown == null) return;
        shown = null;
        holder = null;
        report();
    }

    /** Tells Wend what Maps shows now; again when Wend asks, as after Wend restarts. */
    synchronized void report() {
        Bundle extras = new Bundle();
        if (shown != null) extras.putParcelable(NOTIFICATION, shown);
        reporter.tellWend("guidance", null, extras);
    }

    private static boolean equal(String a, String b) { return a == null ? b == null : a.equals(b); }
}
