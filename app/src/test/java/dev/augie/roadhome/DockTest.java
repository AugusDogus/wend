package dev.augie.roadhome;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DockTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test @Config(shadows = UnavailableLauncherApps.class)
    public void aFailedAppListStillShowsTheInstalledPins() {
        TestApps.installInPackageManager(context, BuildConfig.MAPS_PACKAGE, "Maps");
        AppCatalog catalog = TestApps.loadedCatalog(context);
        assertEquals(AppCatalog.Status.FAILED, catalog.status());

        Dock dock = dock(catalog, DockPins.defaults(5));

        // Spotify is not installed, so only Maps is pinned beside the fixed buttons.
        assertEquals(Arrays.asList("Maps", "Apps", "Settings"), buttons(dock));
    }

    @Test @Config(shadows = UnavailableLauncherApps.class)
    public void retryingAFailedAppListKeepsTheDockButtons() {
        TestApps.installInPackageManager(context, BuildConfig.MAPS_PACKAGE, "Maps");
        AppCatalog catalog = TestApps.loadedCatalog(context);
        Dock dock = dock(catalog, DockPins.defaults(5));

        catalog.retryIfFailed();

        assertEquals(AppCatalog.Status.LOADING, catalog.status());
        assertEquals(Arrays.asList("Maps", "Apps", "Settings"), buttons(dock));
    }

    private Dock dock(AppCatalog catalog, DockPins pins) {
        return new Dock(context, catalog, pins, new Dock.Actions() {
            @Override public void openApp(String packageName) { }
            @Override public void showApps() { }
            @Override public void showSettings() { }
        });
    }

    /** The dock's buttons top to bottom, by their spoken labels. */
    private static List<String> buttons(View view) {
        List<String> labels = new ArrayList<>();
        if (view instanceof ImageButton) labels.add(String.valueOf(view.getContentDescription()));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) labels.addAll(buttons(group.getChildAt(i)));
        }
        return labels;
    }
}
