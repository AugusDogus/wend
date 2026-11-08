package dev.augie.roadhome;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.UserHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AppListTest {
    private static final UserHandle OWNER = UserHandle.getUserHandleForUid(10_000);
    private static final UserHandle WORK = UserHandle.getUserHandleForUid(10 * 100_000 + 10_000);
    private static final Bitmap ICON = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    private Locale previousLocale;

    @Before public void useEnglishCollation() {
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @After public void restoreLocale() { Locale.setDefault(previousLocale); }

    @Test public void sortsByLocalizedLabelIgnoringCaseAndAccents() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Arrays.asList(app("z.zoom", "Zoom"), app("e.eclair", "éclair"),
                app("b.banana", "banana"), app("a.apple", "Apple")));
        assertEquals(Arrays.asList("Apple", "banana", "éclair", "Zoom"), labels(list.sorted()));
    }

    @Test public void equalLabelsHaveAStableOrderByPackage() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Arrays.asList(app("com.second", "Notes"), app("com.first", "Notes")));
        assertEquals("com.first", list.sorted().get(0).key.packageName);
        assertEquals("com.second", list.sorted().get(1).key.packageName);
    }

    @Test public void excludesTheLauncherItself() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Arrays.asList(app("dev.augie.roadhome", "Road Home"), app("com.maps", "Maps")));
        assertEquals(Collections.singletonList("Maps"), labels(list.sorted()));
        list.replacePackage("dev.augie.roadhome", OWNER,
                Collections.singletonList(app("dev.augie.roadhome", "Road Home")));
        assertEquals(Collections.singletonList("Maps"), labels(list.sorted()));
    }

    @Test public void keepsOnlyTheFirstLauncherActivityOfAPackage() {
        AppList list = new AppList("dev.augie.roadhome");
        LauncherApp primary = app("com.maps", "Maps");
        list.replaceAll(Arrays.asList(primary, app("com.maps", "Navigation")));
        assertEquals(1, list.sorted().size());
        assertSame(primary, list.sorted().get(0));
    }

    @Test public void listsTheSamePackageSeparatelyForEachProfile() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Arrays.asList(app("com.mail", "Mail", OWNER), app("com.mail", "Mail", WORK)));
        assertEquals(2, list.sorted().size());
    }

    @Test public void addedPackageAppearsInSortedPosition() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Arrays.asList(app("a.apple", "Apple"), app("c.cherry", "Cherry")));
        list.replacePackage("b.banana", OWNER, Collections.singletonList(app("b.banana", "Banana")));
        assertEquals(Arrays.asList("Apple", "Banana", "Cherry"), labels(list.sorted()));
    }

    @Test public void changedPackageReplacesItsLabelAndIconWithoutTouchingOthers() {
        AppList list = new AppList("dev.augie.roadhome");
        LauncherApp other = app("com.other", "Other");
        list.replaceAll(Arrays.asList(app("com.maps", "Maps"), other));
        LauncherApp renamed = app("com.maps", "Google Maps");
        list.replacePackage("com.maps", OWNER, Collections.singletonList(renamed));
        assertEquals(Arrays.asList("Google Maps", "Other"), labels(list.sorted()));
        assertSame(renamed, list.sorted().get(0));
        assertSame(other, list.sorted().get(1));
    }

    @Test public void packageWithoutLauncherActivitiesIsRemoved() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Arrays.asList(app("com.maps", "Maps"), app("com.other", "Other")));
        list.replacePackage("com.maps", OWNER, Collections.<LauncherApp>emptyList());
        assertEquals(Collections.singletonList("Other"), labels(list.sorted()));
    }

    @Test public void removingAPackageOnlyAffectsItsProfile() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Arrays.asList(app("com.mail", "Mail", OWNER), app("com.mail", "Mail", WORK)));
        list.removePackage("com.mail", WORK);
        assertEquals(1, list.sorted().size());
        assertEquals(OWNER, list.sorted().get(0).key.user);
    }

    @Test(expected = IllegalArgumentException.class)
    public void packageUpdateRejectsActivitiesFromAnotherPackage() {
        new AppList("dev.augie.roadhome").replacePackage("com.maps", OWNER,
                Collections.singletonList(app("com.other", "Other")));
    }

    @Test public void fullReloadDropsAppsThatAreNoLongerInstalled() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Arrays.asList(app("com.maps", "Maps"), app("com.old", "Old")));
        list.replaceAll(Collections.singletonList(app("com.maps", "Maps")));
        assertEquals(Collections.singletonList("Maps"), labels(list.sorted()));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void sortedSnapshotCannotBeModifiedByListeners() {
        AppList list = new AppList("dev.augie.roadhome");
        list.replaceAll(Collections.singletonList(app("com.maps", "Maps")));
        list.sorted().clear();
    }

    private static LauncherApp app(String packageName, String label) { return app(packageName, label, OWNER); }

    private static LauncherApp app(String packageName, String label, UserHandle user) {
        return new LauncherApp(new ComponentName(packageName, packageName + ".Main"), user, label, ICON);
    }

    private static List<String> labels(List<LauncherApp> apps) {
        List<String> labels = new ArrayList<>();
        for (LauncherApp app : apps) labels.add(app.label);
        return labels;
    }
}
