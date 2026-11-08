package dev.augie.roadhome;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * Typed access to the launcher's choices in SharedPreferences "layout". The keys predate
 * this class, so existing installs keep their layout. An unreadable or unknown stored value
 * is logged and reads as the default; it is replaced the next time that choice is saved.
 */
final class LauncherSettings {
    enum MapSide { LEFT, RIGHT }

    enum MapWidth {
        BALANCED(60), MORE_MAP(70);

        /** The map's share of the width beside the dock. */
        final int percent;

        MapWidth(int percent) { this.percent = percent; }
    }

    static final String PREFERENCES = "layout";
    private static final String MAPS_ON_RIGHT = "mapsOnRight";
    private static final String MAP_PERCENT = "mapPercent";
    private static final String DOCK_PINS = "dockPins";
    private static final String FLOATING_VIDEO = "floatingVideo";
    private static final String FLOATING_VIDEO_CORNER = "floatingVideoCorner";
    private static final String FLOATING_MAPS = "floatingMaps";
    private static final String FLOATING_MAPS_CORNER = "floatingMapsCorner";
    private static final String TAG = "RoadHome";

    private final SharedPreferences preferences;

    LauncherSettings(SharedPreferences preferences) { this.preferences = preferences; }

    MapSide mapSide() { return readBoolean(MAPS_ON_RIGHT, true) ? MapSide.RIGHT : MapSide.LEFT; }

    void setMapSide(MapSide side) { preferences.edit().putBoolean(MAPS_ON_RIGHT, side == MapSide.RIGHT).apply(); }

    MapWidth mapWidth() {
        int percent;
        try {
            percent = preferences.getInt(MAP_PERCENT, MapWidth.MORE_MAP.percent);
        } catch (ClassCastException error) {
            Log.w(TAG, "Stored map width is not a number; using " + MapWidth.MORE_MAP.percent
                    + "% until it is chosen again", error);
            return MapWidth.MORE_MAP;
        }
        for (MapWidth width : MapWidth.values()) if (width.percent == percent) return width;
        Log.w(TAG, "Stored map width " + percent + "% is not offered; using " + MapWidth.MORE_MAP.percent + "%");
        return MapWidth.MORE_MAP;
    }

    void setMapWidth(MapWidth width) { preferences.edit().putInt(MAP_PERCENT, width.percent).apply(); }

    /** Whether playing video floats in a small window while Maps is fullscreen. */
    boolean floatingVideo() { return readBoolean(FLOATING_VIDEO, true); }

    void setFloatingVideo(boolean enabled) { preferences.edit().putBoolean(FLOATING_VIDEO, enabled).apply(); }

    /** Whether Maps floats in a small window while a video is fullscreen. */
    boolean floatingMaps() { return readBoolean(FLOATING_MAPS, true); }

    void setFloatingMaps(boolean enabled) { preferences.edit().putBoolean(FLOATING_MAPS, enabled).apply(); }

    /** The corner the floating video last settled in. */
    FloatingPlacement.Corner floatingVideoCorner() { return readCorner(FLOATING_VIDEO_CORNER); }

    void setFloatingVideoCorner(FloatingPlacement.Corner corner) {
        preferences.edit().putString(FLOATING_VIDEO_CORNER, corner.name()).apply();
    }

    /** The corner floating Maps last settled in. */
    FloatingPlacement.Corner floatingMapsCorner() { return readCorner(FLOATING_MAPS_CORNER); }

    void setFloatingMapsCorner(FloatingPlacement.Corner corner) {
        preferences.edit().putString(FLOATING_MAPS_CORNER, corner.name()).apply();
    }

    /** The stored pins, limited to {@code capacity}; the defaults if none were ever saved. */
    DockPins dockPins(int capacity) {
        String stored;
        try {
            stored = preferences.getString(DOCK_PINS, null);
        } catch (ClassCastException error) {
            Log.w(TAG, "Stored dock apps are unreadable; showing the default dock until it is edited", error);
            return DockPins.defaults(capacity);
        }
        DockPins pins = DockPins.parse(stored, capacity);
        if (stored != null && !pins.serialize().equals(stored)) {
            Log.w(TAG, "Ignored invalid, repeated or surplus dock entries in \"" + stored + "\"; the dock shows "
                    + pins.packages() + " (room for " + capacity + ")");
        }
        return pins;
    }

    void setDockPins(DockPins pins) { preferences.edit().putString(DOCK_PINS, pins.serialize()).apply(); }

    private FloatingPlacement.Corner readCorner(String key) {
        FloatingPlacement.Corner fallback = FloatingPlacement.Corner.BOTTOM_RIGHT;
        String stored;
        try {
            stored = preferences.getString(key, null);
        } catch (ClassCastException error) {
            Log.w(TAG, "Stored " + key + " is not a corner name; using " + fallback + " until it is moved again", error);
            return fallback;
        }
        FloatingPlacement.Corner corner = FloatingPlacement.Corner.parse(stored, fallback);
        if (stored != null && !corner.name().equals(stored)) {
            Log.w(TAG, "Stored " + key + " \"" + stored + "\" is not a corner; using " + fallback + " until it is moved again");
        }
        return corner;
    }

    private boolean readBoolean(String key, boolean fallback) {
        try {
            return preferences.getBoolean(key, fallback);
        } catch (ClassCastException error) {
            Log.w(TAG, "Stored setting " + key + " is not on/off; using " + fallback
                    + " until it is chosen again", error);
            return fallback;
        }
    }
}
