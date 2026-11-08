package dev.augie.wend;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** The drawer at the head unit's 1280 x 720 px, 240 dpi screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "w853dp-h480dp-land-hdpi")
public class AppDrawerTest {
    private static final String RECENT = ", recently opened";

    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    private final LauncherSettings settings = new LauncherSettings(
            activity.getSharedPreferences(LauncherSettings.PREFERENCES, Context.MODE_PRIVATE));
    private final FrostedBackdrop backdrop = new FrostedBackdrop(activity);
    private AppDrawer drawer;

    @After public void release() {
        if (drawer != null) drawer.release();
        backdrop.release();
    }

    @Test public void theHeaderHasOnlyTheSearchFieldCenteredUnderTheHandle() {
        View window = open();

        assertTrue("Dragging, Back, the backdrop and Home close the drawer",
                views(window, ImageButton.class).isEmpty());
        assertCentered(window, search(window));
    }

    @Test public void theSearchFieldStaysCenteredRightToLeft() {
        View window = open();
        // Robolectric ignores the ldrtl qualifier, so the window is mirrored directly.
        window.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        window.requestLayout();
        idle();

        assertEquals(View.LAYOUT_DIRECTION_RTL, search(window).getLayoutDirection());
        assertCentered(window, search(window));
    }

    @Test public void withoutHistoryTheGridStartsAtTheTop() {
        install("Maps", "Waze");

        View window = open();

        assertEquals(Collections.<String>emptyList(), recentLabels(window));
        assertEquals(Arrays.asList("Maps", "Waze"), gridLabels(window));
    }

    @Test public void recentAppsFillOneRowNewestFirstAboveTheWholeGrid() {
        install("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot");
        RecentApps recent = RecentApps.empty();
        for (String label : new String[]{"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot"}) {
            recent = recent.launched(packageOf(label));
        }
        settings.setRecentApps(recent);

        View window = open();

        assertEquals(Arrays.asList("Foxtrot", "Echo", "Delta", "Charlie", "Bravo"), recentLabels(window));
        List<TextView> row = recentTiles(window);
        for (TextView tile : row) assertEquals("One row", top(row.get(0)), top(tile));
        assertEquals("The grid still lists every app",
                Arrays.asList("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot"), gridLabels(window));
        assertTrue("The grid starts below the row", top(gridTiles(window).get(0)) >= top(row.get(0)) + row.get(0).getHeight());
    }

    @Test public void searchingHidesTheRowAndClearingBringsItBack() {
        install("Maps", "Waze");
        settings.setRecentApps(RecentApps.empty().launched(packageOf("Waze")));
        View window = open();

        search(window).setText("waz");

        assertEquals(Collections.<String>emptyList(), recentLabels(window));
        assertEquals(Collections.singletonList("Waze"), gridLabels(window));

        search(window).setText("");

        assertEquals(Collections.singletonList("Waze"), recentLabels(window));
    }

    @Test public void uninstalledAppsLeaveTheRowAndTheHistory() {
        install("Maps");
        settings.setRecentApps(RecentApps.empty().launched("com.removed.app").launched(packageOf("Maps")));

        View window = open();

        assertEquals(Collections.singletonList("Maps"), recentLabels(window));
        assertEquals(Collections.singletonList(packageOf("Maps")), settings.recentApps().packages());
    }

    @Test public void anAppOpenedFromTheDrawerLeadsTheRowNextTime() {
        install("Maps", "Waze");
        TestApps.installInPackageManager(activity, packageOf("Waze"), "Waze");
        settings.setRecentApps(RecentApps.empty().launched(packageOf("Maps")));
        View window = open();

        gridTiles(window).get(1).performClick();
        idle();
        assertFalse("Opening an app removes the drawer", ShadowDialog.getLatestDialog().isShowing());
        assertNotNull(shadowOf(activity).getNextStartedActivity());
        drawer.show();
        idle();

        assertEquals(Arrays.asList("Waze", "Maps"), recentLabels(window));
    }

    @Test public void anAppOpenedTwiceIsShownOnce() {
        install("Waze");
        TestApps.installInPackageManager(activity, packageOf("Waze"), "Waze");
        View window = open();

        AppLaunch.open(activity, packageOf("Waze"));
        AppLaunch.open(activity, packageOf("Waze"));
        drawer.close();
        idle();
        drawer.show();
        idle();

        assertEquals(Collections.singletonList("Waze"), recentLabels(window));
    }

    private void install(String... labels) {
        for (String label : labels) TestApps.install(activity, packageOf(label), label);
    }

    private static String packageOf(String label) { return "com.example." + label.toLowerCase(Locale.ROOT); }

    private View open() {
        drawer = new AppDrawer(activity, TestApps.loadedCatalog(activity), settings, backdrop);
        drawer.show();
        idle();
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }

    private static EditText search(View window) {
        List<EditText> fields = views(window, EditText.class);
        assertEquals(1, fields.size());
        return fields.get(0);
    }

    private static List<TextView> recentTiles(View window) {
        List<TextView> recent = new ArrayList<>();
        for (TextView tile : tiles(window)) {
            if (tile.getContentDescription().toString().endsWith(RECENT)) recent.add(tile);
        }
        return recent;
    }

    private static List<TextView> gridTiles(View window) {
        List<TextView> grid = new ArrayList<>();
        for (TextView tile : tiles(window)) {
            if (!tile.getContentDescription().toString().endsWith(RECENT)) grid.add(tile);
        }
        return grid;
    }

    /** Visible app tiles: text views with a description, unlike the search field and status line. */
    private static List<TextView> tiles(View window) {
        List<TextView> tiles = new ArrayList<>();
        for (TextView view : views(window, TextView.class)) {
            if (!(view instanceof EditText) && view.getContentDescription() != null) tiles.add(view);
        }
        return tiles;
    }

    private static List<String> recentLabels(View window) { return labels(recentTiles(window)); }
    private static List<String> gridLabels(View window) { return labels(gridTiles(window)); }

    private static List<String> labels(List<TextView> tiles) {
        List<String> labels = new ArrayList<>();
        for (TextView tile : tiles) {
            labels.add(tile.getText().toString());
            if (tile.getContentDescription().toString().endsWith(RECENT)) {
                assertEquals(tile.getText() + RECENT, tile.getContentDescription().toString());
            }
        }
        return labels;
    }

    private static int top(View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return location[1];
    }

    private static void assertCentered(View window, View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        assertTrue("The field is laid out", view.getWidth() > 0);
        int start = location[0];
        int end = window.getWidth() - location[0] - view.getWidth();
        assertEquals("Equal space on both sides of the search field", start, end);
    }

    private static <T extends View> List<T> views(View view, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (view.getVisibility() != View.VISIBLE) return found;
        if (type.isInstance(view)) found.add(type.cast(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(views(group.getChildAt(i), type));
        }
        return found;
    }
}
