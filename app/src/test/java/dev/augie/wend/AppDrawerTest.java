package dev.augie.wend;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** The drawer at the head unit's 1280 x 720 px, 240 dpi screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "w853dp-h480dp-land-hdpi")
public class AppDrawerTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    private final FrostedBackdrop backdrop = new FrostedBackdrop(activity);
    private AppDrawer drawer;

    @After public void release() {
        if (drawer != null) drawer.release();
        backdrop.release();
    }

    @Test public void theHeaderHasOnlyTheSearchFieldCenteredUnderTheHandle() {
        View window = open();

        assertTrue("Dragging, Back, the backdrop and Home close the drawer",
                views(window, ImageButton.class).isEmpty());
        assertCentered(window, search(window));
    }

    @Test public void theSearchFieldStaysCenteredRightToLeft() {
        View window = open();
        // Robolectric ignores the ldrtl qualifier, so the window is mirrored directly.
        window.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        window.requestLayout();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(View.LAYOUT_DIRECTION_RTL, search(window).getLayoutDirection());
        assertCentered(window, search(window));
    }

    private View open() {
        drawer = new AppDrawer(activity, TestApps.loadedCatalog(activity), backdrop);
        drawer.show();
        shadowOf(Looper.getMainLooper()).idle();
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    private static EditText search(View window) {
        List<EditText> fields = views(window, EditText.class);
        assertEquals(1, fields.size());
        return fields.get(0);
    }

    private static void assertCentered(View window, View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        assertTrue("The field is laid out", view.getWidth() > 0);
        int start = location[0];
        int end = window.getWidth() - location[0] - view.getWidth();
        assertEquals("Equal space on both sides of the search field", start, end);
    }

    private static <T extends View> List<T> views(View view, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (view.getVisibility() != View.VISIBLE) return found;
        if (type.isInstance(view)) found.add(type.cast(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(views(group.getChildAt(i), type));
        }
        return found;
    }
}
