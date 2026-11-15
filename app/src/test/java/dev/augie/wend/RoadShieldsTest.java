package dev.augie.wend;

import android.graphics.Bitmap;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class RoadShieldsTest {
    static Bundle reported(String text, int[] starts, int[] ends, Bitmap... images) {
        Bundle extras = new Bundle();
        extras.putString("text", text);
        extras.putIntArray("starts", starts);
        extras.putIntArray("ends", ends);
        ArrayList<Bitmap> list = new ArrayList<>();
        for (Bitmap image : images) list.add(image);
        extras.putParcelableArrayList("images", list);
        return extras;
    }

    static Bitmap shield() { return Bitmap.createBitmap(56, 47, Bitmap.Config.ARGB_8888); }

    /** Maps' card shows "CA-20 W WEST" with a shield drawn over "CA-20 W": the shield, then "WEST". */
    @Test public void mapsCardLineIsItsShieldsAndTheWordsBetweenThem() {
        Bitmap twenty = shield();
        RoadShields shields = RoadShields.from(reported("CA-20 W WEST", new int[]{0}, new int[]{7}, twenty));
        List<Object> parts = shields.parts();
        assertEquals(2, parts.size());
        assertSame(twenty, parts.get(0));
        assertEquals("WEST", parts.get(1));
    }

    @Test public void twoRoutesKeepTheirOrderAndTheWordsBetween() {
        Bitmap first = shield(), second = shield();
        RoadShields shields = RoadShields.from(reported("US-101 N / CA-20 E toward Ukiah",
                new int[]{0, 11}, new int[]{8, 18}, first, second));
        List<Object> parts = shields.parts();
        assertSame(first, parts.get(0));
        assertEquals("/", parts.get(1));
        assertSame(second, parts.get(2));
        assertEquals("toward Ukiah", parts.get(3));
    }

    /** The card's line belongs to the turn whose road it names, as Maps' notification gives it. */
    @Test public void itNamesTheRoadItsLineContains() {
        RoadShields shields = RoadShields.from(reported("CA-20 W WEST", new int[]{0}, new int[]{7}, shield()));
        assertTrue(shields.names("CA-20 W"));
        assertFalse(shields.names("Main St"));
        assertFalse(shields.names(""));
    }

    @Test public void aReportWithoutShieldsOrThatDoesNotAddUpIsNone() {
        assertNull(RoadShields.from(null));
        assertNull(RoadShields.from(new Bundle()));
        assertNull("No shields", RoadShields.from(reported("Main St", new int[0], new int[0])));
        assertNull("Counts differ", RoadShields.from(reported("CA-20 W WEST", new int[]{0}, new int[]{7})));
        assertNull("Outside the text", RoadShields.from(reported("CA-20", new int[]{0}, new int[]{7}, shield())));
        assertNull("Overlapping", RoadShields.from(reported("CA-20 W WEST", new int[]{0, 3}, new int[]{7, 9}, shield(), shield())));
    }
}
