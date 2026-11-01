package dev.augie.roadhome;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Two untouchable masks above a native task; the outer screen edge remains square. */
final class MapWindowCorners {
    private final Activity host;
    private final View panel;
    private final WindowManager windows;
    private View top;
    private View bottom;

    MapWindowCorners(Activity host, View panel) {
        this.host = host; this.panel = panel;
        windows = host.getSystemService(WindowManager.class);
    }

    void show(boolean mapsOnRight) {
        hide();
        if (windows == null || !panel.isLaidOut()) return;
        int[] point = new int[2]; panel.getLocationOnScreen(point);
        int size = Ui.dp(host, 20);
        int x = mapsOnRight ? point[0] : point[0] + panel.getWidth() - size;
        top = add(mapsOnRight ? MapCornerMask.Corner.TOP_LEFT : MapCornerMask.Corner.TOP_RIGHT,
                x, point[1], size);
        bottom = add(mapsOnRight ? MapCornerMask.Corner.BOTTOM_LEFT : MapCornerMask.Corner.BOTTOM_RIGHT,
                x, point[1] + panel.getHeight() - size, size);
    }

    private View add(MapCornerMask.Corner corner, int x, int y, int size) {
        View mask = new MapCornerMask(host, corner, host.getWindow().getDecorView());
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT; params.x = x; params.y = y;
        params.setTitle("Road Home map corner");
        windows.addView(mask, params);
        return mask;
    }

    void hide() {
        if (top != null) { windows.removeViewImmediate(top); top = null; }
        if (bottom != null) { windows.removeViewImmediate(bottom); bottom = null; }
    }
}
