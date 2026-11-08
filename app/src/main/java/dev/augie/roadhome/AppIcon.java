package dev.augie.roadhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;

/** The app artwork fills one squircle; there is no surrounding icon tile. */
final class AppIcon extends Drawable {
    private static final float CORNER = 0.23f;
    private final Drawable artwork;
    private final Path mask = new Path();

    AppIcon(Drawable artwork) { this.artwork = artwork.mutate(); }

    /** Renders artwork once into an antialiased squircle that any number of views can share. */
    static Bitmap render(Drawable artwork, int size) {
        Bitmap layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        AppIcon icon = new AppIcon(artwork);
        icon.setBounds(0, 0, size, size);
        icon.drawArtwork(new Canvas(layers));
        Bitmap masked = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float radius = size * CORNER;
        new Canvas(masked).drawRoundRect(0, 0, size, size, radius, radius, paint);
        layers.recycle();
        return masked;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        artwork.setBounds(bounds);
        if (artwork instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) artwork;
            int inset = Math.round(Math.min(bounds.width(), bounds.height()) * 0.25f);
            Rect expanded = new Rect(bounds.left - inset, bounds.top - inset,
                    bounds.right + inset, bounds.bottom + inset);
            if (adaptive.getBackground() != null) adaptive.getBackground().setBounds(expanded);
            if (adaptive.getForeground() != null) adaptive.getForeground().setBounds(expanded);
        }
        mask.reset();
        float radius = Math.min(bounds.width(), bounds.height()) * CORNER;
        mask.addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, radius, radius, Path.Direction.CW);
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(mask);
        drawArtwork(canvas);
        canvas.restoreToCount(save);
    }

    private void drawArtwork(Canvas canvas) {
        if (artwork instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) artwork;
            if (adaptive.getBackground() != null) adaptive.getBackground().draw(canvas);
            if (adaptive.getForeground() != null) adaptive.getForeground().draw(canvas);
        } else artwork.draw(canvas);
    }

    @Override public int getIntrinsicWidth() { return 96; }
    @Override public int getIntrinsicHeight() { return 96; }
    @Override public void setAlpha(int alpha) { artwork.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { artwork.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
