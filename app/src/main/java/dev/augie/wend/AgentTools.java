package dev.augie.wend;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What an agent may do in the car through Wend's MCP server ({@link AgentServer}): start Maps'
 * guidance somewhere, add or remove a stop of the trip Maps guides, list and switch its routes, end that
 * guidance, and read the trip; and, through {@link CarControls}, use the head unit itself. Each tool has a name,
 * a description and an input schema for the agent, and a call. Called on any thread; what reads or
 * changes Maps' guidance as Wend keeps it runs on the main thread, and adding a stop waits off it.
 */
final class AgentTools {
    static final String TAG = "WendAgent";

    /** A tool's answer: text for the agent, and whether it says what went wrong. */
    static final class Result {
        final String text;
        final boolean error;
        /** A picture for the agent after the text, as {@link #imageType}, or null. */
        final byte[] image;
        final String imageType;

        private Result(String text, boolean error, byte[] image, String imageType) {
            this.text = text;
            this.error = error;
            this.image = image;
            this.imageType = imageType;
        }

        static Result ok(String text) { return new Result(text, false, null, null); }
        static Result failed(String text) { return new Result(text, true, null, null); }
        static Result image(String text, byte[] image, String type) { return new Result(text, false, image, type); }
    }

    /** No such tool; the agent asked for a name {@link #list()} does not give. */
    static final class UnknownTool extends Exception {
        UnknownTool(String name) { super("Wend has no tool named " + name + "; tools/list names its tools"); }
    }

    /** How long Wend's main thread may take to answer a tool. */
    private static final long MAIN_WAIT_MS = 5_000;
    /** How long Maps may take to say whether it changed a stop; it reroutes over the network. */
    static final long STOP_WAIT_MS = 25_000;
    /** The Maps bridge module's broadcasts that ask Maps to add or remove a stop, and their extras. */
    static final String ADD_STOP = "dev.augie.wend.mapsbridge.ADD_STOP", REMOVE_STOP = "dev.augie.wend.mapsbridge.REMOVE_STOP",
            END_NAVIGATION = "dev.augie.wend.mapsbridge.END_NAVIGATION", ROUTES = "dev.augie.wend.mapsbridge.ROUTES",
            SWITCH_ROUTE = "dev.augie.wend.mapsbridge.SWITCH_ROUTE", ROUTE = "route",
            PLACE = "place", LATITUDE = "latitude", LONGITUDE = "longitude", POSITION = "position", REQUEST = "request";
    /** Asks the Maps bridge module to replace the trip Maps guides with a navigation link's, without asking the driver. */
    static final String REPLACE_TRIP = "dev.augie.wend.REPLACE_TRIP";
    private static final AtomicInteger stopRequests = new AtomicInteger();
    /** Maps' answers to come, by request. */
    private static final Map<Integer, CompletableFuture<Bundle>> stopAnswers = new ConcurrentHashMap<>();
    private final Context context;
    private final CarControls car;
    private final Handler main = new Handler(Looper.getMainLooper());

    AgentTools(Context context) { this(context, new CarControls(context)); }

    AgentTools(Context context, CarControls car) {
        this.context = context;
        this.car = car;
    }

