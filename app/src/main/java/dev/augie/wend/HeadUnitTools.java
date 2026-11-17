package dev.augie.wend;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Agent tools for what only the Microntek head unit has ({@link HeadUnit}): the car's state as the
 * unit sees it, and its brightness and EQ.
 * Each answers that the car has no such unit when Wend runs elsewhere.
 */
final class HeadUnitTools {
    private final Context context;
    /** The unit's MCU; null without one, as on the emulator. */
    private final HeadUnit.Mcu mcu;
    private final CarSignals signals;

    HeadUnitTools(Context context) { this(context, HeadUnit.mcu()); }

    HeadUnitTools(Context context, HeadUnit.Mcu mcu) {
        this.context = context;
        this.mcu = mcu;
        signals = new CarSignals(context);
    }

    /** @return the tools, as MCP's tools/list gives them */
    JSONArray list() throws JSONException {
        return new JSONArray()
                .put(tool("car_status", "Reads the car as the head unit sees it: whether it is in reverse (and which"
                        + " camera shows), the headlights, the parking brake, its power state, the audio source (android,"
                        + " radio, bluetooth, aux, ...), whether it is muted, and the MCU's version; and the speed (km/h),"
                        + " RPM and outside temperature with how many seconds ago the car sent each, only if this car's"
                        + " CAN adapter sends them.", noArguments()))
                .put(tool("brightness", "Reads the head unit's screen brightness, or sets it to percent, as its own"
                        + " brightness setting does.", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("percent", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100)
                                .put("description", "The brightness to set, 0 to 100; the unit keeps a little light at 0")))))
                .put(tool("eq", "Reads the head unit's sound EQ preset and loudness, or sets them, as its sound settings"
                        + " do. Preset 0 is the driver's own EQ; 1 to 6 are the unit's presets.",
                        new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("preset", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 6))
                                .put("loudness", new JSONObject().put("type", "boolean")))));
    }

    /** @return the tool's answer, or null if {@code name} is none of {@link #list()} */
    AgentTools.Result call(String name, JSONObject arguments) throws JSONException {
        switch (name) {
            case "car_status": return mcu == null ? none() : carStatus();
            case "brightness": return mcu == null ? none() : brightness(arguments.has("percent") ? arguments.optInt("percent") : null);
            case "eq":
                return mcu == null ? none() : eq(arguments.has("preset") ? arguments.optInt("preset") : null,
                        arguments.has("loudness") ? arguments.optBoolean("loudness") : null);
            default: return null;
        }
    }

    private static AgentTools.Result none() {
        return AgentTools.Result.failed("This car's Android has no Microntek head unit MCU, so Wend cannot reach that setting.");
    }

    private AgentTools.Result carStatus() throws JSONException {
        JSONObject status = new JSONObject().put("reverse", mcu.booleanState("backview"));
        String camera = mcu.stringState("backview_mode");
        if (mcu.booleanState("backview") && camera != null) status.put("camera", camera);
        // The unit's "handbrake" state is its driving lock: on while the parking brake is off.
        status.put("headlights", mcu.booleanState("headlight")).put("parking_brake", !mcu.booleanState("handbrake"));
        String power = mcu.stringState("carpower");
        if (power != null) status.put("power", power);
        String source = mcu.stringState("av_channel");
        if (source != null) status.put("audio_source", SOURCES.getOrDefault(source, source));
        status.put("muted", "true".equals(mcu.parameter("av_mute")));
        signal(status, "speed_kmh", CarSignals.SPEED);
        signal(status, "rpm", CarSignals.RPM);
        signal(status, "outside_temperature", CarSignals.TEMPERATURE);
        String version = mcu.parameter("sta_mcu_version");
        if (version != null && !version.isEmpty()) status.put("mcu_version", version);
        return AgentTools.Result.ok(status.toString());
    }

    /** The MCU's audio sources, by what the driver calls them. */
    private static final java.util.Map<String, String> SOURCES = new java.util.HashMap<>();
    static {
        SOURCES.put("sys", "android");
        SOURCES.put("fm", "radio");
        SOURCES.put("gsm_bt", "bluetooth");
        SOURCES.put("line", "aux");
        SOURCES.put("dvr", "dashcam");
        SOURCES.put("dvd", "dvd");
        SOURCES.put("dtv", "tv");
        SOURCES.put("ipod", "ipod");
        SOURCES.put("usbipod", "ipod");
        SOURCES.put("easyconn", "phone_link");
    }

    private void signal(JSONObject status, String name, String action) throws JSONException {
        CarSignals.Signal heard = signals.last(action);
        if (heard == null) return;
        status.put(name, heard.value).put(name + "_seconds_ago", (android.os.SystemClock.uptimeMillis() - heard.at) / 1000);
    }

    /** CarSignals hears {@code intent}, as if the car's CAN app sent it; for tests. */
    void heard(Intent intent) { signals.heard(intent); }

    private static JSONObject noArguments() throws JSONException {
        return new JSONObject().put("type", "object").put("properties", new JSONObject());
    }

    /** The unit's backlight, 0 to 255, which its brightness setting sets with the same broadcast. */
    private AgentTools.Result brightness(Integer percent) throws JSONException {
        if (percent != null) {
            if (percent < 0 || percent > 100) return AgentTools.Result.failed("percent " + percent + " is not between 0 and 100; nothing changed.");
            // The unit's own setting never goes below 10, which leaves the screen readable.
            int target = Math.max(MIN_BACKLIGHT, Math.round(percent * 255 / 100f));
            context.sendBroadcast(new Intent(HeadUnit.BACKLIGHT_SET).putExtra("level", target));
            HeadUnit.settle(() -> backlight() == target);
        }
        int level = backlight();
        return AgentTools.Result.ok(new JSONObject().put("percent", Math.round(level * 100f / 255)).put("level", level)
                .put("max", 255).toString());
    }

    private static final int MIN_BACKLIGHT = 10;

    private int backlight() { return CarControls.number(mcu.parameter("cfg_backlight"), -1); }

    private AgentTools.Result eq(Integer preset, Boolean loudness) throws JSONException {
        if (preset != null) {
            if (preset < 0 || preset > 6) return AgentTools.Result.failed("preset " + preset + " is not between 0 and 6; nothing changed.");
            mcu.eq(preset);
        }
        if (loudness != null) mcu.loudness(loudness);
        return AgentTools.Result.ok(new JSONObject().put("preset", mcu.eq()).put("loudness", mcu.loudness()).toString());
    }

    static JSONObject tool(String name, String description, JSONObject inputSchema) throws JSONException {
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", inputSchema);
    }
}
