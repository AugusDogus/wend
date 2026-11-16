package dev.augie.wend.mapsbridge;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs inside Google Maps. Changes the stops of the trip Maps guides through Maps' own navigation
 * ({@link MapsNavigation}), as Maps' own buttons do; Maps keeps guiding and keeps its destination,
 * and reroutes. Nothing shows on screen but the new route.
 *
 * <ul>
 * <li>{@link #ADD}, with {@link #PLACE}: adds a stop there, as Maps' "Add stop" does. Maps finds
 *     the place from its text itself.
 * <li>{@link #REMOVE}, with {@link #POSITION}: removes that stop ahead, 1 being the next.
 * <li>{@link #END}: ends the guidance, as Maps' own end-navigation button does.
 * <li>{@link #ROUTES}: answers with Maps' routes for the trip ({@link MapsRoutes}) as JSON in
 *     {@link #ROUTES}.
 * <li>{@link #SWITCH}, with {@link #ROUTE}: guides along that route of them instead.
 * </ul>
 *
 * <p>Wend asks with these ordered broadcasts, with {@link #REQUEST}, from a sender that may manage
 * activity stacks; one gets result code 1 if this module will answer. The answer is a call of
 * {@code stop} on Wend's provider with the extras {@link #REQUEST}, {@link #DONE}, and
 * {@link #PROBLEM} when not done.
 */
final class TripStops {
    static final String ADD = "dev.augie.wend.mapsbridge.ADD_STOP", REMOVE = "dev.augie.wend.mapsbridge.REMOVE_STOP",
            END = "dev.augie.wend.mapsbridge.END_NAVIGATION", ROUTES = "dev.augie.wend.mapsbridge.ROUTES",
            SWITCH = "dev.augie.wend.mapsbridge.SWITCH_ROUTE";
    static final String PLACE = "place", POSITION = "position", ROUTE = "route", REQUEST = "request", DONE = "done",
            PROBLEM = "problem";
    private static final String ASKERS = "android.permission.MANAGE_ACTIVITY_STACKS";
    /** Maps answered within about 1 s on the emulator; it reroutes over the network. */
    private static final long ANSWER_WAIT_S = 20;

    private TripStops() { }

    /**
     * @param navigation Maps' navigation, or null if it was not found in this Maps, which this
     *     then says to each ask
     * @param why what went wrong finding it, when {@code navigation} is null
     */
    static void install(Application maps, TripReporter reporter, MapsNavigation navigation, String why) {
        // Its own thread: waiting on Maps here holds up none of the other reports to Wend.
        HandlerThread thread = new HandlerThread("wend-maps-bridge-stops");
        thread.start();
        Handler worker = new Handler(thread.getLooper());
        IntentFilter asks = new IntentFilter(ADD);
        asks.addAction(REMOVE);
        asks.addAction(END);
        asks.addAction(ROUTES);
        asks.addAction(SWITCH);
        maps.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (isOrderedBroadcast()) setResultCode(1);
                PendingResult pending = goAsync();
                worker.post(() -> {
                    try {
                        boolean aboutRoutes = ROUTES.equals(intent.getAction()) || SWITCH.equals(intent.getAction());
                        reporter.tellWend("stop", null, aboutRoutes ? routes(maps, navigation, why, intent) : answer(navigation, why, intent));
                    }
                    finally { pending.finish(); }
                });
            }
        }, asks, ASKERS, worker);
    }

    /** Found the first time Wend asks about routes; worker thread only. */
    private static MapsRoutes routes;

    private static Bundle routes(Application maps, MapsNavigation navigation, String why, Intent asked) {
        Bundle extras = new Bundle();
        extras.putInt(REQUEST, asked.getIntExtra(REQUEST, 0));
        String problem = null;
        try {
            if (navigation == null) throw new NoSuchMethodException("Maps' navigation was not found in this Maps: " + why);
            if (routes == null) routes = MapsRoutes.find(maps, navigation.adapterClass(), navigation.apk());
            if (ROUTES.equals(asked.getAction())) {
                extras.putString(ROUTES, routes.routes(navigation.adapter()).toString());
            } else {
                String route = asked.getStringExtra(ROUTE);
                if (route == null || route.isEmpty()) throw new IllegalArgumentException("No route was named");
                if (!Boolean.TRUE.equals(routes.switchTo(navigation.adapter(), route).get(ANSWER_WAIT_S, TimeUnit.SECONDS))) {
                    problem = "Maps did not switch to route " + route;
                }
            }
        } catch (IllegalStateException notGuided) {
            problem = "Maps is not guiding: " + notGuided.getMessage();
        } catch (TimeoutException slow) {
            problem = "Maps did not answer within " + ANSWER_WAIT_S + " s; it may still switch";
        } catch (ExecutionException failed) {
            problem = "Maps could not switch the route: " + failed.getCause();
        } catch (ReflectiveOperationException | org.json.JSONException | RuntimeException | InterruptedException broken) {
            problem = "Wend's Maps bridge could not read or switch Maps' routes: " + broken;
        }
        extras.putBoolean(DONE, problem == null);
        if (problem != null) {
            extras.putString(PROBLEM, problem);
            Log.w(MapsBridge.TAG, "Routes for Wend: " + problem);
        }
        return extras;
    }

    private static Bundle answer(MapsNavigation navigation, String why, Intent asked) {
        boolean add = ADD.equals(asked.getAction()), end = END.equals(asked.getAction());
        String place = asked.getStringExtra(PLACE);
        int position = asked.getIntExtra(POSITION, 0);
        String change = end ? "end the guidance" : add ? "add \"" + place + "\"" : "remove stop " + position;
        Bundle extras = new Bundle();
        extras.putInt(REQUEST, asked.getIntExtra(REQUEST, 0));
        String problem = null;
        if (navigation == null) problem = "Wend's Maps bridge could not find Maps' stops in this version of Maps: " + why;
        else if (add && (place == null || place.trim().isEmpty())) problem = "No place was given to add";
        else if (end) {
            try {
                navigation.endGuidance();
            } catch (IllegalStateException notGuided) {
                problem = "Maps is not guiding: " + notGuided.getMessage();
            } catch (ReflectiveOperationException | RuntimeException broken) {
                problem = "Wend's Maps bridge could not call Maps to " + change + ": " + broken;
            }
        }
        else if (!add && position < 1) problem = "No stop was named to remove";
        else {
            try {
                Future<?> changed = add ? navigation.addStop(place.trim()) : navigation.removeStop(position);
                if (!Boolean.TRUE.equals(changed.get(ANSWER_WAIT_S, TimeUnit.SECONDS))) problem = "Maps did not " + change;
            } catch (IllegalStateException notGuided) {
                problem = "Maps is not guiding a trip to change: " + notGuided.getMessage();
            } catch (TimeoutException slow) {
                problem = "Maps did not answer within " + ANSWER_WAIT_S + " s; it may still " + change;
            } catch (ExecutionException failed) {
                problem = "Maps could not " + change + ": " + failed.getCause();
            } catch (ReflectiveOperationException | RuntimeException | InterruptedException broken) {
                problem = "Wend's Maps bridge could not call Maps to " + change + ": " + broken;
            }
        }
        extras.putBoolean(DONE, problem == null);
        if (problem != null) {
            extras.putString(PROBLEM, problem);
            Log.w(MapsBridge.TAG, "Could not " + change + " for Wend: " + problem);
        } else {
            Log.i(MapsBridge.TAG, "Did " + change + " for Wend");
        }
        return extras;
    }
}
