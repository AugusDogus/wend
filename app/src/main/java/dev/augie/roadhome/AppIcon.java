package dev.augie.roadhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/** The app artwork fills one squircle; there is no surrounding icon tile. */
final class AppIcon {
    private static final float CORNER = 0.23f;

    private AppIcon() { }

    /** Renders artwork once into an antialiased squircle that any number of views can share. */
    static Bitmap render(Drawable source, int size) {
        Drawable artwork = source.mutate();
        artwork.setBounds(0, 0, size, size);
        Bitmap layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(layers);
        if (artwork instanceof AdaptiveIconDrawable) {
            // Adaptive layers extend a quarter of the size past each edge of the visible mask.
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) artwork;
            int inset = Math.round(size * 0.25f);
            Rect expanded = new Rect(-inset, -inset, size + inset, size + inset);
            drawLayer(adaptive.getBackground(), expanded, canvas);
            drawLayer(adaptive.getForeground(), expanded, canvas);
        } else {
            artwork.draw(canvas);
        }
        Bitmap masked = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float radius = size * CORNER;
        new Canvas(masked).drawRoundRect(0, 0, size, size, radius, radius, paint);
        layers.recycle();
        return masked;
    }

    private static void drawLayer(Drawable layer, Rect bounds, Canvas canvas) {
        if (layer == null) return;
        layer.setBounds(bounds);
        layer.draw(canvas);
    }
}