    /** @return the tools, as MCP's tools/list gives them */
    JSONArray list() {
        try {
            JSONArray tools = new JSONArray()
                    .put(tool("navigate_to", "Starts Google Maps' turn-by-turn guidance in the car to a destination,"
                            + " replacing any route Maps guides now. Maps searches the destination as given, so pass"
                            + " a full address or \"latitude,longitude\" when you know it, not only a business name.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject().put("destination", new JSONObject()
                                            .put("type", "string")
                                            .put("description", "An address, \"latitude,longitude\", or a place name")))
                                    .put("required", new JSONArray().put("destination"))))
                    .put(tool("add_stop", "Adds a stop to the trip Google Maps guides in the car now, through Maps'"
                            + " own Add stop: Maps keeps guiding and keeps its destination, and reroutes through the stop."
                            + " Use it for a detour on the way, like a gas station; use navigate_to to go somewhere else"
                            + " instead. Given only place, Maps searches its text and adds only what it matches to one"
                            + " address; a business name alone often fails. With latitude and longitude, Maps adds the"
                            + " stop at that point, and stops_ahead lists it as place: pass both when you"
                            + " know where the place is.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject()
                                            .put("place", new JSONObject().put("type", "string")
                                                    .put("description", "The stop's name, like \"Wawa\", with latitude and longitude;"
                                                            + " otherwise an address, or \"latitude,longitude\""))
                                            .put(LATITUDE, new JSONObject().put("type", "number").put("minimum", -90).put("maximum", 90)
                                                    .put("description", "The place's latitude, with longitude"))
                                            .put(LONGITUDE, new JSONObject().put("type", "number").put("minimum", -180).put("maximum", 180)
                                                    .put("description", "The place's longitude, with latitude")))
                                    .put("required", new JSONArray().put("place"))))
                    .put(tool("remove_stop", "Removes a stop from the trip Google Maps guides in the car now, through"
                            + " Maps' own navigation: Maps keeps guiding and keeps its destination, and reroutes. position"
                            + " counts the stops ahead as trip_status lists them in stops_ahead, from 1, the next.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject().put("position", new JSONObject()
                                            .put("type", "integer").put("minimum", 1)
                                            .put("description", "Which stop ahead; 1, the next, if left out")))))
                    .put(tool("remove_next_stop", "Removes the next stop ahead from the trip Google Maps guides in"
                            + " the car now, through Maps' own navigation: Maps keeps guiding and keeps its destination.",
                            new JSONObject().put("type", "object").put("properties", new JSONObject())))
                    .put(tool("list_routes", "Lists the routes Google Maps has for the trip it guides in the car now: the one it"
                            + " guides along first (guided true), then the alternatives it offers, each with its id, its travel"
                            + " time in minutes as Maps estimates it, and the roads it goes by (via).",
                            new JSONObject().put("type", "object").put("properties", new JSONObject())))
                    .put(tool("switch_route", "Switches the trip Google Maps guides in the car to another of its routes, as"
                            + " tapping that route in Maps does; Maps keeps the destination and stops. Take the id from"
                            + " list_routes.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject().put("route", new JSONObject()
                                            .put("type", "string").put("description", "The route's id, from list_routes")))
                                    .put("required", new JSONArray().put("route"))))
                    .put(tool("end_navigation", "Ends the guidance Google Maps runs in the car, as Maps' own end-navigation"
                            + " button does; Maps then shows the trip's route with Start. Its stops are not kept.",
                            new JSONObject().put("type", "object").put("properties", new JSONObject())))
                    .put(tool("trip_status", "Reads the trip Google Maps guides in the car now, as Maps itself tracks it:"
                            + " the next turn (its distance, also in meters and seconds), the time and distance left, the"
                            + " arrival time, whether the car is on the route, and the destination and stops ahead when"
                            + " Maps reported them. guiding is false when Maps guides nowhere. The turn's distance is"
                            + " empty, and its meters left out, while Maps itself does not know it, as off the route.",
                            new JSONObject().put("type", "object").put("properties", new JSONObject())))
                    .put(tool("go_home", "Brings Wend's home screen to the front of the car's screen, as the Home button"
                            + " does: Maps' map and the media panel, with any app in front put behind.", noArguments()))
                    .put(tool("go_back", "Presses Back for the app in front of the car's screen, as the Back button does.", noArguments()))
                    .put(tool("list_apps", "Lists the apps on the car's head unit, each with its name and package.", noArguments()))
                    .put(tool("open_app", "Opens an app on the car's screen, as tapping it in Wend's app drawer does; Maps and"
                            + " YouTube come back where they were.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject().put("app", new JSONObject().put("type", "string")
                                            .put("description", "The app's name, part of it, or its package, from list_apps")))
                                    .put("required", new JSONArray().put("app"))))
                    .put(tool("screen_status", "Names the app in front of the car's screen; home is true when it is Wend's"
                            + " home screen.", noArguments()))
                    .put(tool("screenshot", "Takes a picture of the car's whole screen, to see what it shows.", noArguments()))
                    .put(tool("media_status", "Lists the media sessions in the car (music, podcasts, video): each one's app,"
                            + " state (playing, paused, ...), title, artist, album, and position and duration in seconds."
                            + " The one playing comes first; media_control acts on it unless told another app.", noArguments()))
                    .put(tool("media_control", "Plays, pauses or skips what plays in the car, through its app's media"
                            + " session, as the steering wheel buttons do.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject()
                                            .put("action", new JSONObject().put("type", "string")
                                                    .put("enum", new JSONArray().put("play").put("pause").put("toggle").put("next").put("previous")))
                                            .put("app", new JSONObject().put("type", "string")
                                                    .put("description", "The app to control, by name or package; the one playing, or"
                                                            + " else the latest, if left out")))
                                    .put("required", new JSONArray().put("action"))))
                    .put(tool("volume", "Reads the car's media volume, or sets it: to percent, or by change. Android shows its"
                            + " volume bar as it changes. Answers the level as a percent, Android's own level and maximum,"
                            + " and whether it is muted.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject()
                                            .put("percent", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100)
                                                    .put("description", "The level to set, 0 to 100"))
                                            .put("change", new JSONObject().put("type", "string")
                                                    .put("enum", new JSONArray().put("up").put("down").put("mute").put("unmute"))
                                                    .put("description", "One step up or down, or mute or unmute")))));
            return tools;
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** @throws UnknownTool if {@code name} is none of {@link #list()} */
    Result call(String name, JSONObject arguments) throws UnknownTool {
        Log.i(TAG, "Agent called " + name + " " + arguments);
        try {
            switch (name) {
                case "navigate_to": return onMain(() -> navigateTo(arguments.optString("destination", "").trim()));
                case "add_stop": return addStop(arguments);
                case "remove_stop": return removeStop(arguments.optInt("position", 1));
                case "end_navigation": return endNavigation();
                case "remove_next_stop": return removeStop(1);
                case "list_routes": return listRoutes();
                case "switch_route": return switchRoute(arguments.optString("route", "").trim());
                case "trip_status": return onMain(this::tripStatus);
                case "go_home": return car.home();
                case "go_back": return car.back();
                case "list_apps": return car.apps(onMain(this::apps));
                case "open_app": return car.openApp(arguments.optString("app", "").trim(), onMain(this::apps));
                case "screen_status": return car.screenStatus();
                case "screenshot": return car.screenshot();
                case "media_status": return car.mediaStatus();
                case "media_control": return car.mediaControl(arguments.optString("action", ""), arguments.optString("app", "").trim());
                case "volume":
                    return car.volume(arguments.has("percent") ? arguments.optInt("percent") : null, arguments.optString("change", ""));
                default: throw new UnknownTool(name);
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        } catch (TimeoutException busy) {
            return Result.failed("Wend did not answer within " + MAIN_WAIT_MS + " ms, so " + name + " may or may not have"
                    + " run; call trip_status before trying again.");
        }
    }

    /** Runs {@code work} on the main thread and waits for it; inline when called there, as in tests. */
    private <T> T onMain(Callable<T> work) throws TimeoutException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return work.call(); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        FutureTask<T> task = new FutureTask<>(work);
        main.post(task);
        try {
            return task.get(MAIN_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException failed) {
            throw new IllegalStateException(failed);
        }
    }

    /**
     * Asks the Maps bridge module to add the stop {@code arguments} name through Maps' own Add stop
     * ({@link #changeStops}): at their latitude and longitude, or at "latitude,longitude" text, under
     * the place's name; otherwise where Maps finds the place's text.
     */
    private Result addStop(JSONObject arguments) throws TimeoutException {
        String place = arguments.optString(PLACE, "").trim();
        if (place.isEmpty()) return Result.failed("add_stop needs a place: a name with latitude and longitude, or an address");
        boolean hasLatitude = arguments.has(LATITUDE), hasLongitude = arguments.has(LONGITUDE);
        if (hasLatitude != hasLongitude) return Result.failed("add_stop needs both latitude and longitude, or neither; nothing changed.");
        double latitude = Double.NaN, longitude = Double.NaN;
        if (hasLatitude) {
            latitude = arguments.optDouble(LATITUDE);
            longitude = arguments.optDouble(LONGITUDE);
        } else {
            java.util.regex.Matcher point = POINT.matcher(place);
            if (point.matches()) {
                latitude = Double.parseDouble(point.group(1));
                longitude = Double.parseDouble(point.group(2));
            }
        }
        if (hasLatitude || !Double.isNaN(latitude)) {
            if (!(latitude >= -90 && latitude <= 90)) {
                return Result.failed("latitude " + latitude + " is not between -90 and 90; nothing changed.");
            }
            if (!(longitude >= -180 && longitude <= 180)) {
                return Result.failed("longitude " + longitude + " is not between -180 and 180; nothing changed.");
            }
        }
        if (!onMain(() -> MapsGuidance.current() != null)) {
            return Result.failed("Maps guides nowhere now, so there is no trip to add a stop to; nothing changed."
                    + " Use navigate_to to go to \"" + place + "\" instead.");
        }
        Intent ask = new Intent(ADD_STOP).putExtra(PLACE, place);
        if (!Double.isNaN(latitude)) {
            ask.putExtra(LATITUDE, latitude).putExtra(LONGITUDE, longitude);
            if (hasLatitude) pointNames.put(String.format(java.util.Locale.US, "%.6f,%.6f", latitude, longitude), place);
        }
        Result added = changeStops(ask, "add \"" + place + "\"",
                "Maps added \"" + place + "\" as a stop on the trip it guides and keeps guiding.", 1);
        // Maps refuses new waypoints while it still sets up a route it just began guiding along.
        Long began = onMain(MapsGuidance::began);
        if (added.error && began != null && android.os.SystemClock.uptimeMillis() - began < JUST_BEGAN_MS) {
            try { Thread.sleep(RETRY_AFTER_MS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return added; }
            added = changeStops(new Intent(ask), "add \"" + place + "\"",
                    "Maps added \"" + place + "\" as a stop on the trip it guides and keeps guiding.", 1);
        }
        // Maps answers only no for text it cannot match to one address, as for a business name alone.
        if (added.error && Double.isNaN(latitude) && added.text.startsWith("Maps did not add")) {
            return Result.failed(added.text + " Maps adds a stop from text only when it matches the text to one address;"
                    + " pass the place's latitude and longitude with its name to add it there.");
        }
        return added;
    }

    /**
     * Asks the Maps bridge module to remove the stop at {@code position} ahead ({@link #changeStops}).
     * Maps would remove its destination at the position after the last stop, so only stops Maps
     * reported ahead ({@link MapsTrips}) may be removed.
     */
    private Result removeStop(int position) throws TimeoutException {
        if (!onMain(() -> MapsGuidance.current() != null)) {
            return Result.failed("Maps guides nowhere now, so there is no stop to remove; nothing changed.");
        }
        String[] stops = stopsAhead();
        if (stops.length == 0) return Result.failed("The trip Maps guides has no stops ahead to remove; nothing changed.");
        if (position < 1 || position > stops.length) {
            return Result.failed("position " + position + " is not a stop ahead: the trip has " + stops.length
                    + (stops.length == 1 ? " stop" : " stops") + " ahead, from 1; nothing changed.");
        }
        String stop = stops[position - 1];
        return changeStops(new Intent(REMOVE_STOP).putExtra(POSITION, position), "remove \"" + stop + "\"",
                "Maps removed \"" + stop + "\" from the trip it guides and keeps guiding.", -1);
    }

    /**
     * Sends the Maps bridge module {@code ask} and waits for Maps' answer ({@link #stopReported}).
     * Off the main thread, which carries the answer.
     *
     * Once Maps did it, it waits for Maps' report of the new stops ({@link MapsTrips}), up to
     * {@link #REPORT_WAIT_MS}, and gives them: the next ask then counts the stops Maps has, and
     * the agent needs no trip_status call to see them.
     *
     * @param change what is asked, for the agent: "add ..." or "remove ..."
     * @param done what the agent hears once Maps did it
     * @param stopsChange how many stops ahead Maps has once it did it, more or fewer
     */
    private Result changeStops(Intent ask, String change, String done, int stopsChange) throws TimeoutException {
        int before = stopsAhead().length;
        Bundle said = askBridge(ask);
        if (said == null) {
            return Result.failed("Maps did not say within " + STOP_WAIT_MS / 1000 + " s whether it did " + change
                    + "; call trip_status to see the route.");
        }
        if (said.getBoolean("done")) return Result.ok(done + " " + reportedStops(before + stopsChange));
        return Result.failed(said.getString("problem", "Maps did not " + change) + ". The route did not change.");
    }

    /** Reads Maps' routes for the trip it guides through the Maps bridge module. */
    private Result listRoutes() throws TimeoutException {
        if (!onMain(() -> MapsGuidance.current() != null)) return Result.failed("Maps guides nowhere now, so it has no routes.");
        Bundle said = askBridge(new Intent(ROUTES));
        if (said == null) return Result.failed("Maps did not list its routes within " + STOP_WAIT_MS / 1000 + " s.");
        if (!said.getBoolean("done")) return Result.failed(said.getString("problem", "Maps did not list its routes") + ".");
        return Result.ok("{\"routes\":" + said.getString(ROUTES, "[]") + "}");
    }

    /** Asks Maps, through the Maps bridge module, to guide along another of its routes. */
    private Result switchRoute(String route) throws TimeoutException {
        if (route.isEmpty()) return Result.failed("switch_route needs a route id from list_routes.");
        if (!onMain(() -> MapsGuidance.current() != null)) return Result.failed("Maps guides nowhere now; nothing changed.");
        Bundle said = askBridge(new Intent(SWITCH_ROUTE).putExtra(ROUTE, route));
        if (said == null) {
            return Result.failed("Maps did not say within " + STOP_WAIT_MS / 1000 + " s whether it switched; call list_routes to see.");
        }
        if (!said.getBoolean("done")) return Result.failed(said.getString("problem", "Maps did not switch") + ". The route did not change.");
        return Result.ok("Maps now guides along route " + route + ". Call trip_status for the new arrival time.");
    }

    /** Ends Maps' guidance through the Maps bridge module, as Maps' own end-navigation button does. */
    private Result endNavigation() throws TimeoutException {
        if (!onMain(() -> MapsGuidance.current() != null)) return Result.failed("Maps guides nowhere now; nothing changed.");
        Bundle said = askBridge(new Intent(END_NAVIGATION));
        if (said == null) {
            return Result.failed("Maps did not say within " + STOP_WAIT_MS / 1000 + " s whether it ended its guidance; call"
                    + " trip_status to see.");
        }
        if (!said.getBoolean("done")) return Result.failed(said.getString("problem", "Maps did not end its guidance") + ". Maps still guides.");
        long until = System.currentTimeMillis() + REPORT_WAIT_MS;
        while (onMain(() -> MapsGuidance.current() != null) && System.currentTimeMillis() < until) {
            try { Thread.sleep(200); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        return Result.ok("Maps ended its guidance and shows the trip's route with Start; navigate_to starts guiding again.");
    }

    /**
     * Sends the Maps bridge module {@code ask} and waits for Maps' answer ({@link #stopReported}).
     * Off the main thread, which carries the answer.
     *
     * @return Maps' answer: "done", or "problem"; null if none came within {@link #STOP_WAIT_MS}
     */
    private Bundle askBridge(Intent ask) {
        int request = stopRequests.incrementAndGet();
        CompletableFuture<Bundle> answer = new CompletableFuture<>();
        stopAnswers.put(request, answer);
        try {
            context.sendOrderedBroadcast(ask.setPackage(EmbeddedMaps.PACKAGE).putExtra(REQUEST, request), null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (getResultCode() == 1) return;
                    Bundle none = new Bundle();
                    none.putString("problem", "The Maps bridge module did not answer: it is not installed, not enabled for"
                            + " Maps in LSPosed, or older than this Wend");
                    answer.complete(none);
                }
            }, main, Activity.RESULT_CANCELED, null, null);
            return answer.get(STOP_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            return null;
        } catch (InterruptedException | ExecutionException failed) {
            throw new IllegalStateException(failed);
        } finally {
            stopAnswers.remove(request);
        }
    }

    /**
     * The names agents gave stops at a point, by the point as the Maps bridge module reports such a
     * stop ("%.6f,%.6f"): Maps keeps no name for a stop at a point.
     */
    private static final Map<String, String> pointNames = new ConcurrentHashMap<>();

    /** @return {@code stops}, each at a point an agent named shown by that name */
    private static String[] named(String[] stops) {
        String[] named = stops.clone();
        for (int i = 0; i < named.length; i++) {
            String name = pointNames.get(named[i]);
            if (name != null) named[i] = name;
        }
        return named;
    }

    /** "latitude,longitude" as an agent may write it for a place. */
    private static final java.util.regex.Pattern POINT = java.util.regex.Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)");

    /** Guidance this young may still refuse a stop, as Maps sets up its route; the stop is asked again once. */
    private static final long JUST_BEGAN_MS = 20_000, RETRY_AFTER_MS = 3_000;

    /** How long Maps' report of changed stops may take: it saves its directions, then the module reads them. */
    private static final long REPORT_WAIT_MS = 5_000;

    /** @return the stops ahead Maps last reported for the trip it guides; none if it reported none */
    private String[] stopsAhead() throws TimeoutException {
        return onMain(() -> {
            MapsTrips.Trip trip = MapsGuidance.current() == null ? null : MapsTrips.of(MapsGuidance.began());
            return trip == null ? new String[0] : named(trip.stops);
        });
    }

    /** @return the stops ahead, once Maps reported {@code expected} of them, or what it reported last */
    private String reportedStops(int expected) throws TimeoutException {
        long until = System.currentTimeMillis() + REPORT_WAIT_MS;
        String[] stops = stopsAhead();
        while (stops.length != expected && System.currentTimeMillis() < until) {
            try { Thread.sleep(200); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            stops = stopsAhead();
        }
        if (stops.length != expected) {
            return "Maps has not reported its new stops yet; call trip_status before changing them again.";
        }
        return stops.length == 0 ? "No stops ahead now." : "Stops ahead now, from 1: " + new JSONArray(java.util.Arrays.asList(stops)) + ".";
    }

    /** The Maps bridge module said whether Maps changed the stops as {@code extras}' request asked. Any thread. */
    static void stopReported(Bundle extras) {
        CompletableFuture<Bundle> answer = extras == null ? null : stopAnswers.get(extras.getInt(REQUEST));
        if (answer != null) answer.complete(extras);
    }

    /**
     * Opens Maps' navigation link, which guides at once. A Maps that guides already would first ask
     * the driver "Exit navigation?"; marked {@link #REPLACE_TRIP}, the Maps bridge module answers
     * yes for Wend, through Maps' own code for that answer (see {@code maps-bridge/README.md}).
     * Without the module, Maps still asks.
     */
    private Result navigateTo(String destination) {
        if (destination.isEmpty()) return Result.failed("navigate_to needs a destination: an address, coordinates or a place name");
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination)))
                .setPackage(EmbeddedMaps.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(REPLACE_TRIP, true);
        String failed = open(link, destination);
        if (failed != null) return Result.failed(failed);
        return Result.ok("Asked Google Maps to navigate to \"" + destination + "\", in place of any trip it guides. Maps"
                + " searches it and starts guiding; call trip_status in a few seconds to see where it is going.");
    }

    /** @return null once Android started the link, or what went wrong */
    private String open(Intent link, String destination) {
        try {
            context.startActivity(link);
            return null;
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w(TAG, "Could not open Maps' navigation to " + destination, error);
            return "Google Maps (" + EmbeddedMaps.PACKAGE + ") did not open its navigation link: " + error.getMessage()
                    + ". Nothing changed in the car; check that Maps is installed and enabled.";
        }
    }

    /** Reads the trip as Maps' own navigation state holds it, through the Maps bridge module ({@link MapsGuidance}). */
    private Result tripStatus() {
        NavigationCard turn = MapsGuidance.current();
        try {
            JSONObject status = new JSONObject().put("guiding", turn != null);
            if (turn == null) return Result.ok(status.toString());
            MapsTrips.Trip trip = MapsTrips.of(MapsGuidance.began());
            if (trip != null) {
                status.put("destination", trip.destination);
                JSONArray stops = new JSONArray();
                for (String stop : named(trip.stops)) stops.put(stop);
                status.put("stops_ahead", stops);
            }
            status.put("on_route", turn.onRoute);
            JSONObject next = new JSONObject().put("distance", turn.distance.toString());
            if (turn.metersToTurn >= 0) next.put("meters", turn.metersToTurn);
            if (turn.secondsToTurn >= 0) next.put("seconds", turn.secondsToTurn);
            status.put("next_turn", next.put("road", turn.road.toString()).put("arrived", turn.arrived));
            if (turn.eta.length() > 0) status.put("remaining", turn.eta.toString());
            if (!turn.arrival.isEmpty()) status.put("arrival", turn.arrival);
            return Result.ok(status.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** @return the apps Wend's drawer lists, or null until it has loaded them; main thread */
    private java.util.List<LauncherApp> apps() {
        AppCatalog catalog = AppCatalog.get(context);
        return catalog.status() == AppCatalog.Status.READY ? catalog.apps() : null;
    }

    /** An input schema without arguments. */
    private static JSONObject noArguments() throws JSONException { return new JSONObject().put("type", "object").put("properties", new JSONObject()); }

    private static JSONObject tool(String name, String description, JSONObject inputSchema) throws JSONException {
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", inputSchema);
    }
}
