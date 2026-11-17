package dev.augie.wend;

import android.app.Application;
import android.content.Intent;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class HeadUnitToolsTest {
    private final Application context = RuntimeEnvironment.getApplication();
    private final FakeMcu mcu = new FakeMcu();
    private HeadUnitTires.Reading tires;
    private HeadUnitRadio.Station station;
    private int[] presets;
    private String stationName;
    private final HeadUnitTools unit = new HeadUnitTools(context, mcu, () -> tires, new HeadUnitRadio.Source() {
        @Override public HeadUnitRadio.Station station() { return station; }
        @Override public int[] presets() { return presets; }
        @Override public String name() { return stationName; }
    });

    @Before public void quickly() { HeadUnit.settleMs = 0; }

    private JSONObject answer(String name, JSONObject arguments) throws Exception {
        AgentTools.Result result = unit.call(name, arguments);
        assertFalse(result.text, result.error);
        return new JSONObject(result.text);
    }

    private Intent sent(String action) {
        Intent found = null;
        for (Intent intent : shadowOf(context).getBroadcastIntents()) if (action.equals(intent.getAction())) found = intent;
        return found;
    }

    @Test public void withoutAHeadUnitItsToolsSaySo() throws Exception {
        AgentTools.Result result = new HeadUnitTools(context, null, null, null).call("brightness", new JSONObject());
        assertTrue(result.error);
        assertTrue(result.text, result.text.contains("no Microntek head unit"));
    }

    @Test public void otherToolsAreNotItsOwn() throws Exception {
        assertNull(unit.call("navigate_to", new JSONObject()));
    }

    @Test public void brightnessReadsTheBacklightAndSetsItAsTheUnitsSettingDoes() throws Exception {
        mcu.parameters.put("cfg_backlight", "255");
        assertEquals(100, answer("brightness", new JSONObject()).getInt("percent"));
        answer("brightness", new JSONObject().put("percent", 40));
        assertEquals(102, sent(HeadUnit.BACKLIGHT_SET).getIntExtra("level", -1));
        answer("brightness", new JSONObject().put("percent", 0));
        assertEquals("The screen stays readable", 10, sent(HeadUnit.BACKLIGHT_SET).getIntExtra("level", -1));
        assertTrue(unit.call("brightness", new JSONObject().put("percent", 101)).error);
    }

    @Test public void eqSetsThePresetAndLoudness() throws Exception {
        JSONObject set = answer("eq", new JSONObject().put("preset", 3).put("loudness", true));
        assertEquals(3, set.getInt("preset"));
        assertTrue(set.getBoolean("loudness"));
        assertTrue(unit.call("eq", new JSONObject().put("preset", 7)).error);
        assertEquals(3, mcu.eq);
    }

    @Test public void carStatusIsTheUnitsStateAndWhatTheCarSent() throws Exception {
        mcu.states.put("backview", true);
        mcu.states.put("backview_mode", "backview");
        mcu.states.put("headlight", true);
        mcu.states.put("handbrake", false);
        mcu.states.put("carpower", "power_on");
        mcu.states.put("av_channel", "fm");
        mcu.parameters.put("av_mute", "false");
        mcu.parameters.put("sta_mcu_version", "MTCE_GS_V3.83");
        unit.heard(new Intent(CarSignals.SPEED).putExtra("speed", "42"));
        JSONObject status = answer("car_status", new JSONObject());
        assertTrue(status.getBoolean("reverse"));
        assertEquals("backview", status.getString("camera"));
        assertTrue(status.getBoolean("headlights"));
        assertTrue("No driving lock: the brake is on", status.getBoolean("parking_brake"));
        assertEquals("radio", status.getString("audio_source"));
        assertEquals("42", status.getString("speed_kmh"));
        assertEquals(0, status.getInt("speed_kmh_seconds_ago"));
        assertFalse("This car sent no RPM", status.has("rpm"));
        assertEquals("MTCE_GS_V3.83", status.getString("mcu_version"));
    }

    @Test public void tirePressureGivesEachTireInItsUnits() throws Exception {
        tires = new HeadUnitTires.Reading(new HeadUnitTires.Tire[]{new HeadUnitTires.Tire("32.5", "21", 0),
                new HeadUnitTires.Tire("32.0", "22", 0), new HeadUnitTires.Tire("24.1", "20", 3),
                new HeadUnitTires.Tire("", "", 0)}, 2, 0);
        JSONObject read = answer("tire_pressure", new JSONObject());
        assertEquals("psi", read.getString("pressure_unit"));
        assertEquals("32.5", read.getJSONObject("front_left").getString("pressure"));
        assertTrue(read.getJSONObject("rear_left").getBoolean("alarm"));
        assertEquals(3, read.getJSONObject("rear_left").getInt("alarm_code"));
        assertFalse("Nothing heard from it yet", read.getJSONObject("rear_right").has("pressure"));
    }

    @Test public void tirePressureWithoutATpmsBoxOrAppSaysSo() throws Exception {
        assertTrue("No TPMS service", unit.call("tire_pressure", new JSONObject()).error);
        mcu.parameters.put("cfg_tpms", "0");
        assertTrue(unit.call("tire_pressure", new JSONObject()).text.contains("no TPMS box"));
    }

    private static HeadUnitRadio.Station fm(int hz, int preset) {
        return new HeadUnitRadio.Station(0, hz, preset, 87_500_000, 107_900_000, 200_000, true);
    }

    @Test public void radioStatusGivesTheStationNameAndPresets() throws Exception {
        mcu.states.put("av_channel", "fm");
        station = fm(101_100_000, 2);
        stationName = "KQED";
        presets = new int[30];
        presets[0] = 88_500_000;
        presets[2] = 101_100_000;
        JSONObject status = answer("radio_status", new JSONObject());
        assertTrue(status.getBoolean("on"));
        assertEquals("FM1", status.getString("band"));
        assertEquals("101.1 MHz", status.getString("frequency"));
        assertEquals(3, status.getInt("preset"));
        assertEquals("KQED", status.getString("station"));
        assertEquals("88.5 MHz", status.getJSONObject("presets").getJSONArray("FM1").getString(0));
        assertTrue(status.getJSONObject("presets").getJSONArray("FM1").isNull(1));
    }

    @Test public void radioTunesThroughItsAppInTheBackground() throws Exception {
        station = fm(88_500_000, 0);
        assertFalse(unit.call("radio", new JSONObject().put("action", "tune").put("frequency", 101.1)).error);
        Intent tune = shadowOf(context).getNextStartedActivity();
        assertEquals("com.microntek.radio.MainActivity", tune.getComponent().getClassName());
        assertEquals("101100000", tune.getStringExtra("freq"));
        assertEquals(1, tune.getIntExtra("start", 0));
    }

    @Test public void radioRefusesAFrequencyItsBandDoesNotHave() throws Exception {
        station = fm(88_500_000, 0);
        AgentTools.Result result = unit.call("radio", new JSONObject().put("action", "tune").put("frequency", 101.0));
        assertTrue("Odd tenths only, as in the USA", result.error);
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test public void radioSeeksOnlyWhileItPlaysAndNeverAutomatically() throws Exception {
        assertTrue(unit.call("radio", new JSONObject().put("action", "seek_up")).error);
        mcu.states.put("av_channel", "fm");
        assertFalse(unit.call("radio", new JSONObject().put("action", "seek_up")).error);
        assertEquals(java.util.Arrays.asList("ctl_radio_seek=up"), mcu.set);
    }

    @Test public void radioOnPressesItsToggleOnlyWhenOff() throws Exception {
        mcu.states.put("av_channel", "fm");
        unit.call("radio", new JSONObject().put("action", "on"));
        assertNull(sent("hct.radio.power.switch"));
        mcu.states.put("av_channel", "sys");
        unit.call("radio", new JSONObject().put("action", "on"));
        assertTrue(sent("hct.radio.power.switch") != null);
        unit.call("radio", new JSONObject().put("action", "preset").put("preset", 4));
        assertTrue(sent("hct.radio.channel.four") != null);
    }
}
