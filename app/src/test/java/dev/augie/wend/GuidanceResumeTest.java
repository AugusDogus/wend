package dev.augie.wend;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;
import android.content.res.Resources;
import org.robolectric.RuntimeEnvironment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class GuidanceResumeTest {
    private static final String MAPS = "com.google.android.apps.maps";
    private static final String SERVICE = "dev.augie.wend/dev.augie.wend.GuidanceResume";
    private static final Set<String> START = Collections.singleton("Start");

    private static AccessibilityNodeInfo node(String type, CharSequence text, AccessibilityNodeInfo... children) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(MAPS);
        node.setClassName(type);
        // Maps names its buttons by content description; the text is on a child view.
        if ("android.widget.Button".equals(type)) node.setContentDescription(text);
        else node.setText(text);
        node.setEnabled(true);
        node.setVisibleToUser(true);
        for (AccessibilityNodeInfo child : children) shadowOf(node).addChild(child);
        return node;
    }

    /** Maps' restored route preview, as the emulator's accessibility tree showed it. */
    private static AccessibilityNodeInfo routePreview(AccessibilityNodeInfo start) {
        AccessibilityNodeInfo from = node("android.widget.TextView", "Your location");
        from.setContentDescription("Start location, Your location");
        return node("android.widget.FrameLayout", null,
                node("android.widget.LinearLayout", null, from,
                        node("android.widget.FrameLayout", "Swap start and destination")),
                node("android.widget.LinearLayout", null, start,
                        node("android.widget.Button", "Add stops"), node("android.widget.Button", "Share")));
    }

    @Test public void findsTheStartButtonOfMapsRoutePreview() {
        AccessibilityNodeInfo start = node("android.widget.Button", "Start", node("android.view.View", "Start"));
        AccessibilityNodeInfo found = GuidanceResume.startButton(routePreview(start), MAPS, START);
        assertNotNull(found);
        assertEquals("android.widget.Button", found.getClassName().toString());
        assertEquals("Start", found.getContentDescription().toString());
    }

    @Test public void onlyAButtonNamedExactlyStartCounts() {
        assertNull("A start location is not the Start button",
                GuidanceResume.startButton(routePreview(node("android.widget.Button", "Start location")), MAPS, START));
        assertNull("Text that is not a button",
                GuidanceResume.startButton(routePreview(node("android.widget.TextView", "Start")), MAPS, START));
    }

    @Test public void aButtonNamedByItsTextCountsToo() {
        AccessibilityNodeInfo start = node("android.widget.Button", null);
        start.setText("Start");
        assertNotNull(GuidanceResume.startButton(routePreview(start), MAPS, START));
    }

    @Test public void aStartButtonThatCannotBePressedYetIsLeftAlone() {
        AccessibilityNodeInfo disabled = node("android.widget.Button", "Start");
        disabled.setEnabled(false);
        assertNull(GuidanceResume.startButton(routePreview(disabled), MAPS, START));
        AccessibilityNodeInfo hidden = node("android.widget.Button", "Start");
        hidden.setVisibleToUser(false);
        assertNull(GuidanceResume.startButton(routePreview(hidden), MAPS, START));
    }

    @Test public void anotherAppsStartButtonIsNeverPressed() {
        AccessibilityNodeInfo start = node("android.widget.Button", "Start");
        AccessibilityNodeInfo root = routePreview(start);
        assertNull(GuidanceResume.startButton(root, "com.example.other", START));
    }

    /**
     * Maps shows its Start button in the device's language, so its label is read from Maps' own
     * strings, by the names Maps 26.43 gives them. Here Wend's resources stand in for Maps'.
     */
    @Test public void theStartButtonsLabelComesFromMapsOwnStrings() {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        String wend = RuntimeEnvironment.getApplication().getPackageName();
        assertEquals(new HashSet<>(Arrays.asList("Arrived", "Navigation")), GuidanceResume.startLabels(resources, wend,
                new String[]{"maps_arrived", "NO_SUCH_STRING", "settings_navigation"}, "Start"));
        assertEquals("Maps renamed them all", START,
                GuidanceResume.startLabels(resources, wend, new String[]{"NO_SUCH_STRING"}, "Start"));
        assertEquals("Maps' strings cannot be read", START,
                GuidanceResume.startLabels(null, wend, new String[]{"maps_arrived"}, "Start"));
    }

    @Test public void aButtonWithAnyOfMapsLabelsCounts() {
        AccessibilityNodeInfo start = node("android.widget.Button", "Starten");
        assertNotNull(GuidanceResume.startButton(routePreview(start), MAPS, new HashSet<>(Arrays.asList("Iniciar", "Starten"))));
        assertNull(GuidanceResume.startButton(routePreview(start), MAPS, START));
    }

    @Test public void theServiceIsAddedToAndroidsEnabledAccessibilityServicesAndRemovedAgain() {
        assertEquals(SERVICE, GuidanceResume.enabled(null, SERVICE));
        assertEquals(SERVICE, GuidanceResume.enabled("", SERVICE));
        assertEquals("a/b:" + SERVICE, GuidanceResume.enabled("a/b", SERVICE));
        assertEquals("Once", "a/b:" + SERVICE, GuidanceResume.enabled("a/b:" + SERVICE, SERVICE));
        assertEquals("a/b", GuidanceResume.disabled("a/b:" + SERVICE, SERVICE));
        assertEquals("a/b:c/d", GuidanceResume.disabled("a/b:" + SERVICE + ":c/d", SERVICE));
        assertEquals("", GuidanceResume.disabled(SERVICE, SERVICE));
        assertEquals("", GuidanceResume.disabled(null, SERVICE));
    }
}
