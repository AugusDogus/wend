package dev.augie.wend;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-width glass app drawer on a BottomSheet. One instance per activity is reused for every
 * opening; it shows AppCatalog's cached apps and search hides tiles instead of rebuilding them.
 * Above the alphabetical grid, a row shows the apps Wend opened most recently.
 */
final class AppDrawer implements AppCatalog.Listener {
    /** As in the settings header: a 48 dp button or balancing space, then an 8 dp gap. */
    private static final int SEARCH_INSET_DP = 56;

    private final AppCatalog catalog;
    private final LauncherSettings settings;
    private final BottomSheet sheet;
    private final AppBrowser browser;

    AppDrawer(Activity activity, AppCatalog catalog, LauncherSettings settings, FrostedBackdrop backdrop) {
        long started = SystemClock.uptimeMillis();
        this.catalog = catalog;
        this.settings = settings;
        // The drawer offers every app and hides none, so its hidden-match messages never show.
        browser = new AppBrowser(activity, catalog, app -> true, "Search apps", R.string.no_apps,
                R.string.no_matching_apps, R.string.no_matching_apps, app -> {
            dismiss();
            AppLaunch.open(activity, app);
        });
        // Dragging, Back, a backdrop tap and Home close the drawer, so it has no close button.
        // Equal insets keep the search field centered under the grab handle in either direction.
        FrameLayout header = new FrameLayout(activity);
        int inset = Ui.dp(activity, SEARCH_INSET_DP);
        FrameLayout.LayoutParams searchLayout = new FrameLayout.LayoutParams(-1, -1);
        searchLayout.setMargins(inset, 0, inset, 0);
        header.addView(browser.search(), searchLayout);
        sheet = new BottomSheet(activity, backdrop, SheetGlass.Density.LIGHT, "App drawer", header,
                browser.body(), browser::reset);
        catalog.addListener(this);
        showRecent();
        Log.i("Wend", "App drawer prepared with " + browser.size() + " apps in "
                + (SystemClock.uptimeMillis() - started) + " ms");
    }

    void show() {
        catalog.retryIfFailed();
        // Launches are recorded while the drawer is hidden; each opening shows the latest.
        showRecent();
        sheet.show();
    }

    @Override public void onAppsChanged(AppCatalog catalog) { showRecent(); }

    /**
     * Shows the recent apps that are still installed, and forgets the others. Until the app
     * list is ready, which apps are installed is unknown, so the row stays hidden.
     */
    private void showRecent() {
        if (catalog.status() != AppCatalog.Status.READY) {
            browser.setRecent(Collections.<LauncherApp>emptyList());
            return;
        }
        RecentApps stored = settings.recentApps();
        RecentApps installed = stored.retain(packageName -> catalog.pinnedApp(packageName) != null);
        if (!installed.equals(stored)) settings.setRecentApps(installed);
        List<LauncherApp> apps = new ArrayList<>(installed.packages().size());
        for (String packageName : installed.packages()) apps.add(catalog.pinnedApp(packageName));
        browser.setRecent(apps);
    }

    /** Slides the drawer away, as Back and Home do. */
    void close() { sheet.close(); }

    /** Hides the drawer immediately, for example when an app opens over it. */
    void dismiss() { sheet.dismiss(); }

    void release() {
        catalog.removeListener(this);
        browser.release();
        sheet.release();
    }
}
