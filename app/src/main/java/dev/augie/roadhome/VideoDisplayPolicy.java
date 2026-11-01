package dev.augie.roadhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Android 10 exposes fixed-to-user rotation only through WindowManager's shell endpoint. */
final class VideoDisplayPolicy {
    private VideoDisplayPolicy() { }

    static void fixOrientation(int displayId) throws IOException {
        if (displayId <= 0) throw new IllegalArgumentException("Expected an owned virtual display");
        // Runs as our platform UID. Never changes the physical display's rotation policy.
        Process command = new ProcessBuilder("/system/bin/cmd", "window", "set-fix-to-user-rotation",
                "-d", Integer.toString(displayId), "enabled").redirectErrorStream(true).start();
        try {
            if (!command.waitFor(2, TimeUnit.SECONDS)) {
                throw new IOException("WindowManager did not fix video display " + displayId + " orientation within 2 seconds");
            }
            if (command.exitValue() != 0) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] bytes = new byte[512];
                int count;
                while (output.size() < 4096 && (count = command.getInputStream().read(bytes)) != -1) {
                    output.write(bytes, 0, count);
                }
                throw new IOException("WindowManager rejected video display " + displayId
                        + " orientation policy (exit " + command.exitValue() + "): " + output.toString("UTF-8"));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Video display orientation setup was interrupted", error);
        } finally {
            command.destroy();
            command.getInputStream().close();
            command.getOutputStream().close();
        }
    }
}
