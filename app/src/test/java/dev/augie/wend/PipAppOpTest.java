package dev.augie.wend;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** The app-op state machine: allowed only during a deliberate float, ignored otherwise and on failure. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PipAppOpTest {
    private static final String PACKAGE = EmbeddedMaps.PACKAGE;
    private static final int UID = 10123;
    private final Context context = RuntimeEnvironment.getApplication();
    private final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);

    private void install() {
        PackageInfo info = new PackageInfo();
        info.packageName = PACKAGE;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = PACKAGE;
        info.applicationInfo.uid = UID;
        shadowOf(context.getPackageManager()).installPackage(info);
    }

    private int mode() {
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, UID, PACKAGE);
    }

    private void setMode(int mode) {
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, UID, PACKAGE, mode);
    }

    @Test public void restingHoldsIgnoredFromTheAllowedDefault() throws Exception {
        install();
        assertEquals(AppOpsManager.MODE_ALLOWED, mode());
        PipAppOp op = new PipAppOp(PACKAGE);
        assertEquals(PipAppOp.Outcome.CHANGED, op.holdResting(context));
        assertEquals(AppOpsManager.MODE_IGNORED, mode());
        assertFalse(op.floating());
        // Idempotent once already ignored.
        assertEquals(PipAppOp.Outcome.UNCHANGED, op.holdResting(context));
        assertEquals(AppOpsManager.MODE_IGNORED, mode());
    }

    @Test public void restingLeavesADeliberateSettingsChoiceAlone() throws Exception {
        install();
        setMode(AppOpsManager.MODE_ERRORED);
        PipAppOp op = new PipAppOp(PACKAGE);
        assertEquals(PipAppOp.Outcome.UNCHANGED, op.holdResting(context));
        assertEquals(AppOpsManager.MODE_ERRORED, mode());
    }

    @Test public void aDeliberateFloatAllowsThenRestoresToIgnored() throws Exception {
        install();
        PipAppOp op = new PipAppOp(PACKAGE);
        op.holdResting(context);
        assertEquals(AppOpsManager.MODE_IGNORED, mode());

        assertEquals(PipAppOp.Outcome.CHANGED, op.allowForFloat(context));
        assertTrue(op.floating());
        assertEquals("Allowed only while floating", AppOpsManager.MODE_ALLOWED, mode());

        assertEquals(PipAppOp.Outcome.CHANGED, op.restore(context));
        assertFalse(op.floating());
        assertEquals("Ignored again after the float", AppOpsManager.MODE_IGNORED, mode());
    }

    @Test public void holdingRestingWhileFloatingNeverChangesTheOp() throws Exception {
        install();
        PipAppOp op = new PipAppOp(PACKAGE);
        op.allowForFloat(context);
        assertEquals(AppOpsManager.MODE_ALLOWED, mode());
        // A resting request during a float must not dismiss the live PiP by changing its op.
        assertEquals(PipAppOp.Outcome.UNCHANGED, op.holdResting(context));
        assertEquals(AppOpsManager.MODE_ALLOWED, mode());
        assertTrue(op.floating());
    }

    @Test public void restoringAfterAFailedFloatLeavesItIgnored() throws Exception {
        install();
        PipAppOp op = new PipAppOp(PACKAGE);
        op.allowForFloat(context);
        // The float's task move failed: the caller restores in its finally.
        op.restore(context);
        assertEquals(AppOpsManager.MODE_IGNORED, mode());
        assertFalse(op.floating());
    }

    @Test public void restoringWithoutAFloatStillEndsIgnored() throws Exception {
        install();
        setMode(AppOpsManager.MODE_ALLOWED);
        PipAppOp op = new PipAppOp(PACKAGE);
        assertEquals(PipAppOp.Outcome.CHANGED, op.restore(context));
        assertEquals(AppOpsManager.MODE_IGNORED, mode());
    }

    @Test public void allowForFloatIsIdempotent() throws Exception {
        install();
        PipAppOp op = new PipAppOp(PACKAGE);
        op.holdResting(context);
        assertEquals(PipAppOp.Outcome.CHANGED, op.allowForFloat(context));
        assertEquals(PipAppOp.Outcome.UNCHANGED, op.allowForFloat(context));
        assertEquals(AppOpsManager.MODE_ALLOWED, mode());
        assertTrue(op.floating());
    }

    @Test public void aMissingPackageIsReportedAndLeavesTheFloatOff() throws Exception {
        PipAppOp op = new PipAppOp(PACKAGE);
        assertEquals(PipAppOp.Outcome.NOT_INSTALLED, op.allowForFloat(context));
        assertFalse(op.floating());
        assertEquals(PipAppOp.Outcome.NOT_INSTALLED, op.holdResting(context));
    }
}
