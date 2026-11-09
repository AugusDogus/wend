package dev.augie.wend;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Wend's control of one app's {@code PICTURE_IN_PICTURE} app-op, so the app enters its own native
 * picture-in-picture only when Wend deliberately floats it.
 *
 * <p>At rest the op is held at {@code MODE_IGNORED}: Android 10's
 * {@code ActivityRecord.checkEnterPictureInPictureAppOpsState} then returns {@code false}, so a
 * backgrounded Maps or video never shrinks itself into a PiP window on plain Home, and no small
 * window flashes before Wend moves the app back to its panel or card. For a deliberate float Wend
 * allows the op, lets the app background and enter its own PiP (with the app's own layout and
 * aspect ratio), and restores {@code MODE_IGNORED} once the app is back.
 *
 * <p>The op and the restore target are a single {@link State}, so the op is {@code MODE_ALLOWED}
 * for exactly one package while it is the deliberately floated app and is never left allowed once
 * the float ends, including when the float fails. SystemUI's {@code PipAppOpsListener} dismisses a
 * PiP when the pinned package's op changes away from allowed, so Wend restores the op only after
 * the app has left PiP (its task has moved back to a Wend display, which unpins it).
 */
final class PipAppOp {
    /** Whether a call changed the stored op mode. */
    enum Outcome { CHANGED, UNCHANGED, NOT_INSTALLED }

    /** The only two states: at rest (ignored) or deliberately floating (allowed). */
    private enum State { RESTING, FLOATING }

    private final String packageName;
    private State state = State.RESTING;

    PipAppOp(String packageName) { this.packageName = packageName; }

    /** @return whether Wend is currently holding the op allowed for a deliberate float. */
    boolean floating() { return state == State.FLOATING; }

    /**
     * Holds the op at {@code MODE_IGNORED} so the app cannot enter PiP when it is backgrounded.
     * A no-op while floating, so it never changes the op out from under a live PiP and lets the
     * app stay in its own window. Only {@code MODE_ALLOWED} is changed: a driver's deliberate
     * Settings choice of another mode is left alone, as is the untouched default once Wend has
     * set it.
     */
    Outcome holdResting(Context context) throws ReflectiveOperationException {
        if (state == State.FLOATING) return Outcome.UNCHANGED;
        return setMode(context, AppOpsManager.MODE_IGNORED, false);
    }

    /**
     * Allows the op so the app can enter its own PiP when Wend backgrounds it for a deliberate
     * float. Idempotent while already floating. The caller must {@link #restore} once the float
     * ends or fails, including in a {@code finally}.
     */
    Outcome allowForFloat(Context context) throws ReflectiveOperationException {
        if (state == State.FLOATING) return Outcome.UNCHANGED;
        Outcome outcome = setMode(context, AppOpsManager.MODE_ALLOWED, true);
        if (outcome != Outcome.NOT_INSTALLED) state = State.FLOATING;
        return outcome;
    }

    /**
     * Returns the op to {@code MODE_IGNORED} after a deliberate float. Safe to call after the app
     * has left PiP; call it only then, because SystemUI dismisses a still-pinned PiP when its op
     * changes. Idempotent at rest, so a failed float that never allowed the op is also left ignored.
     */
    Outcome restore(Context context) throws ReflectiveOperationException {
        state = State.RESTING;
        return setMode(context, AppOpsManager.MODE_IGNORED, false);
    }

    /**
     * @param force when false, only an op currently at {@code MODE_ALLOWED} is changed, leaving a
     *     driver's other Settings choice intact; when true, any mode is set (used for the float,
     *     whose round trip Wend owns end to end)
     */
    private Outcome setMode(Context context, int mode, boolean force) throws ReflectiveOperationException {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) throw new IllegalStateException("Android app-ops service is unavailable");
        int uid;
        try { uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid; }
        catch (PackageManager.NameNotFoundException missing) { return Outcome.NOT_INSTALLED; }
        int current = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, uid, packageName);
        if (current == mode) return Outcome.UNCHANGED;
        if (!force && current != AppOpsManager.MODE_ALLOWED) return Outcome.UNCHANGED;
        // System API, hidden from the public SDK. Android 10 grants MANAGE_APP_OPS_MODES to UID 1000.
        AppOpsManager.class.getMethod("setMode", String.class, int.class, String.class, int.class)
                .invoke(appOps, AppOpsManager.OPSTR_PICTURE_IN_PICTURE, uid, packageName, mode);
        Log.i("WendPip", "Set picture-in-picture app-op for " + packageName + " (uid " + uid + ") to "
                + (mode == AppOpsManager.MODE_ALLOWED ? "allow (deliberate float)" : "ignore (resting)"));
        return Outcome.CHANGED;
    }
}
