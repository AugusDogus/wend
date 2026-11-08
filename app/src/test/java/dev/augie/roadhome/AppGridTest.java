package dev.augie.roadhome;

import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AppGridTest {
    @Test public void hiddenTilesCloseTheGapInReadingOrder() {
        AppGrid grid = new AppGrid(RuntimeEnvironment.getApplication(), 3, 100);
        View[] tiles = new View[5];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = new View(RuntimeEnvironment.getApplication());
            grid.addView(tiles[i]);
        }
        tiles[1].setVisibility(View.GONE);
        layout(grid, 300);

        assertEquals(200, grid.getMeasuredHeight());
        assertPosition(tiles[0], 0, 0);
        assertPosition(tiles[2], 100, 0);
        assertPosition(tiles[3], 200, 0);
        assertPosition(tiles[4], 0, 100);
        assertEquals(100, tiles[4].getWidth());
        assertEquals(100, tiles[4].getHeight());
    }

    @Test public void filteringEverythingOutCollapsesTheGrid() {
        AppGrid grid = new AppGrid(RuntimeEnvironment.getApplication(), 5, 150);
        View tile = new View(RuntimeEnvironment.getApplication());
        grid.addView(tile);
        tile.setVisibility(View.GONE);
        layout(grid, 1280);
        assertEquals(0, grid.getMeasuredHeight());
    }

    @Test public void paddingInsetsEveryCell() {
        AppGrid grid = new AppGrid(RuntimeEnvironment.getApplication(), 2, 50);
        grid.setPadding(10, 4, 10, 6);
        View first = new View(RuntimeEnvironment.getApplication());
        View second = new View(RuntimeEnvironment.getApplication());
        grid.addView(first);
        grid.addView(second);
        layout(grid, 220);
        assertEquals(60, grid.getMeasuredHeight());
        assertPosition(first, 10, 4);
        assertPosition(second, 110, 4);
    }

    private static void layout(AppGrid grid, int width) {
        grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, grid.getMeasuredWidth(), grid.getMeasuredHeight());
    }

    private static void assertPosition(View view, int left, int top) {
        assertEquals(left, view.getLeft());
        assertEquals(top, view.getTop());
    }
}
