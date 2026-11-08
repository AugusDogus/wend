package dev.augie.roadhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

/**
 * What the video card shows whenever its live video is not there: the last live frame, else the
 * session's artwork, else a quiet surface with the video app's icon. A paused or stopped video
 * that its session can play adds a 48 dp play control. It owns the stills it is given, never the
 * session's artwork.
 */
final class VideoCardCover extends View {
    /** What the card promises over its picture. A tap on the card opens the video either way. */
    enum Control {
        /** A paused or stopped video that its session can play. */
        PLAY,
        /** A playing video on its way back, or a session that cannot play from here, as when YouTube stopped it. */
        NONE
    }

    private static final int CONTROL_DP = 48;
    private static final int GLYPH_DP = 24;
    private static final int ICON_DP = 24;
    private static final int INSET_DP = 12;
    /** Over a picture, a dark disc keeps the glyph readable on bright frames. */
    private static final int CONTROL_OVER_PICTURE = 0x8C000000;
    /** On the plain surface, a faint light disc. */
    private static final int CONTROL_ON_SURFACE = 0x24FFFFFF;
    private final Paint pictures = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint control = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable glyph = new Ui.Symbol(Ui.Icon.PLAY, Ui.TEXT);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private Control promised = Control.PLAY;
    private Bitmap artwork;
    private Bitmap still;
    /** The still last handed to the renderer, which may hold it until that frame retires. */
    private Bitmap drawnStill;
    private Bitmap icon;
    private boolean iconLoaded;

    VideoCardCover(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** @param artwork the session's own picture, used when there is no still; never recycled here */
    void show(Control control, Bitmap artwork) {
        promised = control;
        this.artwork = artwork;
        if (still == null && artwork == null) loadIcon();
        invalidate();
    }

    /** Takes ownership of a newer still, or drops the current one with null. */
    void keep(Bitmap next) {
        Bitmap previous = still;
        if (previous == next) return;
        still = next;
        // One the renderer never saw is freed now; one it drew is left to the collector, since its
        // frame may still be in flight.
        if (previous != null && previous != drawnStill) previous.recycle();
        if (still == null && artwork == null) loadIcon();
        invalidate();
    }

    void release() {
        keep(null);
        artwork = null;
    }

    private void loadIcon() {
        if (iconLoaded) return;
        iconLoaded = true;
        Drawable appIcon = null;
        try { appIcon = getContext().getPackageManager().getApplicationIcon(EmbeddedVideo.PACKAGE); }
        catch (PackageManager.NameNotFoundException error) { appIcon = null; }
        if (appIcon == null) {
            Log.i("RoadHomeVideo", "No icon for " + EmbeddedVideo.PACKAGE + "; the video card's placeholder"
                    + " shows without it");
            return;
        }
        icon = AppIcon.render(appIcon, Ui.dp(getContext(), ICON_DP));
    }

    @Override protected void onDraw(Canvas canvas) {
        Bitmap picture = still != null ? still : artwork;
        if (picture != null) {
            centerCrop(picture);
            canvas.drawBitmap(picture, source, destination, pictures);
            if (picture == still) drawnStill = still;
        } else {
            canvas.drawColor(Ui.PANEL);
            if (icon != null) {
                float inset = Ui.dp(getContext(), INSET_DP);
                canvas.drawBitmap(icon, inset, inset, pictures);
            }
        }
        if (promised != Control.PLAY) return;
        float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
        control.setColor(picture != null ? CONTROL_OVER_PICTURE : CONTROL_ON_SURFACE);
        canvas.drawCircle(centerX, centerY, Ui.dp(getContext(), CONTROL_DP) / 2f, control);
        int half = Ui.dp(getContext(), GLYPH_DP) / 2;
        glyph.setBounds(Math.round(centerX) - half, Math.round(centerY) - half,
                Math.round(centerX) + half, Math.round(centerY) + half);
        glyph.draw(canvas);
    }

    /** Fills the card with the middle of the picture, never stretching it. */
    private void centerCrop(Bitmap picture) {
        destination.set(0, 0, getWidth(), getHeight());
        float scale = Math.max(getWidth() / (float) picture.getWidth(), getHeight() / (float) picture.getHeight());
        int width = Math.round(getWidth() / scale), height = Math.round(getHeight() / scale);
        int left = (picture.getWidth() - width) / 2, top = (picture.getHeight() - height) / 2;
        source.set(left, top, left + width, top + height);
    }
}
