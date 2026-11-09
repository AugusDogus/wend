package dev.augie.wend;

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
import static org.junit.Assert.assertTrue;

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

    @Test @Config(qualifiers = "hdpi")
    public void aFullDockFitsThePx6ScreenWith48dpTargets() {
        int capacity = DockLayout.pinCapacity(480);
        List<String> packages = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            String packageName = "com.app" + i + ".launcher";
            TestApps.install(context, packageName, "App " + i);
            packages.add(packageName);
        }
        Dock dock = dock(TestApps.loadedCatalog(context), DockPins.parse(String.join(",", packages), capacity));
        int height = Ui.dp(context, 480);

        dock.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context, DockLayout.WIDTH_DP), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        dock.layout(0, 0, dock.getMeasuredWidth(), height);

        List<View> buttons = imageButtons(dock);
        assertEquals(capacity + 2, buttons.size());
        int target = Ui.dp(context, 48);
        for (View button : buttons) {
            assertTrue(button.getContentDescription() + " is " + button.getWidth() + " x " + button.getHeight() + " px",
                    button.getWidth() >= target && button.getHeight() >= target);
        }
        View settings = buttons.get(buttons.size() - 1);
        assertEquals("Settings", settings.getContentDescription());
        int contentBottom = height - dock.getPaddingBottom();
        assertTrue("Settings ends at " + settings.getBottom() + " px, below the dock's content at " + contentBottom,
                settings.getBottom() <= contentBottom);
    }

    private Dock dock(AppCatalog catalog, DockPins pins) {
        return new Dock(context, catalog, pins, new Dock.Actions() {
            @Override public void openApp(String packageName, View source) { }
            @Override public void showApps() { }
            @Override public void showSettings() { }
        });
    }

    /** The dock's buttons top to bottom, by their spoken labels. */
    private static List<String> buttons(View dock) {
        List<String> labels = new ArrayList<>();
        for (View button : imageButtons(dock)) labels.add(String.valueOf(button.getContentDescription()));
        return labels;
    }

    private static List<View> imageButtons(View view) {
        List<View> buttons = new ArrayList<>();
        if (view instanceof ImageButton) buttons.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) buttons.addAll(imageButtons(group.getChildAt(i)));
        }
        return buttons;
    }
}
