package dev.augie.wend;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.widget.FrameLayout;

/**
 * Full-width glass app drawer on a BottomSheet. One instance per activity is reused for every
 * opening; it shows AppCatalog's cached apps and search hides tiles instead of rebuilding them.
 */
final class AppDrawer {
    /** As in the settings header: a 48 dp button or balancing space, then an 8 dp gap. */
    private static final int SEARCH_INSET_DP = 56;

    private final AppCatalog catalog;
    private final BottomSheet sheet;
    private final AppBrowser browser;

    AppDrawer(Activity activity, AppCatalog catalog, FrostedBackdrop backdrop) {
        long started = SystemClock.uptimeMillis();
        this.catalog = catalog;
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
        Log.i("Wend", "App drawer prepared with " + browser.size() + " apps in "
                + (SystemClock.uptimeMillis() - started) + " ms");
    }

    void show() {
        catalog.retryIfFailed();
        sheet.show();
    }

    /** Slides the drawer away, as Back and Home do. */
    void close() { sheet.close(); }

    /** Hides the drawer immediately, for example when an app opens over it. */
    void dismiss() { sheet.dismiss(); }

    void release() {
        browser.release();
        sheet.release();
    }
}
