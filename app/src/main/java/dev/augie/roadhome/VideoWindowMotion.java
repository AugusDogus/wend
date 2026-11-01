package dev.augie.roadhome;

import android.graphics.Rect;

/** Uniform scale preserves video proportions while the crop reveals the full app around it. */
final class VideoWindowMotion {
    private final float startScale, startX, startY;
    private final VideoSurface.Bounds video;
    private final int width, height;

    VideoWindowMotion(int x, int y, int cardWidth, int cardHeight,
            VideoSurface.Bounds video, int width, int height) {
        this.video = video;
        this.width = width;
        this.height = height;
        startScale = Math.min(cardWidth / video.width, cardHeight / video.height);
        startX = x + (cardWidth - video.width * startScale) / 2 - video.left * startScale;
        startY = y + (cardHeight - video.height * startScale) / 2 - video.top * startScale;
    }

    float scale(float fraction) { return lerp(startScale, 1, fraction); }
    float x(float fraction) { return lerp(startX, 0, fraction); }
    float y(float fraction) { return lerp(startY, 0, fraction); }

    void crop(float fraction, Rect result) {
        result.set(Math.round(lerp(video.left, 0, fraction)), Math.round(lerp(video.top, 0, fraction)),
                Math.round(lerp(video.left + video.width, width, fraction)),
                Math.round(lerp(video.top + video.height, height, fraction)));
    }

    private static float lerp(float from, float to, float fraction) { return from + (to - from) * fraction; }
}
