package dev.augie.wend;

import android.app.PendingIntent;
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
 * guidance somewhere, add a stop to the trip Maps guides, and read that trip. Each tool has a name,
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

    /**
     * How long Maps may take to end its guidance on its "Exit navigation" before the new link
     * opens anyway; Maps then asks the driver whether to exit.
     */
    static final long EXIT_WAIT_MS = 3_000;
    /** How long Wend's main thread may take to answer a tool. */
    private static final long MAIN_WAIT_MS = 5_000;
    /** How long Maps may take to say whether it added a stop; it reroutes over the network. */
    static final long STOP_WAIT_MS = 25_000;
    /** The Maps bridge module's broadcast that asks Maps to add a stop, and its extras. */
    static final String ADD_STOP = "dev.augie.wend.mapsbridge.ADD_STOP", PLACE = "place", REQUEST = "request";
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
                    .put(tool("add_stop", "Adds a stop to the trip Google Maps guides in the car now, through Maps' own"
                            + " Add stop: Maps keeps guiding, keeps its destination, and reroutes through the stop, which"
                            + " goes before the destination (Maps picks where among the stops ahead). Use it for a detour"
                            + " on the way, like a gas station; use navigate_to to go somewhere else instead. Maps finds"
                            + " the place from the text, so pass a full address when you know it.",
                            new JSONObject().put("type", "object")
                                    .put("properties", new JSONObject().put("place", new JSONObject()
                                            .put("type", "string")
                                            .put("description", "An address, or a place name with its town")))
                                    .put("required", new JSONArray().put("place"))))
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

    /**
     * Asks the Maps bridge module to add {@code place} through Maps' own Add stop, and waits for
     * Maps' answer ({@link #stopReported}). Off the main thread, which carries the answer.
     */
    private Result addStop(String place) throws TimeoutException {
        if (place.isEmpty()) return Result.failed("add_stop needs a place: an address, or a place name with its town");
        if (!onMain(() -> NavigationNotifications.current() != null)) {
            return Result.failed("Maps guides nowhere now, so there is no trip to add a stop to; nothing changed."
                    + " Use navigate_to to go to \"" + place + "\" instead.");
        }
        int request = stopRequests.incrementAndGet();
        CompletableFuture<Bundle> answer = new CompletableFuture<>();
        stopAnswers.put(request, answer);
        try {
            Intent ask = new Intent(ADD_STOP).setPackage(EmbeddedMaps.PACKAGE).putExtra(PLACE, place).putExtra(REQUEST, request);
            context.sendOrderedBroadcast(ask, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (getResultCode() == 1) return;
                    Bundle none = new Bundle();
                    none.putString("problem", "The Maps bridge module did not answer: it is not installed, not enabled for"
                            + " Maps in LSPosed, or older than this Wend.");
                    answer.complete(none);
                }
            }, main, Activity.RESULT_CANCELED, null, null);
            Bundle said = answer.get(STOP_WAIT_MS, TimeUnit.MILLISECONDS);
            if (said.getBoolean("added")) {
                return Result.ok("Maps added \"" + place + "\" as a stop on the trip it guides and keeps guiding. Call"
                        + " trip_status for the stops ahead and the new arrival time.");
            }
            return Result.failed(said.getString("problem", "Maps did not add the stop") + ". The route did not change.");
        } catch (TimeoutException slow) {
            return Result.failed("Maps did not say within " + STOP_WAIT_MS / 1000 + " s whether it added \"" + place
                    + "\"; call trip_status to see whether the stop is on the route.");
        } catch (InterruptedException | ExecutionException failed) {
            throw new IllegalStateException(failed);
        } finally {
            stopAnswers.remove(request);
        }
    }

    /** The Maps bridge module said whether Maps added the stop of {@code extras}' request. Any thread. */
    static void stopReported(Bundle extras) {
        CompletableFuture<Bundle> answer = extras == null ? null : stopAnswers.get(extras.getInt(REQUEST));
        if (answer != null) answer.complete(extras);
    }

    /**
     * Opens Maps' navigation link. A Maps that guides already would ask the driver whether to exit
     * that guidance, and take no as its answer, so its guidance is ended first through its own
     * notification's "Exit navigation", and the link opens once it has ended.
     */
    private Result navigateTo(String destination) {
        if (destination.isEmpty()) return Result.failed("navigate_to needs a destination: an address, coordinates or a place name");
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination)))
                .setPackage(EmbeddedMaps.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NavigationCard guiding = NavigationNotifications.current();
        if (guiding != null && guiding.exit != null && exit(guiding.exit)) {
            openWhenGuidanceEnds(link, destination);
            return Result.ok("Ended Maps' current guidance and asked Google Maps to navigate to \"" + destination + "\"."
                    + " Maps searches it and starts guiding; call trip_status in a few seconds to see where it is going.");
        }
        String failed = open(link, destination);
        if (failed != null) return Result.failed(failed);
        return Result.ok("Asked Google Maps to navigate to \"" + destination + "\". Maps searches it and starts guiding;"
                + " call trip_status in a few seconds to see where it is going."
                + (guiding != null ? " Maps was guiding already and may ask the driver whether to exit that route." : ""));
    }

    private static boolean exit(PendingIntent exit) {
        try {
            exit.send();
            return true;
        } catch (PendingIntent.CanceledException gone) {
            Log.w(TAG, "Maps' Exit navigation is no longer valid; opening the new link anyway, so Maps asks the driver", gone);
            return false;
        }
    }

    private void openWhenGuidanceEnds(Intent link, String destination) {
        boolean[] opened = {false};
        NavigationNotifications.Listener[] ended = {null};
        Runnable openOnce = () -> {
            if (opened[0]) return;
            opened[0] = true;
            NavigationNotifications.stop(ended[0]);
            String failed = open(link, destination);
            if (failed != null) Log.w(TAG, failed);
        };
        ended[0] = card -> { if (card == null) { main.removeCallbacks(openOnce); openOnce.run(); } };
        NavigationNotifications.listen(ended[0]);
        main.postDelayed(openOnce, EXIT_WAIT_MS);
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
