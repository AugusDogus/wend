package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Full-width glass app drawer on a BottomSheet. One instance per activity is reused for every
 * opening; it shows AppCatalog's cached apps and search hides tiles instead of rebuilding them.
 */
final class AppDrawer implements AppCatalog.Listener {
    private static final int COLUMNS = 5;

    private final Activity activity;
    private final AppCatalog catalog;
    private final BottomSheet sheet;
    private final EditText search;
    private final TextView status;
    private final ScrollView scroll;
    private final AppGrid grid;
    private final Map<LauncherApp.Key, TextView> tiles = new HashMap<>();

    AppDrawer(Activity activity, AppCatalog catalog) {
        long started = SystemClock.uptimeMillis();
        this.activity = activity;
        this.catalog = catalog;
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.iconButton(activity, Ui.Icon.CLOSE, "Close apps", this::close),
                new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        search = searchField();
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(0, Ui.dp(activity, 48), 1);
        searchLayout.setMargins(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0);
        header.addView(search, searchLayout);
        header.addView(new View(activity), new LinearLayout.LayoutParams(Ui.dp(activity, 48), 1));
        FrameLayout body = new FrameLayout(activity);
        grid = new AppGrid(activity, COLUMNS, Ui.dp(activity, 100));
        grid.setPadding(0, 0, 0, Ui.dp(activity, 8));
        scroll = new ScrollView(activity);
        // Rows fade out under the header and at the bottom edge, hinting at more apps.
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(activity, 24));
        scroll.addView(grid);
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(activity, "", 17, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        body.addView(status, new FrameLayout.LayoutParams(-1, Ui.dp(activity, 48), Gravity.TOP));
        sheet = new BottomSheet(activity, "App drawer", header, body, this::reset);
        catalog.addListener(this);
        bind();
        Log.i("RoadHome", "App drawer prepared with " + tiles.size() + " apps in "
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
        catalog.removeListener(this);
        sheet.release();
    }

    @Override public void onAppsChanged(AppCatalog catalog) { bind(); }

    static void open(Activity activity, String packageName) {
        if (EmbeddedVideo.PACKAGE.equals(packageName) && activity instanceof MainActivity
                && ((MainActivity) activity).openVideoFullscreen(() -> openDirect(activity, packageName))) return;
        openDirect(activity, packageName);
    }

    private static void openDirect(Activity activity, String packageName) {
        if (EmbeddedMaps.PACKAGE.equals(packageName) && activity instanceof MainActivity) {
            ((MainActivity) activity).openMapsFullscreen();
            return;
        }
        MainActivity home = activity instanceof MainActivity ? (MainActivity) activity : null;
        if (home != null) home.externalAppOpening();
        if (openExternal(activity, packageName)) {
            if (home != null && EmbeddedVideo.PACKAGE.equals(packageName)) home.videoOpened();
        } else if (home != null) home.externalAppFailed();
    }

    private static boolean openExternal(Activity activity, String packageName) {
        if (EmbeddedVideo.PACKAGE.equals(packageName)) {
            try {
                if (VideoTask.openFullscreen(activity)) return true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e("RoadHome", "Could not restore the existing video task", error);
                Toast.makeText(activity, "The video could not be restored. Open it from Recents; existing tasks were left intact.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Toast.makeText(activity, "This app is not installed or cannot be opened.", Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            activity.startActivity(launch, mainDisplay(activity));
            return true;
        }
        catch (ActivityNotFoundException | SecurityException error) {
            Log.e("RoadHome", "Cannot launch " + packageName, error);
            Toast.makeText(activity, "Android could not open this app. Try another app.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Apps from another profile, such as a work profile, open through LauncherApps. */
    private static void openInProfile(Activity activity, LauncherApp app) {
        MainActivity home = activity instanceof MainActivity ? (MainActivity) activity : null;
        if (home != null) home.externalAppOpening();
        try {
            LauncherApps launcherApps = activity.getSystemService(LauncherApps.class);
            if (launcherApps == null) throw new SecurityException("LauncherApps service is unavailable");
            launcherApps.startMainActivity(app.component, app.key.user, null, mainDisplay(activity));
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e("RoadHome", "Cannot launch " + app.component + " in profile " + app.key.user, error);
            Toast.makeText(activity, "Android could not open this app in its profile. "
                    + "Check that the profile is turned on.", Toast.LENGTH_LONG).show();
            if (home != null) home.externalAppFailed();
        }
    }

    private static Bundle mainDisplay(Activity activity) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(activity.getWindowManager().getDefaultDisplay().getDisplayId());
        return options.toBundle();
    }

    private void reset() {
        search.setText("");
        search.clearFocus();
        scroll.scrollTo(0, 0);
    }

    private void bind() {
        Map<LauncherApp.Key, TextView> previous = new HashMap<>(tiles);
        tiles.clear();
        grid.removeAllViews();
        for (LauncherApp app : catalog.apps()) {
            TextView tile = previous.remove(app.key);
            if (tile == null) tile = tile();
            if (tile.getTag() != app) bindTile(tile, app);
            tiles.put(app.key, tile);
            grid.addView(tile);
        }
        filter();
    }

    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View tile = grid.getChildAt(i);
            LauncherApp app = (LauncherApp) tile.getTag();
            tile.setVisibility(app.label.toLowerCase(Locale.ROOT).contains(query) ? View.VISIBLE : View.GONE);
        }
        showStatus();
    }

    private void showStatus() {
        switch (catalog.status()) {
            case LOADING: status.setText(R.string.loading_apps); break;
            case FAILED: status.setText(R.string.apps_unavailable); break;
            case READY:
                if (catalog.apps().isEmpty()) status.setText(R.string.no_apps);
                else if (visibleTiles() == 0) status.setText(R.string.no_matching_apps);
                else { status.setVisibility(View.GONE); return; }
                break;
        }
        status.setVisibility(View.VISIBLE);
    }

    private int visibleTiles() {
        int visible = 0;
        for (int i = 0; i < grid.getChildCount(); i++) {
            if (grid.getChildAt(i).getVisibility() == View.VISIBLE) visible++;
        }
        return visible;
    }

    private TextView tile() {
        TextView tile = Ui.text(activity, "", 15, Ui.TEXT);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setMaxLines(2);
        tile.setEllipsize(TextUtils.TruncateAt.END);
        tile.setCompoundDrawablePadding(Ui.dp(activity, 8));
        tile.setPadding(Ui.dp(activity, 4), Ui.dp(activity, 6), Ui.dp(activity, 4), 0);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3384BFFF),
                Ui.surface(activity, Color.TRANSPARENT, 12), null));
        tile.setFocusable(true);
        return tile;
    }

    private void bindTile(TextView tile, LauncherApp app) {
        tile.setTag(app);
        tile.setText(app.label);
        tile.setContentDescription(app.label);
        BitmapDrawable icon = new BitmapDrawable(activity.getResources(), app.icon);
        int size = Ui.dp(activity, AppCatalog.ICON_DP);
        icon.setBounds(0, 0, size, size);
        tile.setCompoundDrawables(null, icon, null, null);
        tile.setOnClickListener(v -> {
            dismiss();
            if (Process.myUserHandle().equals(app.key.user)) open(activity, app.key.packageName);
            else openInProfile(activity, app);
        });
    }

    private EditText searchField() {
        EditText field = new EditText(activity);
        field.setHint("Search apps");
        field.setContentDescription("Search apps");
        field.setTextSize(17);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.MUTED);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
            field.clearFocus();
            return true;
        });
        field.setBackground(Ui.surface(activity, 0x553C536A, 24));
        field.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        return field;
    }
}
