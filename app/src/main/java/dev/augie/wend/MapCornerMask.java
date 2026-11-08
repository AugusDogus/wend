package dev.augie.wend;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;

/** Paints one corner over ActivityView's separate surface, without covering its touch area. */
final class MapCornerMask extends View {
    enum Corner {
        TOP_LEFT(Gravity.TOP | Gravity.LEFT), BOTTOM_LEFT(Gravity.BOTTOM | Gravity.LEFT),
        TOP_RIGHT(Gravity.TOP | Gravity.RIGHT), BOTTOM_RIGHT(Gravity.BOTTOM | Gravity.RIGHT);
        final int gravity;
        Corner(int gravity) { this.gravity = gravity; }
    }

    private final Corner corner;
    private final View anchor;
    private final Path cutout = new Path();
    private final Drawable wallpaper = Ui.wallpaper();
    private final int[] location = new int[2];

    MapCornerMask(Context context, Corner corner) {
        this(context, corner, null);
    }

    MapCornerMask(Context context, Corner corner, View anchor) {
        super(context);
        this.corner = corner;
        this.anchor = anchor;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float radius = Math.min(width, height);
        boolean left = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT;
        boolean top = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT;
        float cx = left ? radius : 0;
        float cy = top ? radius : 0;
        Path circle = new Path();
        circle.addOval(new RectF(cx - radius, cy - radius, cx + radius, cy + radius), Path.Direction.CW);
        cutout.reset();
        cutout.addRect(0, 0, width, height, Path.Direction.CW);
        cutout.op(circle, Path.Op.DIFFERENCE);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (anchor == null) getLocationInWindow(location);
        else getLocationOnScreen(location);
        View root = anchor == null ? getRootView() : anchor;
        wallpaper.setBounds(-location[0], -location[1], root.getWidth() - location[0], root.getHeight() - location[1]);
        int save = canvas.save();
        canvas.clipPath(cutout);
        wallpaper.draw(canvas);
        canvas.restoreToCount(save);
    }
}
