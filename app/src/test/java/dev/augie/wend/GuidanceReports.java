package dev.augie.wend;

import android.graphics.Bitmap;
import android.os.Bundle;

/** Maps' navigation state as the Maps bridge module reports it to Wend ({@link MapsGuidance}), for tests. */
final class GuidanceReports {
    private GuidanceReports() { }

    /** Maps guides, its turn {@code distance} ahead onto {@code cue}. */
    static Bundle guiding(String distance, String cue) {
        Bundle report = new Bundle();
        report.putString("state", "GUIDING");
        report.putCharSequence("distance", distance);
        report.putCharSequence("cue", cue);
        report.putParcelable("arrow", Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888));
        report.putInt("color", 0xff015f61);
        report.putInt("meters_to_step", 322);
        report.putInt("seconds_to_step", 20);
        report.putInt("meters_remaining", 13518);
        report.putInt("seconds_remaining", 540);
        report.putCharSequence("distance_left", "8.4 mi");
        report.putCharSequence("time_left", "9 min");
        report.putBoolean("on_route", true);
        report.putBoolean("done", false);
        return report;
    }

    static Bundle guiding() { return guiding("0.2 mi", "CA-20 W"); }

    /** Maps guides nowhere. */
    static Bundle off() {
        Bundle report = new Bundle();
        report.putString("state", "OFF");
        return report;
    }
}
