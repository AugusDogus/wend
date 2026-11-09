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
    /**
     * How long the video, moved fullscreen on the main display, is left to composite before a still
     * of it is captured to cover Maps' arming for Case B. Captured sooner, the still could catch the
     * tail of the video's open transition rather than the video.
     */
    private static final long VIDEO_COMPOSITE_MS = 250;
    /**
     * A safety net, used only if Android never reports Maps pinned, after which the video still that
     * hides Maps' arming is revealed regardless, so it can never stay stuck over the screen. It must
     * be comfortably longer than Maps takes to enter PiP after the covering task switch (hundreds of
     * ms on this loaded emulator); the reveal normally fires on the {@code pinned} watcher as Maps
     * begins its shrink, so the still masks Maps' brief fullscreen frame and the shrink shows beneath.
     */
    private static final long PIP_REVEAL_FALLBACK_MS = 2500;
    /**
     * How long after Android reports Maps pinned the video still is held before it is revealed, to
     * let SystemUI finish shrinking Maps into its corner behind the still. Android marks the task
     * pinned when it enters the pinned stack, but on this loaded emulator SystemUI takes several
     * hundred ms more to actually animate it down; revealing on the pinned report alone showed Maps
     * at fullscreen in the pinned stack before it shrank. Held past the shrink, the user sees only
     * the video, then Maps already in its corner.
     */
    private static final long REVEAL_AFTER_PIN_MS = 500;
    /** Holds a still of the fullscreen video over the screen while Maps arms its own nav PiP behind it. */
    private PipEntryCover pipEntry;
    private final SystemPip.Watcher pip = new SystemPip.Watcher(new SystemPip.Watcher.Listener() {
        @Override public void pinned(String packageName, int taskId) {
            if (packageName.equals(EmbeddedMaps.PACKAGE) || packageName.equals(EmbeddedVideo.PACKAGE)) {
                pinnedByWend = taskId;
            }
            // Case B: Maps entered its nav PiP. Hold the video still a little longer so SystemUI's
            // shrink finishes behind it, then reveal: the user never sees Maps at fullscreen. No-op
            // when nothing is covered.
            if (packageName.equals(EmbeddedMaps.PACKAGE) && pipEntry != null) {
                content.postDelayed(pipEntry::reveal, REVEAL_AFTER_PIN_MS);
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
        media = new MediaPanel(this, this::openMusic, this::openVideo, () -> maps.returnFromFloating());
        // The dock and the map side are places in the car, not reading order, so the row of
        // panels never mirrors; each panel still lays out its own content for the locale.
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (View panel : new View[]{dock, maps, media}) panel.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        arrangePanels();
        setContentView(root);
        pipEntry = new PipEntryCover(this);
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
            if (resumed && settings.floatingVideo() && media.videoLive() && media.beginVideoNativePipFloat()) {
                maps.coverVideoNativePip();
                return;
            }
            maps.openFullscreen(new MapPanel.Covering() {
                @Override public Rect starting() { return null; }
                @Override public void abandoned() { media.videoCardShown(); }
            }, false);
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
     * Wend opens the video app itself on the main display (a paused or unavailable preview,
     * or no live video): live Maps floats above it when allowed, as above the live video.
     *
     * @return true when this fully handled opening the video (Case B placed the video fullscreen and
     *     floated Maps into its own native PiP over it); false to let the caller open it plainly
     */
    boolean videoAppOpening() {
        if (BuildConfig.SYSTEM_PIP && resumed && settings.floatingMaps() && media.videoLive()) {
            // Case B: the live video goes fullscreen and Maps enters its OWN native nav PiP above it.
            // Order matters so Android marks Maps supportsEnterPipOnTaskSwitch on the covering switch:
            //   1. Place the live video fullscreen on display 0 as its own task (its op stays ignore).
            //   2. Bring Maps fullscreen on display 0 on top of the video, allowing Maps' PiP op.
            //   3. Cover Maps with the video via a plain task switch (not startActivityFromRecents,
            //      whose Home-stack reorder would stop Maps being flagged): Maps backgrounds as the
            //      top activity and enters its own nav PiP via onUserLeaveHint.
            // Capture the live video before suspending, since suspend clears its live state.
            if (media.placeVideoFullscreen()) {
                externalAppOpening();
                // The video is opening fullscreen. Once it has composited, cover it with a held
                // still and arm Maps behind that still, so steps 2 and 3 above are never seen: the
                // user sees only the video, then the native PiP shrink. postDelayed runs on the
                // main thread even while Wend is stopped under the fullscreen video.
                content.postDelayed(this::floatMapsIntoPipBehindVideo, VIDEO_COMPOSITE_MS);
                return true;
            }
        }
        externalAppOpening();
        if (BuildConfig.SYSTEM_PIP) {
            // No live video to float Maps over (paused or unavailable): Maps goes fullscreen and
            // allows its op; opening the video over it backgrounds Maps into its own nav PiP card.
            if (resumed && settings.floatingMaps()) maps.beginNativePipFloat();
            return false;
        }
        if (settings.floatingMaps()) maps.floatAboveVideoApp(settings.floatingMapsCorner(), floatingMaps);
        return false;
    }

    /**
     * Case B, continued once the video shows fullscreen: hold a still of the video over the whole
     * screen, launch Maps fullscreen behind it and let it settle into navigation, then cover Maps
     * with the video so it enters its own native nav PiP. The still is revealed as Maps pins (the
     * {@code pinned} watcher), so SystemUI's PiP shrink is the only motion the user sees.
     */
    private void floatMapsIntoPipBehindVideo() {
        // Launch Maps only once the still is actually drawn over the screen, so Maps never flashes
        // fullscreen before the still covers it.
        pipEntry.cover(() -> {
            if (!maps.beginNativePipFloat()) {
                // No Maps task to float: the video is already fullscreen, so leave it fullscreen
                // plainly. Maps returns to its panel on Home and restores its op there.
                pipEntry.dispose();
                videoOpened();
                return;
            }
            videoOpened();
            content.postDelayed(() -> {
                media.coverMapsIntoPip();
                // The pinned watcher reveals the still as Maps shrinks; this fallback guarantees
                // the still is never left stuck over the screen if Android never reports Maps pinned.
                content.postDelayed(pipEntry::reveal, PIP_REVEAL_FALLBACK_MS);
            }, PIP_SETTLE_MS);
        });
    }

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
        if (pipEntry != null) pipEntry.dispose();
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
        // Home wins over a Case B entry still mid-flight: never keep a video still over the screen.
        pipEntry.dispose();
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
