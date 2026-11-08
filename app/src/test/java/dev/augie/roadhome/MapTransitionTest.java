package dev.augie.roadhome;

import android.app.Activity;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MapTransitionTest {
    @Test public void withoutACoverMapsMovesAndIsRevealedAtOnceInOrder() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        // Never laid out, as before Road Home's first frame: no frozen frame can be held.
        View panel = new View(host);
        MapTransition transition = new MapTransition(host, panel);
        List<String> events = new ArrayList<>();
        transition.move(true, () -> events.add("moved"), () -> events.add("revealed"));
        assertEquals(List.of("moved", "revealed"), events);
        assertFalse(transition.active());
    }

    @Test public void aHeldTakeoverWithoutACoverIsRevealedRightAfterItsWork() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        MapTransition transition = new MapTransition(host, new View(host));
        List<String> events = new ArrayList<>();
        transition.holdThenCrossfade(() -> events.add("covered"), () -> events.add("revealed"));
        transition.settleHeld();
        assertEquals(List.of("covered", "revealed"), events);
        assertFalse(transition.active());
    }
}
