package dev.augie.wend.hook;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JUnit: the hook's decision logic, with no Xposed API. */
public class PipDecisionTest {
    @Test public void forcesPipLeavingTheWatchActivityWhilePlaying() {
        assertTrue(PipDecision.shouldEnter(PipDecision.WATCH_ACTIVITY, false, false));
    }

    @Test public void neverPipsTheBrowseOrOtherActivities() {
        assertFalse(PipDecision.shouldEnter(
                "com.google.android.apps.youtube.app.honeycomb.Shell$HomeActivity", false, false));
    }

    @Test public void neverPipsAFinishingActivity() {
        assertFalse(PipDecision.shouldEnter(PipDecision.WATCH_ACTIVITY, true, false));
    }

    @Test public void neverPipsWhenAlreadyInPip() {
        assertFalse(PipDecision.shouldEnter(PipDecision.WATCH_ACTIVITY, false, true));
    }
}
