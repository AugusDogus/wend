package dev.augie.wend;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.Consumer;

/**
 * The settings sheet's building blocks in the launcher's dark glass style: section labels
 * over rounded cards, hairline dividers, and rows of at least 64 dp whose whole width is the
 * touch target. Layout is relative (start/end), so rows mirror in right-to-left languages.
 */
final class SettingsRows {
    static final int ROW_DP = 64;
    static final int SECTION_GAP_DP = 20;

    private final Context context;

    SettingsRows(Context context) { this.context = context; }

    /** A muted label, then its card; {@code gapAbove} separates it from the previous section. */
    void addSection(LinearLayout column, int label, View card, boolean gapAbove) {
        LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(-1, Ui.dp(context, 36));
        if (gapAbove) labelLayout.topMargin = Ui.dp(context, SECTION_GAP_DP);
        column.addView(Ui.sectionLabel(context, context.getString(label)), labelLayout);
        column.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Stacks rows in a card with hairlines between them, inset to the text. */
    void addRows(LinearLayout card, View... rows) {
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, 1);
                layout.setMarginStart(Ui.dp(context, 16));
                layout.setMarginEnd(Ui.dp(context, 16));
                card.addView(divider(), layout);
            }
            card.addView(rows[i], new LinearLayout.LayoutParams(-1, -2));
        }
    }

    View divider() {
        View line = new View(context);
        line.setBackgroundColor(Ui.DIVIDER);
        return line;
    }

    /** A label at the start and a fixed-width control at the end. */
    View choiceRow(int label, View control, int controlWidthDp) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(context, context.getString(label), 17, Ui.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams controlLayout = new LinearLayout.LayoutParams(
                Ui.dp(context, controlWidthDp), Ui.dp(context, 48));
        controlLayout.setMarginStart(Ui.dp(context, 12));
        row.addView(control, controlLayout);
        return row;
    }

    /** A two-line switch row: the whole row toggles, and every change is saved at once. */
    Switch toggle(int title, int hint, boolean checked, Consumer<Boolean> save) {
        Switch toggle = new Switch(context);
        SpannableStringBuilder text = new SpannableStringBuilder(context.getString(title));
        int start = text.length();
        text.append('\n').append(context.getString(hint));
        text.setSpan(new AbsoluteSizeSpan(14, true), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Ui.MUTED), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        toggle.setText(text);
        toggle.setTextSize(17);
        toggle.setTextColor(Ui.TEXT);
        toggle.setLineSpacing(Ui.dp(context, 4), 1);
        toggle.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        toggle.setMinHeight(Ui.dp(context, 72));
        int padding = Ui.dp(context, 16);
        toggle.setPaddingRelative(padding, Ui.dp(context, 10), padding, Ui.dp(context, 10));
        toggle.setSwitchPadding(padding);
        toggle.setThumbDrawable(thumb());
        toggle.setTrackDrawable(track());
        toggle.setShowText(false);
        toggle.setBackground(Ui.ripple(context, 0));
        toggle.setChecked(checked); // Before the listener, so building the row saves nothing.
        toggle.setOnCheckedChangeListener((CompoundButton button, boolean on) -> save.accept(on));
        return toggle;
    }

    /** A row that opens another screen, marked with a forward chevron. */
    TextView link(int label, Runnable open) {
        TextView row = Ui.text(context, context.getString(label), 17, Ui.TEXT);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        row.setMinHeight(Ui.dp(context, ROW_DP));
        row.setPaddingRelative(Ui.dp(context, 16), 0, Ui.dp(context, 12), 0);
        Ui.Symbol chevron = new Ui.Symbol(Ui.Icon.FORWARD, Ui.MUTED);
        int size = Ui.dp(context, 20);
        chevron.setBounds(0, 0, size, size);
        row.setCompoundDrawablesRelative(null, null, chevron, null);
        row.setBackground(Ui.ripple(context, 0));
        row.setOnClickListener(v -> open.run());
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        return row;
    }

    /** [leading button] [content] [balancing space], so the content stays centered. */
    View header(Ui.Icon icon, int description, Runnable action, View content) {
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.iconButton(context, icon, context.getString(description), action),
                new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
        LinearLayout.LayoutParams contentLayout = new LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1);
        contentLayout.setMargins(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0);
        header.addView(content, contentLayout);
        header.addView(new View(context), new LinearLayout.LayoutParams(Ui.dp(context, 48), 1));
        return header;
    }

    TextView title(int text) {
        TextView title = Ui.text(context, context.getString(text), 20, Ui.TEXT);
        title.setTypeface(Ui.medium());
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        return title;
    }

    private InsetDrawable thumb() {
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        int size = Ui.dp(context, 24);
        knob.setSize(size, size);
        knob.setColor(checkedColors(0xFF0D2A44, Ui.MUTED));
        // The vertical inset centers the round knob in the 32 dp track.
        return new InsetDrawable(knob, 0, Ui.dp(context, 4), 0, Ui.dp(context, 4));
    }

    private GradientDrawable track() {
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Ui.dp(context, 16));
        track.setSize(Ui.dp(context, 56), Ui.dp(context, 32));
        track.setColor(checkedColors(Ui.ACCENT, 0x2EFFFFFF));
        int inset = Ui.dp(context, 4);
        track.setPadding(inset, inset, inset, inset);
        return track;
    }

    private static ColorStateList checkedColors(int checked, int unchecked) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{checked, unchecked});
    }
}
