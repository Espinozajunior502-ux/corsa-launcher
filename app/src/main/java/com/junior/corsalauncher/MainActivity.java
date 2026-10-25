package com.junior.corsalauncher;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CorsaLauncher";
    private static final int REQ_PERMISSIONS = 100;
    private static final String USER_NAME = "Junior";
    private static final long WEATHER_REFRESH_MS = 30 * 60 * 1000L;

    // Acciones que algunos radios/reproductores envían al cambiar de canción
    private static final String[] MUSIC_ACTIONS = {
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.android.music.playbackcomplete",
            "com.android.music.queuechanged",
            "com.spotify.music.metadatachanged",
            "com.spotify.music.playbackstatechanged",
            "com.htc.music.metachanged",
            "com.sec.android.app.music.metachanged",
            "com.miui.player.metachanged",
            "com.amazon.mp3.metachanged",
            "com.maxmpz.audioplayer.TPOS_SYNC",
            "fm.last.android.metachanged",
            "com.android.bluetooth.avrcp.metachanged",
            "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT",
            "com.android.music.avrcp.metachanged"
    };

    private static final int[] BT_PROFILES = {
            BluetoothProfile.HEADSET, BluetoothProfile.A2DP, 11 /* A2DP_SINK */, 16 /* HEADSET_CLIENT */
    };

    private TextView clockText, dateText, greetingText, weatherText, btText, btBattery, speedValue;
    private TextView songTitle, songArtist, songAlbum, sourceText;
    private ImageView btnPlayPause, btnPrev, btnNext, albumArt, weatherIcon;
    private ProgressBar musicProgress;
    private View weatherPill, drawerOverlay, diagOverlay;
    private GridView appGrid;
    private CarView carView;
    private TextView diagText;

    private LocationManager locationManager;
    private boolean gpsStarted = false;
    private long lastWeatherAt = 0;
    private boolean weatherLoading = false;

    private BluetoothAdapter bluetoothAdapter;
    private final android.util.SparseArray<BluetoothProfile> btProxies = new android.util.SparseArray<>();
    private final boolean[] btProxyRequested = new boolean[BT_PROFILES.length];
    private String connectedBtName = null;
    private BluetoothDevice connectedBtDevice = null;

    private boolean notifAccess = false;
    private MediaListenerService.NowPlayingInfo currentInfo = null;
    private String lastBroadcast = "(ninguno)";
    private final List<String> sniffLog = new ArrayList<>();
    private final Map<String, Set<String>> twActions = new LinkedHashMap<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            Calendar c = Calendar.getInstance();
            clockText.setText(String.format(Locale.getDefault(), "%02d:%02d",
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
            dateText.setText(formatDate(c));
            int hour = c.get(Calendar.HOUR_OF_DAY);
            String saludo = hour < 12 ? "Buenos días" : (hour < 19 ? "Buenas tardes" : "Buenas noches");
            greetingText.setText(saludo + ", " + USER_NAME);
            mainHandler.postDelayed(this, 10000);
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                connectedBtDevice = d;
                connectedBtName = deviceName(d);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (connectedBtName != null && connectedBtName.equals(deviceName(d))) {
                    connectedBtName = null;
                    connectedBtDevice = null;
                }
            }
            updateBluetoothStatus();
        }
    };

    private final BroadcastReceiver musicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            lastBroadcast = String.valueOf(intent.getAction());
            if (MediaListenerService.hasController() || MediaListenerService.hasNotifActions()) return; // la sesión real manda
            String title = firstExtra(intent, "track", "title", "song", "songname");
            if (title == null || title.isEmpty()) return;
            MediaListenerService.NowPlayingInfo info = new MediaListenerService.NowPlayingInfo();
            info.title = title;
            String artist = firstExtra(intent, "artist", "artistname");
            String album = firstExtra(intent, "album", "albumname");
            info.artist = artist == null ? "" : artist;
            info.album = album == null ? "" : album;
            info.source = "Bluetooth";
            info.isPlaying = intent.getBooleanExtra("playing", true);
            info.updateTime = SystemClock.elapsedRealtime();
            MediaListenerService.nowPlaying.setValue(info);
        }
    };

    /** Escucha las acciones que declaran las apps com.tw.* del radio y apunta lo que llega. */
    private final BroadcastReceiver sniffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%tT ", Calendar.getInstance()));
            sb.append(intent.getAction());
            String title = null, artist = null, album = null;
            Bundle ex = intent.getExtras();
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object v = ex.get(k);
                    String vs = String.valueOf(v);
                    if (vs.length() > 60) vs = vs.substring(0, 60) + "…";
                    sb.append(" | ").append(k).append("=").append(vs);
                    if (v instanceof String && !((String) v).isEmpty()) {
                        String kl = k.toLowerCase(Locale.ROOT);
                        if (kl.contains("title") || kl.contains("song") || kl.contains("track") || kl.equals("name")) {
                            if (title == null) title = (String) v;
                        } else if (kl.contains("artist") || kl.contains("singer")) {
                            artist = (String) v;
                        } else if (kl.contains("album")) {
                            album = (String) v;
                        }
                    }
                }
            }
            sniffLog.add(sb.toString());
            while (sniffLog.size() > 25) sniffLog.remove(0);
            lastBroadcast = String.valueOf(intent.getAction());
            if (title != null && !MediaListenerService.hasController()) {
                MediaListenerService.NowPlayingInfo info = new MediaListenerService.NowPlayingInfo();
                info.title = title;
                info.artist = artist == null ? "" : artist;
                info.album = album == null ? "" : album;
                info.source = "Bluetooth";
                info.isPlaying = true;
                info.updateTime = SystemClock.elapsedRealtime();
                MediaListenerService.nowPlaying.setValue(info);
            }
        }
    };

    /** Lee el AndroidManifest de otra app instalada y junta sus acciones de receptores/servicios. */
    private Set<String> readManifestActions(String pkg) {
        Set<String> out = new LinkedHashSet<>();
        try {
            XmlResourceParser xp = getPackageManager().getResourcesForApplication(pkg)
                    .getAssets().openXmlResourceParser("AndroidManifest.xml");
            int ev;
            while ((ev = xp.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && "action".equals(xp.getName())) {
                    String n = xp.getAttributeValue("http://schemas.android.com/apk/res/android", "name");
                    if (n != null && !n.equals("android.intent.action.MAIN")
                            && !n.startsWith("android.intent.action.BOOT")
                            && !n.equals("android.intent.action.VIEW")) {
                        out.add(n);
                    }
                }
            }
            xp.close();
        } catch (Exception e) {
            Log.w(TAG, "No pude leer el manifest de " + pkg, e);
        }
        return out;
    }

    private void startSniffer() {
        try {
            IntentFilter f = new IntentFilter();
            int count = 0;
            for (PackageInfo pi : getPackageManager().getInstalledPackages(0)) {
                String pn = pi.packageName;
                if (!(pn.startsWith("com.tw.") || pn.startsWith("android.tw") || pn.contains(".tw."))) continue;
                Set<String> acts = readManifestActions(pn);
                if (acts.isEmpty()) continue;
                twActions.put(pn, acts);
                for (String a : acts) {
                    if (count++ > 300) break;
                    f.addAction(a);
                }
            }
            if (count > 0) registerReceiver(sniffReceiver, f);
        } catch (Exception e) {
            Log.w(TAG, "Espía no iniciado", e);
        }
    }

    private static String firstExtra(Intent i, String... keys) {
        for (String k : keys) {
            Object o = i.getExtras() != null ? i.getExtras().get(k) : null;
            if (o instanceof CharSequence && o.toString().length() > 0) return o.toString();
        }
        return null;
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            showSpeed(location);
            maybeFetchWeather(location);
        }

        // Obligatorios en Android < 11 (si faltan, crashea con AbstractMethodError)
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
        }
    };

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        clockText = findViewById(R.id.clockText);
        dateText = findViewById(R.id.dateText);
        greetingText = findViewById(R.id.greetingText);
        weatherText = findViewById(R.id.weatherText);
        weatherIcon = findViewById(R.id.weatherIcon);
        weatherPill = findViewById(R.id.weatherPill);
        btText = findViewById(R.id.btText);
        btBattery = findViewById(R.id.btBattery);
        speedValue = findViewById(R.id.speedValue);
        songTitle = findViewById(R.id.songTitle);
        songArtist = findViewById(R.id.songArtist);
        songAlbum = findViewById(R.id.songAlbum);
        sourceText = findViewById(R.id.sourceText);
        musicProgress = findViewById(R.id.musicProgress);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        albumArt = findViewById(R.id.albumArt);
        drawerOverlay = findViewById(R.id.drawerOverlay);
        diagOverlay = findViewById(R.id.diagOverlay);
        diagText = findViewById(R.id.diagText);
        appGrid = findViewById(R.id.appGrid);
        carView = findViewById(R.id.carImage);

        setupDock();
        setupDrawer();
        setupMusic();
        setupBluetooth();
        setupDiagnostics();
        startSniffer();
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(clockTick);
        mainHandler.removeCallbacks(progressTick);
        clockTick.run();
        progressTick.run();
        refreshNotificationAccess();
        setupGps();
        setupBluetoothProxies();
        updateBluetoothStatus();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(clockTick);
        mainHandler.removeCallbacks(progressTick);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer();
        diagOverlay.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (diagOverlay.getVisibility() == View.VISIBLE) {
            diagOverlay.setVisibility(View.GONE);
        } else if (drawerOverlay.getVisibility() == View.VISIBLE) {
            closeDrawer();
        }
        // Siendo launcher, Atrás en la pantalla principal no hace nada.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(musicReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(sniffReceiver);
        } catch (Exception ignored) {
        }
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
        for (int i = 0; i < btProxies.size(); i++) {
            try {
                bluetoothAdapter.closeProfileProxy(btProxies.keyAt(i), btProxies.valueAt(i));
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ fecha

    private static String formatDate(Calendar c) {
        String s = new SimpleDateFormat("EEE, dd MMM", new Locale("es", "ES")).format(c.getTime());
        s = s.replace(".", "");
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ permisos

    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        setupGps();
        setupBluetoothProxies();
        updateBluetoothStatus();
    }

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ música

    private void setupMusic() {
        btnPlayPause.setOnClickListener(v -> {
            if (!MediaListenerService.togglePlayPause()) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        });
        btnPrev.setOnClickListener(v -> {
            if (!MediaListenerService.skipToPrevious()) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        });
        btnNext.setOnClickListener(v -> {
            if (!MediaListenerService.skipToNext()) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        });

        findViewById(R.id.musicCard).setOnClickListener(v -> {
            if (!notifAccess) {
                openNotificationAccessSettings();
            } else {
                openFactoryMusic();
            }
        });

        IntentFilter f = new IntentFilter();
        for (String a : MUSIC_ACTIONS) f.addAction(a);
        registerReceiver(musicReceiver, f);

        MediaListenerService.nowPlaying.observe(this, info -> {
            currentInfo = info;
            renderMusic();
        });
    }

    /** Abre la app de Bluetooth/música de fábrica del radio. */
    private void openFactoryMusic() {
        for (String pkg : new String[]{"com.tw.bt", "com.tw.media"}) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                launch(i);
                return;
            }
        }
        Toast.makeText(this, "No encontré la app de música del radio", Toast.LENGTH_SHORT).show();
    }

    private void sendMediaKey(int keyCode) {
        // Si una app del radio (com.tw.*) recibe botones multimedia, se los mandamos a ella sola
        try {
            Intent probe = new Intent(Intent.ACTION_MEDIA_BUTTON);
            List<ResolveInfo> rs = getPackageManager().queryBroadcastReceivers(probe, 0);
            boolean sent = false;
            for (ResolveInfo r : rs) {
                String pn = r.activityInfo.packageName;
                if (!pn.startsWith("com.tw.")) continue;
                for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
                    i.setComponent(new ComponentName(pn, r.activityInfo.name));
                    i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, keyCode));
                    sendBroadcast(i);
                }
                sent = true;
            }
            if (sent) return;
        } catch (Exception e) {
            Log.w(TAG, "Botón multimedia dirigido falló", e);
        }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } catch (Exception e) {
            Log.w(TAG, "No se pudo enviar tecla multimedia", e);
        }
    }

    private void renderMusic() {
        MediaListenerService.NowPlayingInfo info = currentInfo;
        if (info == null) {
            songTitle.setText("Sin reproducción");
            songArtist.setText("");
            songAlbum.setText("");
            sourceText.setText(notifAccess
                    ? "Reproduce música para verla aquí"
                    : "Toca la tarjeta para activar la música");
            btnPlayPause.setImageResource(R.drawable.ic_play);
            albumArt.setImageBitmap(null);
            musicProgress.setProgress(0);
            return;
        }
        songTitle.setText(info.title.isEmpty() ? "Reproduciendo" : info.title);
        songArtist.setText(info.artist);
        songAlbum.setText(info.album);
        sourceText.setText(info.source == null || info.source.isEmpty() ? "" : "Fuente: " + info.source);
        btnPlayPause.setImageResource(info.isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        albumArt.setImageBitmap(info.albumArt);
        updateProgress();
    }

    private void updateProgress() {
        MediaListenerService.NowPlayingInfo info = currentInfo;
        if (info == null || info.duration <= 0) {
            musicProgress.setProgress(0);
            return;
        }
        musicProgress.setProgress((int) (1000L * info.currentPosition() / info.duration));
    }

    private void refreshNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean now = enabled != null && enabled.contains(getPackageName());
        if (now && !notifAccess) {
            try {
                NotificationListenerService.requestRebind(
                        new ComponentName(this, MediaListenerService.class));
            } catch (Exception ignored) {
            }
        }
        notifAccess = now;
        if (currentInfo == null) renderMusic();
    }

    private void openNotificationAccessSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Este radio no tiene esa pantalla de ajustes", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ dock y apps

    private static final String[] DOCK_LABELS = {"Navegación", "Teléfono", "Mensajes", "Cámara trasera"};
    private static final int[] DOCK_ICONS = {R.drawable.ic_nav, R.drawable.ic_phone, R.drawable.ic_msg, R.drawable.ic_cam};

    private void setupDock() {
        int[] slots = {R.id.dockSlot0, R.id.dockSlot1, R.id.dockSlot2, R.id.dockSlot3};
        int[] icons = {R.id.dockIcon0, R.id.dockIcon1, R.id.dockIcon2, R.id.dockIcon3};
        int[] labels = {R.id.dockLabel0, R.id.dockLabel1, R.id.dockLabel2, R.id.dockLabel3};
        for (int i = 0; i < 4; i++) {
            ((ImageView) findViewById(icons[i])).setImageResource(DOCK_ICONS[i]);
            ((TextView) findViewById(labels[i])).setText(DOCK_LABELS[i]);
        }
        findViewById(slots[0]).setOnClickListener(v -> {
            if (!launchByLabel("navegac", "navi", "gps", "waze", "maps", "mapa")) {
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")));
            }
        });
        findViewById(slots[1]).setOnClickListener(v -> {
            if (!launchByLabel("bluetooth phone", "btphone", "bt phone", "teléfono bt", "bt call")) {
                launch(new Intent(Intent.ACTION_DIAL));
            }
        });
        findViewById(slots[2]).setOnClickListener(v ->
                launch(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))));
        findViewById(slots[3]).setOnClickListener(v -> {
            if (!launchByLabel("cámara trasera", "camara trasera", "reversa", "avm", "360", "rear", "camera", "cámara", "camara")) {
                Toast.makeText(this, "No encontré una app de cámara trasera", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btnApps).setOnClickListener(v -> openDrawer());
    }

    private boolean launchByLabel(String... keywords) {
        loadApps();
        for (String k : keywords) {
            for (AppEntry e : apps) {
                if (e.label.toLowerCase(Locale.ROOT).contains(k)) {
                    launch(Intent.makeMainActivity(e.component));
                    return true;
                }
            }
        }
        return false;
    }

    private void launch(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay app para esto en el radio", Toast.LENGTH_SHORT).show();
        }
    }

    private static class AppEntry {
        String label;
        ComponentName component;
        Drawable icon;
    }

    private final List<AppEntry> apps = new ArrayList<>();

    private final BaseAdapter appAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_app, parent, false);
            AppEntry e = apps.get(position);
            ((ImageView) v.findViewById(R.id.appIcon)).setImageDrawable(e.icon);
            ((TextView) v.findViewById(R.id.appLabel)).setText(e.label);
            return v;
        }
    };

    private void setupDrawer() {
        appGrid.setAdapter(appAdapter);
        appGrid.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry e = apps.get(position);
            closeDrawer();
            launch(Intent.makeMainActivity(e.component));
        });
        findViewById(R.id.btnCloseDrawer).setOnClickListener(v -> closeDrawer());
    }

    private void openDrawer() {
        loadApps();
        drawerOverlay.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerOverlay.setVisibility(View.GONE);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        apps.clear();
        for (ResolveInfo ri : list) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            AppEntry e = new AppEntry();
            e.label = String.valueOf(ri.loadLabel(pm));
            e.icon = ri.loadIcon(pm);
            e.component = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            apps.add(e);
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        appAdapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ Bluetooth

    private void setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(btReceiver, filter);
        updateBluetoothStatus();
    }

    /** Detecta el dispositivo ya conectado al abrir la app (el receiver solo ve cambios). */
    private void setupBluetoothProxies() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) return;
        for (int i = 0; i < BT_PROFILES.length; i++) {
            if (btProxyRequested[i]) continue;
            btProxyRequested[i] = true;
            try {
                bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        btProxies.put(profile, proxy);
                        try {
                            List<BluetoothDevice> connected = proxy.getConnectedDevices();
                            if (connected != null && !connected.isEmpty() && connectedBtName == null) {
                                connectedBtDevice = connected.get(0);
                                connectedBtName = deviceName(connectedBtDevice);
                            }
                        } catch (SecurityException ignored) {
                        }
                        updateBluetoothStatus();
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                    }
                }, BT_PROFILES[i]);
            } catch (Exception e) {
                Log.w(TAG, "Perfil Bluetooth no disponible: " + BT_PROFILES[i], e);
            }
        }
    }

    private String deviceName(BluetoothDevice d) {
        if (d == null) return "Dispositivo";
        try {
            String n = d.getName();
            return n != null ? n : "Dispositivo";
        } catch (SecurityException e) {
            return "Dispositivo";
        }
    }

    /** Nivel de batería del dispositivo conectado (API oculta; -1 si no se puede). */
    private int deviceBattery(BluetoothDevice d) {
        if (d == null) return -1;
        try {
            Object r = BluetoothDevice.class.getMethod("getBatteryLevel").invoke(d);
            return r instanceof Integer ? (Integer) r : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private void updateBluetoothStatus() {
        btBattery.setVisibility(View.GONE);
        if (bluetoothAdapter == null) {
            btText.setText("Sin Bluetooth");
        } else if (!bluetoothAdapter.isEnabled()) {
            btText.setText("Bluetooth apagado");
        } else if (connectedBtName != null) {
            btText.setText(connectedBtName);
            int level = deviceBattery(connectedBtDevice);
            if (level >= 0 && level <= 100) {
                btBattery.setText("•  " + level + "%");
                btBattery.setVisibility(View.VISIBLE);
            }
        } else {
            btText.setText("Bluetooth");
        }
    }

    // ------------------------------------------------------------------ GPS y clima

    private void setupGps() {
        if (gpsStarted || !has(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, locationListener);
            gpsStarted = true;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location last = null;
                try {
                    last = locationManager.getLastKnownLocation(provider);
                } catch (IllegalArgumentException ignored) {
                }
                if (last != null) {
                    maybeFetchWeather(last);
                    break;
                }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "GPS no disponible", e);
        }
    }

    private void showSpeed(Location location) {
        float kmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        if (kmh < 3f) kmh = 0f; // filtra el ruido del GPS parado
        speedValue.setText(String.format(Locale.getDefault(), "%.0f", kmh));
        carView.setSpeed(kmh);
    }

    private void maybeFetchWeather(Location loc) {
        long now = System.currentTimeMillis();
        if (weatherLoading || now - lastWeatherAt < WEATHER_REFRESH_MS) return;
        weatherLoading = true;
        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                long temp = Math.round(cur.getDouble("temperature_2m"));
                int code = cur.optInt("weather_code", 0);
                lastWeatherAt = System.currentTimeMillis();
                mainHandler.post(() -> {
                    weatherText.setText(temp + "°C");
                    weatherIcon.setImageResource(code <= 1 ? R.drawable.ic_sun : R.drawable.ic_cloud);
                    weatherPill.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                Log.w(TAG, "Clima no disponible", e);
            } finally {
                if (conn != null) conn.disconnect();
                weatherLoading = false;
            }
        });
    }

    // ------------------------------------------------------------------ diagnóstico

    private void setupDiagnostics() {
        clockText.setOnLongClickListener(v -> {
            diagOverlay.setVisibility(View.VISIBLE);
            diagText.setText(buildDiagnostics());
            return true;
        });
        findViewById(R.id.btnCloseDiag).setOnClickListener(v -> diagOverlay.setVisibility(View.GONE));
        findViewById(R.id.btnRefreshDiag).setOnClickListener(v -> diagText.setText(buildDiagnostics()));
    }

    private String buildDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Equipo: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n");
        sb.append("Acceso a notificaciones: ").append(notifAccess ? "SÍ" : "NO").append("\n");
        sb.append("Sesión de música activa en la app: ").append(MediaListenerService.hasController() ? "SÍ" : "NO").append("\n");
        sb.append("Último aviso de música recibido: ").append(lastBroadcast).append("\n");
        sb.append("Permiso ubicación: ").append(has(Manifest.permission.ACCESS_FINE_LOCATION) ? "SÍ" : "NO").append("\n");
        sb.append("Bluetooth: ").append(bluetoothAdapter == null ? "no existe"
                : (bluetoothAdapter.isEnabled() ? "encendido" : "apagado")).append("\n");
        sb.append("Dispositivo conectado: ").append(connectedBtName == null ? "(ninguno detectado)" : connectedBtName).append("\n\n");

        sb.append("Sesiones de medios del sistema:\n");
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(this, MediaListenerService.class));
            if (list == null || list.isEmpty()) {
                sb.append("  (ninguna)\n");
            } else {
                for (MediaController c : list) {
                    PlaybackState st = c.getPlaybackState();
                    CharSequence t = c.getMetadata() != null
                            ? c.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_TITLE) : null;
                    sb.append("  • ").append(c.getPackageName())
                            .append(" | estado=").append(st == null ? "?" : String.valueOf(st.getState()))
                            .append(" | título=").append(t == null ? "-" : t).append("\n");
                }
            }
        } catch (SecurityException e) {
            sb.append("  (sin acceso: activa el acceso a notificaciones)\n");
        } catch (Exception e) {
            sb.append("  error: ").append(e).append("\n");
        }

        sb.append("\nNotificaciones multimedia:\n").append(MediaListenerService.describeNotifications());
        sb.append("\nAvisos recibidos de apps com.tw.* (últimos):\n");
        if (sniffLog.isEmpty()) sb.append("  (ninguno aún)\n");
        for (int i = Math.max(0, sniffLog.size() - 12); i < sniffLog.size(); i++) {
            sb.append("  ").append(sniffLog.get(i)).append("\n");
        }
        sb.append("\nAcciones que declaran las apps del radio:\n");
        for (Map.Entry<String, Set<String>> e : twActions.entrySet()) {
            if (!(e.getKey().equals("com.tw.bt") || e.getKey().equals("com.tw.media"))) continue;
            sb.append("  ").append(e.getKey()).append(":\n");
            int n = 0;
            for (String a : e.getValue()) {
                if (n++ >= 40) {
                    sb.append("   …y más\n");
                    break;
                }
                sb.append("   ").append(a).append("\n");
            }
        }
        sb.append("\nApps relacionadas con música/Bluetooth/cámara:\n");
        loadApps();
        for (AppEntry e : apps) {
            String l = e.label.toLowerCase(Locale.ROOT);
            String p = e.component.getPackageName().toLowerCase(Locale.ROOT);
            if (l.contains("music") || l.contains("músic") || l.contains("musica") || l.contains("blue")
                    || l.contains("bt") || l.contains("cam") || l.contains("cám") || l.contains("avm")
                    || p.contains("music") || p.contains("bluetooth") || p.contains("btphone")) {
                sb.append("  • ").append(e.label).append("  (").append(e.component.getPackageName()).append(")\n");
            }
        }
        return sb.toString();
    }
}
