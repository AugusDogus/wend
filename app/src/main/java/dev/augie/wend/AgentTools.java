package dev.augie.wend;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What an agent may do in the car through Wend's MCP server ({@link AgentServer}): start Maps'
 * guidance somewhere, and read the guidance Maps runs. Each tool has a name, a description and an
 * input schema for the agent, and a call. Main thread, where Maps' guidance is kept.
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
        switch (name) {
            case "navigate_to": return navigateTo(arguments.optString("destination", "").trim());
            case "trip_status": return tripStatus();
            default: throw new UnknownTool(name);
        }
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
