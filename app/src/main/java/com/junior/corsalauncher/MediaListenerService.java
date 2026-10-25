package com.junior.corsalauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Lee qué se está reproduciendo en cualquier app con MediaSession
 * (Spotify, YouTube Music, la app de música del radio, etc.).
 * Requiere "Acceso a notificaciones" para Corsa Launcher.
 */
public class MediaListenerService extends NotificationListenerService {

    public static final MutableLiveData<NowPlayingInfo> nowPlaying = new MutableLiveData<>();
    private static MediaController activeController;
    /** true si la sesión activa entrega título (si no, los controles no sirven). */
    private static boolean controllerUsable = false;
    /** Botones de la notificación multimedia (si la hay). */
    private static List<Notification.Action> notifActions = new ArrayList<>();
    private static MediaListenerService instance;

    // ---- AVRCP (datos de la canción que manda el teléfono por Bluetooth) ----
    public static final String ACTION_AVRCP_TRACK = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT";
    private static final String EXTRA_AVRCP_META = "android.bluetooth.avrcp-controller.profile.extra.METADATA";
    private static final String EXTRA_AVRCP_PLAY = "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK";
    public static final String ACTION_TW_ID3 = "com.unisound.intent.action.SEND_ID3_INFO";
    public static final String ACTION_TW_PLAYSTATE = "com.unisound.intent.action.BT_MUSIC_PLAYING_STATE";
    private static final String[] AVRCP_ACTIONS = {
            ACTION_AVRCP_TRACK,
            ACTION_TW_ID3,
            ACTION_TW_PLAYSTATE,
            "android.bluetooth.avrcp-controller.profile.action.PLAYER_SETTING",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED",
            "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED"
    };
    public static final MutableLiveData<NowPlayingInfo> avrcpNow = new MutableLiveData<>();
    private static NowPlayingInfo avrcpState = null;
    public static final List<String> avrcpLog = new ArrayList<>();

    /** Procesa un aviso Bluetooth. Se llama desde el servicio y desde la pantalla. */
    public static void handleAvrcpIntent(Intent intent) {
        String action = String.valueOf(intent.getAction());
        StringBuilder log = new StringBuilder(String.format(java.util.Locale.US, "%tT ", Calendar.getInstance()));
        log.append(action.replace("android.bluetooth.", "").replace(".profile.action.", ":"));
        Bundle exs = intent.getExtras();
        if (exs != null) {
            for (String k : new ArrayList<>(exs.keySet())) {
                Object v = exs.get(k);
                String kk = k.substring(k.lastIndexOf('.') + 1);
                log.append("\n     ").append(kk).append("=");
                if (v instanceof MediaMetadata) {
                    MediaMetadata md = (MediaMetadata) v;
                    log.append("META{");
                    for (String mk : md.keySet()) {
                        String sv = md.getString(mk);
                        String lv = sv != null ? sv : String.valueOf(md.getLong(mk));
                        if (lv.length() > 30) lv = lv.substring(0, 30);
                        log.append(mk.substring(mk.lastIndexOf('.') + 1)).append(':').append(lv).append(' ');
                    }
                    log.append("}");
                } else {
                    String sv = String.valueOf(v);
                    if (sv.length() > 60) sv = sv.substring(0, 60);
                    log.append(v == null ? "null" : v.getClass().getSimpleName() + " " + sv);
                }
            }
        }
        avrcpLog.add(log.toString());
        while (avrcpLog.size() > 6) avrcpLog.remove(0);

        NowPlayingInfo info = new NowPlayingInfo();
        if (avrcpState != null) {
            info.title = avrcpState.title;
            info.artist = avrcpState.artist;
            info.album = avrcpState.album;
            info.duration = avrcpState.duration;
            info.isPlaying = avrcpState.isPlaying;
            info.position = avrcpState.position;
            info.updateTime = avrcpState.updateTime;
            info.speed = avrcpState.speed;
        } else {
            info.updateTime = SystemClock.elapsedRealtime();
        }
        info.source = "Bluetooth";
        try {
            if (ACTION_AVRCP_TRACK.equals(action)) {
                Bundle ex = intent.getExtras();
                if (ex != null) {
                    for (String k : new ArrayList<>(ex.keySet())) {
                        Object v = ex.get(k);
                        if (v instanceof MediaMetadata) {
                            MediaMetadata md = (MediaMetadata) v;
                            String t = pick(md, MediaMetadata.METADATA_KEY_TITLE,
                                    MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                            String ar = pick(md, MediaMetadata.METADATA_KEY_ARTIST,
                                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                                    MediaMetadata.METADATA_KEY_WRITER);
                            String al = pick(md, MediaMetadata.METADATA_KEY_ALBUM);
                            if (t == null) { // último recurso: primer texto no vacío
                                for (String mk : md.keySet()) {
                                    String sv = md.getString(mk);
                                    if (sv != null && !sv.isEmpty() && !mk.contains("ART_URI")) {
                                        t = sv;
                                        break;
                                    }
                                }
                            }
                            if (t != null) info.title = t;
                            if (ar != null) info.artist = ar;
                            if (al != null) info.album = al;
                            long d = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                            if (d > 0) info.duration = d;
                        } else if (v instanceof PlaybackState) {
                            PlaybackState st = (PlaybackState) v;
                            info.isPlaying = st.getState() == PlaybackState.STATE_PLAYING;
                            info.position = Math.max(0, st.getPosition());
                            info.updateTime = SystemClock.elapsedRealtime();
                            info.speed = st.getPlaybackSpeed();
                        } else if (v instanceof String && !((String) v).isEmpty()) {
                            String kl = k.toLowerCase(java.util.Locale.ROOT);
                            if (kl.contains("title") || kl.contains("track") || kl.contains("song")) info.title = (String) v;
                            else if (kl.contains("artist")) info.artist = (String) v;
                            else if (kl.contains("album")) info.album = (String) v;
                        }
                    }
                }
            } else if (ACTION_TW_ID3.equals(action)) {
                String t = intent.getStringExtra("musicTitle");
                String ar = intent.getStringExtra("musicArtist");
                if (t != null && !t.trim().isEmpty()) {
                    info.title = t.trim();
                    info.artist = ar == null ? "" : ar.trim();
                    info.isPlaying = true;
                    info.position = 0;
                    info.updateTime = SystemClock.elapsedRealtime();
                }
            } else if (ACTION_TW_PLAYSTATE.equals(action)) {
                return; // solo se registra en el diagnóstico
            } else if (action.contains("PLAYING_STATE_CHANGED")) {
                int st = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1);
                if (st == 10) info.isPlaying = true;
                else if (st == 11) info.isPlaying = false;
                info.updateTime = SystemClock.elapsedRealtime();
            } else {
                return;
            }
        } catch (Exception e) {
            avrcpLog.add("error: " + e);
        }
        avrcpState = info;
        avrcpNow.setValue(info);
    }

