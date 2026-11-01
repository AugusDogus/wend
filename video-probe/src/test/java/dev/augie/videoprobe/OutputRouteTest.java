package dev.augie.videoprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class OutputRouteTest {
    @Test public void pausedPlaybackCannotAttachAndSendsNoDecoderCommand() {
        List<String> switches = new ArrayList<>();
        OutputRoute<String> route = new OutputRoute<>("source", switches::add, () -> false);
        assertEquals(OutputRoute.Result.NOT_PLAYING, route.attach("receiver", "panel"));
        assertTrue(switches.isEmpty());
    }
    @Test public void detachRestoresOriginalEvenAfterPlaybackPauses() {
        AtomicBoolean playing = new AtomicBoolean(true);
        List<String> switches = new ArrayList<>();
        OutputRoute<String> route = new OutputRoute<>("source", switches::add, playing::get);
        assertEquals(OutputRoute.Result.ATTACHED, route.attach("receiver", "panel"));
        playing.set(false);
        assertTrue(route.detach("receiver"));
        assertEquals(java.util.Arrays.asList("panel", "source"), switches);
    }
    @Test public void staleReceiverCannotDetachNewReceiver() {
        List<String> switches = new ArrayList<>();
        OutputRoute<String> route = new OutputRoute<>("source", switches::add, () -> true);
        route.attach("old", "old-panel"); route.attach("new", "new-panel");
        assertFalse(route.detach("old"));
        assertEquals(2, switches.size());
        assertTrue(route.detach("new"));
    }
    @Test public void failedSwitchPreservesPreviousLease() {
        List<String> switches = new ArrayList<>();
        OutputRoute<String> route = new OutputRoute<>("source", surface -> {
            if (surface.equals("invalid")) throw new IllegalArgumentException("Invalid surface");
            switches.add(surface);
        }, () -> true);
        route.attach("old", "panel");
        assertEquals(OutputRoute.Result.SWITCH_FAILED, route.attach("new", "invalid"));
        assertFalse(route.detach("new"));
        assertTrue(route.detach("old"));
        assertEquals(java.util.Arrays.asList("panel", "source"), switches);
    }
    @Test public void detachWithoutAttachmentDoesNothing() {
        List<String> switches = new ArrayList<>();
        OutputRoute<String> route = new OutputRoute<>("source", switches::add, () -> true);
        assertFalse(route.detach("unknown")); assertTrue(switches.isEmpty());
    }
}
