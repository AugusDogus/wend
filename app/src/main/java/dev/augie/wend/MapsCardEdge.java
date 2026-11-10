package dev.augie.wend;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Finds where Google Maps' own next-turn card ends in its panel display, about once a second while
 * a floating Maps window needs it. The card grows with what it says: in the 70% panel it ends 143 px
 * down with one road line, 188 px with two, and 273 px with lane arrows, a darker strip along its
 * bottom. Maps tells no one, so this reads two columns of the display's pixels, down the card's
 * two sides, where it is one flat color. The pixels are read from Wend's own display of Maps and
 * stay in memory.
 */
final class MapsCardEdge {
    interface Display {
        /**
         * Called on the main thread.
         * @return a read of the top {@code rows} of Maps' display, or null while Maps has none
         */
        Read top(int rows) throws ReflectiveOperationException;
    }

    interface Read {
        /** Called off the main thread; the caller owns the bitmap. */
        Bitmap pixels() throws ReflectiveOperationException;
    }

    interface Listener {
        /** @param bottom the row just below Maps' card, in its display's pixels, or 0 if it shows none */
        void measured(int bottom);
    }

    static final int INTERVAL_MS = 1000;
    /** How far in from each side of the display the columns are: inside the card's rounded corners. */
    private static final int SIDE_DP = 21;
    /** A row inside any card: Maps leaves 9 dp of map above it. */
    private static final int PROBE_DP = 20;
    /** Deep enough for the tallest card a floating window can start under (see {@link FloatingMapCrop}). */
    private static final int ROWS_DP = 220;
    /** A card with one road line is 87 dp tall. */
    private static final int SHORTEST_CARD_DP = 60;
    /** The strip of lane arrows is 57 dp tall. */
    private static final int SHORTEST_LANES_DP = 40, TALLEST_LANES_DP = 70;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor worker;
    private final float density;
    private final Display display;
    private final Listener listener;
    private final Runnable tick = this::tick;
    /** Changes with each stop, so a read in flight is dropped. */
    private int generation;
    private boolean running;
    private boolean reading;
    /** Logged once until a read succeeds. */
    private boolean failureLogged;

    /** @param worker reads the pixels, off the main thread */
    MapsCardEdge(Executor worker, float density, Display display, Listener listener) {
        this.worker = worker;
        this.density = density;
        this.display = display;
        this.listener = listener;
    }

    /** Measures now and then every {@link #INTERVAL_MS}, until {@link #stop}. */
    void start() {
        // Reading a display's layers needs Android 10's hidden capture and the platform signature.
        if (running || Build.VERSION.SDK_INT != 29 || Process.myUid() != Process.SYSTEM_UID) return;
        running = true;
        tick();
    }

    void stop() {
        generation++;
        running = false;
        reading = false;
        main.removeCallbacks(tick);
    }

    private void tick() {
        if (!running) return;
        main.postDelayed(tick, INTERVAL_MS);
        if (reading) return;
        int request = generation;
        try {
            Read read = display.top(Math.round(ROWS_DP * density));
            if (read == null) return;
            reading = true;
            worker.execute(() -> {
                int bottom;
                try { bottom = measure(read.pixels()); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    main.post(() -> { if (request == generation) failed(error); });
                    return;
                }
                main.post(() -> {
                    if (request != generation) return;
                    reading = false;
                    failureLogged = false;
                    listener.measured(bottom);
                });
            });
        } catch (RejectedExecutionException released) {
            stop();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            failed(error);
        }
    }

    private void failed(Throwable error) {
        reading = false;
        if (failureLogged) return;
        failureLogged = true;
        Log.w("WendMap", "Could not read Maps' display to see how tall its next-turn card is. Floating Maps"
                + " keeps its usual map, so lane arrows can show along its top. Retrying every second,"
                + " logged once until a read succeeds", error);
    }

    private int measure(Bitmap shot) {
        Bitmap pixels = shot.getConfig() == Bitmap.Config.HARDWARE ? shot.copy(Bitmap.Config.ARGB_8888, false) : shot;
        try {
            if (pixels == null) throw new IllegalStateException("Could not copy the top of Maps' display into memory");
            int side = Math.round(SIDE_DP * density), rows = pixels.getHeight();
            if (pixels.getWidth() <= side * 2) return 0;
            int[] left = new int[rows], right = new int[rows];
            pixels.getPixels(left, 0, 1, side, 0, 1, rows);
            pixels.getPixels(right, 0, 1, pixels.getWidth() - 1 - side, 0, 1, rows);
            return bottom(left, right, density);
        } finally {
            if (pixels != null && pixels != shot) pixels.recycle();
            shot.recycle();
        }
    }

    /**
     * @param left the display's pixels down a column inside the card's left edge, from the top
     * @param right the same inside its right edge
     * @return the row just below Maps' card, or 0 unless both columns show the same card
     */
    static int bottom(int[] left, int[] right, float density) {
        int probe = Math.round(PROBE_DP * density);
        if (left.length <= probe || right.length <= probe || !same(left[probe], right[probe])) return 0;
        int a = end(left, probe, density), b = end(right, probe, density);
        return a > 0 && Math.abs(a - b) <= 2 ? Math.max(a, b) : 0;
    }

    private static int end(int[] column, int probe, float density) {
        int card = column[probe], top = probe, bottom = probe;
        while (top > 0 && same(column[top - 1], card)) top--;
        while (bottom < column.length && same(column[bottom], card)) bottom++;
        // Flat map reaches the display's top; a card has map above it.
        if (top == 0 || bottom == column.length || bottom - top < SHORTEST_CARD_DP * density) return 0;
        // Lane arrows: a darker shade of the card right under it, past the row that blends the two.
        int lanes = bottom + 2;
        if (lanes >= column.length || !darker(column[lanes], card)) return bottom;
        int end = lanes;
        while (end < column.length && same(column[end], column[lanes])) end++;
        float tall = (end - bottom) / density;
        return end < column.length && tall >= SHORTEST_LANES_DP && tall <= TALLEST_LANES_DP ? end : bottom;
    }

    private static boolean same(int a, int b) {
        return Math.abs((a >> 16 & 0xff) - (b >> 16 & 0xff)) <= 2 && Math.abs((a >> 8 & 0xff) - (b >> 8 & 0xff)) <= 2
                && Math.abs((a & 0xff) - (b & 0xff)) <= 2;
    }

    /** Whether {@code a} is another color with no more of any primary than {@code b}. */
    private static boolean darker(int a, int b) {
        return !same(a, b) && (a >> 16 & 0xff) <= (b >> 16 & 0xff) + 2 && (a >> 8 & 0xff) <= (b >> 8 & 0xff) + 2
                && (a & 0xff) <= (b & 0xff) + 2;
    }
}
