package dev.augie.roadhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

public final class MainActivity extends Activity {
    /** "layout" preference: live video keeps playing in a floating window over fullscreen Maps. */
    static final String FLOATING_VIDEO = "floatingVideo";
    /** "layout" preference: the corner the floating video last settled in. */
    static final String FLOATING_VIDEO_CORNER = "floatingVideoCorner";
    private MapPanel maps;
    private MediaPanel media;
    private LinearLayout content;
    private LauncherSettings settings;
    private SharedPreferences preferences;
    private Dock dock;
    private int dockCapacity;
    private FrostedBackdrop backdrop;
    private AppDrawer drawer;
    private SettingsSheet settingsSheet;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(LauncherSettings.PREFERENCES, MODE_PRIVATE);
        settings = new LauncherSettings(preferences);
        AppCatalog.get(this); // Loads apps in the background so the drawer opens with them.
        backdrop = new FrostedBackdrop(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(Ui.wallpaper());
        int inset = BuildConfig.WINDOWED_MAPS ? getResources().getDimensionPixelSize(
                getResources().getIdentifier("status_bar_height", "dimen", "android")) : 0;
        root.setPadding(0, inset, 0, 0);
        dockCapacity = dockCapacity(this, inset);
        dock = new Dock(this, AppCatalog.get(this), settings.dockPins(dockCapacity), new Dock.Actions() {
            @Override public void openApp(String packageName) { AppLaunch.open(MainActivity.this, packageName); }
            @Override public void showApps() { MainActivity.this.showApps(); }
            @Override public void showSettings() { MainActivity.this.showSettings(); }
        });
        root.addView(dock, new LinearLayout.LayoutParams(Ui.dp(this, DockLayout.WIDTH_DP), -1));
        View dockDivider = new View(this);
        dockDivider.setBackgroundColor(0x20FFFFFF);
        root.addView(dockDivider, new LinearLayout.LayoutParams(1, -1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(0, -1, 1);
        body.leftMargin = 0;
        root.addView(content, body);
        maps = BuildConfig.WINDOWED_MAPS ? new WindowedMaps(this) : new EmbeddedMaps(this);
        media = new MediaPanel(this, this::openMusic);
        // The dock and the map side are places in the car, not reading order, so the row of
        // panels never mirrors; each panel still lays out its own content for the locale.
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (View panel : new View[]{dock, maps, media}) panel.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        arrangePanels();
        setContentView(root);
        // Build the sheets and their tiles when the launcher is idle, not on the first tap.
        Looper.myQueue().addIdleHandler(() -> {
            if (drawer == null && !isDestroyed()) drawer = new AppDrawer(this, AppCatalog.get(this), backdrop);
            return false;
        });
        Looper.myQueue().addIdleHandler(() -> {
            if (settingsSheet == null && !isDestroyed()) settingsSheet = createSettingsSheet();
            return false;
        });
    }

    private static int dockCapacity(Activity activity, int topInset) {
        DisplayMetrics real = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(real);
        // The launcher is landscape-only, so the dock spans the display's shorter side.
        int height = Math.min(real.widthPixels, real.heightPixels) - topInset;
        return DockLayout.pinCapacity(Math.round(height / real.density));
    }

    private void showApps() {
        if (drawer == null) drawer = new AppDrawer(this, AppCatalog.get(this), backdrop);
        drawer.show();
    }

    private void openMusic() { AppLaunch.open(this, media.musicPackage()); }
    private final LiveSurfaceWindow.Listener floatingVideo = new LiveSurfaceWindow.Listener() {
        @Override public void settled(FloatingPlacement.Corner corner) {
            preferences.edit().putString(FLOATING_VIDEO_CORNER, corner.name()).apply();
        }
        @Override public void tapped() { media.expandVideo(); }
    };

    void openMapsFullscreen() {
        maps.openFullscreen(() -> {
            if (!preferences.getBoolean(FLOATING_VIDEO, true)) return null;
            return media.floatVideo(FloatingPlacement.Corner.parse(
                    preferences.getString(FLOATING_VIDEO_CORNER, null), FloatingPlacement.Corner.BOTTOM_RIGHT),
                    floatingVideo);
        });
    }
    boolean openVideoFullscreen(Runnable open) { return media.openVideoFullscreen(open); }
    // A virtual display can keep Home started behind another fullscreen app on Android 10.
    // Remove native Maps overlays explicitly instead of relying on onStop.
    void externalAppOpening() { maps.suspend(); media.suspendVideo(); }
    void externalAppFailed() { maps.resume(); media.resumeVideo(); }
    void videoOpened() { media.videoOpened(); }

    private void arrangePanels() {
        content.removeAllViews();
        int percent = settings.mapWidth().percent;
        LinearLayout.LayoutParams mapLayout = new LinearLayout.LayoutParams(0, -1, percent);
        LinearLayout.LayoutParams mediaLayout = new LinearLayout.LayoutParams(0, -1, 100 - percent);
        boolean mapsOnRight = settings.mapSide() == LauncherSettings.MapSide.RIGHT;
        maps.setInnerCorners(mapsOnRight);
        if (mapsOnRight) {
            content.addView(media, mediaLayout);
            content.addView(maps, mapLayout);
        } else {
            content.addView(maps, mapLayout);
            content.addView(media, mediaLayout);
        }
    }

    private void showSettings() {
        if (settingsSheet == null) settingsSheet = createSettingsSheet();
        settingsSheet.show();
    }

    private SettingsSheet createSettingsSheet() {
        SettingsSheet.Host host = new SettingsSheet.Host() {
            @Override public void onLayoutChanged() { arrangePanels(); }
            @Override public void onDockChanged(DockPins pins) { dock.setPins(pins); }
        };
        return new SettingsSheet(this, backdrop, AppCatalog.get(this), settings, dockCapacity, host);
    }

    @Override protected void onStart() { super.onStart(); overridePendingTransition(0, 0); maps.resume(); media.start(); }
    @Override protected void onStop() {
        if (drawer != null) drawer.dismiss();
        if (settingsSheet != null) settingsSheet.dismiss();
        maps.suspend();
        media.stop();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (drawer != null) drawer.release();
        if (settingsSheet != null) settingsSheet.release();
        backdrop.release();
        dock.release();
        media.release();
        maps.release();
        super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Home slides an open sheet away.
        if (drawer != null) drawer.close();
        if (settingsSheet != null) settingsSheet.close();
        hideSystemBars();
        // Moving an embedded task fullscreen can leave Home started. Its return intent
        // must restore Maps even when Android does not deliver another onStart.
        maps.resume();
        media.resumeVideo();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }
    @Override public void onBackPressed() { hideSystemBars(); }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
