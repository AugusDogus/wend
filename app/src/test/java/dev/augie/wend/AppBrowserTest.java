package dev.augie.wend;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AppBrowserTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

    @Test public void thePickerLeavesWorkProfileAppsOutInsteadOfCallingThemPinned() {
        TestApps.install(activity, BuildConfig.MAPS_PACKAGE, "Maps");
        TestApps.install(activity, TestApps.workProfile(activity), "com.slack", "Slack", new ColorDrawable(Color.RED));
        AppBrowser picker = picker(TestApps.loadedCatalog(activity), DockPins.defaults(5));

        picker.search().setText("slack");

        assertEquals(Collections.singletonList("No apps match “slack”. Try another name."), visibleTexts(picker.body()));
    }

    @Test public void thePickerStillNamesAMatchingAppThatIsAlreadyPinned() {
        TestApps.install(activity, BuildConfig.MAPS_PACKAGE, "Maps");
        TestApps.install(activity, TestApps.workProfile(activity), BuildConfig.MAPS_PACKAGE, "Maps",
                new ColorDrawable(Color.RED));
        AppBrowser picker = picker(TestApps.loadedCatalog(activity), DockPins.defaults(5));

        picker.search().setText("maps");

        assertEquals(Collections.singletonList("Maps is already in the dock."), visibleTexts(picker.body()));
    }

    @Test public void talkBackReadsTheTypedQueryRatherThanTheHint() {
        AppBrowser drawer = drawer(TestApps.loadedCatalog(activity));

        drawer.search().setText("waze");

        // A description on an editable field replaces the typed text for TalkBack.
        assertNull(drawer.search().getContentDescription());
        AccessibilityNodeInfo field = drawer.search().createAccessibilityNodeInfo();
        assertEquals("waze", field.getText().toString());
        assertEquals("Search apps", field.getHintText().toString());
    }

    @Test public void searchResultMessagesAreAnnouncedPolitely() {
        TestApps.install(activity, BuildConfig.MAPS_PACKAGE, "Maps");
        AppBrowser drawer = drawer(TestApps.loadedCatalog(activity));

        drawer.search().setText("waze");

        TextView status = visibleTextView(drawer.body());
        assertEquals("No apps match “waze”. Try another name.", status.getText().toString());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.getAccessibilityLiveRegion());
    }

    @Test public void theRecentRowShowsEachAppOnceAndAtMostOneRow() {
        for (String name : new String[]{"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot"}) {
            TestApps.install(activity, "com.example." + name.toLowerCase(Locale.ROOT), name);
        }
        AppCatalog catalog = TestApps.loadedCatalog(activity);
        List<LauncherApp> apps = catalog.apps();
        AppBrowser drawer = drawer(catalog);

        drawer.setRecent(Arrays.asList(apps.get(5), apps.get(5), apps.get(0), apps.get(1), apps.get(2),
                apps.get(3), apps.get(4)));

        List<String> recent = new ArrayList<>();
        for (String text : visibleDescriptions(drawer.body())) if (text.endsWith(", recently opened")) recent.add(text);
        assertEquals(Arrays.asList("Foxtrot, recently opened", "Alpha, recently opened", "Bravo, recently opened",
                "Charlie, recently opened", "Delta, recently opened"), recent);

        drawer.setRecent(Collections.<LauncherApp>emptyList());

        assertEquals("Every app, once, in the grid", 6, visibleDescriptions(drawer.body()).size());
    }

    /** Configured as the app drawer. */
    private AppBrowser drawer(AppCatalog catalog) {
        return new AppBrowser(activity, catalog, app -> true, "Search apps", R.string.no_apps,
                R.string.no_matching_apps, R.string.no_matching_apps, app -> { });
    }

    /** Configured as the settings sheet's Add app page. */
    private AppBrowser picker(AppCatalog catalog, DockPins pins) {
        AppBrowser picker = new AppBrowser(activity, catalog, AppCatalog::canPin, "Search apps to add",
                R.string.every_app_pinned, R.string.matching_app_pinned, R.string.matching_apps_pinned, app -> { });
        picker.setHidden(app -> pins.contains(app.key.packageName));
        return picker;
    }

    private static TextView visibleTextView(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = visibleTextView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<String> visibleDescriptions(View view) {
        List<String> descriptions = new ArrayList<>();
        if (view.getVisibility() != View.VISIBLE) return descriptions;
        if (view.getContentDescription() != null) descriptions.add(view.getContentDescription().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) descriptions.addAll(visibleDescriptions(group.getChildAt(i)));
        }
        return descriptions;
    }

    private static List<String> visibleTexts(View view) {
        List<String> texts = new ArrayList<>();
        if (view.getVisibility() != View.VISIBLE) return texts;
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) texts.addAll(visibleTexts(group.getChildAt(i)));
        }
        return texts;
    }
}