    private static String pick(MediaMetadata md, String... keys) {
        for (String k : keys) {
            String v = md.getString(k);
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private final BroadcastReceiver avrcpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleAvrcpIntent(intent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        for (String a : AVRCP_ACTIONS) f.addAction(a);
        try {
            registerReceiver(avrcpReceiver, f);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(avrcpReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

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
        return activeController != null && controllerUsable;
    }

    public static boolean hasNotifActions() {
        return !notifActions.isEmpty();
    }

    /** Texto para la pantalla de diagnóstico. */
    public static String describeNotifications() {
        MediaListenerService svc = instance;
        if (svc == null) return "  (servicio no conectado)\n";
        StringBuilder sb = new StringBuilder();
        try {
            StatusBarNotification[] arr = svc.getActiveNotifications();
            int n = 0;
            if (arr != null) {
                for (StatusBarNotification sbn : arr) {
                    Notification nt = sbn.getNotification();
                    boolean media = Notification.CATEGORY_TRANSPORT.equals(nt.category)
                            || nt.extras.containsKey("android.mediaSession");
                    if (!media) continue;
                    n++;
                    sb.append("  • ").append(sbn.getPackageName())
                            .append(" | título=").append(nt.extras.getCharSequence(Notification.EXTRA_TITLE))
                            .append(" | texto=").append(nt.extras.getCharSequence(Notification.EXTRA_TEXT))
                            .append(" | botones=");
                    if (nt.actions != null) {
                        for (Notification.Action a : nt.actions) sb.append("[").append(a.title).append("]");
                    }
                    sb.append("\n");
                }
            }
            if (n == 0) sb.append("  (ninguna)\n");
        } catch (Exception e) {
            sb.append("  error: ").append(e).append("\n");
        }
        return sb.toString();
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
        instance = this;
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
        instance = null;
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

    private static boolean hasTitle(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return false;
        String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null || t.isEmpty()) t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return t != null && !t.isEmpty();
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState st = c.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    /** Prefiere: sonando con título > con título > sonando > la primera. */
    private void selectController(List<MediaController> controllers) {
        MediaController chosen = null;
        if (controllers != null && !controllers.isEmpty()) {
            chosen = controllers.get(0);
            int best = -1;
            for (MediaController c : controllers) {
                int score = (hasTitle(c) ? 2 : 0) + (isPlaying(c) ? 1 : 0);
                if (score > best) {
                    best = score;
                    chosen = c;
                }
            }
        }
        if (chosen == null) {
            if (activeController != null) {
                activeController.unregisterCallback(controllerCallback);
                activeController = null;
            }
            controllerUsable = false;
            publish(null);
            return;
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

    /** Info sacada de la notificación multimedia (si existe). */
    private NowPlayingInfo fromNotifications() {
        notifActions = new ArrayList<>();
        try {
            StatusBarNotification[] arr = getActiveNotifications();
            if (arr == null) return null;
            for (StatusBarNotification sbn : arr) {
                if (getPackageName().equals(sbn.getPackageName())) continue;
                Notification n = sbn.getNotification();
                boolean media = Notification.CATEGORY_TRANSPORT.equals(n.category)
                        || n.extras.containsKey("android.mediaSession");
                if (!media) continue;
                CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                NowPlayingInfo info = new NowPlayingInfo();
                info.title = title == null ? "" : title.toString();
                info.artist = text == null ? "" : text.toString();
                info.source = appLabel(sbn.getPackageName());
                info.updateTime = SystemClock.elapsedRealtime();
                try {
                    Icon li = n.getLargeIcon();
                    if (li != null) {
                        Drawable d = li.loadDrawable(this);
                        if (d instanceof BitmapDrawable) info.albumArt = ((BitmapDrawable) d).getBitmap();
                    }
                } catch (Exception ignored) {
                }
                if (n.actions != null) {
                    for (Notification.Action a : n.actions) notifActions.add(a);
                }
                return info;
            }
        } catch (Exception ignored) {
        }
        return null;
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
        NowPlayingInfo fromNotif = null;
        boolean usable = controller != null && hasTitle(controller);
        controllerUsable = usable;
        if (!usable) fromNotif = fromNotifications();
        else notifActions = new ArrayList<>();

        if (controller == null) {
            nowPlaying.postValue(fromNotif);
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        NowPlayingInfo info = new NowPlayingInfo();
        if (metadata != null) {
            info.title = firstNonEmpty(metadata, MediaMetadata.METADATA_KEY_TITLE,
                    MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            info.artist = firstNonEmpty(metadata, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            info.album = firstNonEmpty(metadata, MediaMetadata.METADATA_KEY_ALBUM);
            info.albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (info.albumArt == null) info.albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (info.albumArt == null) info.albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            info.duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        info.source = appLabel(controller.getPackageName());
        if (state != null) {
            info.isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
            info.position = Math.max(0, state.getPosition());
            info.updateTime = state.getLastPositionUpdateTime();
            if (info.updateTime <= 0) info.updateTime = SystemClock.elapsedRealtime();
            info.speed = state.getPlaybackSpeed();
        } else {
            info.updateTime = SystemClock.elapsedRealtime();
        }
        if (!usable && fromNotif != null) {
            // La sesión no trae datos: usamos los de la notificación
            fromNotif.isPlaying = info.isPlaying;
            fromNotif.position = info.position;
            fromNotif.updateTime = info.updateTime;
            fromNotif.speed = info.speed;
            info = fromNotif;
        }
        nowPlaying.postValue(info);
    }

    // ---- controles --------------------------------------------------------

    private static boolean pressNotifAction(String... keywords) {
        for (Notification.Action a : notifActions) {
            String t = a.title == null ? "" : a.title.toString().toLowerCase(Locale.ROOT);
            for (String k : keywords) {
                if (t.contains(k) && a.actionIntent != null) {
                    try {
                        a.actionIntent.send();
                        return true;
                    } catch (PendingIntent.CanceledException ignored) {
                    }
                }
            }
        }
        return false;
    }

    /** @return true si la orden pudo enviarse; si es false, usar tecla multimedia. */
    public static boolean togglePlayPause() {
        if (hasController()) {
            PlaybackState state = activeController.getPlaybackState();
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            if (playing) {
                activeController.getTransportControls().pause();
            } else {
                activeController.getTransportControls().play();
            }
            return true;
        }
        return pressNotifAction("pause", "pausa", "play", "reprod");
    }

    public static boolean skipToNext() {
        if (hasController()) {
            activeController.getTransportControls().skipToNext();
            return true;
        }
        return pressNotifAction("next", "siguiente", "sig");
    }

    public static boolean skipToPrevious() {
        if (hasController()) {
            activeController.getTransportControls().skipToPrevious();
            return true;
        }
        return pressNotifAction("prev", "anterior", "atrás", "atras");
    }
}
