package dev.augie.roadhome;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DockPinsTest {
    private static final int CAPACITY = 5;

    @Test public void defaultsPinMapsThenSpotify() {
        DockPins pins = DockPins.defaults(CAPACITY);
        assertEquals(Arrays.asList(BuildConfig.MAPS_PACKAGE, "com.spotify.music"), pins.packages());
        assertEquals(CAPACITY, pins.capacity());
    }

    @Test public void defaultsFitASmallerDock() {
        assertEquals(Collections.singletonList(BuildConfig.MAPS_PACKAGE), DockPins.defaults(1).packages());
        assertTrue(DockPins.defaults(0).packages().isEmpty());
    }

    @Test public void aMissingPreferenceUsesTheDefaults() {
        assertEquals(DockPins.defaults(CAPACITY).packages(), DockPins.parse(null, CAPACITY).packages());
    }

    @Test public void anEmptyPreferenceKeepsAnEmptyDock() {
        assertTrue(DockPins.parse("", CAPACITY).packages().isEmpty());
        assertEquals("", DockPins.parse("", CAPACITY).serialize());
    }

    @Test public void serializedPinsParseBackInOrder() {
        DockPins pins = DockPins.parse("com.spotify.music,com.google.android.apps.maps,com.android.settings", CAPACITY);
        assertEquals(Arrays.asList("com.spotify.music", "com.google.android.apps.maps", "com.android.settings"),
                pins.packages());
        assertEquals(pins.packages(), DockPins.parse(pins.serialize(), CAPACITY).packages());
    }

    @Test public void garbageEntriesAreDroppedAndValidOnesKept() {
        DockPins pins = DockPins.parse(" com.a.b , ,,not a package,1com.bad,com.,.com,com..x,com.c_d.e9,"
                + "com.a.b,single,com.ok", CAPACITY);
        assertEquals(Arrays.asList("com.a.b", "com.c_d.e9", "com.ok"), pins.packages());
    }

    @Test public void aPreferenceOfOnlyGarbageIsAnEmptyDock() {
        assertTrue(DockPins.parse(";;; \n", CAPACITY).packages().isEmpty());
    }

    @Test public void duplicatesKeepTheirFirstPosition() {
        assertEquals(Arrays.asList("com.b.b", "com.a.a"),
                DockPins.parse("com.b.b,com.a.a,com.b.b", CAPACITY).packages());
    }

    @Test public void storedPinsBeyondCapacityAreDropped() {
        DockPins pins = DockPins.parse("com.a.a,com.b.b,com.c.c", 2);
        assertEquals(Arrays.asList("com.a.a", "com.b.b"), pins.packages());
        assertTrue(pins.isFull());
    }

    @Test public void addAppendsToTheEnd() {
        DockPins pins = DockPins.parse("com.a.a", CAPACITY).add("com.b.b");
        assertEquals(Arrays.asList("com.a.a", "com.b.b"), pins.packages());
    }

    @Test public void addingAPinnedAppChangesNothing() {
        DockPins pins = DockPins.parse("com.a.a,com.b.b", CAPACITY);
        assertSame(pins, pins.add("com.a.a"));
    }

    @Test public void aFullDockRefusesAnotherApp() {
        DockPins pins = DockPins.parse("com.a.a,com.b.b", 2);
        assertFalse(pins.canAdd("com.c.c"));
        try {
            pins.add("com.c.c");
            fail("A full dock accepted another app");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("2"));
        }
    }

    @Test public void addRejectsAnInvalidPackageName() {
        DockPins pins = DockPins.parse("", CAPACITY);
        assertFalse(pins.canAdd("not a package"));
        try {
            pins.add("not a package");
            fail("An invalid package name was pinned");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not a package"));
        }
    }

    @Test public void removeDropsOnlyThatApp() {
        DockPins pins = DockPins.parse("com.a.a,com.b.b,com.c.c", CAPACITY).remove("com.b.b");
        assertEquals(Arrays.asList("com.a.a", "com.c.c"), pins.packages());
        assertSame(pins, pins.remove("com.missing.app"));
    }

    @Test public void removingFromAFullDockMakesRoom() {
        DockPins pins = DockPins.parse("com.a.a,com.b.b", 2).remove("com.a.a");
        assertFalse(pins.isFull());
        assertTrue(pins.canAdd("com.c.c"));
    }

    @Test public void moveReordersPins() {
        DockPins pins = DockPins.parse("com.a.a,com.b.b,com.c.c", CAPACITY);
        assertEquals(Arrays.asList("com.b.b", "com.a.a", "com.c.c"), pins.move(1, 0).packages());
        assertEquals(Arrays.asList("com.a.a", "com.c.c", "com.b.b"), pins.move(1, 2).packages());
        assertEquals(Arrays.asList("com.b.b", "com.c.c", "com.a.a"), pins.move(0, 2).packages());
        assertSame(pins, pins.move(1, 1));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void movePastTheEndIsRejected() {
        DockPins.parse("com.a.a,com.b.b", CAPACITY).move(1, 2);
    }

    @Test public void changesNeverMutateTheOriginal() {
        DockPins pins = DockPins.parse("com.a.a,com.b.b", CAPACITY);
        pins.add("com.c.c");
        pins.remove("com.a.a");
        pins.move(0, 1);
        assertEquals(Arrays.asList("com.a.a", "com.b.b"), pins.packages());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void packagesAreUnmodifiable() {
        DockPins.defaults(CAPACITY).packages().add("com.a.a");
    }

    @Test public void containsReportsPinnedPackages() {
        DockPins pins = DockPins.parse("com.a.a", CAPACITY);
        assertTrue(pins.contains("com.a.a"));
        assertFalse(pins.contains("com.b.b"));
    }
}
