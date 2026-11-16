package dev.augie.wend;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Looper;
import java.time.Duration;
import android.os.Bundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class McpProtocolTest {
    private static final String MOTEL = "Motel 6 Ukiah, CA - North, 1340 N State St, Ukiah, CA 95482";
    private final Application context = RuntimeEnvironment.getApplication();
    private final McpProtocol protocol = new McpProtocol(new AgentTools(context));

    @After public void forget() { NavigationNotifications.forget(); MapsTrips.forget(); }

    private JSONObject ask(String method, JSONObject params) throws Exception {
        return protocol.answer(new JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", method).put("params", params).toString());
    }

    private JSONObject callTool(String name, JSONObject arguments) throws Exception {
        return ask("tools/call", new JSONObject().put("name", name).put("arguments", arguments)).getJSONObject("result");
    }

    private static String text(JSONObject result) throws Exception {
        return result.getJSONArray("content").getJSONObject(0).getString("text");
    }

    private Notification guidance(String distance, String road, String trip) {
        return guidanceBuilder(distance, road, trip).build();
    }

    /** Maps' guidance with its one action, "Exit navigation", which starts a service of Maps. */
    private Notification guidanceWithExit() {
        PendingIntent exit = PendingIntent.getService(context, 0, new Intent("exit-navigation").setClassName(EmbeddedMaps.PACKAGE, "MapsNavigationService"), PendingIntent.FLAG_IMMUTABLE);
        return guidanceBuilder("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA")
                .addAction(new Notification.Action.Builder(null, "Exit navigation", exit).build()).build();
    }

    private Notification.Builder guidanceBuilder(String distance, String road, String trip) {
        return new Notification.Builder(context, "1_foreground_1")
                .setSmallIcon(Icon.createWithBitmap(Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)))
                .setLargeIcon(Icon.createWithBitmap(Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888)))
                .setCategory(Notification.CATEGORY_NAVIGATION).setOngoing(true)
                .setContentTitle(distance).setContentText(road).setSubText(trip);
    }

    @Test public void itSpeaksTheVersionTheClientAsksForWhenItKnowsItAndItsNewestOtherwise() throws Exception {
        JSONObject known = ask("initialize", new JSONObject().put("protocolVersion", "2025-06-18")).getJSONObject("result");
        assertEquals("2025-06-18", known.getString("protocolVersion"));
        assertTrue("Offers tools", known.getJSONObject("capabilities").has("tools"));
        assertEquals("wend", known.getJSONObject("serverInfo").getString("name"));
        JSONObject unknown = ask("initialize", new JSONObject().put("protocolVersion", "2099-01-01")).getJSONObject("result");
        assertEquals(McpProtocol.VERSIONS.get(0), unknown.getString("protocolVersion"));
    }

    @Test public void itListsItsToolsWithTheirInputSchemas() throws Exception {
        JSONArray tools = ask("tools/list", new JSONObject()).getJSONObject("result").getJSONArray("tools");
        assertEquals(3, tools.length());
        assertEquals("navigate_to", tools.getJSONObject(0).getString("name"));
        assertEquals("destination", tools.getJSONObject(0).getJSONObject("inputSchema").getJSONArray("required").getString(0));
        assertEquals("add_stop", tools.getJSONObject(1).getString("name"));
        assertEquals("place", tools.getJSONObject(1).getJSONObject("inputSchema").getJSONArray("required").getString(0));
        assertEquals("trip_status", tools.getJSONObject(2).getString("name"));
    }

    @Test public void navigateToOpensMapsNavigationLinkToTheDestination() throws Exception {
        JSONObject result = callTool("navigate_to", new JSONObject().put("destination", MOTEL));
        assertFalse(result.getBoolean("isError"));
        Intent link = shadowOf(context).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, link.getAction());
        assertEquals(EmbeddedMaps.PACKAGE, link.getPackage());
        assertEquals("google.navigation", link.getData().getScheme());
        assertEquals("q=" + MOTEL, Uri.decode(link.getData().getEncodedSchemeSpecificPart()));
    }

    /**
     * Maps, already guiding, asks the driver whether to exit its route when a new link arrives, and
     * keeps the route when nobody answers. Its own "Exit navigation" ends it without asking.
     */
    @Test public void navigateToWhileMapsGuidesEndsThatGuidanceFirst() throws Exception {
        NavigationNotifications.reportedByMaps(guidanceWithExit());
        JSONObject result = callTool("navigate_to", new JSONObject().put("destination", MOTEL));
        assertFalse(result.getBoolean("isError"));
        assertEquals("exit-navigation", shadowOf(context).getNextStartedService().getAction());
        assertNull("Not before the guidance ended", shadowOf(context).getNextStartedActivity());
        NavigationNotifications.reportedByMaps(null);
        Intent link = shadowOf(context).getNextStartedActivity();
        assertEquals("q=" + MOTEL, Uri.decode(link.getData().getEncodedSchemeSpecificPart()));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AgentTools.EXIT_WAIT_MS));
        assertNull("Only once", shadowOf(context).getNextStartedActivity());
    }

    @Test public void navigateToOpensTheLinkAnywayIfMapsKeepsGuiding() throws Exception {
        NavigationNotifications.reportedByMaps(guidanceWithExit());
        callTool("navigate_to", new JSONObject().put("destination", MOTEL));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AgentTools.EXIT_WAIT_MS));
        assertEquals("google.navigation", shadowOf(context).getNextStartedActivity().getData().getScheme());
    }

    /** The Maps bridge module takes Wend's ask, as it does inside Maps; it answers later through Wend's provider. */
    private void mapsBridgeListens() {
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context received, Intent intent) { setResultCode(1); }
        }, new android.content.IntentFilter(AgentTools.ADD_STOP));
    }

    /** add_stop as Wend's MCP server calls it, off the main thread, while the main thread runs. */
    private Future<JSONObject> addStopOffMain(String place) {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        return worker.submit(() -> callTool("add_stop", new JSONObject().put("place", place)));
    }

    /** Runs the main thread until the module's ask goes out, as it does for the server's thread. */
    private Intent askedOfMaps() throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            for (Intent sent : shadowOf(context).getBroadcastIntents()) {
                if (AgentTools.ADD_STOP.equals(sent.getAction())) return sent;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Wend never asked Maps to add the stop");
    }

    private JSONObject answered(Future<JSONObject> call) throws Exception {
        for (int i = 0; i < 500 && !call.isDone(); i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        return call.get(1, TimeUnit.SECONDS);
    }

    /** The Maps bridge module, inside Maps, reports Maps' answer as Maps' own uid. */
    private void mapsSays(int request, boolean added, String problem) {
        shadowOf(context.getPackageManager()).setPackagesForUid(10111, EmbeddedMaps.PACKAGE);
        ShadowBinder.setCallingUid(10111);
        Bundle extras = new Bundle();
        extras.putInt(AgentTools.REQUEST, request);
        extras.putBoolean("added", added);
        if (problem != null) extras.putString("problem", problem);
        org.robolectric.Robolectric.buildContentProvider(MapsTrips.class).create("dev.augie.wend.mapstrips").get()
                .call("stop", null, extras);
        ShadowBinder.reset();
    }

    /** Maps' own Add stop, through the module: Maps keeps guiding and reroutes through the place. */
    @Test public void addStopAsksMapsAndSaysWhatMapsAnswered() throws Exception {
        NavigationNotifications.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        mapsBridgeListens();
        Future<JSONObject> call = addStopOffMain("Chevron, 1099 S State St, Ukiah, CA");
        Intent ask = askedOfMaps();
        assertEquals(EmbeddedMaps.PACKAGE, ask.getPackage());
        assertEquals("Chevron, 1099 S State St, Ukiah, CA", ask.getStringExtra(AgentTools.PLACE));
        mapsSays(ask.getIntExtra(AgentTools.REQUEST, -1), true, null);
        JSONObject result = answered(call);
        assertFalse(result.getBoolean("isError"));
        assertTrue(text(result).startsWith("Maps added \"Chevron, 1099 S State St, Ukiah, CA\""));
    }

    @Test public void addStopSaysWhyMapsAddedNothing() throws Exception {
        NavigationNotifications.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        mapsBridgeListens();
        Future<JSONObject> call = addStopOffMain("Nowhere at all");
        mapsSays(askedOfMaps().getIntExtra(AgentTools.REQUEST, -1), false, "Maps could not add \"Nowhere at all\"");
        JSONObject result = answered(call);
        assertTrue(result.getBoolean("isError"));
        assertEquals("Maps could not add \"Nowhere at all\". The route did not change.", text(result));
    }

    /** No module in Maps answers the ordered broadcast, which comes back with its first result. */
    @Test public void addStopWithoutTheMapsBridgeSaysSoAtOnce() throws Exception {
        NavigationNotifications.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        JSONObject result = answered(addStopOffMain("Chevron, Ukiah, CA"));
        assertTrue(result.getBoolean("isError"));
        assertTrue(text(result).startsWith("The Maps bridge module did not answer"));
    }

    @Test public void addStopWithoutGuidanceAsksNothing() throws Exception {
        JSONObject result = callTool("add_stop", new JSONObject().put("place", "Chevron, Ukiah, CA"));
        assertTrue(result.getBoolean("isError"));
        assertTrue(shadowOf(context).getBroadcastIntents().isEmpty());
    }

    @Test public void navigateToWithoutADestinationOpensNothing() throws Exception {
        JSONObject result = callTool("navigate_to", new JSONObject().put("destination", "  "));
        assertTrue(result.getBoolean("isError"));
        assertNull(shadowOf(context).getNextStartedActivity());
    }

    @Test public void tripStatusSaysWhenMapsGuidesNowhere() throws Exception {
        JSONObject status = new JSONObject(text(callTool("trip_status", new JSONObject())));
        assertFalse(status.getBoolean("guiding"));
        assertEquals(1, status.length());
    }

    /** What Maps' guidance notification and the Maps bridge module report, as the agent reads it. */
    @Test public void tripStatusIsTheGuidanceMapsReportsWithItsDestination() throws Exception {
        NavigationNotifications.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        MapsTrips.reported(MOTEL, new String[]{"Chevron, 1000 N State St"});
        JSONObject status = new JSONObject(text(callTool("trip_status", new JSONObject())));
        assertTrue(status.getBoolean("guiding"));
        assertEquals(MOTEL, status.getString("destination"));
        assertEquals("Chevron, 1000 N State St", status.getJSONArray("stops_ahead").getString(0));
        JSONObject turn = status.getJSONObject("next_turn");
        assertEquals("0.2 mi", turn.getString("distance"));
        assertEquals("CA-20 W", turn.getString("road"));
        assertFalse(turn.getBoolean("arrived"));
        assertEquals("9 min · 8.4 mi · 4:37 PM PST ETA", status.getString("remaining"));
        assertEquals("4:37 PM", status.getString("arrival"));
    }

    @Test public void tripStatusWithoutAReportedDestinationLeavesItOut() throws Exception {
        NavigationNotifications.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        JSONObject status = new JSONObject(text(callTool("trip_status", new JSONObject())));
        assertTrue(status.getBoolean("guiding"));
        assertFalse(status.has("destination"));
    }

    @Test public void unknownToolsAndMethodsAreProtocolErrors() throws Exception {
        JSONObject tool = ask("tools/call", new JSONObject().put("name", "open_sunroof"));
        assertEquals(-32602, tool.getJSONObject("error").getInt("code"));
        assertEquals(7, tool.getInt("id"));
        assertEquals(-32601, ask("resources/list", new JSONObject()).getJSONObject("error").getInt("code"));
    }

    @Test public void notificationsAndResponsesGetNoAnswerAndBadJsonIsAParseError() throws Exception {
        assertNull(protocol.answer("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertNull(protocol.answer("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"));
        JSONObject bad = protocol.answer("{not json");
        assertEquals(-32700, bad.getJSONObject("error").getInt("code"));
        assertTrue(bad.isNull("id"));
    }

    @Test public void pingIsAnsweredEmpty() throws Exception {
        assertEquals(0, ask("ping", new JSONObject()).getJSONObject("result").length());
    }
}
