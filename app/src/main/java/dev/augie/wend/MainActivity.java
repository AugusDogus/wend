package dev.augie.wend;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
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
    private FrostedBackdrop backdrop;
    private AppDrawer drawer;
    private SettingsSheet settingsSheet;
    private boolean resumed;
    /** The Maps or video task now in its own native PiP, or -1: Home takes it back, and only its expansion swaps. */
    private int pinnedByWend = -1;
    /**
     * How long Maps, moved fullscreen on the main display, is left to resume into active navigation
     * before the video covers it for Case B. Covered sooner, Maps is still starting (not RESUMED),
     * so Android does not deliver onUserLeaveHint and Maps never enters its own nav PiP. Real Maps
     * took 0.6 to 0.9 s to lay out on this loaded emulator.
     */
    private static final long PIP_SETTLE_MS = 900;
    private final SystemPip.Watcher pip = new SystemPip.Watcher(new SystemPip.Watcher.Listener() {
        @Override public void pinned(String packageName, int taskId) {
            if (packageName.equals(EmbeddedMaps.PACKAGE) || packageName.equals(EmbeddedVideo.PACKAGE)) {
                pinnedByWend = taskId;
            }
        }
        @Override public void unpinned(String packageName, int taskId) { pipUnpinned(packageName, taskId); }
    });
    private final LiveSurfaceWindow.Listener floatingVideo = new LiveSurfaceWindow.Listener() {
        @Override public void settled(FloatingPlacement.Corner corner) {
            settings.setFloatingVideoCorner(corner);
        }
        @Override public void tapped() { swapToVideo(); }
    };
    private final LiveSurfaceWindow.Listener floatingMaps = new LiveSurfaceWindow.Listener() {
        @Override public void settled(FloatingPlacement.Corner corner) {
            settings.setFloatingMapsCorner(corner);
        }
        @Override public void tapped() { swapToMaps(); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new LauncherSettings(getSharedPreferences(LauncherSettings.PREFERENCES, MODE_PRIVATE));
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
            @Override public void openApp(String packageName, View source) { AppLaunch.open(MainActivity.this, packageName, source); }
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
        media = new MediaPanel(this, this::openMusic, this::openVideo, () -> maps.returnFromFloating());
        // The dock and the map side are places in the car, not reading order, so the row of
        // panels never mirrors; each panel still lays out its own content for the locale.
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (View panel : new View[]{dock, maps, media}) panel.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        arrangePanels();
        setContentView(root);
        if (BuildConfig.SYSTEM_PIP) {
            try { pip.start(); }
            catch (ReflectiveOperationException | RuntimeException error) {
                Log.e("WendPip", "Cannot watch picture-in-picture: expanding one app from PiP will not put"
                        + " the other in PiP. Home still returns both to Wend", error);
            }
        }
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
        media.videoCardCovered();
        if (drawer == null) drawer = new AppDrawer(this, AppCatalog.get(this), backdrop);
        drawer.show();
    }

    private void openMusic() { AppLaunch.open(this, media.musicPackage()); }

    /** The video card's app, even while another app owns the media controls. */
    private void openVideo() { AppLaunch.open(this, EmbeddedVideo.PACKAGE); }

    /**
     * The floating video grows over fullscreen Maps. Hidden beneath it, Maps returns to its
     * panel's display and Wend comes forward; Maps then emerges floating when allowed.
     */
    private void swapToVideo() {
        media.expandVideo(() -> maps.returnBeneathVideo(settings.floatingMaps()
                ? () -> floatMaps(LiveSurfaceWindow.Entry.EMERGING) : null));
    }

    void openMapsFullscreen() {
        // Maps sweeps over the card before Wend stops.
        media.videoCardCovered();
        if (BuildConfig.SYSTEM_PIP) {
            // Native PiP: the live video floats in its own player PiP. The video goes fullscreen
            // first and allows its PiP op; Maps then covers it with a direct move (so Wend stopping
            // under the fullscreen video cannot cancel it), and the video enters its own PiP on the
            // task switch. Decided at the tap: Wend can be paused by the time Maps has moved.
            if (resumed && settings.floatingVideo() && media.videoLive()) {
                // With the pip-hook module the video floats straight from its card first; without
                // it, by the task switch below.
                media.floatVideoFromCardIntoPip(new PanelPip.Result() {
                    @Override public void entered() { maps.coverVideoNativePip(); }
                    @Override public void stayed(String why) {
                        if (media.beginVideoNativePipFloat()) maps.coverVideoNativePip();
                        else openMapsPlainly();
                    }
                });
                return;
            }
            openMapsPlainly();
            return;
        }
        maps.openFullscreen(new MapPanel.Covering() {
            @Override public Rect starting() {
                // An app that came to the front while Maps prepared has paused Wend, before
                // the video could watch for it: nothing floats above that app.
                FloatingPlacement.Corner corner = resumed ? videoCorner() : null;
                return corner == null ? null : media.floatVideo(corner, floatingVideo);
            }
            // Wend still shows: a video that floated for Maps returns to its card.
            @Override public void abandoned() { media.homeVideo(); media.videoCardShown(); }
        });
    }

    /** Maps takes the screen with nothing floating above it. */
    private void openMapsPlainly() {
        maps.openFullscreen(new MapPanel.Covering() {
            @Override public Rect starting() { return null; }
            @Override public void abandoned() { media.videoCardShown(); }
        }, false);
    }

    /**
     * The driver took the app's own PiP out of it. Expanded (its menu's fullscreen button or a
     * double tap), it is now the fullscreen app on the main display, so the other app floats in its
     * own native PiP above it: it goes fullscreen, then the expanded app covers it again. Closed or
     * flung away, or returned by Home, nothing follows.
     */
    private void pipUnpinned(String packageName, int taskId) {
        if (taskId != pinnedByWend) return;
        pinnedByWend = -1;
        try {
            if (!SystemPip.showsFullscreen(taskId)) return;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("WendPip", "Could not read whether " + packageName + " (task " + taskId + ") expanded"
                    + " from its PiP; the other app stays where it is and Home returns both to Wend", error);
            return;
        }
        if (packageName.equals(EmbeddedVideo.PACKAGE)) {
            // The video's player PiP expanded: float Maps in its nav PiP above the fullscreen video.
            if (settings.floatingMaps() && maps.beginNativePipFloat()) media.coverWithVideo();
        } else if (packageName.equals(EmbeddedMaps.PACKAGE) && settings.floatingVideo()) {
            // Maps' nav PiP expanded: float the video in its player PiP above fullscreen Maps.
            if (media.beginVideoNativePipFloat()) maps.coverVideoNativePip();
        }
    }

    /** Where the video floats over fullscreen Maps, or null when it should not float. */
    private FloatingPlacement.Corner videoCorner() {
        return settings.floatingVideo() ? settings.floatingVideoCorner() : null;
    }

    /**
     * Floating Maps takes the screen as fullscreen Maps. Under a frozen frame, the fullscreen video
     * steps aside, then emerges floating above Maps when allowed.
     */
    private void swapToMaps() {
        maps.openFullscreenFromFloating(new MapPanel.Takeover() {
            @Override public void covered() { media.videoBeneathMaps(videoCorner(), floatingVideo); }
            @Override public void revealed() { media.emergeVideo(); }
            // Above the video app Wend is stopped, and the video stays where it is.
            @Override public void failed() { if (resumed) media.homeVideo(); }
        });
    }

    /** Live video expands from its card; Maps then leaves its panel for a corner above it. */
    boolean openVideoFullscreen(Runnable open) {
        // With Android's PiP, the video app opens directly on the main display (videoOpened).
        if (BuildConfig.SYSTEM_PIP) return false;
        if (!media.openVideoFullscreen(open, () -> maps.leavePanel())) return false;
        if (media.videoPresenting()) floatMaps(LiveSurfaceWindow.Entry.HELD_AT_CARD);
        return true;
    }

    private void floatMaps(LiveSurfaceWindow.Entry entry) {
        if (settings.floatingMaps()) maps.floatAboveVideo(settings.floatingMapsCorner(), floatingMaps, entry);
    }

    // A virtual display can keep Home started behind another fullscreen app on Android 10.
    // Remove native Maps overlays explicitly instead of relying on onStop.
    void externalAppOpening() { maps.suspend(); media.suspendVideo(); }
    void externalAppFailed() { maps.resume(); media.resumeVideo(); }

    /**
     * Wend opens the video app itself on the main display: live Maps floats above it when allowed.
     * Under Android's PiP, Maps first enters its own nav PiP straight from its panel; without the
     * pip-hook module that enables it, Maps floats by the task-switch path instead.
     *
     * @param openApp opens the video app plainly on the main display (it reports videoOpened or
     *     externalAppFailed itself); run unless a live video is placed fullscreen instead
     */
    void videoAppOpening(Runnable openApp) {
        if (BuildConfig.SYSTEM_PIP && resumed && settings.floatingMaps()) {
            maps.floatFromPanelIntoPip(new PanelPip.Result() {
                @Override public void entered() { openVideoUnderMapsPip(openApp); }
                @Override public void stayed(String why) { floatMapsByTaskSwitch(openApp); }
            });
            return;
        }
        externalAppOpening();
        if (!BuildConfig.SYSTEM_PIP && settings.floatingMaps()) {
            maps.floatAboveVideoApp(settings.floatingMapsCorner(), floatingMaps);
        }
        openApp.run();
    }

    /**
     * Maps is already shrinking from its panel into its own PiP: the video takes the screen below it,
     * the live video as it plays (placed fullscreen), otherwise the video app.
     */
    private void openVideoUnderMapsPip(Runnable openApp) {
        // Capture the live video before suspending, since suspend clears its live state.
        boolean placed = media.videoLive() && media.placeVideoFullscreen();
        externalAppOpening();
        if (placed) videoOpened();
        else openApp.run();
    }

    /**
     * Without the pip-hook module, Maps can only enter its own PiP when it is the fullscreen, resumed
     * app on display 0 and another task is brought over it:
     *   1. Place the live video fullscreen on display 0 as its own task (its op stays ignore).
     *   2. Bring Maps fullscreen on display 0 on top of the video, allowing Maps' PiP op.
     *   3. Cover Maps with the video via a plain task switch (not startActivityFromRecents,
     *      whose Home-stack reorder would stop Maps being flagged): Maps backgrounds as the
     *      top activity and enters its own nav PiP via onUserLeaveHint.
     * Without a live video, Maps goes fullscreen and the video app opened over it does step 3.
     */
    private void floatMapsByTaskSwitch(Runnable openApp) {
        // Capture the live video before suspending, since suspend clears its live state.
        if (media.videoLive() && media.placeVideoFullscreen()) {
            externalAppOpening();
            if (maps.beginNativePipFloat()) {
                videoOpened();
                // Let Maps resume into active navigation on display 0 before the video covers
                // it. Covered while still starting, Maps is not yet RESUMED, so Android does not
                // deliver onUserLeaveHint and Maps never enters its own PiP.
                content.postDelayed(media::coverMapsIntoPip, PIP_SETTLE_MS);
                return;
            }
            // Maps did not float: the video is already fullscreen, so leave it fullscreen
            // plainly. Maps returns to its panel on Home and restores its op there.
            videoOpened();
            return;
        }
        externalAppOpening();
        maps.beginNativePipFloat();
        openApp.run();
    }

    /** The video card, for the video to grow out of as it opens fullscreen. */
    View videoCard() { return media.videoCard(); }

    /** Whether Wend has just floated Maps into its own native PiP; lets AppLaunch allow the video's auto-PiP cover. */
    boolean floatingMapsIntoPip() { return BuildConfig.SYSTEM_PIP; }

    /** The video app is fullscreen on the main display; a floated Maps has entered its own PiP above it. */
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
        media.videoCardCovered();
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
    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onStop() {
        if (drawer != null) drawer.dismiss();
        if (settingsSheet != null) settingsSheet.dismiss();
        maps.suspend();
        media.stop();
        super.onStop();
    }
    @Override protected void onDestroy() {
        pip.stop();
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
        // Home takes back whatever Wend put in Android's PiP.
        pinnedByWend = -1;
        // Moving an embedded task fullscreen can leave Home started. Its return intent
        // must restore Maps even when Android does not deliver another onStart.
        // Floating Maps lands over its panel first; the fullscreen video then shrinks into its
        // card below it, and the end of that presentation hands Maps back to its panel.
        maps.home(() -> {
            media.homeVideo();
            if (!media.videoPresenting()) maps.returnFromFloating();
        });
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        hideSystemBars();
        // A sheet that covered the card has closed.
        media.videoCardShown();
    }
    @Override public void onBackPressed() { hideSystemBars(); }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
