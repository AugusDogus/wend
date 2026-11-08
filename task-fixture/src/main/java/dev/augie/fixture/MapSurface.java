package dev.augie.fixture;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;
import android.view.SurfaceHolder;

/**
 * A stand-in for Google Maps' tile SurfaceView: a street grid, route, and position marker drawn
 * around the surface's center at every size, so launcher frame-readiness checks and resizes
 * behave as they do with real map tiles. Offline fixtures only.
 */
final class MapSurface implements SurfaceHolder.Callback {
    private static final int BLOCK = 96;
    private final Paint streets = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint route = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    MapSurface() {
        streets.setColor(0xff2b6a63);
        streets.setStrokeWidth(10);
        route.setColor(0xff4c8dff);
        route.setStyle(Paint.Style.STROKE);
        route.setStrokeWidth(12);
        route.setStrokeJoin(Paint.Join.ROUND);
        route.setStrokeCap(Paint.Cap.ROUND);
        marker.setColor(0xffffffff);
        label.setColor(0xffb7d6d2);
        label.setTextSize(22);
        label.setTextAlign(Paint.Align.CENTER);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            Log.w("RoadFixture", "Map surface could not be drawn at " + width + "x" + height);
            return;
        }
        try {
            float cx = width / 2f, cy = height / 2f;
            canvas.drawColor(0xff124c48);
            for (float x = cx % BLOCK; x < width; x += BLOCK) canvas.drawLine(x, 0, x, height, streets);
            for (float y = cy % BLOCK; y < height; y += BLOCK) canvas.drawLine(0, y, width, y, streets);
            path.reset();
            path.moveTo(cx - 3 * BLOCK, cy + 3 * BLOCK);
            path.lineTo(cx - BLOCK, cy + BLOCK);
            path.lineTo(cx, cy);
            path.lineTo(cx + 2 * BLOCK, cy);
            path.lineTo(cx + 2 * BLOCK, cy - 3 * BLOCK);
            canvas.drawPath(path, route);
            marker.setColor(0xff4c8dff);
            canvas.drawCircle(cx, cy, 18, marker);
            marker.setColor(0xffffffff);
            canvas.drawCircle(cx, cy, 8, marker);
            canvas.drawText("Map surface " + width + " x " + height, cx, height - 24, label);
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { }
}
