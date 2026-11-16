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
 * guidance somewhere, add or remove a stop of the trip Maps guides, and read that trip. Each tool has a name,
 * a description and an input schema for the agent, and a call. Called on any thread; what reads or
 * changes Maps' guidance as Wend keeps it runs on the main thread, and adding a stop waits off it.
 */
final class AgentTools {
    static final String TAG = "WendAgent";

    /** A tool's answer: text for the agent, and whether it says what went wrong. */
    static final class Result {
        final String text;
        final boolean error;

        private Result(String text, boolean error) {
            this.text = text;
            this.error = error;
        }

        static Result ok(String text) { return new Result(text, false); }
        static Result failed(String text) { return new Result(text, true); }
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
            PLACE = "place", POSITION = "position", REQUEST = "request";
    /** Asks the Maps bridge module to replace the trip Maps guides with a navigation link's, without asking the driver. */
    static final String REPLACE_TRIP = "dev.augie.wend.REPLACE_TRIP";
    private static final AtomicInteger stopRequests = new AtomicInteger();
    /** Maps' answers to come, by request. */
    private static final Map<Integer, CompletableFuture<Bundle>> stopAnswers = new ConcurrentHashMap<>();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    AgentTools(Context context) { this.context = context; }

    /** @return the tools, as MCP's tools/list gives them */
    JSONArray list() {
        try {
            return new JSONArray()
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
                            + " instead. Maps finds the place from the text, so pass a full address when you know it.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject().put("place", new JSONObject()
                                            .put("type", "string")
                                            .put("description", "An address, or a place name with its town")))
                                    .put("required", new JSONArray().put("place"))))
                    .put(tool("remove_stop", "Removes a stop from the trip Google Maps guides in the car now, through"
                            + " Maps' own navigation: Maps keeps guiding and keeps its destination, and reroutes. position"
                            + " counts the stops ahead as trip_status lists them in stops_ahead, from 1, the next.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject().put("position", new JSONObject()
                                            .put("type", "integer").put("minimum", 1)
                                            .put("description", "Which stop ahead; 1, the next, if left out")))))
                    .put(tool("trip_status", "Reads the trip Google Maps guides in the car now, as Maps itself shows it:"
                            + " the next turn, the time and distance left, the arrival time, and the destination and"
                            + " stops ahead when Maps reported them. guiding is false when Maps guides nowhere.",
                            new JSONObject().put("type", "object").put("properties", new JSONObject())));
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
                case "add_stop": return addStop(arguments.optString("place", "").trim());
                case "remove_stop": return removeStop(arguments.optInt("position", 1));
                case "trip_status": return onMain(this::tripStatus);
                default: throw new UnknownTool(name);
            }
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

    /** Asks the Maps bridge module to add {@code place} through Maps' own Add stop ({@link #changeStops}). */
    private Result addStop(String place) throws TimeoutException {
        if (place.isEmpty()) return Result.failed("add_stop needs a place: an address, or a place name with its town");
        if (!onMain(() -> NavigationNotifications.current() != null)) {
            return Result.failed("Maps guides nowhere now, so there is no trip to add a stop to; nothing changed."
                    + " Use navigate_to to go to \"" + place + "\" instead.");
        }
        return changeStops(new Intent(ADD_STOP).putExtra(PLACE, place), "add \"" + place + "\"",
                "Maps added \"" + place + "\" as a stop on the trip it guides and keeps guiding.", 1);
    }

    /**
     * Asks the Maps bridge module to remove the stop at {@code position} ahead ({@link #changeStops}).
     * Maps would remove its destination at the position after the last stop, so only stops Maps
     * reported ahead ({@link MapsTrips}) may be removed.
     */
    private Result removeStop(int position) throws TimeoutException {
        if (!onMain(() -> NavigationNotifications.current() != null)) {
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
            Bundle said = answer.get(STOP_WAIT_MS, TimeUnit.MILLISECONDS);
            if (said.getBoolean("done")) return Result.ok(done + " " + reportedStops(before + stopsChange));
            return Result.failed(said.getString("problem", "Maps did not " + change) + ". The route did not change.");
        } catch (TimeoutException slow) {
            return Result.failed("Maps did not say within " + STOP_WAIT_MS / 1000 + " s whether it did " + change
                    + "; call trip_status to see the route.");
        } catch (InterruptedException | ExecutionException failed) {
            throw new IllegalStateException(failed);
        } finally {
            stopAnswers.remove(request);
        }
    }

    /** How long Maps' report of changed stops may take: it saves its directions, then the module reads them. */
    private static final long REPORT_WAIT_MS = 5_000;

    /** @return the stops ahead Maps last reported for the trip it guides; none if it reported none */
    private String[] stopsAhead() throws TimeoutException {
        return onMain(() -> {
            MapsTrips.Trip trip = NavigationNotifications.current() == null ? null : MapsTrips.of(NavigationNotifications.began());
            return trip == null ? new String[0] : trip.stops;
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

    private Result tripStatus() {
        NavigationCard turn = NavigationNotifications.current();
        try {
            JSONObject status = new JSONObject().put("guiding", turn != null);
            if (turn == null) return Result.ok(status.toString());
            MapsTrips.Trip trip = MapsTrips.of(NavigationNotifications.began());
            if (trip != null) {
                status.put("destination", trip.destination);
                JSONArray stops = new JSONArray();
                for (String stop : trip.stops) stops.put(stop);
                status.put("stops_ahead", stops);
            }
            status.put("next_turn", new JSONObject().put("distance", turn.distance.toString())
                    .put("road", turn.road.toString()).put("arrived", turn.arrived));
            if (turn.eta.length() > 0) status.put("remaining", turn.eta.toString());
            String arrival = turn.arrival();
            if (!arrival.isEmpty()) status.put("arrival", arrival);
            return Result.ok(status.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JSONObject tool(String name, String description, JSONObject inputSchema) throws JSONException {
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", inputSchema);
    }
}
