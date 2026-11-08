package dev.augie.roadhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextClock;

public final class MainActivity extends Activity {
    private MapPanel maps;
    private MediaPanel media;
    private LinearLayout content;
    private SharedPreferences preferences;
    private AppDrawer drawer;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("layout", MODE_PRIVATE);
        AppCatalog.get(this); // Loads apps in the background so the drawer opens with them.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(Ui.wallpaper());
        int inset = BuildConfig.WINDOWED_MAPS ? getResources().getDimensionPixelSize(
                getResources().getIdentifier("status_bar_height", "dimen", "android")) : 0;
        root.setPadding(0, inset, 0, 0);
        root.addView(createDock(), new LinearLayout.LayoutParams(Ui.dp(this, 60), -1));
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

    private LinearLayout createDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);
        dock.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 4));
        dock.setBackgroundColor(0xFF15242E);
        TextClock clock = new TextClock(this);
        if (BuildConfig.WINDOWED_MAPS) clock.setVisibility(View.GONE);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Ui.TEXT);
        clock.setTextSize(18);
        clock.setGravity(Gravity.CENTER);
        dock.addView(clock, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE d");
        date.setFormat24Hour("EEE d");
        date.setTextColor(Ui.MUTED);
        date.setTextSize(12);
        date.setGravity(Gravity.CENTER);
        dock.addView(date, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));
        dock.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        ImageButton mapButton = Ui.iconButton(this, Ui.Icon.MAP, "Google Maps", this::openMapsFullscreen);
        setAppIcon(mapButton, EmbeddedMaps.PACKAGE);
        dock.addView(mapButton, dockButtonLayout());
        ImageButton musicButton = Ui.iconButton(this, Ui.Icon.MUSIC, "Spotify", () -> AppDrawer.open(this, "com.spotify.music"));
        setAppIcon(musicButton, "com.spotify.music");
        dock.addView(musicButton, dockButtonLayout());
        dock.addView(Ui.iconButton(this, Ui.Icon.APPS, "Apps", this::showApps), dockButtonLayout());
        dock.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        dock.addView(Ui.iconButton(this, Ui.Icon.SETTINGS, "Layout and settings", this::showSettings), dockButtonLayout());
        return dock;
    }

    private LinearLayout.LayoutParams dockButtonLayout() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        layout.topMargin = Ui.dp(this, 4);
        return layout;
    }

    private void setAppIcon(ImageButton button, String packageName) {
        try {
            button.setImageDrawable(new AppIcon(getPackageManager().getApplicationIcon(packageName)));
            int padding = Ui.dp(this, 4);
            button.setPadding(padding, padding, padding, padding);
        }
        catch (PackageManager.NameNotFoundException error) {
            android.util.Log.i("RoadHome", "Using fallback icon for unavailable app " + packageName);
        }
    }

    private void showApps() {
        if (drawer == null) drawer = new AppDrawer(this, AppCatalog.get(this));
        drawer.show();
    }

    private void openMusic() { AppDrawer.open(this, media.musicPackage()); }
    void openMapsFullscreen() { maps.openFullscreen(); }
    boolean openVideoFullscreen(Runnable open) { return media.openVideoFullscreen(open); }
    // A virtual display can keep Home started behind another fullscreen app on Android 10.
    // Remove native Maps overlays explicitly instead of relying on onStop.
    void externalAppOpening() { maps.suspend(); media.suspendVideo(); }
    void externalAppFailed() { maps.resume(); media.resumeVideo(); }
    void videoOpened() { media.videoOpened(); }

    private void arrangePanels() {
        content.removeAllViews();
        int percent = preferences.getInt("mapPercent", 70);
        if (percent != 60 && percent != 70) percent = 70;
        LinearLayout.LayoutParams mapLayout = new LinearLayout.LayoutParams(0, -1, percent);
        LinearLayout.LayoutParams mediaLayout = new LinearLayout.LayoutParams(0, -1, 100 - percent);
        boolean mapsOnRight = preferences.getBoolean("mapsOnRight", true);
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
        boolean onRight = preferences.getBoolean("mapsOnRight", true);
        String[] choices = {onRight ? "Move Maps to the left" : "Move Maps to the right",
                "Map size", "Android settings", "Choose home app"};
        showLauncherDialog(new AlertDialog.Builder(this).setTitle("Road Home").setItems(choices, (dialog, which) -> {
            switch (which) {
                case 0:
                    preferences.edit().putBoolean("mapsOnRight", !onRight).apply();
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
        int selected = preferences.getInt("mapPercent", 70) == 60 ? 0 : 1;
        showLauncherDialog(new AlertDialog.Builder(this).setTitle("Map size")
                .setSingleChoiceItems(new String[]{"Balanced (60%)", "More map (70%)"}, selected, (dialog, which) -> {
                    preferences.edit().putInt("mapPercent", which == 0 ? 60 : 70).apply();
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
