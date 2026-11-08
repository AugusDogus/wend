package dev.augie.roadhome;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Road Home's settings on a glass BottomSheet: a centered column of grouped cards for the
 * layout, the dock, floating windows and Android's own settings. Every choice applies live.
 * Add app opens a picker page inside the same sheet; choosing an app pins it and returns.
 */
final class SettingsSheet implements AppCatalog.Listener {
    interface Host {
        /** The map side or width changed; rearrange the panels. */
        void onLayoutChanged();
        /** The pins changed and are saved; redraw the dock. */
        void onDockChanged(DockPins pins);
    }

    private static final int COLUMN_DP = 680;
    /** Wide enough for "More map 70%" in each half. */
    private static final int CONTROL_DP = 290;
    private static final int PAGE_SHIFT_DP = 40;
    private static final int PAGE_IN_MS = 220;
    private static final int PAGE_OUT_MS = 150;
    private static final TimeInterpolator EASE_OUT = new PathInterpolator(0.23f, 1, 0.32f, 1);

    private final Activity activity;
    private final AppCatalog catalog;
    private final LauncherSettings settings;
    private final int dockCapacity;
    private final Host host;
    private final BottomSheet sheet;
    private final View mainHeader;
    private final View pickerHeader;
    private final ScrollView mainPage;
    private final View pickerPage;
    private final AppBrowser picker;
    private final LayoutPreview preview;
    private final SegmentedControl<LauncherSettings.MapSide> sideControl;
    private final SegmentedControl<LauncherSettings.MapWidth> widthControl;
    private final DockEditor dockEditor;
    private final Switch floatingVideo;
    private final Switch floatingMaps;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Rearranging reattaches the Maps window, which stalls the shared render thread for about
    // 100 ms on the emulator. It runs once the preview has settled, or as the sheet closes.
    private final Runnable applyLayout = this::flushLayout;
    private boolean layoutPending;
    private DockPins pins;
    private boolean picking;

