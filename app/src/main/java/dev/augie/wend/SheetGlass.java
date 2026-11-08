package dev.augie.wend;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/**
 * A sheet's frosted surface. The blurred screen capture is drawn at display coordinates,
 * so it stays aligned with the content beneath while the sheet slides or is dragged.
 * Until the blur arrives, a denser tint keeps moving map detail from showing through.
 */
final class SheetGlass extends Drawable {
    /** How much of the blurred screen shows through the tint once it arrives. */
    enum Density {
        /** For icon grids: the launcher stays recognizable through the glass. */
        LIGHT(0xB8),
        /** For reading: over bright map tiles, muted text keeps about 5:1 contrast. */
        DENSE(0xE0);

        final int frostedAlpha;

        Density(int frostedAlpha) { this.frostedAlpha = frostedAlpha; }
    }

    private static final int CLEAR_ALPHA = 0xEE;
    private final int frostedAlpha;
    private final int displayWidth;
    private final int displayHeight;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint();
    private final Paint edge = new Paint();
    private final GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0x28FFFFFF, 0x24131D2C});
    private final RectF screen = new RectF();
    private Bitmap backdrop;
    private float frost;
    private float screenTop;

    SheetGlass(int displayWidth, int displayHeight, Density density) {
        frostedAlpha = density.frostedAlpha;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        edge.setColor(0x1FFFFFFF);
    }

    /** A display-proportioned blurred capture, or null for plain tinted glass. */
    void setBackdrop(Bitmap bitmap) {
        backdrop = bitmap;
        invalidateSelf();
    }

    /** 0 shows plain tinted glass; 1 shows the blurred capture under a lighter tint. */
    void setFrost(float value) {
        frost = value;
        invalidateSelf();
    }

    /** The sheet's top edge in display coordinates. */
    void setScreenTop(float top) {
        if (top == screenTop) return;
        screenTop = top;
        if (backdrop != null && frost > 0) invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) { sheen.setBounds(bounds); }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (backdrop != null && frost > 0) {
            image.setAlpha(Math.round(255 * frost));
            float top = bounds.top - screenTop;
            screen.set(bounds.left, top, bounds.left + displayWidth, top + displayHeight);
            canvas.drawBitmap(backdrop, null, screen, image);
        }
        int alpha = Math.round(CLEAR_ALPHA + (frostedAlpha - CLEAR_ALPHA) * frost);
        tint.setColor(Color.argb(alpha, 0x10, 0x19, 0x22));
        canvas.drawRect(bounds, tint);
        sheen.draw(canvas);
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + 1, edge);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
