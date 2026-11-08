package dev.augie.wend;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MediaSessionSubscriptionsTest {
    @Test public void removingOneSessionKeepsTheOtherSessionSubscribed() {
        Context context = RuntimeEnvironment.getApplication();
        MediaSession first = new MediaSession(context, "first");
        MediaSession second = new MediaSession(context, "second");
        MediaController one = first.getController();
        MediaController two = second.getController();
        AtomicInteger changes = new AtomicInteger();
        MediaSessionSubscriptions subscriptions = new MediaSessionSubscriptions(
                new Handler(Looper.getMainLooper()), changes::incrementAndGet);
        try {
            subscriptions.update(Arrays.asList(one, two));
            subscriptions.update(Collections.singletonList(two));
            assertEquals(0, Shadows.shadowOf(one).getCallbacks().size());
            Shadows.shadowOf(two).executeOnMetadataChanged(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Still observed").build());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, changes.get());
            // Android may return a new controller wrapper for the same token.
            MediaController wrapper = new MediaController(context, second.getSessionToken());
            subscriptions.update(Arrays.asList(wrapper, wrapper));
            assertEquals(1, Shadows.shadowOf(two).getCallbacks().size());
            assertEquals(0, Shadows.shadowOf(wrapper).getCallbacks().size());
        } finally {
            subscriptions.clear(); first.release(); second.release();
        }
        assertEquals(0, Shadows.shadowOf(two).getCallbacks().size());
    }
}
