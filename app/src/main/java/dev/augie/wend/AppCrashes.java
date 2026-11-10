package dev.augie.wend;

import android.os.DropBoxManager;

/**
 * Whether an app crashed, from Android's drop box, where Android 10 files each crash with the
 * process's name on its first line. Android reports a crashed app's task like one the driver closed
 * ({@code onTaskRemovalStarted}), so nothing else tells the two apart. Reading the drop box takes
 * {@code READ_LOGS} and usage access, which the system user has.
 */
final class AppCrashes {
    /** Java and native crashes, of apps the driver installed and of the system image's. Not "anr": that app is not gone. */
    private static final String[] TAGS = {"data_app_crash", "system_app_crash", "data_app_native_crash",
            "system_app_native_crash"};
    /** Enough of an entry for its first line. */
    private static final int HEADER_BYTES = 512;

    private AppCrashes() { }

    /**
     * @param since a time in wall-clock ms
     * @return whether Android filed a crash of {@code packageName}'s main process after {@code since}
     */
    static boolean since(DropBoxManager dropBox, String packageName, long since) {
        String process = "Process: " + packageName + "\n";
        for (String tag : TAGS) {
            long after = since;
            for (DropBoxManager.Entry entry = dropBox.getNextEntry(tag, after); entry != null;
                    entry = dropBox.getNextEntry(tag, after)) {
                try {
                    String text = entry.getText(HEADER_BYTES);
                    if (text != null && text.startsWith(process)) return true;
                    after = entry.getTimeMillis();
                } finally { entry.close(); }
            }
        }
        return false;
    }
}
