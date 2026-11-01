package dev.augie.roadhome;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Watches metadata and playback on every active session, including unselected apps. */
final class MediaSessionSubscriptions {
    private final Handler handler;
    private final Runnable changed;
    private final Map<MediaSession.Token, Subscription> observed = new HashMap<>();

    MediaSessionSubscriptions(Handler handler, Runnable changed) {
        this.handler = handler;
        this.changed = changed;
    }

    void update(List<MediaController> active) {
        Map<MediaSession.Token, Subscription> current = new HashMap<>();
        if (active != null) {
            for (MediaController session : active) {
                MediaSession.Token token = session.getSessionToken();
                if (current.containsKey(token)) continue;
                Subscription existing = observed.get(token);
                current.put(token, existing == null ? new Subscription(session) : existing);
            }
        }
        for (Map.Entry<MediaSession.Token, Subscription> entry : observed.entrySet()) {
            if (!current.containsKey(entry.getKey())) entry.getValue().close();
        }
        observed.clear();
        observed.putAll(current);
    }

    void clear() {
        for (Subscription subscription : observed.values()) subscription.close();
        observed.clear();
    }

    boolean isDestroyed(MediaController controller) {
        Subscription subscription = observed.get(controller.getSessionToken());
        return subscription != null && subscription.destroyed;
    }

    private final class Subscription {
        private final MediaController controller;
        private boolean destroyed;
        // Callback owns its Android Handler state. Sharing one across controllers would
        // disable the remaining observers when any controller unregisters it.
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata metadata) { changed.run(); }
            @Override public void onPlaybackStateChanged(PlaybackState state) { changed.run(); }
            @Override public void onSessionDestroyed() { destroyed = true; changed.run(); }
        };

        Subscription(MediaController controller) {
            this.controller = controller;
            controller.registerCallback(callback, handler);
        }
        void close() { controller.unregisterCallback(callback); }
    }
}
