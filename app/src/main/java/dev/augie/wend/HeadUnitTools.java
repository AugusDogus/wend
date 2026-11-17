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
    private final HeadUnitTires.Source tires;
    private final HeadUnitRadio.Source radio;
    private final HeadUnitPhone.Source phone;

    HeadUnitTools(Context context) { this(context, HeadUnit.mcu()); }

    HeadUnitTools(Context context, HeadUnit.Mcu mcu) {
        this(context, mcu, HeadUnitTires.of(context), mcu == null ? null : HeadUnitRadio.of(context, mcu), HeadUnitPhone.of(context));
    }

    HeadUnitTools(Context context, HeadUnit.Mcu mcu, HeadUnitTires.Source tires, HeadUnitRadio.Source radio,
            HeadUnitPhone.Source phone) {
        this.context = context;
        this.mcu = mcu;
        this.tires = tires;
        this.radio = radio;
        this.phone = phone;
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
                .put(tool("tire_pressure", "Reads each tire's pressure, temperature and alarm from the head unit's TPMS"
                        + " app, when the car has a TPMS box set up on the unit. Off the main thread.", noArguments()))
                .put(tool("radio_status", "Reads the head unit's FM/AM radio: whether it plays, its band, frequency,"
                        + " preset, the station's RDS name when it sends one, and the presets of each band. Off the main"
                        + " thread.", noArguments()))
                .put(tool("radio", "Controls the head unit's FM/AM radio through its radio app, which stays behind"
                        + " whatever is on screen: on (makes the radio the car's audio), off, tune (to frequency, MHz for"
                        + " FM like 101.1 or kHz for AM like 1010), seek_up or seek_down to the next station, or preset"
                        + " (1 to 6 of the band it is on).",
                        new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("action", new JSONObject().put("type", "string").put("enum", new JSONArray().put("on")
                                        .put("off").put("tune").put("seek_up").put("seek_down").put("preset")))
                                .put("frequency", new JSONObject().put("type", "number")
                                        .put("description", "For tune: MHz for FM (101.1), kHz for AM (1010)"))
                                .put("preset", new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 6)))
                                .put("required", new JSONArray().put("action"))))
                .put(tool("phone_status", "Reads the phone connected to the head unit over Bluetooth: its name, and"
                        + " the call (idle, dialing, ringing or in_call) with its number and the caller's name from the"
                        + " phone book the unit synced. Off the main thread.", noArguments()))
                .put(tool("recent_calls", "Lists the latest calls of the phone connected to the head unit, newest"
                        + " first: number, name from its phone book, when, and outgoing, incoming or missed. Off the main thread.",
                        new JSONObject().put("type", "object").put("properties", new JSONObject().put("limit",
                                new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50)
                                        .put("description", "How many; 10 if left out")))))
                .put(tool("call", "Calls a number, or a contact by name from the phone book the head unit synced,"
                        + " on the phone connected over Bluetooth; the call plays through the car. Give number or"
                        + " contact. Off the main thread.",
                        new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("number", new JSONObject().put("type", "string").put("description", "Digits, with + * # allowed"))
                                .put("contact", new JSONObject().put("type", "string")
                                        .put("description", "A name, or part of one, from the phone book")))))
                .put(tool("answer", "Answers the call ringing on the phone connected to the head unit.", noArguments()))
                .put(tool("hang_up", "Hangs up the call on the phone connected to the head unit, or declines it while it"
                        + " rings.", noArguments()))
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
            case "tire_pressure": return mcu == null ? none() : tirePressure();
            case "radio_status": return mcu == null ? none() : radioStatus();
            case "radio":
                return mcu == null ? none() : radio(arguments.optString("action", ""),
                        arguments.has("frequency") ? arguments.optDouble("frequency") : null,
                        arguments.has("preset") ? arguments.optInt("preset") : null);
            case "phone_status": return mcu == null ? none() : phoneStatus();
            case "recent_calls": return mcu == null ? none() : recentCalls(arguments.optInt("limit", 10));
            case "call":
                return mcu == null ? none() : call(arguments.optString("number", "").trim(), arguments.optString("contact", "").trim());
            case "answer": return mcu == null ? none() : answer();
            case "hang_up": return mcu == null ? none() : hangUp();
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

    private AgentTools.Result tirePressure() throws JSONException {
        if ("0".equals(mcu.parameter("cfg_tpms"))) {
            return AgentTools.Result.failed("This head unit has no TPMS box set up (its MCU's cfg_tpms is 0), so it reads no tire pressures.");
        }
        HeadUnitTires.Reading reading;
        try {
            reading = tires.read();
        } catch (android.os.RemoteException | RuntimeException failed) {
            return AgentTools.Result.failed("The head unit's TPMS app did not answer: " + failed + ".");
        }
        if (reading == null) return AgentTools.Result.failed("The head unit's TPMS app (com.microntek.tpms) is not running or not installed.");
        JSONObject answer = new JSONObject()
                .put("pressure_unit", reading.pressureUnit == 2 ? "psi" : reading.pressureUnit == 1 ? "bar" : "kPa")
                .put("temperature_unit", reading.temperatureUnit == 1 ? "F" : "C");
        String[] names = {"front_left", "front_right", "rear_left", "rear_right"};
        for (int i = 0; i < names.length; i++) {
            HeadUnitTires.Tire tire = reading.tires[i];
            JSONObject read = new JSONObject().put("alarm", tire.alarm != 0);
            if (tire.pressure != null && !tire.pressure.isEmpty()) read.put("pressure", tire.pressure);
            if (tire.temperature != null && !tire.temperature.isEmpty()) read.put("temperature", tire.temperature);
            if (tire.alarm != 0) read.put("alarm_code", tire.alarm);
            answer.put(names[i], read);
        }
        return AgentTools.Result.ok(answer.toString());
    }

    private boolean radioPlays() { return "fm".equals(mcu.stringState("av_channel")); }

    private AgentTools.Result radioStatus() throws JSONException {
        JSONObject status = new JSONObject().put("on", radioPlays());
        HeadUnitRadio.Station station = radio.station();
        if (station == null) {
            return AgentTools.Result.failed("The head unit's radio app (com.microntek.radio) did not answer; it may not be"
                    + " running, or this unit has no tuner.");
        }
        status.put("band", HeadUnitRadio.BANDS[Math.min(station.band, 4)]).put("frequency", HeadUnitRadio.words(station.hz))
                .put("frequency_hz", station.hz);
        if (station.preset >= 0) status.put("preset", station.preset + 1);
        String name = radio.name();
        if (name != null) status.put("station", name);
        int[] presets = radio.presets();
        if (presets != null) {
            JSONObject bands = new JSONObject();
            for (int band = 0; band < HeadUnitRadio.BANDS.length; band++) {
                JSONArray own = new JSONArray();
                for (int hz : HeadUnitRadio.band(presets, band)) own.put(hz > 0 ? HeadUnitRadio.words(hz) : JSONObject.NULL);
                bands.put(HeadUnitRadio.BANDS[band], own);
            }
            status.put("presets", bands);
        }
        return AgentTools.Result.ok(status.toString());
    }

    /** The radio app's own broadcasts for presets 1 to 6 of its band. */
    private static final String[] PRESETS = {"one", "two", "three", "four", "five", "six"};

    private AgentTools.Result radio(String action, Double frequency, Integer preset) throws JSONException {
        // The unit's phone call owns the audio; the radio app ignores its keys then, and so does Wend.
        if (!action.equals("off") && phoneState() >= HeadUnitPhone.DIALING) {
            return AgentTools.Result.failed("A phone call is on; the radio waits until it ends. Nothing changed.");
        }
        switch (action) {
            case "on":
                // The radio app's power switch toggles, so it is pressed only to turn it on.
                if (!radioPlays()) context.sendBroadcast(new Intent("hct.radio.power.switch"));
                HeadUnit.settle(this::radioPlays);
                return radioPlays() ? AgentTools.Result.ok("The radio plays.") : AgentTools.Result.failed("The radio did not start.");
            case "off":
                context.sendBroadcast(new Intent("com.microntek.finish").putExtra("class", "com.microntek.radio"));
                HeadUnit.settle(() -> !radioPlays());
                return AgentTools.Result.ok("The radio is off.");
            case "seek_up": case "seek_down":
                if (!radioPlays()) return AgentTools.Result.failed("The radio is off; turn it on first. Nothing changed.");
                // Never "auto": that scans and overwrites the presets.
                mcu.set("ctl_radio_seek", action.equals("seek_up") ? "up" : "down");
                return AgentTools.Result.ok("The radio seeks the " + (action.equals("seek_up") ? "next" : "previous")
                        + " station; call radio_status in a few seconds for where it stopped.");
            case "preset":
                if (preset == null || preset < 1 || preset > 6) return AgentTools.Result.failed("preset needs a preset from 1 to 6.");
                context.sendBroadcast(new Intent("hct.radio.channel." + PRESETS[preset - 1]));
                return AgentTools.Result.ok("The radio tunes to preset " + preset + " of its band.");
            case "tune": return tune(frequency);
            default:
                return AgentTools.Result.failed("radio's action is on, off, tune, seek_up, seek_down or preset, not \"" + action + "\".");
        }
    }

    /**
     * Tunes through the radio app's activity, which sets its band and preset right and starts its
     * audio; started with {@code start}, it goes behind at once, and whatever was in front is put
     * back if it came forward anyway.
     */
    private AgentTools.Result tune(Double frequency) throws JSONException {
        int hz = frequency == null ? -1 : HeadUnitRadio.hz(frequency);
        if (hz < 0) return AgentTools.Result.failed("tune needs a frequency: MHz for FM (101.1) or kHz for AM (1010).");
        HeadUnitRadio.Station station = radio.station();
        if (station != null && station.fm == hz >= 10_000_000 && station.minHz > 0
                && (hz < station.minHz || hz > station.maxHz || station.stepHz > 0 && (hz - station.minHz) % station.stepHz != 0)) {
            return AgentTools.Result.failed(HeadUnitRadio.words(hz) + " is not a frequency this radio tunes: from "
                    + HeadUnitRadio.words(station.minHz) + " to " + HeadUnitRadio.words(station.maxHz) + " in steps of "
                    + station.stepHz / 1000 + " kHz. Nothing changed.");
        }
        int front = frontTask();
        context.startActivity(new Intent().setClassName("com.microntek.radio", "com.microntek.radio.MainActivity")
                .putExtra("freq", String.valueOf(hz)).putExtra("start", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        HeadUnit.settle(this::radioPlays);
        if (front != -1 && frontTask() != front) {
            android.app.ActivityManager tasks = context.getSystemService(android.app.ActivityManager.class);
            if (tasks != null) tasks.moveTaskToFront(front, 0);
        }
        return AgentTools.Result.ok("The radio tunes to " + HeadUnitRadio.words(hz) + ".");
    }

    /** @return the task in front, or -1 if Android does not say */
    @SuppressWarnings("deprecation")
    private int frontTask() {
        android.app.ActivityManager tasks = context.getSystemService(android.app.ActivityManager.class);
        java.util.List<android.app.ActivityManager.RunningTaskInfo> top = tasks == null ? null : tasks.getRunningTasks(1);
        return top == null || top.isEmpty() ? -1 : top.get(0).id;
    }

    private static final String NO_PHONE = "The head unit's Bluetooth service (android.microntek.mtcser) did not answer.";

    private static String callState(int state) {
        switch (state) {
            case HeadUnitPhone.IDLE: return "idle";
            case HeadUnitPhone.DIALING: return "dialing";
            case HeadUnitPhone.RINGING: return "ringing";
            case HeadUnitPhone.IN_CALL: return "in_call";
            default: return "none";
        }
    }

    private AgentTools.Result phoneStatus() throws JSONException {
        try {
            Integer state = phone.state();
            if (state == null) return AgentTools.Result.failed(NO_PHONE);
            JSONObject status = new JSONObject().put("connected", state != HeadUnitPhone.DISCONNECTED);
            if (state == HeadUnitPhone.DISCONNECTED) return AgentTools.Result.ok(status.toString());
            status.put("phone", phone.phoneName()).put("call", callState(state));
            if (state >= HeadUnitPhone.DIALING) {
                String number = phone.number();
                status.put("number", number);
                String name = HeadUnitPhone.nameOf(number, phone.phoneBook());
                if (name != null) status.put("name", name);
            }
            return AgentTools.Result.ok(status.toString());
        } catch (android.os.RemoteException | RuntimeException failed) {
            return AgentTools.Result.failed(NO_PHONE + " " + failed);
        }
    }

    private AgentTools.Result recentCalls(int limit) throws JSONException {
        try {
            Integer state = phone.state();
            if (state == null) return AgentTools.Result.failed(NO_PHONE);
            if (state == HeadUnitPhone.DISCONNECTED) return AgentTools.Result.failed("No phone is connected to the head unit.");
            long address = phone.phoneAddress();
            java.util.List<String> book = phone.phoneBook(), log = phone.calls();
            JSONArray calls = new JSONArray();
            for (int i = log.size() - 1; i >= 0 && calls.length() < Math.max(1, Math.min(limit, 50)); i--) {
                String[] fields = log.get(i).split("\\^");
                // Only this phone's calls: each entry starts with its phone's address in hex.
                if (fields.length < 6 || address != 0 && !fields[0].equalsIgnoreCase(String.format("%012X", address))) continue;
                JSONObject call = new JSONObject().put("number", fields[1]).put("when", fields[2] + " " + fields[3])
                        .put("type", fields[5].equals("1") ? "outgoing" : fields[5].equals("2") ? "incoming" : "missed");
                String name = HeadUnitPhone.nameOf(fields[1], book);
                if (name != null) call.put("name", name);
                calls.put(call);
            }
            return AgentTools.Result.ok(new JSONObject().put("calls", calls).toString());
        } catch (android.os.RemoteException | RuntimeException failed) {
            return AgentTools.Result.failed(NO_PHONE + " " + failed);
        }
    }

    /** What the Bluetooth module may be asked to dial: anything else could carry its own commands. */
    private static final java.util.regex.Pattern DIALABLE = java.util.regex.Pattern.compile("\\+?[0-9*#]{1,32}");

    private AgentTools.Result call(String number, String contact) throws JSONException {
        if (number.isEmpty() == contact.isEmpty()) return AgentTools.Result.failed("call needs a number or a contact, not both.");
        try {
            Integer state = phone.state();
            if (state == null) return AgentTools.Result.failed(NO_PHONE);
            if (state == HeadUnitPhone.DISCONNECTED) return AgentTools.Result.failed("No phone is connected to the head unit.");
            if (state != HeadUnitPhone.IDLE) return AgentTools.Result.failed("The phone is " + callState(state) + "; nothing was dialed.");
            String name = null;
            if (!contact.isEmpty()) {
                java.util.Map<String, String> found = contacts(contact, phone.phoneBook());
                if (found.isEmpty()) {
                    return AgentTools.Result.failed("No one in the phone book the head unit synced is named \"" + contact + "\"."
                            + (phone.phoneBook().isEmpty() ? " Its phone book is empty: open the unit's Bluetooth phone app"
                            + " once with the phone connected to sync it." : "") + " Nothing was dialed.");
                }
                if (found.size() > 1) {
                    return AgentTools.Result.failed("\"" + contact + "\" matches more than one number: " + found
                            + ". Pass the number. Nothing was dialed.");
                }
                java.util.Map.Entry<String, String> only = found.entrySet().iterator().next();
                number = only.getKey();
                name = only.getValue();
            }
            String dialed = number.replaceAll("[\\s().-]", "");
            if (!DIALABLE.matcher(dialed).matches()) {
                return AgentTools.Result.failed("\"" + number + "\" is not a phone number: digits, with + * # allowed. Nothing was dialed.");
            }
            phone.dial(dialed);
            HeadUnit.settle(() -> phoneState() != HeadUnitPhone.IDLE);
            return AgentTools.Result.ok("Dialing " + (name == null ? dialed : name + " (" + dialed + ")") + " on the phone;"
                    + " the unit's call screen shows it. hang_up ends it.");
        } catch (android.os.RemoteException | RuntimeException failed) {
            return AgentTools.Result.failed(NO_PHONE + " " + failed);
        }
    }

    /** @return the numbers in {@code phoneBook} of names that hold {@code contact}, each with its name */
    static java.util.Map<String, String> contacts(String contact, java.util.List<String> phoneBook) {
        java.util.Map<String, String> exact = new java.util.LinkedHashMap<>(), partly = new java.util.LinkedHashMap<>();
        String wanted = contact.toLowerCase(java.util.Locale.ROOT);
        for (String entry : phoneBook) {
            String[] fields = entry.split("\\^");
            if (fields.length < 2 || fields[1].isEmpty()) continue;
            String name = fields[0].toLowerCase(java.util.Locale.ROOT);
            if (name.equals(wanted)) exact.put(fields[1], fields[0]);
            else if (name.contains(wanted)) partly.put(fields[1], fields[0]);
        }
        return exact.isEmpty() ? partly : exact;
    }

    private int phoneState() {
        try {
            Integer state = phone.state();
            return state == null ? HeadUnitPhone.DISCONNECTED : state;
        } catch (android.os.RemoteException failed) {
            return HeadUnitPhone.DISCONNECTED;
        }
    }

    private AgentTools.Result answer() throws JSONException {
        try {
            Integer state = phone.state();
            if (state == null) return AgentTools.Result.failed(NO_PHONE);
            if (state != HeadUnitPhone.RINGING) return AgentTools.Result.failed("No call is ringing; the phone is " + callState(state) + ".");
            phone.answer();
            HeadUnit.settle(() -> phoneState() == HeadUnitPhone.IN_CALL);
            return AgentTools.Result.ok("Answered the call.");
        } catch (android.os.RemoteException | RuntimeException failed) {
            return AgentTools.Result.failed(NO_PHONE + " " + failed);
        }
    }

    private AgentTools.Result hangUp() throws JSONException {
        try {
            Integer state = phone.state();
            if (state == null) return AgentTools.Result.failed(NO_PHONE);
            if (state < HeadUnitPhone.DIALING) return AgentTools.Result.failed("There is no call; the phone is " + callState(state) + ".");
            phone.hangUp();
            HeadUnit.settle(() -> phoneState() < HeadUnitPhone.DIALING);
            return AgentTools.Result.ok(state == HeadUnitPhone.RINGING ? "Declined the call." : "Hung up.");
        } catch (android.os.RemoteException | RuntimeException failed) {
            return AgentTools.Result.failed(NO_PHONE + " " + failed);
        }
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
