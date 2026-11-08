package dev.augie.roadhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings' Dock section: the pinned apps in dock order, each with move up, move down and
 * remove, then Add app. A pin whose app is not installed stays listed as "Not installed"; while
 * the app list is still loading, a pin is listed as loading instead.
 * Changes animate in place: moved rows slide, removed rows fade, and the card resizes.
 */
final class DockEditor implements AppCatalog.Listener {
    interface Host {
        void onPinsChanged(DockPins pins);
        void onAddRequested();
    }

    private static final TimeInterpolator EASE_OUT = new PathInterpolator(0.23f, 1, 0.32f, 1);
    private static final int MOVE_MS = 240;
    private static final int FADE_MS = 160;
    /** A removed row mostly fades before the rows below slide into its place. */
    private static final int FADE_OUT_MS = 120;
    private static final int REMOVE_LEAD_MS = 60;
    /** A row moved with its arrows rises above the row it swaps with. */
    private static final int LIFT_DP = 6;
    private static final int LIFTED_FILL = 0xFF4A535C;
    private static final int ROW_DP = 64;
    private static final int ICON_DP = 40;
    private static final String ADD_ROW = "add";

    private final Context context;
    private final AppCatalog catalog;
    private final Host host;
    private final LinearLayout section;
    private final TextView count;
    private final LinearLayout card;
    private final LinearLayout addRow;
    private final ImageView addIcon;
    private final TextView addTitle;
    private final TextView addHint;
    private final GradientDrawable divider = new GradientDrawable();
    private final Map<String, Row> rows = new HashMap<>();
    private DockPins pins;
    private ValueAnimator resize;
    /** The pin the user just moved, which slides over its neighbor instead of through it. */
    private String lifted;

