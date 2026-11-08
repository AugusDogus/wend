package dev.augie.wend;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VideoCardStateTest {
    @Test public void pausedSelectedVideoKeepsThumbnail() {
        assertEquals(VideoCardState.THUMBNAIL,
                VideoCardState.fromMedia(EmbeddedVideo.PACKAGE, "Selected video", false));
    }
    @Test public void playingSelectedVideoUsesLivePlayer() {
        assertEquals(VideoCardState.PLAYING,
                VideoCardState.fromMedia(EmbeddedVideo.PACKAGE, "Selected video", true));
    }
    @Test public void pausingChangesPresentationWithoutRemovingCard() {
        assertEquals(VideoCardState.PLAYING,
                VideoCardState.fromMedia(EmbeddedVideo.PACKAGE, "Selected video", true));
        assertEquals(VideoCardState.THUMBNAIL,
                VideoCardState.fromMedia(EmbeddedVideo.PACKAGE, "Selected video", false));
    }
    @Test public void clearingSelectedVideoRemovesCardEvenIfPlaybackStateLags() {
        assertEquals(VideoCardState.HIDDEN,
                VideoCardState.fromMedia(EmbeddedVideo.PACKAGE, null, true));
        assertEquals(VideoCardState.HIDDEN,
                VideoCardState.fromMedia(EmbeddedVideo.PACKAGE, "  \n ", false));
    }
    @Test public void closedSessionCannotLeaveStaleCard() {
        assertEquals(VideoCardState.HIDDEN,
                VideoCardState.fromMedia(null, "Old video title", false));
    }
    @Test public void otherAppsDoNotCreateYouTubeCard() {
        assertEquals(VideoCardState.HIDDEN,
                VideoCardState.fromMedia("com.spotify.music", "Song title", true));
    }
}
