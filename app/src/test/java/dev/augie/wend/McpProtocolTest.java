package dev.augie.wend;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Looper;
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

    @After public void forget() { MapsGuidance.forget(); MapsTrips.forget(); }

    private JSONObject ask(String method, JSONObject params) throws Exception {
        return protocol.answer(new JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", method).put("params", params).toString());
    }

    private JSONObject callTool(String name, JSONObject arguments) throws Exception {
        return ask("tools/call", new JSONObject().put("name", name).put("arguments", arguments)).getJSONObject("result");
    }

    private static String text(JSONObject result) throws Exception {
        return result.getJSONArray("content").getJSONObject(0).getString("text");
    }

    /** Maps guides, as the Maps bridge module reports it; {@code trip} names the time and distance left in Maps' words. */
    private static Bundle guidance(String distance, String road, String trip) {
        Bundle report = GuidanceReports.guiding(distance, road);
        String[] parts = trip.split(" · ");
        report.putCharSequence("time_left", parts[0]);
        report.putCharSequence("distance_left", parts[1]);
        return report;
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
        assertEquals(25, tools.length());
        assertEquals("navigate_to", tools.getJSONObject(0).getString("name"));
        assertEquals("destination", tools.getJSONObject(0).getJSONObject("inputSchema").getJSONArray("required").getString(0));
        assertEquals("add_stop", tools.getJSONObject(1).getString("name"));
        assertEquals("place", tools.getJSONObject(1).getJSONObject("inputSchema").getJSONArray("required").getString(0));
        assertEquals("remove_stop", tools.getJSONObject(2).getString("name"));
        assertEquals("remove_next_stop", tools.getJSONObject(3).getString("name"));
        assertEquals("list_routes", tools.getJSONObject(4).getString("name"));
        assertEquals("switch_route", tools.getJSONObject(5).getString("name"));
        assertEquals("end_navigation", tools.getJSONObject(6).getString("name"));
        assertEquals("trip_status", tools.getJSONObject(7).getString("name"));
        String[] car = {"go_home", "go_back", "list_apps", "open_app", "screen_status", "screenshot", "media_status",
                "media_control", "volume"};
        for (int i = 0; i < car.length; i++) assertEquals(car[i], tools.getJSONObject(8 + i).getString("name"));
        assertEquals("app", tools.getJSONObject(11).getJSONObject("inputSchema").getJSONArray("required").getString(0));
        assertEquals("action", tools.getJSONObject(15).getJSONObject("inputSchema").getJSONArray("required").getString(0));
    }

    @Test public void navigateToOpensMapsNavigationLinkToTheDestination() throws Exception {
        JSONObject result = callTool("navigate_to", new JSONObject().put("destination", MOTEL));
        assertFalse(result.getBoolean("isError"));
        Intent link = shadowOf(context).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, link.getAction());
        assertEquals(EmbeddedMaps.PACKAGE, link.getPackage());
        assertEquals("google.navigation", link.getData().getScheme());
        assertEquals("q=" + MOTEL, Uri.decode(link.getData().getEncodedSchemeSpecificPart()));
        assertTrue("The Maps bridge module answers Maps' Exit navigation? for it", link.getBooleanExtra(AgentTools.REPLACE_TRIP, false));
    }

    /** The Maps bridge module takes Wend's ask, as it does inside Maps; it answers later through Wend's provider. */
    private void mapsBridgeListens() {
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context received, Intent intent) { setResultCode(1); }
        }, new android.content.IntentFilter(AgentTools.ADD_STOP));
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context received, Intent intent) { setResultCode(1); }
        }, new android.content.IntentFilter(AgentTools.REMOVE_STOP));
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context received, Intent intent) { setResultCode(1); }
        }, new android.content.IntentFilter(AgentTools.END_NAVIGATION));
        for (String action : new String[]{AgentTools.ROUTES, AgentTools.SWITCH_ROUTE}) {
            context.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context received, Intent intent) { setResultCode(1); }
            }, new android.content.IntentFilter(action));
        }
    }

    /** add_stop as Wend's MCP server calls it, off the main thread, while the main thread runs. */
    private Future<JSONObject> addStopOffMain(String place) {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        return worker.submit(() -> callTool("add_stop", new JSONObject().put("place", place)));
    }

    /** Runs the main thread until the module's ask goes out, as it does for the server's thread. */
    private Intent askedOfMaps() throws InterruptedException {
        return askedOfMaps(AgentTools.ADD_STOP);
    }

    private Intent askedOfMaps(String action) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            for (Intent sent : shadowOf(context).getBroadcastIntents()) {
                if (action.equals(sent.getAction())) return sent;
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
        extras.putBoolean("done", added);
        if (problem != null) extras.putString("problem", problem);
        org.robolectric.Robolectric.buildContentProvider(MapsTrips.class).create("dev.augie.wend.mapstrips").get()
                .call("stop", null, extras);
        ShadowBinder.reset();
    }

    /** Maps' own Add stop, through the module: Maps keeps guiding and reroutes through the place. */
    @Test public void addStopAsksMapsAndSaysWhatMapsAnswered() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        mapsBridgeListens();
        Future<JSONObject> call = addStopOffMain("Chevron, 1099 S State St, Ukiah, CA");
        Intent ask = askedOfMaps();
        assertEquals(EmbeddedMaps.PACKAGE, ask.getPackage());
        assertEquals("Chevron, 1099 S State St, Ukiah, CA", ask.getStringExtra(AgentTools.PLACE));
        mapsSays(ask.getIntExtra(AgentTools.REQUEST, -1), true, null);
        MapsTrips.reported(MOTEL, new String[]{"Chevron, 1099 S State St, Ukiah, CA 95482"});
        JSONObject result = answered(call);
        assertFalse(result.getBoolean("isError"));
        assertTrue(text(result).startsWith("Maps added \"Chevron, 1099 S State St, Ukiah, CA\""));
        assertTrue("With the stops Maps reported", text(result).endsWith("Stops ahead now, from 1: [\"Chevron, 1099 S State St, Ukiah, CA 95482\"]."));
    }

    /** A business name Maps cannot match from text alone; the agent knows where it is. */
    @Test public void addStopAtCoordinatesAsksMapsForThatPointUnderTheName() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        mapsBridgeListens();
        Future<JSONObject> call = Executors.newSingleThreadExecutor().submit(() -> callTool("add_stop",
                new JSONObject().put("place", "Wawa").put("latitude", 30.6012).put("longitude", -87.1405)));
        Intent ask = askedOfMaps();
        assertEquals("Wawa", ask.getStringExtra(AgentTools.PLACE));
        assertEquals(30.6012, ask.getDoubleExtra(AgentTools.LATITUDE, Double.NaN), 0);
        assertEquals(-87.1405, ask.getDoubleExtra(AgentTools.LONGITUDE, Double.NaN), 0);
        mapsSays(ask.getIntExtra(AgentTools.REQUEST, -1), true, null);
        // Maps keeps no name for a stop at a point; the module reports its coordinates.
        MapsTrips.reported(MOTEL, new String[]{"30.601200,-87.140500"});
        JSONObject result = answered(call);
        assertFalse(result.getBoolean("isError"));
        assertTrue(text(result), text(result).endsWith("Stops ahead now, from 1: [\"Wawa\"]."));
        JSONObject status = new JSONObject(text(answered(tripStatusOffMain())));
        assertEquals("Wawa", status.getJSONArray("stops_ahead").getString(0));
    }

    @Test public void addStopTakesLatitudeLongitudeTextAsThatPoint() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        mapsBridgeListens();
        addStopOffMain(" 30.6012, -87.1405 ");
        Intent ask = askedOfMaps();
        assertEquals(30.6012, ask.getDoubleExtra(AgentTools.LATITUDE, Double.NaN), 0);
        assertEquals(-87.1405, ask.getDoubleExtra(AgentTools.LONGITUDE, Double.NaN), 0);
    }

    @Test public void addStopRefusesHalfOrImpossibleCoordinatesWithoutAskingMaps() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        mapsBridgeListens();
        JSONObject half = callTool("add_stop", new JSONObject().put("place", "Wawa").put("latitude", 30.6));
        assertTrue(half.getBoolean("isError"));
        assertEquals("add_stop needs both latitude and longitude, or neither; nothing changed.", text(half));
        JSONObject impossible = callTool("add_stop", new JSONObject().put("place", "Wawa").put("latitude", 91).put("longitude", 0));
        assertTrue(impossible.getBoolean("isError"));
        assertTrue(text(impossible).startsWith("latitude 91.0 is not between -90 and 90"));
        for (Intent sent : shadowOf(context).getBroadcastIntents()) assertFalse(AgentTools.ADD_STOP.equals(sent.getAction()));
    }

    @Test public void addStopByTextMapsRefusedSaysToPassCoordinates() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21)); // guidance long under way
        mapsBridgeListens();
        Future<JSONObject> call = addStopOffMain("Wawa");
        mapsSays(askedOfMaps().getIntExtra(AgentTools.REQUEST, -1), false, "Maps did not add \"Wawa\"");
        JSONObject result = answered(call);
        assertTrue(result.getBoolean("isError"));
        assertTrue(text(result).endsWith("pass the place's latitude and longitude with its name to add it there."));
    }

    @Test public void addStopSaysWhyMapsAddedNothing() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21)); // guidance long under way
        mapsBridgeListens();
        Future<JSONObject> call = addStopOffMain("Nowhere at all");
        mapsSays(askedOfMaps().getIntExtra(AgentTools.REQUEST, -1), false, "Maps could not add \"Nowhere at all\"");
        JSONObject result = answered(call);
        assertTrue(result.getBoolean("isError"));
        assertEquals("Maps could not add \"Nowhere at all\". The route did not change.", text(result));
    }

    /** Maps refuses a stop while it sets up a route it just began guiding along; it is asked again once. */
    @Test public void addStopAsksAgainWhenGuidanceHasJustBegun() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        mapsBridgeListens();
        Future<JSONObject> call = addStopOffMain("Chevron, 1099 S State St, Ukiah, CA");
        mapsSays(askedOfMaps().getIntExtra(AgentTools.REQUEST, -1), false, "Maps did not add \"Chevron\"");
        Intent again = null;
        for (int i = 0; i < 600 && again == null; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            int asks = 0;
            for (Intent sent : shadowOf(context).getBroadcastIntents()) {
                if (AgentTools.ADD_STOP.equals(sent.getAction()) && ++asks == 2) again = sent;
            }
            Thread.sleep(10);
        }
        if (again == null) throw new AssertionError("Wend did not ask Maps again");
        mapsSays(again.getIntExtra(AgentTools.REQUEST, -1), true, null);
        MapsTrips.reported(MOTEL, new String[]{"Chevron, 1099 S State St, Ukiah, CA 95482"});
        assertFalse(answered(call).getBoolean("isError"));
    }

    /** No module in Maps answers the ordered broadcast, which comes back with its first result. */
    @Test public void addStopWithoutTheMapsBridgeSaysSoAtOnce() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        JSONObject result = answered(addStopOffMain("Chevron, Ukiah, CA"));
        assertTrue(result.getBoolean("isError"));
        assertTrue(text(result).startsWith("The Maps bridge module did not answer"));
    }

    private Future<JSONObject> removeStopOffMain(JSONObject arguments) {
        return Executors.newSingleThreadExecutor().submit(() -> callTool("remove_stop", arguments));
    }

    private void guidingThroughTwoStops() {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "13 min · 11 mi · 4:41 PM PST ETA"));
        MapsTrips.reported(MOTEL, new String[]{"Safeway, 653 S State St, Ukiah, CA 95482", "Chevron, 1099 S State St, Ukiah, CA 95482"});
    }

    /** Maps' own remove, through the module: position counts the stops ahead from 1, as trip_status lists them. */
    @Test public void removeStopAsksMapsToRemoveThatStopAhead() throws Exception {
        guidingThroughTwoStops();
        mapsBridgeListens();
        Future<JSONObject> call = removeStopOffMain(new JSONObject().put("position", 2));
        Intent ask = askedOfMaps(AgentTools.REMOVE_STOP);
        assertEquals(EmbeddedMaps.PACKAGE, ask.getPackage());
        assertEquals(2, ask.getIntExtra(AgentTools.POSITION, -1));
        mapsSays(ask.getIntExtra(AgentTools.REQUEST, -1), true, null);
        MapsTrips.reported(MOTEL, new String[]{"Safeway, 653 S State St, Ukiah, CA 95482"});
        JSONObject result = answered(call);
        assertFalse(result.getBoolean("isError"));
        assertTrue(text(result).startsWith("Maps removed \"Chevron, 1099 S State St, Ukiah, CA 95482\""));
    }

    @Test public void removeStopWithoutAPositionRemovesTheNextStop() throws Exception {
        guidingThroughTwoStops();
        mapsBridgeListens();
        Future<JSONObject> call = removeStopOffMain(new JSONObject());
        Intent ask = askedOfMaps(AgentTools.REMOVE_STOP);
        assertEquals(1, ask.getIntExtra(AgentTools.POSITION, -1));
        mapsSays(ask.getIntExtra(AgentTools.REQUEST, -1), true, null);
        MapsTrips.reported(MOTEL, new String[]{"Chevron, 1099 S State St, Ukiah, CA 95482"});
        assertTrue(text(answered(call)).startsWith("Maps removed \"Safeway"));
    }

    @Test public void removeNextStopRemovesTheFirstStopAhead() throws Exception {
        guidingThroughTwoStops();
        mapsBridgeListens();
        Future<JSONObject> call = Executors.newSingleThreadExecutor().submit(() -> callTool("remove_next_stop", new JSONObject()));
        Intent ask = askedOfMaps(AgentTools.REMOVE_STOP);
        assertEquals(1, ask.getIntExtra(AgentTools.POSITION, -1));
        mapsSays(ask.getIntExtra(AgentTools.REQUEST, -1), true, null);
        MapsTrips.reported(MOTEL, new String[]{"Chevron, 1099 S State St, Ukiah, CA 95482"});
        assertTrue(text(answered(call)).startsWith("Maps removed \"Safeway"));
    }

    /** Past the last stop Maps would remove its destination, so Wend asks nothing. */
    @Test public void removeStopPastTheStopsAheadAsksNothing() throws Exception {
        guidingThroughTwoStops();
        JSONObject result = callTool("remove_stop", new JSONObject().put("position", 3));
        assertTrue(result.getBoolean("isError"));
        assertTrue(text(result).contains("2 stops ahead"));
        assertTrue(shadowOf(context).getBroadcastIntents().isEmpty());
    }

    @Test public void removeStopWithoutStopsAheadAsksNothing() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        MapsTrips.reported(MOTEL);
        JSONObject result = callTool("remove_stop", new JSONObject());
        assertTrue(result.getBoolean("isError"));
        assertTrue(shadowOf(context).getBroadcastIntents().isEmpty());
    }

    /** Maps' own end-navigation, through the module; it answers once Maps' guidance notification is gone. */
    @Test public void endNavigationAsksMapsToEndItsGuidance() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        mapsBridgeListens();
        Future<JSONObject> call = Executors.newSingleThreadExecutor().submit(() -> callTool("end_navigation", new JSONObject()));
        Intent ask = askedOfMaps(AgentTools.END_NAVIGATION);
        assertEquals(EmbeddedMaps.PACKAGE, ask.getPackage());
        mapsSays(ask.getIntExtra(AgentTools.REQUEST, -1), true, null);
        MapsGuidance.reportedByMaps(GuidanceReports.off());
        JSONObject result = answered(call);
        assertFalse(result.getBoolean("isError"));
        assertTrue(text(result).startsWith("Maps ended its guidance"));
    }

    /** The routes Maps has, as the module reads them from Maps' guidance, the guided one first. */
    @Test public void listRoutesGivesTheRoutesMapsHas() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "2 hr 11 min · 124 mi · 9:47 PM PST ETA"));
        mapsBridgeListens();
        Future<JSONObject> call = Executors.newSingleThreadExecutor().submit(() -> callTool("list_routes", new JSONObject()));
        Intent ask = askedOfMaps(AgentTools.ROUTES);
        shadowOf(context.getPackageManager()).setPackagesForUid(10111, EmbeddedMaps.PACKAGE);
        ShadowBinder.setCallingUid(10111);
        Bundle extras = new Bundle();
        extras.putInt(AgentTools.REQUEST, ask.getIntExtra(AgentTools.REQUEST, -1));
        extras.putBoolean("done", true);
        extras.putString(AgentTools.ROUTES, "[{\"id\":\"a\",\"guided\":true,\"minutes\":131,\"via\":\"US-101 S\"},"
                + "{\"id\":\"b\",\"guided\":false,\"minutes\":134,\"via\":\"US-101 S and I-580 E\"}]");
        org.robolectric.Robolectric.buildContentProvider(MapsTrips.class).create("dev.augie.wend.mapstrips").get().call("stop", null, extras);
        ShadowBinder.reset();
        JSONArray routes = new JSONObject(text(answered(call))).getJSONArray("routes");
        assertEquals(2, routes.length());
        assertEquals("US-101 S and I-580 E", routes.getJSONObject(1).getString("via"));
    }

    @Test public void switchRouteAsksMapsForThatRoute() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "2 hr 11 min · 124 mi · 9:47 PM PST ETA"));
        mapsBridgeListens();
        Future<JSONObject> call = Executors.newSingleThreadExecutor().submit(() -> callTool("switch_route", new JSONObject().put("route", "b")));
        Intent ask = askedOfMaps(AgentTools.SWITCH_ROUTE);
        assertEquals("b", ask.getStringExtra(AgentTools.ROUTE));
        mapsSays(ask.getIntExtra(AgentTools.REQUEST, -1), true, null);
        assertFalse(answered(call).getBoolean("isError"));
    }

    @Test public void routesWithoutGuidanceAskNothing() throws Exception {
        assertTrue(callTool("list_routes", new JSONObject()).getBoolean("isError"));
        assertTrue(callTool("switch_route", new JSONObject().put("route", "b")).getBoolean("isError"));
        assertTrue(shadowOf(context).getBroadcastIntents().isEmpty());
    }

    @Test public void endNavigationWithoutGuidanceAsksNothing() throws Exception {
        JSONObject result = callTool("end_navigation", new JSONObject());
        assertTrue(result.getBoolean("isError"));
        assertTrue(shadowOf(context).getBroadcastIntents().isEmpty());
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

    /** trip_status as Wend's MCP server calls it, off the main thread. */
    private Future<JSONObject> tripStatusOffMain() {
        return Executors.newSingleThreadExecutor().submit(() -> callTool("trip_status", new JSONObject()));
    }

    /** Maps' own guidance knows the distance to the turn, in its words and in meters, and whether the car is on the route. */
    @Test public void tripStatusTakesTheTurnFromMapsGuidance() throws Exception {
        Bundle report = guidance("1.2 mi", "toward T R Robinson Dr", "13 min · 6.8 mi · 1:33 PM ETA");
        report.putInt("meters_to_step", 1931);
        report.putInt("seconds_to_step", 95);
        MapsGuidance.reportedByMaps(report);
        JSONObject status = new JSONObject(text(answered(tripStatusOffMain())));
        JSONObject turn = status.getJSONObject("next_turn");
        assertEquals("1.2 mi", turn.getString("distance"));
        assertEquals(1931, turn.getInt("meters"));
        assertEquals(95, turn.getInt("seconds"));
        assertEquals("toward T R Robinson Dr", turn.getString("road"));
        assertTrue(status.getBoolean("on_route"));
    }

    /** Maps does not know the distance to the turn, as off its route: it words none, and gives -1 meters. */
    @Test public void tripStatusLeavesOutADistanceMapsDoesNotKnow() throws Exception {
        Bundle report = guidance("", "toward T R Robinson Dr", "13 min · 6.8 mi · 1:33 PM ETA");
        report.remove("distance");
        report.putInt("meters_to_step", -1);
        report.putInt("seconds_to_step", -1);
        report.putBoolean("on_route", false);
        MapsGuidance.reportedByMaps(report);
        JSONObject status = new JSONObject(text(answered(tripStatusOffMain())));
        JSONObject turn = status.getJSONObject("next_turn");
        assertEquals("", turn.getString("distance"));
        assertFalse(turn.has("meters"));
        assertFalse(status.getBoolean("on_route"));
    }

    @Test public void tripStatusSaysWhenMapsGuidesNowhere() throws Exception {
        JSONObject status = new JSONObject(text(answered(tripStatusOffMain())));
        assertFalse(status.getBoolean("guiding"));
        assertEquals(1, status.length());
    }

    /** What Maps' guidance notification and the Maps bridge module report, as the agent reads it. */
    @Test public void tripStatusIsTheGuidanceMapsReportsWithItsDestination() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        MapsTrips.reported(MOTEL, new String[]{"Chevron, 1000 N State St"});
        JSONObject status = new JSONObject(text(answered(tripStatusOffMain())));
        assertTrue(status.getBoolean("guiding"));
        assertEquals(MOTEL, status.getString("destination"));
        assertEquals("Chevron, 1000 N State St", status.getJSONArray("stops_ahead").getString(0));
        JSONObject turn = status.getJSONObject("next_turn");
        assertEquals("0.2 mi", turn.getString("distance"));
        assertEquals("CA-20 W", turn.getString("road"));
        assertFalse(turn.getBoolean("arrived"));
        // Maps' 540 s left, from now, as the clock words it.
        String arrival = status.getString("arrival");
        assertTrue(arrival, arrival.matches("\\d{1,2}:\\d{2}( [AP]M)?"));
        assertEquals("9 min · 8.4 mi · " + arrival, status.getString("remaining"));
    }

    @Test public void tripStatusWithoutAReportedDestinationLeavesItOut() throws Exception {
        MapsGuidance.reportedByMaps(guidance("0.2 mi", "CA-20 W", "9 min · 8.4 mi · 4:37 PM PST ETA"));
        JSONObject status = new JSONObject(text(answered(tripStatusOffMain())));
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
