package dev.augie.wend;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class CarControlsTest {
    private final Application context = RuntimeEnvironment.getApplication();
    private final List<Integer> pressed = new ArrayList<>();
    private final CarControls car = new CarControls(context, pressed::add);
    private final McpProtocol protocol = new McpProtocol(new AgentTools(context, car));

    private JSONObject callTool(String name, JSONObject arguments) throws Exception {
        JSONObject asked = new JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call")
                .put("params", new JSONObject().put("name", name).put("arguments", arguments));
        return protocol.answer(asked.toString()).getJSONObject("result");
    }

    private static String text(JSONObject result) throws Exception {
        return result.getJSONArray("content").getJSONObject(0).getString("text");
    }

    @Test public void goHomeBringsWendsHomeScreenToTheFront() throws Exception {
        assertFalse(callTool("go_home", new JSONObject()).getBoolean("isError"));
        Intent home = shadowOf(context).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), home.getComponent().getClassName());
        assertTrue(home.hasCategory(Intent.CATEGORY_HOME));
    }

    @Test public void goBackPressesBack() throws Exception {
        assertFalse(callTool("go_back", new JSONObject()).getBoolean("isError"));
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_BACK), pressed);
    }

    @Test public void screenStatusNamesTheAppInFront() throws Exception {
        ActivityManager.RunningTaskInfo maps = new ActivityManager.RunningTaskInfo();
        maps.topActivity = new ComponentName(EmbeddedMaps.PACKAGE, "com.google.android.maps.MapsActivity");
        shadowOf(context.getSystemService(ActivityManager.class)).setTasks(Arrays.asList(maps));
        JSONObject status = new JSONObject(text(callTool("screen_status", new JSONObject())));
        assertEquals(EmbeddedMaps.PACKAGE, status.getString("package"));
        assertFalse(status.getBoolean("home"));
    }
}
