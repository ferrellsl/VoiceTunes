package com.ferrellsl.voicetunes;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Plays the queue in the background, with the system's media notification and lock-screen
 * controls, and answers Bluetooth, headset and car buttons. The app's screen controls it
 * through a MediaController.
 */
public final class PlaybackService extends MediaSessionService {

    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        ExoPlayer player = new ExoPlayer.Builder(this)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)           // (pause when headphones come out)
            .setWakeMode(C.WAKE_MODE_NETWORK)            // (keep streaming with the screen off)
            .build();
        session = new MediaSession.Builder(this, player).build();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(android.content.Intent rootIntent) {
        // swiped away while paused or stopped: end; while playing, play on
        Player p = session.getPlayer();
        if (!p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        session.getPlayer().release();
        session.release();
        session = null;
        super.onDestroy();
    }
}