    SettingsSheet(Activity activity, FrostedBackdrop backdrop, AppCatalog catalog, LauncherSettings settings,
            int dockCapacity, Host host) {
        this.activity = activity;
        this.catalog = catalog;
        this.settings = settings;
        this.dockCapacity = dockCapacity;
        this.host = host;
        pins = settings.dockPins(dockCapacity);
        picker = new AppBrowser(activity, catalog, activity.getString(R.string.settings_search_hint),
                R.string.every_app_pinned, R.string.matching_app_pinned, R.string.matching_apps_pinned, this::pin);

        SettingsRows rows = new SettingsRows(activity);
        mainHeader = rows.header(Ui.Icon.CLOSE, R.string.settings_close, this::close,
                rows.title(R.string.settings_title));
        pickerHeader = rows.header(Ui.Icon.BACK, R.string.settings_back, () -> showMain(true), picker.search());
        FrameLayout headers = new FrameLayout(activity);
        headers.addView(mainHeader, new FrameLayout.LayoutParams(-1, -1));
        headers.addView(pickerHeader, new FrameLayout.LayoutParams(-1, -1));

        MaxWidthColumn column = new MaxWidthColumn(activity, COLUMN_DP);
        column.setPadding(0, 0, 0, Ui.dp(activity, 8));
        LauncherSettings.MapSide side = settings.mapSide();
        LauncherSettings.MapWidth width = settings.mapWidth();
        preview = new LayoutPreview(activity, side, width);
        sideControl = new SegmentedControl<>(activity, Arrays.asList(
                new SegmentedControl.Option<>(LauncherSettings.MapSide.LEFT,
                        activity.getString(R.string.settings_left)),
                new SegmentedControl.Option<>(LauncherSettings.MapSide.RIGHT,
                        activity.getString(R.string.settings_right))),
                side, this::chooseSide);
        // Left and Right are places in the car, so they never swap in right-to-left languages.
        sideControl.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        widthControl = new SegmentedControl<>(activity, Arrays.asList(
                new SegmentedControl.Option<>(LauncherSettings.MapWidth.BALANCED,
                        activity.getString(R.string.settings_balanced, LauncherSettings.MapWidth.BALANCED.percent)),
                new SegmentedControl.Option<>(LauncherSettings.MapWidth.MORE_MAP,
                        activity.getString(R.string.settings_more_map, LauncherSettings.MapWidth.MORE_MAP.percent))),
                width, this::chooseWidth);
        rows.addSection(column, R.string.settings_layout, layoutCard(rows), false);

        dockEditor = new DockEditor(activity, catalog, pins, new DockEditor.Host() {
            @Override public void onPinsChanged(DockPins next) { savePins(next); }
            @Override public void onAddRequested() { showPicker(); }
        });
        LinearLayout.LayoutParams dockLayout = new LinearLayout.LayoutParams(-1, -2);
        dockLayout.topMargin = Ui.dp(activity, SettingsRows.SECTION_GAP_DP);
        column.addView(dockEditor.view(), dockLayout);

        LinearLayout floating = Ui.card(activity);
        floatingVideo = rows.toggle(R.string.settings_floating_video, R.string.settings_floating_video_hint,
                settings.floatingVideo(), settings::setFloatingVideo);
        floatingMaps = rows.toggle(R.string.settings_floating_maps, R.string.settings_floating_maps_hint,
                settings.floatingMaps(), settings::setFloatingMaps);
        rows.addRows(floating, floatingVideo, floatingMaps);
        rows.addSection(column, R.string.settings_floating, floating, true);

        LinearLayout system = Ui.card(activity);
        rows.addRows(system,
                rows.link(R.string.settings_android, () -> openSystemScreen(R.string.settings_android,
                        new Intent(Settings.ACTION_SETTINGS))),
                rows.link(R.string.settings_home, () -> openSystemScreen(R.string.settings_home,
                        new Intent(Settings.ACTION_HOME_SETTINGS))));
        rows.addSection(column, R.string.settings_system, system, true);

        TextView version = Ui.text(activity, activity.getString(R.string.settings_version, BuildConfig.VERSION_NAME),
                13, Ui.MUTED);
        version.setGravity(Gravity.CENTER);
        column.addView(version, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 56)));

        mainPage = new ScrollView(activity);
        mainPage.setVerticalFadingEdgeEnabled(true);
        mainPage.setFadingEdgeLength(Ui.dp(activity, 24));
        mainPage.setClipToPadding(false);
        mainPage.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));

        MaxWidthColumn pickerColumn = new MaxWidthColumn(activity, COLUMN_DP);
        pickerColumn.addView(picker.body(), new LinearLayout.LayoutParams(-1, -1));
        FrameLayout pickerFrame = new FrameLayout(activity);
        pickerFrame.addView(pickerColumn, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));
        pickerPage = pickerFrame;

        FrameLayout body = new FrameLayout(activity);
        body.addView(mainPage, new FrameLayout.LayoutParams(-1, -1));
        body.addView(pickerPage, new FrameLayout.LayoutParams(-1, -1));
        sheet = new BottomSheet(activity, backdrop, SheetGlass.Density.DENSE,
                activity.getString(R.string.settings_title), headers, body, new BottomSheet.Listener() {
                    @Override public void onClosing() { flushLayout(); }
                    @Override public void onDismissed() {
                        flushLayout();
                        reset();
                    }
                    @Override public boolean onBack() {
                        if (!picking) return false;
                        showMain(true);
                        return true;
                    }
                });
        catalog.addListener(this);
        reset();
        updatePreviewIcons();
    }

    void show() {
        catalog.retryIfFailed();
        // Reflect the stored choices, in case anything else changed them.
        pins = settings.dockPins(dockCapacity);
        dockEditor.setPins(pins, false);
        updatePreviewIcons();
        sideControl.select(settings.mapSide());
        widthControl.select(settings.mapWidth());
        preview.show(settings.mapSide(), settings.mapWidth());
        floatingVideo.setChecked(settings.floatingVideo());
        floatingMaps.setChecked(settings.floatingMaps());
        sheet.show();
    }

    void close() { sheet.close(); }

    void dismiss() { sheet.dismiss(); }

    void release() {
        main.removeCallbacks(applyLayout);
        catalog.removeListener(this);
        dockEditor.release();
        picker.release();
        sheet.release();
    }

    @Override public void onAppsChanged(AppCatalog catalog) { updatePreviewIcons(); }

    private void chooseSide(LauncherSettings.MapSide side) {
        settings.setMapSide(side);
        preview.show(side, settings.mapWidth());
        scheduleLayout();
    }

    private void chooseWidth(LauncherSettings.MapWidth width) {
        settings.setMapWidth(width);
        preview.show(settings.mapSide(), width);
        scheduleLayout();
    }

    private void scheduleLayout() {
        layoutPending = true;
        main.removeCallbacks(applyLayout);
        main.postDelayed(applyLayout, LayoutPreview.MORPH_MS + 40);
    }

    private void flushLayout() {
        main.removeCallbacks(applyLayout);
        if (!layoutPending) return;
        layoutPending = false;
        host.onLayoutChanged();
    }

    private void savePins(DockPins next) {
        pins = next;
        settings.setDockPins(next);
        dockEditor.setPins(next, true);
        updatePreviewIcons();
        host.onDockChanged(next);
    }

    private void pin(LauncherApp app) {
        showMain(true);
        if (!pins.canAdd(app.key.packageName)) {
            // The picker hides pinned apps and cannot open while the dock is full.
            Log.e("RoadHome", "The app picker offered " + app.key + " although " + pins
                    + " cannot take it; the dock was left unchanged");
            return;
        }
        savePins(pins.add(app.key.packageName));
    }

    private void showPicker() {
        if (picking || pins.isFull()) return;
        picker.reset();
        picker.setHidden(app -> !Process.myUserHandle().equals(app.key.user) || pins.contains(app.key.packageName));
        picking = true;
        switchPage(mainHeader, mainPage, pickerHeader, pickerPage, 1);
    }

    private void showMain(boolean animate) {
        if (!picking) return;
        picking = false;
        // The query stays while the picker fades out; showPicker and dismissal clear it.
        picker.hideKeyboard();
        if (animate) switchPage(pickerHeader, pickerPage, mainHeader, mainPage, -1);
        else {
            show(mainHeader, mainPage);
            hide(pickerHeader, pickerPage);
        }
    }

    /** The incoming page slides in a short way from the side it is pushed from, over the outgoing one. */
    private void switchPage(View outHeader, View outPage, View inHeader, View inPage, int forward) {
        boolean rtl = sheetDirection() == View.LAYOUT_DIRECTION_RTL;
        float shift = Ui.dp(activity, PAGE_SHIFT_DP) * forward * (rtl ? -1 : 1);
        show(inHeader, inPage);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            hide(outHeader, outPage);
            return;
        }
        for (View view : new View[]{inHeader, inPage}) {
            view.animate().cancel();
            view.setAlpha(0);
            view.setTranslationX(shift);
            view.animate().alpha(1).translationX(0).setDuration(PAGE_IN_MS).setInterpolator(EASE_OUT).withLayer();
        }
        for (View view : new View[]{outHeader, outPage}) {
            view.animate().cancel();
            view.animate().alpha(0).translationX(-shift / 2).setDuration(PAGE_OUT_MS).setInterpolator(EASE_OUT)
                    .withLayer().withEndAction(() -> hide(view));
        }
    }

    private int sheetDirection() { return mainPage.getLayoutDirection(); }

    private static void show(View... views) {
        for (View view : views) {
            view.animate().cancel();
            view.setVisibility(View.VISIBLE);
            view.setAlpha(1);
            view.setTranslationX(0);
        }
    }

    /** Hidden pages keep their layout, so returning to one can animate from where it was. */
    private static void hide(View... views) {
        for (View view : views) {
            view.setVisibility(View.INVISIBLE);
            view.setAlpha(1);
            view.setTranslationX(0);
        }
    }

    private void reset() {
        picking = true;
        showMain(false);
        picker.reset();
        mainPage.scrollTo(0, 0);
    }

    private void updatePreviewIcons() {
        Map<String, Bitmap> installed = new HashMap<>();
        for (LauncherApp app : catalog.apps()) {
            if (Process.myUserHandle().equals(app.key.user)) installed.put(app.key.packageName, app.icon);
        }
        List<Bitmap> icons = new ArrayList<>();
        for (String packageName : pins.packages()) {
            Bitmap icon = installed.get(packageName);
            if (icon != null) icons.add(icon);
        }
        preview.setDockIcons(icons);
    }

    private View layoutCard(SettingsRows rows) {
        LinearLayout card = Ui.card(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int padding = Ui.dp(activity, 8);
        // The drawing starts at the same 16 dp inset as the section label and the dock icons.
        card.setPaddingRelative(Ui.dp(activity, 16), padding, Ui.dp(activity, 16), padding);
        // A 16:9 drawing of the 853 x 480 dp screen.
        card.addView(preview, new LinearLayout.LayoutParams(Ui.dp(activity, 213), Ui.dp(activity, 120)));
        LinearLayout choices = new LinearLayout(activity);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.addView(rows.choiceRow(R.string.settings_map_position, sideControl, CONTROL_DP),
                new LinearLayout.LayoutParams(-1, 0, 1));
        choices.addView(rows.divider(), new LinearLayout.LayoutParams(-1, 1));
        choices.addView(rows.choiceRow(R.string.settings_map_width, widthControl, CONTROL_DP),
                new LinearLayout.LayoutParams(-1, 0, 1));
        // Two 64 dp rows beside the drawing.
        LinearLayout.LayoutParams choicesLayout = new LinearLayout.LayoutParams(0,
                Ui.dp(activity, 2 * SettingsRows.ROW_DP), 1);
        choicesLayout.setMarginStart(Ui.dp(activity, 20));
        card.addView(choices, choicesLayout);
        return card;
    }

    private void openSystemScreen(int name, Intent intent) {
        sheet.dismiss();
        AppLaunch.openSystemScreen(activity, intent, activity.getString(name));
    }
}
