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
    private final HeadUnitTools unit = new HeadUnitTools(context, mcu);

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
        AgentTools.Result result = new HeadUnitTools(context, null).call("brightness", new JSONObject());
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
}
