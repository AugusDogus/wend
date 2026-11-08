package dev.augie.wend;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RecentAppsTest {
    @Test public void holdsOneDrawerRow() {
        assertEquals(AppBrowser.COLUMNS, RecentApps.CAPACITY);
    }

    @Test public void aNewInstallHasNoHistory() {
        assertTrue(RecentApps.parse(null).packages().isEmpty());
        assertTrue(RecentApps.parse("").packages().isEmpty());
        assertEquals("", RecentApps.empty().serialize());
    }

    @Test public void theNewestLaunchComesFirst() {
        RecentApps recent = RecentApps.empty().launched("com.a.a").launched("com.b.b").launched("com.c.c");
        assertEquals(Arrays.asList("com.c.c", "com.b.b", "com.a.a"), recent.packages());
    }

    @Test public void relaunchingAnAppMovesItToTheFrontInsteadOfRepeatingIt() {
        RecentApps recent = RecentApps.empty().launched("com.a.a").launched("com.b.b").launched("com.c.c")
                .launched("com.a.a");
        assertEquals(Arrays.asList("com.a.a", "com.c.c", "com.b.b"), recent.packages());
    }

    @Test public void relaunchingTheNewestAppChangesNothing() {
        RecentApps recent = RecentApps.empty().launched("com.a.a");
        assertSame(recent, recent.launched("com.a.a"));
    }

    @Test public void theOldestAppIsDroppedPastTheCapacity() {
        RecentApps recent = RecentApps.empty();
        for (String name : new String[]{"com.a.a", "com.b.b", "com.c.c", "com.d.d", "com.e.e", "com.f.f"}) {
            recent = recent.launched(name);
        }
        assertEquals(Arrays.asList("com.f.f", "com.e.e", "com.d.d", "com.c.c", "com.b.b"), recent.packages());
    }

    @Test public void relaunchingAnAppInAFullHistoryKeepsEveryOtherApp() {
        RecentApps full = RecentApps.parse("com.a.a,com.b.b,com.c.c,com.d.d,com.e.e");
        assertEquals(Arrays.asList("com.e.e", "com.a.a", "com.b.b", "com.c.c", "com.d.d"),
                full.launched("com.e.e").packages());
    }

    @Test public void survivesARoundTrip() {
        RecentApps recent = RecentApps.empty().launched("com.a.a").launched("com.b.b");
        assertEquals("com.b.b,com.a.a", recent.serialize());
        assertEquals(recent, RecentApps.parse(recent.serialize()));
    }

    @Test public void storedGarbageKeepsItsValidEntriesInOrder() {
        RecentApps recent = RecentApps.parse("com.a.a,,bad name, com.b.b ,com.a.a,com.c.c,com.d.d,com.e.e,com.f.f");
        assertEquals(Arrays.asList("com.a.a", "com.b.b", "com.c.c", "com.d.d", "com.e.e"), recent.packages());
    }

    @Test public void retainDropsUninstalledAppsAndKeepsTheOrder() {
        RecentApps recent = RecentApps.parse("com.a.a,com.b.b,com.c.c");
        assertEquals(Arrays.asList("com.a.a", "com.c.c"), recent.retain(name -> !name.equals("com.b.b")).packages());
        assertSame("Nothing to drop", recent, recent.retain(name -> true));
        assertEquals(Collections.<String>emptyList(), recent.retain(name -> false).packages());
    }

    @Test public void changesNeverModifyTheOriginal() {
        RecentApps original = RecentApps.parse("com.a.a,com.b.b");
        original.launched("com.c.c");
        original.retain(name -> false);
        assertEquals(Arrays.asList("com.a.a", "com.b.b"), original.packages());
        try {
            original.packages().add("com.d.d");
            fail("The packages are unmodifiable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(2, original.packages().size());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aLaunchMustNameAPackage() {
        RecentApps.empty().launched("not a package");
    }
}
