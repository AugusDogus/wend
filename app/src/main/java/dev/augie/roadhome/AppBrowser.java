package dev.augie.roadhome;

import android.app.Activity;
import android.graphics.drawable.BitmapDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A searchable grid of the catalog's apps, shared by the app drawer and the dock's app
 * picker. Tiles are built once and reused across catalog changes; search and the hidden
 * filter only toggle their visibility. Place {@link #search()} in a sheet header and
 * {@link #body()} below it.
 */
final class AppBrowser implements AppCatalog.Listener {
    interface Chooser { void onChosen(LauncherApp app); }

    static final int COLUMNS = 5;
    private static final int CELL_DP = 100;

    private final Activity activity;
    private final AppCatalog catalog;
    private final Chooser chooser;
    private final int allHiddenMessage;
    private final int hiddenMatchOne;
    private final int hiddenMatchMany;
    private final EditText search;
    private final TextView status;
    private final ScrollView scroll;
    private final AppGrid grid;
    private final FrameLayout body;
    private final Map<LauncherApp.Key, TextView> tiles = new HashMap<>();
    /** Apps the browser leaves out, such as apps already in the dock. */
    private Predicate<LauncherApp> hidden = app -> false;

    /**
     * {@code allHiddenMessage} explains an empty grid when every app is hidden. When only
     * hidden apps match the query, {@code hiddenMatchOne} is formatted with that app's label
     * and {@code hiddenMatchMany} with the query.
     */
    AppBrowser(Activity activity, AppCatalog catalog, String searchHint, int allHiddenMessage,
            int hiddenMatchOne, int hiddenMatchMany, Chooser chooser) {
        this.activity = activity;
        this.catalog = catalog;
        this.chooser = chooser;
        this.allHiddenMessage = allHiddenMessage;
        this.hiddenMatchOne = hiddenMatchOne;
        this.hiddenMatchMany = hiddenMatchMany;
        search = searchField(searchHint);
        body = new FrameLayout(activity);
        grid = new AppGrid(activity, COLUMNS, Ui.dp(activity, CELL_DP));
        grid.setPadding(0, 0, 0, Ui.dp(activity, 8));
        scroll = new ScrollView(activity);
        // Rows fade out under the header and at the bottom edge, hinting at more apps.
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(activity, 24));
        scroll.addView(grid);
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(activity, "", 17, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        int inset = Ui.dp(activity, 24);
        status.setPadding(inset, 0, inset, 0);
        body.addView(status, new FrameLayout.LayoutParams(-1, Ui.dp(activity, 48), Gravity.TOP));
        catalog.addListener(this);
        bind();
    }

    EditText search() { return search; }
    View body() { return body; }
    int size() { return tiles.size(); }

    void setHidden(Predicate<LauncherApp> next) {
        hidden = next;
        filter();
    }

    /** Clears the query, hides the keyboard and scrolls back to the first row. */
    void reset() {
        search.setText("");
        hideKeyboard();
        scroll.scrollTo(0, 0);
    }

    void hideKeyboard() {
        search.clearFocus();
        InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    void release() { catalog.removeListener(this); }

    @Override public void onAppsChanged(AppCatalog catalog) { bind(); }

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
        String query = query();
        List<String> hiddenMatches = new ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View tile = grid.getChildAt(i);
            LauncherApp app = (LauncherApp) tile.getTag();
            boolean matches = app.label.toLowerCase(Locale.ROOT).contains(query);
            boolean isHidden = hidden.test(app);
            if (matches && isHidden) hiddenMatches.add(app.label);
            tile.setVisibility(matches && !isHidden ? View.VISIBLE : View.GONE);
        }
        showStatus(hiddenMatches);
    }

    private String typedQuery() { return search.getText().toString().trim(); }
    private String query() { return typedQuery().toLowerCase(Locale.ROOT); }

    private void showStatus(List<String> hiddenMatches) {
        switch (catalog.status()) {
            case LOADING: status.setText(R.string.loading_apps); break;
            case FAILED: status.setText(R.string.apps_unavailable); break;
            case READY:
                if (catalog.apps().isEmpty()) {
                    status.setText(R.string.no_apps);
                } else if (visibleTiles() > 0) {
                    status.setVisibility(View.GONE);
                    return;
                } else if (query().isEmpty()) {
                    status.setText(allHiddenMessage);
                } else if (hiddenMatches.size() == 1) {
                    status.setText(activity.getString(hiddenMatchOne, hiddenMatches.get(0)));
                } else if (!hiddenMatches.isEmpty()) {
                    status.setText(activity.getString(hiddenMatchMany, typedQuery()));
                } else {
                    status.setText(activity.getString(R.string.no_matching_apps, typedQuery()));
                }
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
        tile.setBackground(Ui.ripple(activity, 12));
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
        tile.setOnClickListener(v -> chooser.onChosen(app));
    }

    private EditText searchField(String hint) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setTextSize(17);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.MUTED);
        field.setSingleLine(true);
        field.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
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
        field.setPaddingRelative(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        return field;
    }
}
