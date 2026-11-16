package dev.augie.wend.mapsbridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs inside Google Maps. Hands Wend the route shields Maps' own turn card draws in the next
 * turn's road ("CA-20 W WEST", a "20" shield drawn over "CA-20 W"), which Maps' guidance state has
 * as text only: the line's text, where each shield lies in it, and each rendered as Maps
 * draws it. The card's line is a {@code TurnCardStepCueTextView} ({@code top_cue_text}); each
 * shield is a {@link ReplacementSpan} over the route's name. Maps' own calls go on unchanged.
 *
 * <p>Each report is a call of {@code shields} on Wend's provider with the extras {@code text},
 * {@code starts}, {@code ends} and {@code images}, or without extras when the line has no shield.
 */
final class ShieldReporter {
    private static final String CUE_VIEW = "com.google.android.libraries.geo.navcore.turncard.views.TurnCardStepCueTextView";
    private static final String CUE_ID = "top_cue_text";
    private final TripReporter reporter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable report = this::report;
    /** Main thread only: Maps' card line, and what was last reported of it. */
    private WeakReference<TextView> line = new WeakReference<>(null);
    private String reportedText;
    private List<Bitmap> reportedImages = new ArrayList<>();
    private boolean logged;

    private ShieldReporter(TripReporter reporter) { this.reporter = reporter; }

    static ShieldReporter install(TripReporter reporter) {
        ShieldReporter shields = new ShieldReporter(reporter);
        XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, TextView.BufferType.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject.getClass().getName().equals(CUE_VIEW)) shields.changed((TextView) param.thisObject);
                    }
                });
        return shields;
    }

    /** Maps set the text of one of its card's lines. Main thread. */
    private void changed(TextView view) {
        if (!CUE_ID.equals(idOf(view))) return;
        line = new WeakReference<>(view);
        soon();
    }

    /**
     * Looks at the card's line again soon, from any thread: Maps posts each guidance update, and
     * a shield whose image was still loading when the text was set draws in full later.
     */
    void soon() {
        main.removeCallbacks(report);
        main.post(report);
    }

    /** Wend asked again, as it does when it starts. */
    void again() {
        main.post(() -> {
            reportedText = null;
            report();
        });
    }

    private void report() {
        TextView view = line.get();
        if (view == null) return;
        CharSequence text = view.getText();
        List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        List<Bitmap> images = new ArrayList<>();
        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            ReplacementSpan[] spans = spanned.getSpans(0, spanned.length(), ReplacementSpan.class);
            java.util.Arrays.sort(spans, (a, b) -> spanned.getSpanStart(a) - spanned.getSpanStart(b));
            for (ReplacementSpan span : spans) {
                int start = spanned.getSpanStart(span), end = spanned.getSpanEnd(span);
                Bitmap image = render(span, view.getPaint(), text, start, end);
                if (image == null) continue;
                starts.add(start);
                ends.add(end);
                images.add(image);
            }
        }
        String plain = text == null ? "" : text.toString();
        if (plain.equals(reportedText) && same(images, reportedImages)) return;
        reportedText = plain;
        reportedImages = images;
        Bundle extras = null;
        if (!images.isEmpty()) {
            extras = new Bundle();
            extras.putString("text", plain);
            extras.putIntArray("starts", toArray(starts));
            extras.putIntArray("ends", toArray(ends));
            extras.putParcelableArrayList("images", new ArrayList<>(images));
        }
        reporter.tellWend("shields", null, extras);
    }

    /** @return the span drawn on its own, as Maps draws it in the card's line; null if it draws nothing */
    private Bitmap render(ReplacementSpan span, TextPaint linePaint, CharSequence text, int start, int end) {
        try {
            TextPaint paint = new TextPaint(linePaint);
            Paint.FontMetricsInt metrics = new Paint.FontMetricsInt();
            int width = span.getSize(paint, text, start, end, metrics);
            int height = metrics.descent - metrics.ascent;
            if (width <= 0 || height <= 0) return null;
            Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            span.draw(new Canvas(image), text, start, end, 0, 0, -metrics.ascent, height, paint);
            return image;
        } catch (RuntimeException error) {
            if (!logged) {
                logged = true;
                Log.w(MapsBridge.TAG, "Could not draw a shield of Maps' turn card; Wend shows that road as text. Logged once", error);
            }
            return null;
        }
    }

    private static String idOf(TextView view) {
        try { return view.getId() != android.view.View.NO_ID ? view.getResources().getResourceEntryName(view.getId()) : null; }
        catch (RuntimeException unnamed) { return null; }
    }

    private static boolean same(List<Bitmap> a, List<Bitmap> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).sameAs(b.get(i))) return false;
        return true;
    }

    private static int[] toArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) array[i] = values.get(i);
        return array;
    }
}
