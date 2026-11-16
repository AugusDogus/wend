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
 * </ul>
 *
 * <p>Wend asks with these ordered broadcasts, with {@link #REQUEST}, from a sender that may manage
 * activity stacks; one gets result code 1 if this module will answer. The answer is a call of
 * {@code stop} on Wend's provider with the extras {@link #REQUEST}, {@link #DONE}, and
 * {@link #PROBLEM} when not done.
 */
final class TripStops {
    static final String ADD = "dev.augie.wend.mapsbridge.ADD_STOP", REMOVE = "dev.augie.wend.mapsbridge.REMOVE_STOP";
    static final String PLACE = "place", POSITION = "position", REQUEST = "request", DONE = "done", PROBLEM = "problem";
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
        maps.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (isOrderedBroadcast()) setResultCode(1);
                PendingResult pending = goAsync();
                worker.post(() -> {
                    try { reporter.tellWend("stop", null, answer(navigation, why, intent)); }
                    finally { pending.finish(); }
                });
            }
        }, asks, ASKERS, worker);
    }

    private static Bundle answer(MapsNavigation navigation, String why, Intent asked) {
        boolean add = ADD.equals(asked.getAction());
        String place = asked.getStringExtra(PLACE);
        int position = asked.getIntExtra(POSITION, 0);
        String change = add ? "add \"" + place + "\"" : "remove stop " + position;
        Bundle extras = new Bundle();
        extras.putInt(REQUEST, asked.getIntExtra(REQUEST, 0));
        String problem = null;
        if (navigation == null) problem = "Wend's Maps bridge could not find Maps' stops in this version of Maps: " + why;
        else if (add && (place == null || place.trim().isEmpty())) problem = "No place was given to add";
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
