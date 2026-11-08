package dev.augie.roadhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

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

    /** Configured as the settings sheet's Add app page. */
    private AppBrowser picker(AppCatalog catalog, DockPins pins) {
        AppBrowser picker = new AppBrowser(activity, catalog, AppCatalog::canPin, "Search apps to add",
                R.string.every_app_pinned, R.string.matching_app_pinned, R.string.matching_apps_pinned, app -> { });
        picker.setHidden(app -> pins.contains(app.key.packageName));
        return picker;
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
