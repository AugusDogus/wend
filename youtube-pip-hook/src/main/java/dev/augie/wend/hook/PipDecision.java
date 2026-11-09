package dev.augie.wend.hook;

/**
 * When to force an app into its own picture-in-picture, kept free of the Xposed API so it is
 * unit-tested with plain JUnit. The hook forces PiP only as the user leaves the watch activity for
 * another task, never on the browse UI or when the activity is going away or already pinned.
 */
public final class PipDecision {
    /** YouTube's watch activity; forcing PiP on any other activity would pin its browse UI. */
    public static final String WATCH_ACTIVITY =
            "com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity";

    private PipDecision() { }

    /**
     * @param activityClassName the leaving activity's class name
     * @param finishing whether the activity is finishing (then there is nothing to keep floating)
     * @param alreadyInPip whether it is already in picture-in-picture
     * @return whether the hook should call {@code enterPictureInPictureMode} now
     */
    public static boolean shouldEnter(String activityClassName, boolean finishing, boolean alreadyInPip) {
        return WATCH_ACTIVITY.equals(activityClassName) && !finishing && !alreadyInPip;
    }
}
