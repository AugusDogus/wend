package dev.augie.wend;

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
    private static final String MUSIC_APP = "musicApp";
    private static final String FLOATING_VIDEO = "floatingVideo";
    private static final String FLOATING_VIDEO_CORNER = "floatingVideoCorner";
    private static final String FLOATING_MAPS = "floatingMaps";
    private static final String FLOATING_MAPS_CORNER = "floatingMapsCorner";
    private static final String RESUME_GUIDANCE = "resumeGuidance";
    private static final String TAG = "Wend";

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

    /** Whether Wend presses Start in a Maps it reopened because Maps' process died while guiding. */
    boolean resumeGuidance() { return readBoolean(RESUME_GUIDANCE, true); }

    void setResumeGuidance(boolean enabled) { preferences.edit().putBoolean(RESUME_GUIDANCE, enabled).apply(); }

    /**
     * The corner the floating video last settled in. Top right by default: landscape guidance
     * keeps the next turn top left, the trip sheet bottom left, and its buttons bottom right.
     */
    FloatingPlacement.Corner floatingVideoCorner() {
        return readCorner(FLOATING_VIDEO_CORNER, FloatingPlacement.Corner.TOP_RIGHT);
    }

    void setFloatingVideoCorner(FloatingPlacement.Corner corner) {
        preferences.edit().putString(FLOATING_VIDEO_CORNER, corner.name()).apply();
    }

    /** The corner floating Maps last settled in; bottom right by default. */
    FloatingPlacement.Corner floatingMapsCorner() {
        return readCorner(FLOATING_MAPS_CORNER, FloatingPlacement.Corner.BOTTOM_RIGHT);
    }

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

    /**
     * The app the media panel opens and prefers among paused sessions. The build's default
     * ({@link BuildConfig#MUSIC_PACKAGE}) until one is chosen; an unreadable or non-package value
     * is logged and reads as that default, and is replaced the next time an app is chosen.
     */
    String musicApp() {
        String stored;
        try {
            stored = preferences.getString(MUSIC_APP, null);
        } catch (ClassCastException error) {
            Log.w(TAG, "Stored music app is not a package name; using " + BuildConfig.MUSIC_PACKAGE
                    + " until one is chosen again", error);
            return BuildConfig.MUSIC_PACKAGE;
        }
        if (stored == null) return BuildConfig.MUSIC_PACKAGE;
        if (!DockPins.isPackageName(stored)) {
            Log.w(TAG, "Stored music app \"" + stored + "\" is not an Android package name; using "
                    + BuildConfig.MUSIC_PACKAGE + " until one is chosen again");
            return BuildConfig.MUSIC_PACKAGE;
        }
        return stored;
    }

    void setMusicApp(String packageName) {
        if (!DockPins.isPackageName(packageName)) {
            throw new IllegalArgumentException("Cannot set the music app to \"" + packageName
                    + "\": it is not an Android package name");
        }
        preferences.edit().putString(MUSIC_APP, packageName).apply();
    }

    private FloatingPlacement.Corner readCorner(String key, FloatingPlacement.Corner fallback) {
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
