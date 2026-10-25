package com.junior.corsalauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.lifecycle.MutableLiveData;

import java.util.List;

/**
 * Lee qué se está reproduciendo en cualquier app con MediaSession
 * (Spotify, YouTube Music, la app de música del radio, etc.).
 * Requiere "Acceso a notificaciones" para Corsa Launcher.
 */
public class MediaListenerService extends NotificationListenerService {

    public static final MutableLiveData<NowPlayingInfo> nowPlaying = new MutableLiveData<>();
    private static MediaController activeController;

    public static class NowPlayingInfo {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String source = "";
        public boolean isPlaying = false;
        public Bitmap albumArt;
        public long duration = 0;       // ms, 0 = desconocida
        public long position = 0;       // ms en el momento de updateTime
        public long updateTime = 0;     // SystemClock.elapsedRealtime()
        public float speed = 1f;

        /** Posición actual estimada. */
        public long currentPosition() {
            long p = position;
            if (isPlaying) p += (long) ((SystemClock.elapsedRealtime() - updateTime) * speed);
            if (duration > 0 && p > duration) p = duration;
            return Math.max(0, p);
        }
    }

    public static boolean hasController() {
        return activeController != null;
    }

    private MediaSessionManager sessionManager;
    private ComponentName component;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> selectController(controllers);

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish(activeController);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish(activeController);
        }

        @Override
        public void onSessionDestroyed() {
            refreshActiveSession();
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        component = new ComponentName(this, MediaListenerService.class);
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component);
        } catch (SecurityException ignored) {
        }
        refreshActiveSession();
    }

    @Override
    public void onListenerDisconnected() {
        try {
            if (sessionManager != null) {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            }
        } catch (Exception ignored) {
        }
        if (activeController != null) {
            activeController.unregisterCallback(controllerCallback);
            activeController = null;
        }
        nowPlaying.postValue(null);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refreshActiveSession();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refreshActiveSession();
    }

    private void refreshActiveSession() {
        if (sessionManager == null) return;
        try {
            selectController(sessionManager.getActiveSessions(component));
        } catch (SecurityException e) {
            // Aún sin acceso a notificaciones.
        }
    }

    /** Prefiere la sesión que está sonando; si no hay, la primera. */
    private void selectController(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            if (activeController != null) {
                activeController.unregisterCallback(controllerCallback);
                activeController = null;
            }
            nowPlaying.postValue(null);
            return;
        }
        MediaController chosen = controllers.get(0);
        for (MediaController c : controllers) {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                chosen = c;
                break;
            }
        }
        if (activeController == null
                || !activeController.getSessionToken().equals(chosen.getSessionToken())) {
            if (activeController != null) {
                activeController.unregisterCallback(controllerCallback);
            }
            activeController = chosen;
            activeController.registerCallback(controllerCallback);
        }
        publish(activeController);
    }

    private static String firstNonEmpty(MediaMetadata m, String... keys) {
        for (String k : keys) {
            String v = m.getString(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private String appLabel(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private void publish(MediaController controller) {
        if (controller == null) {
            nowPlaying.postValue(null);
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        if (metadata == null) {
            nowPlaying.postValue(null);
            return;
        }
        NowPlayingInfo info = new NowPlayingInfo();
        info.title = firstNonEmpty(metadata, MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        info.artist = firstNonEmpty(metadata, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        info.album = firstNonEmpty(metadata, MediaMetadata.METADATA_KEY_ALBUM);
        info.source = appLabel(controller.getPackageName());
        info.albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (info.albumArt == null) info.albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (info.albumArt == null) info.albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        info.duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (state != null) {
            info.isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
            info.position = Math.max(0, state.getPosition());
            info.updateTime = state.getLastPositionUpdateTime();
            if (info.updateTime <= 0) info.updateTime = SystemClock.elapsedRealtime();
            info.speed = state.getPlaybackSpeed();
        } else {
            info.updateTime = SystemClock.elapsedRealtime();
        }
        nowPlaying.postValue(info);
    }

    public static void togglePlayPause() {
        if (activeController == null) return;
        PlaybackState state = activeController.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        if (playing) {
            activeController.getTransportControls().pause();
        } else {
            activeController.getTransportControls().play();
        }
    }

    public static void skipToNext() {
        if (activeController != null) activeController.getTransportControls().skipToNext();
    }

    public static void skipToPrevious() {
        if (activeController != null) activeController.getTransportControls().skipToPrevious();
    }
}
