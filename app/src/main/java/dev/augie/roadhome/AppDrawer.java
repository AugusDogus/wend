package dev.augie.roadhome;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Full-width glass app drawer on a BottomSheet. One instance per activity is reused for every
 * opening; it shows AppCatalog's cached apps and search hides tiles instead of rebuilding them.
 */
final class AppDrawer {
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
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.iconButton(activity, Ui.Icon.CLOSE, "Close apps", this::close),
                new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(0, Ui.dp(activity, 48), 1);
        searchLayout.setMargins(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0);
        header.addView(browser.search(), searchLayout);
        header.addView(new View(activity), new LinearLayout.LayoutParams(Ui.dp(activity, 48), 1));
        sheet = new BottomSheet(activity, backdrop, SheetGlass.Density.LIGHT, "App drawer", header,
                browser.body(), browser::reset);
        Log.i("RoadHome", "App drawer prepared with " + browser.size() + " apps in "
                + (SystemClock.uptimeMillis() - started) + " ms");
    }

    void show() {
        catalog.retryIfFailed();
        sheet.show();
    }

    /** Slides the drawer away, as the close button, Back, and Home do. */
    void close() { sheet.close(); }

    /** Hides the drawer immediately, for example when an app opens over it. */
    void dismiss() { sheet.dismiss(); }

    void release() {
        browser.release();
        sheet.release();
    }
}
