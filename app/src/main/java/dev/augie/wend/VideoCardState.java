package dev.augie.wend;

/** A selected video keeps its card even when playback is paused, stopped, or buffering. */
enum VideoCardState {
    HIDDEN, THUMBNAIL, PLAYING;

    static VideoCardState fromMedia(String packageName, CharSequence title, boolean playing) {
        if (!EmbeddedVideo.PACKAGE.equals(packageName) || title == null
                || title.toString().trim().isEmpty()) return HIDDEN;
        return playing ? PLAYING : THUMBNAIL;
    }
}
