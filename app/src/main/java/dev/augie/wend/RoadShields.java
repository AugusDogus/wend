package dev.augie.wend;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The next turn's road as Google Maps' own turn card shows it, with the route shields Maps draws
 * over the route names ("CA-20 W WEST", a "20" shield over "CA-20 W"). Maps' guidance notification
 * carries only the text, so the Maps bridge module renders the shields inside Maps and reports
 * them ({@link MapsTrips}): the text, where each shield lies in it, and its image.
 */
final class RoadShields {
    /** The line's text, shields included as the words they stand for. */
    final String text;
    private final int[] starts, ends;
    private final List<Bitmap> images;

    private RoadShields(String text, int[] starts, int[] ends, List<Bitmap> images) {
        this.text = text;
        this.starts = starts;
        this.ends = ends;
        this.images = images;
    }

    /** @return the shields the module reported, or null for none or a report that does not add up */
    static RoadShields from(Bundle extras) {
        if (extras == null) return null;
        String text = extras.getString("text");
        int[] starts = extras.getIntArray("starts"), ends = extras.getIntArray("ends");
        ArrayList<Bitmap> images = extras.getParcelableArrayList("images");
        if (TextUtils.isEmpty(text) || starts == null || ends == null || images == null || starts.length == 0
                || starts.length != ends.length || starts.length != images.size()) {
            return null;
        }
        int after = 0;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] < after || ends[i] <= starts[i] || ends[i] > text.length() || images.get(i) == null) return null;
            after = ends[i];
        }
        return new RoadShields(text, starts.clone(), ends.clone(), Collections.unmodifiableList(new ArrayList<>(images)));
    }

    /** @return whether this is the line of the turn onto {@code road}, as Maps' notification names it */
    boolean names(CharSequence road) {
        String name = road == null ? "" : road.toString().trim();
        return !name.isEmpty() && text.contains(name);
    }

    /** @return the line in order: each shield's image, and the words between them (as Strings, trimmed) */
    List<Object> parts() {
        List<Object> parts = new ArrayList<>();
        int at = 0;
        for (int i = 0; i < starts.length; i++) {
            words(parts, text.substring(at, starts[i]));
            parts.add(images.get(i));
            at = ends[i];
        }
        words(parts, text.substring(at));
        return parts;
    }

    private static void words(List<Object> parts, String words) {
        String trimmed = words.trim();
        if (!trimmed.isEmpty()) parts.add(trimmed);
    }
}
