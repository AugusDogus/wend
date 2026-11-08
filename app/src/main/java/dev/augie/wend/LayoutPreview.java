package dev.augie.wend;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A small drawing of the car screen: the dock with its pinned icons, the media panel and the
 * map. When the map side or width changes, the map slides and resizes into place and the
 * media panel is revealed around it, so the change reads before the sheet is closed.
 * Geometry is in screen dp (853 x 480 on the PX6) and scaled to the view; it never mirrors,
 * because Left and Right are physical positions in the car.
 */
@SuppressLint("ViewConstructor")
final class LayoutPreview extends View {
    private static final float SCREEN_W = 853.33f;
    private static final float SCREEN_H = 480;
    private static final float CONTENT_X = DockLayout.WIDTH_DP + 1;
    private static final float CONTENT_W = SCREEN_W - CONTENT_X;
    private static final float MAP_CORNER = 14;
    static final int MORPH_MS = 280;
    // Ease-in-out: the map travels across the screen rather than entering or leaving it.
    private static final TimeInterpolator EASE_IN_OUT = new PathInterpolator(0.65f, 0, 0.35f, 1);

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];
    private List<Bitmap> dockIcons = Collections.emptyList();
    /** 0 places the map against the dock, 1 against the screen's right edge. */
    private float side;
    /** The map's share of the content width. */
    private float share;
    private ValueAnimator morph;
    private LinearGradient wallpaper;

    LayoutPreview(Context context, LauncherSettings.MapSide side, LauncherSettings.MapWidth width) {
        super(context);
        this.side = target(side);
        this.share = width.percent / 100f;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        describe(side, width);
    }

    /** The installed dock apps' icons, top to bottom. */
    void setDockIcons(List<Bitmap> icons) {
        dockIcons = new ArrayList<>(icons);
        invalidate();
    }

    void show(LauncherSettings.MapSide nextSide, LauncherSettings.MapWidth nextWidth) {
        describe(nextSide, nextWidth);
        float toSide = target(nextSide);
        float toShare = nextWidth.percent / 100f;
        boolean moving = morph != null && morph.isRunning();
        if (!moving && side == toSide && share == toShare) return;
        if (morph != null) morph.cancel();
        if (!isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            side = toSide;
            share = toShare;
            invalidate();
            return;
        }
        float fromSide = side;
        float fromShare = share;
        morph = ValueAnimator.ofFloat(0, 1);
        morph.setDuration(MORPH_MS);
        morph.setInterpolator(EASE_IN_OUT);
        morph.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            side = fromSide + (toSide - fromSide) * t;
            share = fromShare + (toShare - fromShare) * t;
            invalidate();
        });
        morph.start();
    }

    private static float target(LauncherSettings.MapSide side) {
        return side == LauncherSettings.MapSide.RIGHT ? 1 : 0;
    }

    /** The map's width in screen dp for its share of the space beside the dock. */
    static float mapWidth(float share) { return share * CONTENT_W; }

    /**
     * The map's left edge in screen dp. {@code side} is 0 against the dock and 1 against the
     * screen's right edge; values between are frames of the slide.
     */
    static float mapStart(float side, float share) { return CONTENT_X + side * (CONTENT_W - mapWidth(share)); }

    /** 0 for a square corner against the dock or screen edge, 1 once the media gap is 24 dp wide. */
    static float cornerRoundness(float gap) { return Math.max(0, Math.min(1, gap / 24)); }

    private void describe(LauncherSettings.MapSide side, LauncherSettings.MapWidth width) {
        setContentDescription(getContext().getString(side == LauncherSettings.MapSide.RIGHT
                ? R.string.preview_map_right : R.string.preview_map_left, width.percent));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        wallpaper = new LinearGradient(0, 0, width, height, new int[]{0xFF283B45, 0xFF222C36, 0xFF19212A},
                null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        float scale = getWidth() / SCREEN_W;
        canvas.save();
        canvas.scale(scale, scale);
        float unit = 1 / scale; // One device pixel, in screen dp.
        // The screen, clipped to soft corners like a display bezel.
        rect.set(0, 0, SCREEN_W, SCREEN_H);
        path.reset();
        path.addRoundRect(rect, 36, 36, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        fill.setShader(wallpaper);
        canvas.save();
        canvas.scale(1 / scale, 1 / scale);
        canvas.drawRect(0, 0, getWidth(), getHeight(), fill);
        canvas.restore();
        fill.setShader(null);
        drawDock(canvas, unit);
        float mapW = mapWidth(share);
        float mapX = mapStart(side, share);
        drawMedia(canvas, CONTENT_X, mapX);
        drawMedia(canvas, mapX + mapW, CONTENT_X + CONTENT_W);
        drawMap(canvas, mapX, mapW, unit);
        canvas.restore();
        stroke.setStrokeWidth(unit);
        stroke.setColor(0x33FFFFFF);
        rect.set(unit / 2, unit / 2, SCREEN_W - unit / 2, SCREEN_H - unit / 2);
        canvas.drawRoundRect(rect, 36, 36, stroke);
        canvas.restore();
    }

    private void drawDock(Canvas canvas, float unit) {
        fill.setColor(0xFF15242E);
        canvas.drawRect(0, 0, DockLayout.WIDTH_DP, SCREEN_H, fill);
        fill.setColor(0x20FFFFFF);
        canvas.drawRect(DockLayout.WIDTH_DP, 0, DockLayout.WIDTH_DP + Math.max(1, unit), SCREEN_H, fill);
        float center = DockLayout.WIDTH_DP / 2f;
        // Clock and date.
        fill.setColor(0xCCF1F6FB);
        bar(canvas, center - 17, 15, 34, 13);
        fill.setColor(0x80A8B9CC);
        bar(canvas, center - 12, 47, 24, 8);
        // Pins and Apps are centered between the date and the Settings button, as in Dock.
        float top = DockLayout.CLOCK_DP + DockLayout.DATE_DP;
        float bottom = SCREEN_H - DockLayout.BOTTOM_PADDING_DP - DockLayout.SLOT_DP;
        int slots = dockIcons.size() + 1;
        float y = top + (bottom - top - slots * DockLayout.SLOT_DP) / 2 + DockLayout.GAP_DP;
        float size = DockLayout.BUTTON_DP - 8; // Dock icons sit inside 4 dp of button padding.
        float left = center - size / 2;
        for (Bitmap bitmap : dockIcons) {
            rect.set(left, y + 4, left + size, y + 4 + size);
            canvas.drawBitmap(bitmap, null, rect, icon);
            y += DockLayout.SLOT_DP;
        }
        fill.setColor(0xCCF1F6FB);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                canvas.drawCircle(center - 7 + column * 7, y + 17 + row * 7, 2.2f, fill);
            }
        }
        float settings = bottom + DockLayout.GAP_DP + DockLayout.BUTTON_DP / 2f;
        stroke.setColor(0xCCF1F6FB);
        stroke.setStrokeWidth(2.5f);
        canvas.drawLine(center - 10, settings - 5, center + 10, settings - 5, stroke);
        canvas.drawLine(center - 10, settings + 5, center + 10, settings + 5, stroke);
    }

    /** The media panel's text and controls, revealed within whatever width it has. */
    private void drawMedia(Canvas canvas, float start, float end) {
        if (end - start < 1) return;
        canvas.save();
        canvas.clipRect(start, 0, end, SCREEN_H);
        // Sized for the panel's settled width, so a sliding map reveals it instead of squeezing it.
        float x = start + 18;
        float width = (1 - share) * CONTENT_W - 36;
        fill.setColor(0xD9F1F6FB);
        bar(canvas, x, 196, width * 0.72f, 22);
        fill.setColor(0x80A8B9CC);
        bar(canvas, x, 232, width * 0.9f, 14);
        fill.setColor(0x99F1F6FB);
        for (int i = 0; i < 3; i++) canvas.drawCircle(x + width * (0.17f + 0.33f * i), 290, 11, fill);
        fill.setColor(0xFF1C2D40);
        rect.set(x, 330, x + width, 376);
        canvas.drawRoundRect(rect, 12, 12, fill);
        fill.setColor(0xB384BFFF);
        bar(canvas, x + width / 2 - 55, 347, 110, 12);
        canvas.restore();
    }

    private void drawMap(Canvas canvas, float x, float width, float unit) {
        // Only corners facing the media panel are rounded; edges against the dock or the
        // screen stay square, as in the launcher.
        float before = cornerRoundness(x - CONTENT_X);
        float after = cornerRoundness(CONTENT_X + CONTENT_W - x - width);
        radii[0] = radii[1] = radii[6] = radii[7] = MAP_CORNER * before;
        radii[2] = radii[3] = radii[4] = radii[5] = MAP_CORNER * after;
        rect.set(x, 0, x + width, SCREEN_H);
        path.reset();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        fill.setColor(0xFF223C3F);
        canvas.drawRect(rect, fill);
        // A few streets and the route, in the map's own proportions.
        stroke.setColor(0xFF3B5A5C);
        stroke.setStrokeWidth(16);
        path.reset();
        path.moveTo(x - 10, 330);
        path.cubicTo(x + width * 0.35f, 300, x + width * 0.55f, 170, x + width + 10, 150);
        canvas.drawPath(path, stroke);
        stroke.setStrokeWidth(10);
        canvas.drawLine(x + width * 0.3f, -10, x + width * 0.44f, SCREEN_H + 10, stroke);
        canvas.drawLine(x - 10, 90, x + width * 0.62f, 60, stroke);
        stroke.setColor(Ui.ACCENT);
        stroke.setStrokeWidth(9);
        path.reset();
        path.moveTo(x + width * 0.36f, SCREEN_H + 10);
        path.lineTo(x + width * 0.38f, 316);
        path.cubicTo(x + width * 0.5f, 270, x + width * 0.58f, 190, x + width * 0.82f, 158);
        canvas.drawPath(path, stroke);
        float puckX = x + width * 0.38f;
        fill.setColor(0xFFFFFFFF);
        canvas.drawCircle(puckX, 316, 15, fill);
        fill.setColor(Ui.ACCENT);
        canvas.drawCircle(puckX, 316, 9, fill);
        // The search bar floating over the map.
        fill.setColor(0x40FFFFFF);
        rect.set(x + 24, 22, x + Math.min(width - 24, 420), 70);
        canvas.drawRoundRect(rect, 24, 24, fill);
        canvas.restore();
        stroke.setStrokeWidth(unit);
    }

    private void bar(Canvas canvas, float left, float top, float width, float height) {
        rect.set(left, top, left + width, top + height);
        canvas.drawRoundRect(rect, height / 2, height / 2, fill);
    }
}
