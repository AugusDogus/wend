package dev.augie.wend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the head unit's CAN app hears from the car and broadcasts, decoded, as it hears it: speed,
 * RPM and the outside temperature. Each car's protocol sends only some of them, and none until the
 * car does, so each is kept with when it came. See {@code build/px6/rom/notes/car.md}.
 */
final class CarSignals {
    /** One value as the CAN app worded it, and when, in uptime ms. */
    static final class Signal {
        final String value;
        final long at;

        Signal(String value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    /** The CAN app's broadcasts, by the name each value goes by here. */
    static final String SPEED = "com.microntek.canbus.speed", RPM = "com.microntek.canbus.rpm",
            TEMPERATURE = "com.canbus.temperature";
    private final Map<String, Signal> heard = new ConcurrentHashMap<>();

    /** Listens on {@code context} from now on. */
    CarSignals(Context context) {
        IntentFilter filter = new IntentFilter(SPEED);
        filter.addAction(RPM);
        filter.addAction(TEMPERATURE);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { heard(intent); }
        }, filter);
    }

    void heard(Intent intent) {
        String extra = SPEED.equals(intent.getAction()) ? "speed" : RPM.equals(intent.getAction()) ? "rpm" : "temperature";
        String value = intent.getStringExtra(extra);
        if (value != null && !value.trim().isEmpty()) heard.put(intent.getAction(), new Signal(value.trim(), SystemClock.uptimeMillis()));
    }

    /** @return the last {@code action} the car sent, or null if none came */
    Signal last(String action) { return heard.get(action); }
}
