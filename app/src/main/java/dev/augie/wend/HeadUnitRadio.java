package dev.augie.wend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The head unit's radio tuner as its radio app ({@code com.microntek.radio}) reports it: that
 * app's background service owns the tuner, and answers broadcasts with its station and presets.
 * The station's RDS name comes only from the MCU, as it changes, so it is kept as heard. See
 * {@code build/px6/rom/notes/radio.md}.
 */
final class HeadUnitRadio {
    /** What the radio app asks for and reports. */
    static final String REPORT = "com.microntek.radio.report", REQUEST_DATA = "hct.radio.request.data",
            REQUEST = "com.microntek.request.radio";
    static final String[] BANDS = {"FM1", "FM2", "FM3", "AM1", "AM2"};

    /** Where the tuner is: its band (0 to 4, {@link #BANDS}), frequency, preset and range. */
    static final class Station {
        final int band, hz, preset, minHz, maxHz, stepHz;
        final boolean fm;

        Station(int band, int hz, int preset, int minHz, int maxHz, int stepHz, boolean fm) {
            this.band = band;
            this.hz = hz;
            this.preset = preset;
            this.minHz = minHz;
            this.maxHz = maxHz;
            this.stepHz = stepHz;
            this.fm = fm;
        }
    }

    interface Source {
        /** @return where the tuner is, or null when the radio app did not answer */
        Station station();
        /** @return the 30 presets, 6 per band in {@link #BANDS}' order, in Hz (0 for none); null if no answer */
        int[] presets();
        /** @return the station's RDS name as last heard, or null */
        String name();
    }

    /** How long the radio app may take to answer. */
    private static final long ANSWER_MS = 1_500;

    private HeadUnitRadio() { }

    /** @return the radio app as it answers on this unit; its reports arrive on a thread of their own */
    static Source of(Context context, HeadUnit.Mcu mcu) {
        HandlerThread thread = new HandlerThread("Wend-radio");
        thread.start();
        Handler reports = new Handler(thread.getLooper());
        Object lock = new Object();
        CompletableFuture<?>[] waiting = new CompletableFuture<?>[2];
        String[] name = new String[1];
        context.registerReceiver(new BroadcastReceiver() {
            @SuppressWarnings("unchecked")
            @Override public void onReceive(Context ignored, Intent report) {
                String type = report.getStringExtra("type");
                synchronized (lock) {
                    if ("content".equals(type) && waiting[0] != null) ((CompletableFuture<Station>) waiting[0]).complete(station(report));
                    if ("list".equals(type) && waiting[1] != null) {
                        ((CompletableFuture<int[]>) waiting[1]).complete(presets(report.getStringArrayListExtra("list")));
                    }
                }
            }
        }, new IntentFilter(REPORT), null, reports);
        if (mcu != null) {
            mcu.listen("Radio", (kind, bundle) -> {
                String type = bundle.getString("type");
                // A new frequency has no name until its RDS says so.
                if ("freq".equals(type) || "seek_start".equals(type)) name[0] = null;
                if ("psn".equals(type)) name[0] = rds(bundle.getByteArray("value"));
            });
        }
        return new Source() {
            @Override public Station station() {
                return ask(new Intent(REQUEST_DATA), 0);
            }

            @Override public int[] presets() {
                // Without type the radio app's service crashes.
                return ask(new Intent(REQUEST).putExtra("type", "list"), 1);
            }

            @Override public String name() { return name[0]; }

            @SuppressWarnings("unchecked")
            private <T> T ask(Intent request, int slot) {
                CompletableFuture<T> answer = new CompletableFuture<>();
                synchronized (lock) { waiting[slot] = answer; }
                context.sendBroadcast(request);
                try {
                    return answer.get(ANSWER_MS, TimeUnit.MILLISECONDS);
                } catch (Exception none) {
                    return null;
                } finally {
                    synchronized (lock) { if (waiting[slot] == answer) waiting[slot] = null; }
                }
            }
        };
    }

    /** @return the radio app's {@code content} report: its band rides in the frequency's top bits */
    static Station station(Intent report) {
        int packed = report.getIntExtra("freq", 0);
        return new Station(packed >>> 28, packed & 0x0FFFFFFF, report.getIntExtra("channel", -1),
                report.getIntExtra("freqmin", 0), report.getIntExtra("freqmax", 0), report.getIntExtra("freqstep", 0),
                report.getBooleanExtra("isfm", true));
    }

    /** @return the radio app's {@code list} report's presets ("rate:101100000,name:..."), in Hz */
    static int[] presets(ArrayList<String> list) {
        if (list == null) return null;
        int[] hz = new int[list.size()];
        for (int i = 0; i < hz.length; i++) {
            String item = list.get(i);
            int start = item.indexOf("rate:"), end = item.indexOf(',', start);
            hz[i] = start < 0 ? 0 : CarControls.number(item.substring(start + 5, end < 0 ? item.length() : end), 0);
        }
        return hz;
    }

    /** @return an RDS name as the MCU sends it, trimmed; null for none */
    private static String rds(byte[] value) {
        if (value == null) return null;
        String text = new String(value, StandardCharsets.ISO_8859_1).replace('\0', ' ').trim();
        return text.isEmpty() ? null : text;
    }

    /** @return {@code hz} as the driver says it: "101.1 MHz" or "1010 kHz" */
    static String words(int hz) {
        if (hz >= 10_000_000) return String.format(java.util.Locale.US, hz % 100_000 == 0 ? "%.1f MHz" : "%.2f MHz", hz / 1e6);
        return hz / 1000 + " kHz";
    }

    /** @return {@code frequency} as a driver gives it (MHz for FM, kHz for AM) in Hz, or -1 if it is neither */
    static int hz(double frequency) {
        if (frequency >= 30 && frequency <= 120) return (int) Math.round(frequency * 1000) * 1000;
        if (frequency >= 140 && frequency <= 30_000) return (int) Math.round(frequency) * 1000;
        return -1;
    }

    /** @return the presets of {@code band}, from {@link Source#presets()} */
    static List<Integer> band(int[] presets, int band) {
        List<Integer> own = new ArrayList<>();
        for (int i = band * 6; i < band * 6 + 6 && i < presets.length; i++) own.add(presets[i]);
        return own;
    }
}
