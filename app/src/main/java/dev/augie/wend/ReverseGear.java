package dev.augie.wend;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the car is in reverse, as the head unit's MCU says (its {@code backview} state and
 * {@code CarEvent} reports), so Wend's windows above other apps can leave the unit's backup
 * camera clear. Without the unit's MCU the car is never in reverse here. Main thread.
 */
final class ReverseGear {
    interface Listener { void changed(boolean reversing); }

    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean engaged, started;

    private ReverseGear() { }

    /** Starts hearing {@code mcu} once; later calls do nothing. */
    static void start(HeadUnit.Mcu mcu) {
        if (started || mcu == null) return;
        started = true;
        engaged = mcu.booleanState("backview");
        mcu.listen("CarEvent", (type, report) -> {
            if (!"backview".equals(report.getString("type"))) return;
            boolean now = report.getBoolean("value");
            main.post(() -> changed(now));
        });
    }

    static boolean engaged() { return engaged; }

    static void listen(Listener listener) { listeners.add(listener); }

    static void stop(Listener listener) { listeners.remove(listener); }

    private static void changed(boolean now) {
        if (now == engaged) return;
        engaged = now;
        for (Listener listener : new ArrayList<>(listeners)) listener.changed(now);
    }

    /** As in a new process; for tests. */
    static void forget() {
        listeners.clear();
        engaged = false;
        started = false;
    }
}
