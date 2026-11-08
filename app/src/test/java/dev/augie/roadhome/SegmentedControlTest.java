package dev.augie.roadhome;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SegmentedControlTest {
    private final List<LauncherSettings.MapSide> chosen = new ArrayList<>();
    private SegmentedControl<LauncherSettings.MapSide> control;

    @Before public void build() {
        control = new SegmentedControl<>(RuntimeEnvironment.getApplication(), Arrays.asList(
                new SegmentedControl.Option<>(LauncherSettings.MapSide.LEFT, "Left"),
                new SegmentedControl.Option<>(LauncherSettings.MapSide.RIGHT, "Right")),
                LauncherSettings.MapSide.RIGHT, chosen::add);
    }

    @Test public void tappingAnotherSegmentReportsItOnce() {
        control.getChildAt(0).performClick();
        assertEquals(Arrays.asList(LauncherSettings.MapSide.LEFT), chosen);
        assertTrue(control.getChildAt(0).isSelected());
        assertFalse(control.getChildAt(1).isSelected());
    }

    @Test public void tappingTheChosenSegmentChangesNothing() {
        control.getChildAt(1).performClick();
        assertTrue(chosen.isEmpty());
    }

    @Test public void showingAStoredValueDoesNotReportAChoice() {
        control.select(LauncherSettings.MapSide.LEFT);
        assertTrue(chosen.isEmpty());
        assertTrue(control.getChildAt(0).isSelected());
    }

    @Test public void segmentsAreAnnouncedAsCheckableRadioButtons() {
        AccessibilityNodeInfo chosenNode = AccessibilityNodeInfo.obtain();
        control.getChildAt(1).onInitializeAccessibilityNodeInfo(chosenNode);
        assertEquals("android.widget.RadioButton", chosenNode.getClassName().toString());
        assertTrue(chosenNode.isCheckable());
        assertTrue(chosenNode.isChecked());
        AccessibilityNodeInfo otherNode = AccessibilityNodeInfo.obtain();
        control.getChildAt(0).onInitializeAccessibilityNodeInfo(otherNode);
        assertFalse(otherNode.isChecked());
    }

    @Test public void everySegmentIsA48dpTouchTargetInItsSettingsRow() {
        Context context = RuntimeEnvironment.getApplication();
        View row = new SettingsRows(context).choiceRow(R.string.settings_map_position, control, 290);
        // As in the settings sheet's layout card: a 64 dp row beside the preview.
        row.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(context, 500), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Ui.dp(context, SettingsRows.ROW_DP), View.MeasureSpec.EXACTLY));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());

        int target = Ui.dp(context, 48);
        for (int i = 0; i < control.getChildCount(); i++) {
            View segment = control.getChildAt(i);
            assertTrue("Segment " + i + " is " + segment.getWidth() + " x " + segment.getHeight() + " px",
                    segment.getWidth() >= target && segment.getHeight() >= target);
        }
    }
}
