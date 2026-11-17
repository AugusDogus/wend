package dev.augie.wend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A head unit MCU for tests: parameters and states as set, and what was set on it. */
final class FakeMcu implements HeadUnit.Mcu {
    final Map<String, String> parameters = new HashMap<>();
    final Map<String, Object> states = new HashMap<>();
    final List<String> set = new ArrayList<>();
    int eq;
    boolean loudness;

    @Override public String parameter(String key) { return parameters.get(key); }
    @Override public void set(String key, String value) { set.add(key + "=" + value); parameters.put(key, value); }
    @Override public boolean booleanState(String key) { return Boolean.TRUE.equals(states.get(key)); }
    @Override public String stringState(String key) { return (String) states.get(key); }
    @Override public int eq() { return eq; }
    @Override public void eq(int preset) { eq = preset; }
    @Override public boolean loudness() { return loudness; }
    @Override public void loudness(boolean on) { loudness = on; }
    final Map<String, java.util.function.BiConsumer<String, android.os.Bundle>> listeners = new HashMap<>();
    @Override public void listen(String types, java.util.function.BiConsumer<String, android.os.Bundle> listener) {
        listeners.put(types, listener);
    }
}
