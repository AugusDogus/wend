package dev.augie.wend;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SettingsSheetTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    private FrostedBackdrop backdrop;
    private SettingsSheet sheet;
    private int layoutChanges;

    @Before public void openSettings() {
        LauncherSettings settings = new LauncherSettings(
                activity.getSharedPreferences(LauncherSettings.PREFERENCES, Context.MODE_PRIVATE));
        backdrop = new FrostedBackdrop(activity);
        sheet = new SettingsSheet(activity, backdrop, TestApps.loadedCatalog(activity), settings, 5,
                new SettingsSheet.Host() {
                    @Override public void onLayoutChanged() { layoutChanges++; }
                    @Override public void onDockChanged(DockPins pins) { }
                    @Override public void onMusicAppChanged() { }
                });
        sheet.show();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void release() {
        sheet.release();
        backdrop.release();
    }

    @Test public void aMapSideChoiceRearrangesTheLauncherWithoutCoveringTheSheet() {
        Dialog window = ShadowDialog.getLatestDialog();
        click(window.getWindow().getDecorView(), "Left");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LayoutPreview.MORPH_MS + 100));

        if (BuildConfig.WINDOWED_MAPS) {
            // Windowed Maps re-adds its corner overlay windows on relayout; above the open sheet
            // they would cover it, so the launcher changes once the sheet's window is gone.
            assertEquals(0, layoutChanges);
            sheet.close();
            assertEquals("Not while the sheet slides away", 0, layoutChanges);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(window.isShowing());
        }
        assertEquals(1, layoutChanges);
    }

    @Test public void choosingAMusicAppStoresItAndRelabelsTheMediaPanel() {
        TestApps.install(activity, "com.example.player", "Player");
        LauncherSettings settings = new LauncherSettings(
                activity.getSharedPreferences(LauncherSettings.PREFERENCES, Context.MODE_PRIVATE));
        int[] musicChanges = {0};
        SettingsSheet musicSheet = new SettingsSheet(activity, backdrop, TestApps.loadedCatalog(activity), settings, 5,
                new SettingsSheet.Host() {
                    @Override public void onLayoutChanged() { }
                    @Override public void onDockChanged(DockPins pins) { }
                    @Override public void onMusicAppChanged() { musicChanges[0]++; }
                });
        try {
            musicSheet.show();
            shadowOf(Looper.getMainLooper()).idle();
            Dialog window = ShadowDialog.getLatestDialog();
            View decor = window.getWindow().getDecorView();
            // The default music app is not installed here, so the row shows its package name.
            View musicRow = findByDescription(decor, "Music app");
            assertNotNull("No music app row in the settings sheet", musicRow);
            musicRow.performClick();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            click(decor, "Player"); // The picker reuses the dock's AppBrowser grid.
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("com.example.player", settings.musicApp());
            assertEquals(1, musicChanges[0]);
        } finally {
            musicSheet.release();
        }
    }

    private static View findByDescription(View view, String prefix) {
        if (view.getVisibility() != View.VISIBLE) return null;
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().startsWith(prefix)) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findByDescription(group.getChildAt(i), prefix);
            if (found != null) return found;
        }
        return null;
    }

    private static void click(View root, String label) {
        View target = find(root, label);
        assertNotNull("No visible \"" + label + "\" in the settings sheet", target);
        target.performClick();
    }

    private static View find(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = find(group.getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
}
