package dev.augie.wend;

import android.util.Log;
import java.lang.reflect.Method;

/**
 * The Microntek (MTC/HCT) head unit's MCU, which owns its volume, brightness, EQ, radio tuner and
 * car signals. Its ROM reaches the MCU through {@code android.microntek.CarManager}, a framework
 * class only that ROM has, so Wend calls it by reflection; elsewhere, as on the emulator, there is
 * none. See {@code build/px6/rom/notes/mcu.md} for the keys this uses.
 */
final class HeadUnit {
    /** Broadcasts the unit's own system service ({@code android.microntek.service}) acts on. */
    static final String VOLUME_SET = "com.microntek.VOLUME_SET", BACKLIGHT_SET = "com.microntek.BLIGHT_SET",
            KEY_DOWN = "com.microntek.irkeyDown";
    /** The MCU key code that toggles mute ({@code IRTable.IR_MUTE}). */
    static final int KEY_MUTE = 258;
    /** The Settings.System key that holds the unit's volume level; the trailing "=" is part of it. */
    static final String VOLUME_SETTING = "av_volume=";

    /** The MCU, as {@code CarManager} offers it. */
    interface Mcu {
        /** @return the MCU's value for {@code key} ("cfg_backlight"), or null if it gave none */
        String parameter(String key);
        /** Sets {@code key=value} on the MCU. */
        void set(String key, String value);
        boolean booleanState(String key);
        String stringState(String key);
        /** @return the EQ preset, 0 for the user's own */
        int eq();
        void eq(int preset);
        boolean loudness();
        void loudness(boolean on);
        /**
         * Hears the MCU's reports of {@code types} ("Radio") from now on, each with its Bundle
         * ({@code type}, {@code value}, ...), on a binder thread.
         */
        void listen(String types, java.util.function.BiConsumer<String, android.os.Bundle> listener);
    }

    /** How long a change may take to show in the unit's state; tests set it to 0. */
    static long settleMs = 1_500;

    /** Waits, off the main thread, until {@code done} or {@link #settleMs} passes. @return whether it is done */
    static boolean settle(java.util.function.BooleanSupplier done) {
        long until = android.os.SystemClock.uptimeMillis() + settleMs;
        while (!done.getAsBoolean()) {
            if (android.os.SystemClock.uptimeMillis() >= until) return false;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    private static final String TAG = "WendHeadUnit";
    private static Mcu mcu;
    private static boolean looked;

    private HeadUnit() { }

    /** @return the unit's MCU, or null when this Android has none */
    static synchronized Mcu mcu() {
        if (!looked) {
            looked = true;
            try {
                mcu = new ReflectedMcu();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError none) {
                Log.i(TAG, "No Microntek MCU on this Android (" + none + "); head unit tools are off");
            }
        }
        return mcu;
    }

    /** {@code android.microntek.CarManager}, called by reflection. */
    private static final class ReflectedMcu implements Mcu {
        private final Object manager;
        private final Method getParameters, setParameters, getBooleanState, getStringState, getEqIdx, setEqIdx, getLud, setLud;

        ReflectedMcu() throws ReflectiveOperationException {
            Class<?> type = Class.forName("android.microntek.CarManager");
            manager = type.getConstructor().newInstance();
            getParameters = type.getMethod("getParameters", String.class);
            setParameters = type.getMethod("setParameters", String.class);
            getBooleanState = type.getMethod("getBooleanState", String.class);
            getStringState = type.getMethod("getStringState", String.class);
            getEqIdx = type.getMethod("getEqIdx");
            setEqIdx = type.getMethod("setEqIdx", int.class);
            getLud = type.getMethod("getLud");
            setLud = type.getMethod("setLud", boolean.class);
        }

        @Override public String parameter(String key) { return (String) call(getParameters, key + "="); }
        @Override public void set(String key, String value) { call(setParameters, key + "=" + value); }
        @Override public boolean booleanState(String key) { return Boolean.TRUE.equals(call(getBooleanState, key)); }
        @Override public String stringState(String key) { return (String) call(getStringState, key); }
        @Override public int eq() { return (Integer) call(getEqIdx); }
        @Override public void eq(int preset) { call(setEqIdx, preset); }
        @Override public boolean loudness() { return ((Integer) call(getLud)) != 0; }
        @Override public void loudness(boolean on) { call(setLud, on); }

        @Override public void listen(String types, java.util.function.BiConsumer<String, android.os.Bundle> listener) {
            try {
                // attach works once per CarManager, so each listener has its own.
                Class<?> type = Class.forName("android.microntek.CarManager"), callback = Class.forName("android.microntek.CarListener");
                Object own = type.getConstructor().newInstance();
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                        (self, method, arguments) -> {
                            if (method.getName().equals("onStatusChanged")) {
                                listener.accept((String) arguments[0], (android.os.Bundle) arguments[1]);
                            }
                            return method.getDeclaringClass() == Object.class ? method.invoke(this, arguments) : null;
                        });
                type.getMethod("attach", callback, String.class).invoke(own, proxy, types);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("Could not listen to the head unit's " + types + " reports", failed);
            }
        }

        private Object call(Method method, Object... arguments) {
            try {
                return method.invoke(manager, arguments);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException("The head unit's CarManager." + method.getName() + " failed", failed);
            }
        }
    }
}
