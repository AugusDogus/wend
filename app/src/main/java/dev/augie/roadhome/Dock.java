package dev.augie.roadhome;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import java.util.HashMap;
import java.util.Map;

/**
 * The launcher's left column: the clock and date, the pinned apps, then the fixed Apps and
 * Settings buttons. Pins draw the catalog's cached icons and follow installs, removals and
 * pin changes live. A pin whose app is not installed is skipped here but stays stored. If the
 * app list cannot load, the pins come from PackageManager instead (see AppCatalog.pinnedApp).
 */
@SuppressLint("ViewConstructor")
final class Dock extends LinearLayout implements AppCatalog.Listener {
    interface Actions {
        void openApp(String packageName);
        void showApps();
        void showSettings();
    }

    private static final int FADE_MS = 150;

    private final AppCatalog catalog;
    private final Actions actions;
    private final LinearLayout slots;
    private final Map<String, ImageButton> buttons = new HashMap<>();
    private DockPins pins;
    /** The pins are reserved as empty slots until the catalog first loads. */
    private boolean waitingForApps = true;

    Dock(Context context, AppCatalog catalog, DockPins pins, Actions actions) {
        super(context);
        this.catalog = catalog;
        this.actions = actions;
        this.pins = pins;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 6), Ui.dp(context, DockLayout.BOTTOM_PADDING_DP));
        setBackgroundColor(0xFF15242E);
        TextClock clock = new TextClock(context);
        if (BuildConfig.WINDOWED_MAPS) clock.setVisibility(View.GONE);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Ui.TEXT);
        clock.setTextSize(18);
        clock.setGravity(Gravity.CENTER);
        addView(clock, new LayoutParams(-1, Ui.dp(context, DockLayout.CLOCK_DP)));
        TextClock date = new TextClock(context);
        date.setFormat12Hour("EEE d");
        date.setFormat24Hour("EEE d");
        date.setTextColor(Ui.MUTED);
        date.setTextSize(12);
        date.setGravity(Gravity.CENTER);
        addView(date, new LayoutParams(-1, Ui.dp(context, DockLayout.DATE_DP)));
        addView(new View(context), new LayoutParams(1, 0, 1));
        slots = new LinearLayout(context);
        slots.setOrientation(VERTICAL);
        slots.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(slots, new LayoutParams(-1, -2));
        addView(Ui.iconButton(context, Ui.Icon.APPS, "Apps", actions::showApps), slot());
        addView(new View(context), new LayoutParams(1, 0, 1));
        addView(Ui.iconButton(context, Ui.Icon.SETTINGS, "Settings", actions::showSettings), slot());
        catalog.addListener(this);
        render();
    }

    void setPins(DockPins next) {
        pins = next;
        render();
    }

    void release() { catalog.removeListener(this); }

    @Override public void onAppsChanged(AppCatalog catalog) { render(); }

    private void render() {
        if (catalog.status() == AppCatalog.Status.LOADING) {
            // Before the first load, hold the pins' space so the Apps button does not jump when the
            // icons arrive. A retry after a failed load keeps the buttons already shown until it ends.
            if (waitingForApps) {
                slots.removeAllViews();
                for (int i = 0; i < pins.size(); i++) slots.addView(new View(getContext()), slot());
            }
            return;
        }
        slots.removeAllViews();
        Map<String, ImageButton> previous = new HashMap<>(buttons);
        buttons.clear();
        for (String packageName : pins.packages()) {
            LauncherApp app = catalog.pinnedApp(packageName);
            if (app == null) continue;
            ImageButton button = previous.remove(packageName);
            if (button == null) button = button(packageName);
            if (button.getTag() != app) bind(button, app);
            buttons.put(packageName, button);
            slots.addView(button, slot());
        }
        if (waitingForApps) {
            waitingForApps = false;
            if (ValueAnimator.areAnimatorsEnabled()) {
                slots.setAlpha(0);
                slots.animate().alpha(1).setDuration(FADE_MS);
            }
        }
    }

    private ImageButton button(String packageName) {
        ImageButton button = new ImageButton(getContext());
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setBackground(Ui.ripple(getContext(), 16));
        int padding = Ui.dp(getContext(), 4);
        button.setPadding(padding, padding, padding, padding);
        button.setOnClickListener(v -> actions.openApp(packageName));
        return button;
    }

    private void bind(ImageButton button, LauncherApp app) {
        button.setTag(app);
        button.setImageDrawable(new BitmapDrawable(getResources(), app.icon));
        button.setContentDescription(app.label);
    }

    private LayoutParams slot() {
        int size = Ui.dp(getContext(), DockLayout.BUTTON_DP);
        LayoutParams layout = new LayoutParams(size, size);
        layout.topMargin = Ui.dp(getContext(), DockLayout.GAP_DP);
        return layout;
    }
}
