package com.ikoralite;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells whether a silent player (YouTube) is playing, without DUMP and without a PC.
 *
 * Only a notification listener may read other apps' media sessions (package and play state).
 * Notifications themselves are never read here: onNotificationPosted is not overridden.
 * The session id is not in a media session either, so while YouTube plays the effect goes on
 * the whole output instead ({@link Eq#setSilentPlaying}); on the XQ-FS44 that output carries
 * music and video only (notification sounds play on another one).
 */
public class PlayingListener extends NotificationListenerService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private final List<MediaController> watched = new ArrayList<>();

    static ComponentName component(Context c) {
        return new ComponentName(c, PlayingListener.class);
    }

    /** Whether the user has let ikora in (Settings → 通知へのアクセス). */
    static boolean allowed(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(component(c));
    }

    private final MediaController.Callback onState = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            judge();
        }

        @Override
        public void onSessionDestroyed() {
            judge();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::follow;

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        try {
            msm.addOnActiveSessionsChangedListener(onSessions, component(this), main);
            follow(msm.getActiveSessions(component(this)));
        } catch (SecurityException e) {
            // Access taken back between connecting and asking.
            Diag.note(this, "再生中のアプリを読めない: " + e.getMessage());
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(onSessions);
        follow(null);
    }

    private void follow(List<MediaController> sessions) {
        for (MediaController c : watched) c.unregisterCallback(onState);
        watched.clear();
        if (sessions != null) {
            for (MediaController c : sessions) {
                if (!Watch.isSilent(c.getPackageName())) continue;
                c.registerCallback(onState, main);
                watched.add(c);
            }
        }
        judge();
    }

    private void judge() {
        boolean playing = false;
        for (MediaController c : watched) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && plays(s.getState())) playing = true;
        }
        Eq.setSilentPlaying(this, playing);
    }

    /** Playing, or about to be (buffering after a seek counts: the sound resumes by itself). */
    private static boolean plays(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }
}
