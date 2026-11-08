package dev.augie.roadhome;

import android.app.Activity;
import android.os.Looper;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DockEditorTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

    @Test public void pinsReadAsLoadingRatherThanNotInstalledWhileTheAppListLoads() {
        TestApps.install(activity, BuildConfig.MAPS_PACKAGE, "Maps");
        AppCatalog catalog = new AppCatalog(activity, Looper.getMainLooper());
        assertEquals(AppCatalog.Status.LOADING, catalog.status());

        DockEditor editor = editor(catalog, DockPins.defaults(5));

        List<String> texts = visibleTexts(editor.view());
        assertFalse(texts.toString(), texts.contains("Not installed"));
        assertEquals(texts.toString(), 2, Collections.frequency(texts, "Loading…"));
    }

    @Test @Config(shadows = UnavailableLauncherApps.class)
    public void aFailedAppListStillNamesInstalledPins() {
        TestApps.installInPackageManager(activity, BuildConfig.MAPS_PACKAGE, "Maps");
        AppCatalog catalog = TestApps.loadedCatalog(activity);

        DockEditor editor = editor(catalog, DockPins.defaults(5));

        List<String> texts = visibleTexts(editor.view());
        assertTrue(texts.toString(), texts.contains("Maps"));
        // Spotify is missing from PackageManager too, so it really is not installed.
        assertEquals(texts.toString(), 1, Collections.frequency(texts, "Not installed"));
    }

    private DockEditor editor(AppCatalog catalog, DockPins pins) {
        return new DockEditor(activity, catalog, pins, new DockEditor.Host() {
            @Override public void onPinsChanged(DockPins next) { }
            @Override public void onAddRequested() { }
        });
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
