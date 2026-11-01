package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Full-width glass app drawer. Package/icon loading happens off the UI thread. */
final class AppDrawer {
    private static final int COLUMNS = 5;
    private static final class App {
        final String packageName;
        final String label;
        final Drawable icon;
        App(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private final Activity activity;
    private final Dialog dialog;
    private final GridLayout grid;
    private final EditText search;
    private final TextView status;
    private final List<App> apps = new ArrayList<>();
    private final FrameLayout backdrop;
    private final LinearLayout sheet;
    private final int sheetWidth;

    private AppDrawer(Activity activity) {
        this.activity = activity;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheetWidth = activity.getResources().getDisplayMetrics().widthPixels;
        backdrop = new FrameLayout(activity);
        backdrop.setOnClickListener(v -> dialog.dismiss());
        LinearLayout root = new LinearLayout(activity);
        sheet = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.glass(activity));
        root.setClickable(true);
        root.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 4), Ui.dp(activity, 8), 0);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.iconButton(activity, Ui.Icon.CLOSE, "Close apps", dialog::dismiss),
                new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        search = new EditText(activity);
        search.setHint("Search apps");
        search.setContentDescription("Search apps");
        search.setTextSize(17);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
            return true;
        });
        search.setBackground(Ui.surface(activity, 0x553C536A, 24));
        search.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(0, Ui.dp(activity, 48), 1);
        searchLayout.setMargins(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0);
        header.addView(search, searchLayout);
        header.addView(new View(activity), new LinearLayout.LayoutParams(Ui.dp(activity, 48), 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 64)));
        status = Ui.text(activity, "Loading apps…", 17, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 48)));
        grid = new GridLayout(activity);
        grid.setColumnCount(COLUMNS);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout.LayoutParams sheetLayout = new FrameLayout.LayoutParams(sheetWidth, -1, Gravity.START);
        backdrop.addView(root, sheetLayout);
        dialog.setContentView(backdrop);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { render(); }
            @Override public void afterTextChanged(Editable text) { }
        });
    }

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
                android.util.Log.e("RoadHome", "Could not restore the existing video task", error);
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
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(activity.getWindowManager().getDefaultDisplay().getDisplayId());
            activity.startActivity(launch, options.toBundle());
            return true;
        }
        catch (ActivityNotFoundException | SecurityException error) {
            android.util.Log.e("RoadHome", "Cannot launch " + packageName, error);
            Toast.makeText(activity, "Android could not open this app. Try another app.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    static void show(Activity activity) { new AppDrawer(activity).show(); }

    private void show() {
        FrostedBackdrop.capture(activity, new FrostedBackdrop.Listener() {
            @Override public void onCaptured(Bitmap bitmap) {
                if (activity.isFinishing() || activity.isDestroyed()) { bitmap.recycle(); return; }
                BitmapDrawable image = new BitmapDrawable(activity.getResources(), bitmap);
                image.setGravity(Gravity.FILL);
                sheet.setBackground(new LayerDrawable(new Drawable[]{image,
                        new ColorDrawable(0xB8101922), Ui.glass(activity)}));
                backdrop.setBackgroundColor(0x22000000);
                showSheet();
            }
            @Override public void onUnavailable() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                sheet.setBackground(new LayerDrawable(new Drawable[]{
                        new ColorDrawable(0xEE101922), Ui.glass(activity)}));
                backdrop.setBackgroundColor(0x22000000);
                showSheet();
            }
        });
    }

    private void showSheet() {
        if (BuildConfig.WINDOWED_MAPS && dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(-1, -1);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        new Thread(() -> {
            List<App> loaded = new ArrayList<>();
            try {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                Set<String> seen = new HashSet<>();
                for (ResolveInfo item : activity.getPackageManager().queryIntentActivities(query, 0)) {
                    String packageName = item.activityInfo.packageName;
                    if (packageName.equals(activity.getPackageName()) || !seen.add(packageName)) continue;
                    loaded.add(new App(packageName, item.loadLabel(activity.getPackageManager()).toString(),
                            item.loadIcon(activity.getPackageManager())));
                }
                Collator collator = Collator.getInstance();
                loaded.sort((a, b) -> collator.compare(a.label, b.label));
                activity.runOnUiThread(() -> {
                    if (!dialog.isShowing()) return;
                    apps.addAll(loaded);
                    render();
                });
            } catch (RuntimeException error) {
                android.util.Log.e("RoadHome", "Could not load launchable applications", error);
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing()) status.setText(R.string.apps_unavailable);
                });
            }
        }, "RoadHome-apps").start();
    }

    private void render() {
        grid.removeAllViews();
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int width = (sheetWidth - Ui.dp(activity, 16)) / COLUMNS;
        for (App app : apps) {
            if (!app.label.toLowerCase(Locale.ROOT).contains(query)) continue;
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3384BFFF),
                    Ui.surface(activity, Color.TRANSPARENT, 12), null));
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(new AppIcon(app.icon));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
            TextView label = Ui.text(activity, app.label, 15, Ui.TEXT);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 38));
            labelLayout.topMargin = Ui.dp(activity, 8);
            item.addView(label, labelLayout);
            item.setContentDescription(app.label);
            item.setFocusable(true);
            item.setOnClickListener(v -> { dialog.dismiss(); open(activity, app.packageName); });
            GridLayout.LayoutParams layout = new GridLayout.LayoutParams();
            layout.width = width;
            layout.height = Ui.dp(activity, 100);
            grid.addView(item, layout);
        }
        status.setText(apps.isEmpty() ? R.string.no_apps : R.string.no_matching_apps);
        status.setVisibility(grid.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }
}
