package dev.augie.roadhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

public final class MainActivity extends Activity {
    private MapPanel maps;
    private MediaPanel media;
    private LinearLayout content;
    private LauncherSettings settings;
    private Dock dock;
    private int dockCapacity;
    private AppDrawer drawer;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new LauncherSettings(getSharedPreferences(LauncherSettings.PREFERENCES, MODE_PRIVATE));
        AppCatalog.get(this); // Loads apps in the background so the drawer opens with them.
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
        arrangePanels();
        setContentView(root);
        // Build the drawer and its tiles when the launcher is idle, not on the first tap.
        Looper.myQueue().addIdleHandler(() -> {
            if (drawer == null && !isDestroyed()) drawer = new AppDrawer(this, AppCatalog.get(this));
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
        if (drawer == null) drawer = new AppDrawer(this, AppCatalog.get(this));
        drawer.show();
    }

    private void openMusic() { AppLaunch.open(this, media.musicPackage()); }
    void openMapsFullscreen() { maps.openFullscreen(); }
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
        boolean onRight = settings.mapSide() == LauncherSettings.MapSide.RIGHT;
        String[] choices = {onRight ? "Move Maps to the left" : "Move Maps to the right",
                "Map size", "Android settings", "Choose home app"};
        showLauncherDialog(new AlertDialog.Builder(this).setTitle("Road Home").setItems(choices, (dialog, which) -> {
            switch (which) {
                case 0:
                    settings.setMapSide(onRight ? LauncherSettings.MapSide.LEFT : LauncherSettings.MapSide.RIGHT);
                    arrangePanels();
                    break;
                case 1: chooseMapSize(); break;
                case 2: startActivity(new Intent(Settings.ACTION_SETTINGS)); break;
                case 3: startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); break;
                default: throw new IllegalArgumentException("Unexpected settings selection: " + which);
            }
        }).setNegativeButton("Close", null).create());
    }

    private void chooseMapSize() {
        int selected = settings.mapWidth() == LauncherSettings.MapWidth.BALANCED ? 0 : 1;
        showLauncherDialog(new AlertDialog.Builder(this).setTitle("Map size")
                .setSingleChoiceItems(new String[]{"Balanced (60%)", "More map (70%)"}, selected, (dialog, which) -> {
                    settings.setMapWidth(which == 0 ? LauncherSettings.MapWidth.BALANCED : LauncherSettings.MapWidth.MORE_MAP);
                    arrangePanels();
                    dialog.dismiss();
                }).setNegativeButton("Close", null).create());
    }

    private void showLauncherDialog(AlertDialog dialog) {
        if (BuildConfig.WINDOWED_MAPS && dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        dialog.show();
    }

    @Override protected void onStart() { super.onStart(); overridePendingTransition(0, 0); maps.resume(); media.start(); }
    @Override protected void onStop() {
        if (drawer != null) drawer.dismiss();
        maps.suspend();
        media.stop();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (drawer != null) drawer.release();
        dock.release();
        media.release();
        maps.release();
        super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (drawer != null) drawer.close(); // Home slides an open drawer away.
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