    DockEditor(Context context, AppCatalog catalog, DockPins pins, Host host) {
        this.context = context;
        this.catalog = catalog;
        this.host = host;
        this.pins = pins;
        section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.sectionLabel(context, context.getString(R.string.settings_dock)),
                new LinearLayout.LayoutParams(0, -1, 1));
        count = Ui.text(context, "", 14, Ui.MUTED);
        count.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        count.setPaddingRelative(0, 0, Ui.dp(context, 16), 0);
        heading.addView(count, new LinearLayout.LayoutParams(-2, -1));
        section.addView(heading, new LinearLayout.LayoutParams(-1, Ui.dp(context, 36)));
        card = Ui.card(context);
        section.addView(card, new LinearLayout.LayoutParams(-1, -2));
        divider.setColor(Ui.DIVIDER);
        addRow = new LinearLayout(context);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setMinimumHeight(Ui.dp(context, ROW_DP));
        addRow.setPaddingRelative(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8));
        LayerDrawable addBackground = new LayerDrawable(new Drawable[]{divider, Ui.ripple(context, 0)});
        // A hairline along the top, inset to the text, separates Add app from the pins.
        addBackground.setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        addBackground.setLayerHeight(0, 1);
        addBackground.setLayerInsetRelative(0, Ui.dp(context, 16), 0, Ui.dp(context, 16), 0);
        addRow.setBackground(addBackground);
        addRow.setOnClickListener(v -> host.onAddRequested());
        addIcon = new ImageView(context);
        GradientDrawable well = Ui.surface(context, 0x2484BFFF, 10);
        addIcon.setBackground(well);
        addIcon.setImageDrawable(new Ui.Symbol(Ui.Icon.ADD, Ui.ACCENT));
        int iconPadding = Ui.dp(context, 9);
        addIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        addRow.addView(addIcon, new LinearLayout.LayoutParams(Ui.dp(context, ICON_DP), Ui.dp(context, ICON_DP)));
        LinearLayout addText = new LinearLayout(context);
        addText.setOrientation(LinearLayout.VERTICAL);
        addTitle = Ui.text(context, context.getString(R.string.settings_add_app), 17, Ui.ACCENT);
        addTitle.setTypeface(Ui.medium());
        addText.addView(addTitle);
        addHint = Ui.text(context, context.getString(R.string.settings_dock_full), 14, Ui.MUTED);
        addHint.setPadding(0, Ui.dp(context, 4), 0, 0);
        addText.addView(addHint);
        LinearLayout.LayoutParams addTextLayout = new LinearLayout.LayoutParams(0, -2, 1);
        addTextLayout.setMarginStart(Ui.dp(context, 16));
        addRow.addView(addText, addTextLayout);
        addRow.setTag(ADD_ROW);
        catalog.addListener(this);
        bind();
    }

    View view() { return section; }

    /** Shows new pins; {@code animate} slides, fades and resizes from the current layout. */
    void setPins(DockPins next, boolean animate) {
        if (!animate || !card.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            pins = next;
            bind();
            return;
        }
        Map<Object, Float> before = new HashMap<>();
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            child.animate().cancel();
            before.put(child.getTag(), child.getTop() + child.getTranslationY());
        }
        int startHeight = card.getHeight();
        if (resize != null) resize.cancel();
        int lead = 0;
        for (String packageName : pins.packages()) {
            if (next.contains(packageName)) continue;
            fadeOut(rows.get(packageName).view);
            lead = REMOVE_LEAD_MS;
        }
        String raised = lifted;
        lifted = null;
        pins = next;
        bind();
        int delay = lead;
        card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < card.getChildCount(); i++) {
                    View child = card.getChildAt(i);
                    Float old = before.get(child.getTag());
                    if (old == null) {
                        child.setAlpha(0);
                        child.animate().alpha(1).setDuration(FADE_MS).setStartDelay(delay + FADE_MS / 2)
                                .setInterpolator(EASE_OUT);
                    } else if (old != child.getTop()) {
                        child.setTranslationY(old - child.getTop());
                        if (child.getTag().equals(raised)) lift(child);
                        child.animate().translationY(0).setDuration(MOVE_MS).setStartDelay(delay)
                                .setInterpolator(EASE_OUT).withEndAction(() -> settle(child));
                    }
                }
                resizeCard(startHeight, card.getHeight(), delay);
                return false; // Draw again at the starting height instead of flashing the final one.
            }
        });
    }

    private void lift(View row) {
        GradientDrawable surface = Ui.surface(context, LIFTED_FILL, 12);
        row.setBackground(surface);
        row.setTranslationZ(Ui.dp(context, LIFT_DP));
    }

    /** The lift fades once the row lands, so it sinks back into the card. */
    private static void settle(View row) {
        if (row.getTranslationZ() == 0) return;
        Drawable surface = row.getBackground();
        ValueAnimator sink = ValueAnimator.ofFloat(1, 0);
        sink.setDuration(FADE_MS);
        sink.setInterpolator(EASE_OUT);
        float z = row.getTranslationZ();
        sink.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            if (row.getBackground() != surface) return; // Lifted again by a newer move.
            surface.setAlpha(Math.round(255 * t));
            row.setTranslationZ(z * t);
        });
        sink.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (row.getBackground() != surface) return;
                row.setBackground(null);
                row.setTranslationZ(0);
            }
        });
        sink.start();
    }

    void release() { catalog.removeListener(this); }

    @Override public void onAppsChanged(AppCatalog catalog) { bind(); }

    private void bind() {
        // While the list loads, no pin can be found yet; that does not mean it is uninstalled.
        int missing = catalog.status() == AppCatalog.Status.LOADING
                ? R.string.settings_pin_loading : R.string.settings_not_installed;
        Map<String, Row> previous = new HashMap<>(rows);
        rows.clear();
        card.removeAllViews();
        List<String> packages = pins.packages();
        for (int i = 0; i < packages.size(); i++) {
            String packageName = packages.get(i);
            Row row = previous.remove(packageName);
            if (row == null) row = new Row(packageName);
            row.bind(catalog.pinnedApp(packageName), missing, i, packages.size());
            rows.put(packageName, row);
            card.addView(row.view, new LinearLayout.LayoutParams(-1, -2));
        }
        card.addView(addRow, new LinearLayout.LayoutParams(-1, -2));
        divider.setAlpha(packages.isEmpty() ? 0 : 255);
        boolean full = pins.isFull();
        addRow.setEnabled(!full);
        addRow.setClickable(!full);
        addIcon.setAlpha(full ? Ui.DISABLED_ALPHA : 1);
        addTitle.setAlpha(full ? Ui.DISABLED_ALPHA : 1);
        addHint.setVisibility(full ? View.VISIBLE : View.GONE);
        count.setText(context.getString(R.string.settings_dock_count, pins.size(), pins.capacity()));
    }

    private void fadeOut(View row) {
        // The overlay keeps drawing the row where it was while the rows below slide up.
        card.getOverlay().add(row);
        row.animate().alpha(0).scaleX(0.97f).scaleY(0.97f).setDuration(FADE_OUT_MS).setStartDelay(0)
                .setInterpolator(EASE_OUT).withEndAction(() -> {
                    card.getOverlay().remove(row);
                    row.setAlpha(1);
                    row.setScaleX(1);
                    row.setScaleY(1);
                });
    }

    private void resizeCard(int from, int to, int delay) {
        ViewGroup.LayoutParams layout = card.getLayoutParams();
        if (from == to) return;
        layout.height = from;
        card.setLayoutParams(layout);
        resize = ValueAnimator.ofInt(from, to);
        resize.setStartDelay(delay);
        resize.setDuration(MOVE_MS);
        resize.setInterpolator(EASE_OUT);
        resize.addUpdateListener(animation -> {
            layout.height = (int) animation.getAnimatedValue();
            card.setLayoutParams(layout);
        });
        resize.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                layout.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                card.setLayoutParams(layout);
            }
        });
        resize.start();
    }

    /** One pinned app: icon, name, and its move and remove buttons. */
    private final class Row {
        final String packageName;
        final LinearLayout view;
        final ImageView icon;
        final TextView title;
        final TextView status;
        final ImageButton up;
        final ImageButton down;
        final ImageButton remove;

        Row(String packageName) {
            this.packageName = packageName;
            view = new LinearLayout(context);
            view.setTag(packageName);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(Ui.dp(context, ROW_DP));
            view.setPaddingRelative(Ui.dp(context, 16), 0, Ui.dp(context, 4), 0);
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.addView(icon, new LinearLayout.LayoutParams(Ui.dp(context, ICON_DP), Ui.dp(context, ICON_DP)));
            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            title = Ui.text(context, "", 17, Ui.TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            text.addView(title, new LinearLayout.LayoutParams(-1, -2));
            status = Ui.text(context, "", 14, Ui.MUTED);
            status.setPadding(0, Ui.dp(context, 4), 0, 0);
            text.addView(status, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(0, -2, 1);
            textLayout.setMarginStart(Ui.dp(context, 16));
            textLayout.setMarginEnd(Ui.dp(context, 8));
            view.addView(text, textLayout);
            up = control(Ui.Icon.UP, () -> move(-1));
            down = control(Ui.Icon.DOWN, () -> move(1));
            remove = control(Ui.Icon.REMOVE, () -> host.onPinsChanged(pins.remove(packageName)));
            view.addView(up, square());
            view.addView(down, square());
            view.addView(remove, square());
        }

        /** {@code missing} explains a null {@code app}: not installed, or not loaded yet. */
        void bind(LauncherApp app, int missing, int index, int size) {
            String label = app == null ? packageName : app.label;
            title.setText(label);
            title.setTextColor(app == null ? Ui.MUTED : Ui.TEXT);
            status.setText(missing);
            status.setVisibility(app == null ? View.VISIBLE : View.GONE);
            if (app == null) icon.setImageDrawable(placeholder());
            else icon.setImageDrawable(new BitmapDrawable(context.getResources(), app.icon));
            up.setContentDescription(context.getString(R.string.settings_move_up, label));
            down.setContentDescription(context.getString(R.string.settings_move_down, label));
            remove.setContentDescription(context.getString(R.string.settings_remove, label));
            enable(up, index > 0);
            enable(down, index < size - 1);
        }

        private void move(int offset) {
            int from = pins.packages().indexOf(packageName);
            int to = from + offset;
            if (from < 0 || to < 0 || to >= pins.size()) return;
            lifted = packageName;
            host.onPinsChanged(pins.move(from, to));
        }

        private GradientDrawable placeholder() {
            GradientDrawable outline = new GradientDrawable();
            outline.setCornerRadius(Ui.dp(context, ICON_DP) * 0.23f);
            outline.setStroke(Ui.dp(context, 1), 0x80A8B9CC, Ui.dp(context, 4), Ui.dp(context, 3));
            return outline;
        }
    }

    private ImageButton control(Ui.Icon glyph, Runnable action) {
        ImageButton button = Ui.iconButton(context, glyph, "", action);
        button.setImageDrawable(new Ui.Symbol(glyph, Ui.MUTED));
        return button;
    }

    private static void enable(View control, boolean enabled) {
        control.setEnabled(enabled);
        control.setAlpha(enabled ? 1 : Ui.DISABLED_ALPHA);
    }

    private LinearLayout.LayoutParams square() {
        return new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48));
    }
}
