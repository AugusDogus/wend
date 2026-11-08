package dev.augie.roadhome;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class LauncherSettingsTest {
    private SharedPreferences preferences;
    private LauncherSettings settings;

    @Before public void openPreferences() {
        Context context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("layout", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        settings = new LauncherSettings(preferences);
    }

    @Test public void aNewInstallUsesTheDefaults() {
        assertEquals(LauncherSettings.MapSide.RIGHT, settings.mapSide());
        assertEquals(LauncherSettings.MapWidth.MORE_MAP, settings.mapWidth());
        assertTrue(settings.floatingVideo());
        assertTrue(settings.floatingMaps());
        assertEquals(DockPins.defaults(5).packages(), settings.dockPins(5).packages());
    }

    @Test public void existingInstallsKeepTheirLayoutChoice() {
        preferences.edit().putBoolean("mapsOnRight", false).putInt("mapPercent", 60).commit();
        assertEquals(LauncherSettings.MapSide.LEFT, settings.mapSide());
        assertEquals(LauncherSettings.MapWidth.BALANCED, settings.mapWidth());
    }

    @Test public void choicesAreStoredUnderTheExistingKeys() {
        settings.setMapSide(LauncherSettings.MapSide.LEFT);
        settings.setMapWidth(LauncherSettings.MapWidth.BALANCED);
        assertFalse(preferences.getBoolean("mapsOnRight", true));
        assertEquals(60, preferences.getInt("mapPercent", 0));
        settings.setMapSide(LauncherSettings.MapSide.RIGHT);
        settings.setMapWidth(LauncherSettings.MapWidth.MORE_MAP);
        assertTrue(preferences.getBoolean("mapsOnRight", false));
        assertEquals(70, preferences.getInt("mapPercent", 0));
    }

    @Test public void mapWidthsArePercentagesOfTheContent() {
        assertEquals(60, LauncherSettings.MapWidth.BALANCED.percent);
        assertEquals(70, LauncherSettings.MapWidth.MORE_MAP.percent);
    }

    @Test public void anUnknownMapPercentFallsBackToMoreMap() {
        preferences.edit().putInt("mapPercent", 55).commit();
        assertEquals(LauncherSettings.MapWidth.MORE_MAP, settings.mapWidth());
    }

    @Test public void wronglyTypedValuesFallBackToTheDefaults() {
        preferences.edit().putString("mapsOnRight", "left").putString("mapPercent", "60")
                .putBoolean("floatingVideo", true).putInt("floatingMaps", 0).putInt("dockPins", 3).commit();
        assertEquals(LauncherSettings.MapSide.RIGHT, settings.mapSide());
        assertEquals(LauncherSettings.MapWidth.MORE_MAP, settings.mapWidth());
        assertTrue(settings.floatingMaps());
        assertEquals(DockPins.defaults(5).packages(), settings.dockPins(5).packages());
    }

    @Test public void floatingWindowSwitchesUseTheirSharedKeys() {
        settings.setFloatingVideo(false);
        settings.setFloatingMaps(false);
        assertFalse(preferences.getBoolean("floatingVideo", true));
        assertFalse(preferences.getBoolean("floatingMaps", true));
        assertFalse(settings.floatingVideo());
        assertFalse(settings.floatingMaps());
    }

    @Test public void dockPinsSurviveARoundTrip() {
        DockPins pins = DockPins.parse("com.android.settings,com.google.android.youtube", 5);
        settings.setDockPins(pins);
        assertEquals("com.android.settings,com.google.android.youtube", preferences.getString("dockPins", null));
        assertEquals(pins.packages(), new LauncherSettings(preferences).dockPins(5).packages());
    }

    @Test public void anEmptiedDockStaysEmpty() {
        settings.setDockPins(DockPins.defaults(5).remove(BuildConfig.MAPS_PACKAGE).remove(DockPins.SPOTIFY));
        assertTrue(settings.dockPins(5).packages().isEmpty());
    }

    @Test public void garbageStoredPinsKeepTheirValidEntries() {
        preferences.edit().putString("dockPins", "com.a.a,,bad name,com.a.a,com.b.b").commit();
        assertEquals(Arrays.asList("com.a.a", "com.b.b"), settings.dockPins(5).packages());
    }
}
