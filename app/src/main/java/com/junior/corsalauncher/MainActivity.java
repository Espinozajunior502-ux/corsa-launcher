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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CorsaLauncher";
    private static final int REQ_PERMISSIONS = 100;
    private static final String USER_NAME = "Junior";
    private static final long WEATHER_REFRESH_MS = 30 * 60 * 1000L;

    private TextView clockText, dateText, greetingText, weatherText, btText, speedText;
    private TextView songTitle, songArtist, btnPlayPause, btnPrev, btnNext;
    private ImageView albumArt;
    private View drawerOverlay;
    private GridView appGrid;

    private LocationManager locationManager;
    private boolean gpsStarted = false;
    private long lastWeatherAt = 0;
    private boolean weatherLoading = false;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothProfile a2dpProxy;
    private String connectedBtName = null;

    private boolean notifAccess = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            Calendar c = Calendar.getInstance();
            clockText.setText(String.format(Locale.getDefault(), "%02d:%02d",
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
            dateText.setText(new SimpleDateFormat("EEE, dd MMM", new Locale("es", "ES")).format(c.getTime()));
            int hour = c.get(Calendar.HOUR_OF_DAY);
            String saludo = hour < 12 ? "Buenos días" : (hour < 19 ? "Buenas tardes" : "Buenas noches");
            greetingText.setText(saludo + ", " + USER_NAME);
            mainHandler.postDelayed(this, 10000);
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                connectedBtName = deviceName(d);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (connectedBtName != null && connectedBtName.equals(deviceName(d))) {
                    connectedBtName = null;
                }
            }
            updateBluetoothStatus();
        }
    };

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        clockText = findViewById(R.id.clockText);
        dateText = findViewById(R.id.dateText);
        greetingText = findViewById(R.id.greetingText);
        weatherText = findViewById(R.id.weatherText);
        btText = findViewById(R.id.btText);
        speedText = findViewById(R.id.speedText);
        songTitle = findViewById(R.id.songTitle);
        songArtist = findViewById(R.id.songArtist);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        albumArt = findViewById(R.id.albumArt);
        drawerOverlay = findViewById(R.id.drawerOverlay);
        appGrid = findViewById(R.id.appGrid);

        setupDock();
        setupDrawer();
        setupMusic();
        setupBluetooth();
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(clockTick);
        clockTick.run();
        refreshNotificationAccess();
        setupGps();
        updateBluetoothStatus();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(clockTick);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Al pulsar HOME estando dentro, volvemos a la pantalla principal.
        closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (drawerOverlay.getVisibility() == View.VISIBLE) {
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
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
        if (bluetoothAdapter != null && a2dpProxy != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
        }
    }

    // ---------------------------------------------------------------- Permisos

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
        setupBluetoothProxy();
        updateBluetoothStatus();
    }

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- Música

    private void setupMusic() {
        btnPlayPause.setOnClickListener(v -> MediaListenerService.togglePlayPause());
        btnPrev.setOnClickListener(v -> MediaListenerService.skipToPrevious());
        btnNext.setOnClickListener(v -> MediaListenerService.skipToNext());

        songTitle.setOnClickListener(v -> {
            if (!notifAccess) openNotificationAccessSettings();
        });

        MediaListenerService.nowPlaying.observe(this, info -> {
            if (info == null) {
                showIdleMusic();
                return;
            }
            songTitle.setText(info.title.isEmpty() ? "Reproduciendo" : info.title);
            songArtist.setText(info.artist);
            btnPlayPause.setText(info.isPlaying ? "⏸" : "▶");
            albumArt.setImageBitmap(info.albumArt);
        });
    }

    private void showIdleMusic() {
        songTitle.setText(notifAccess ? "Sin reproducción" : "Toca aquí para activar la música");
        songArtist.setText("");
        btnPlayPause.setText("▶");
        albumArt.setImageBitmap(null);
    }

    private void refreshNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean now = enabled != null && enabled.contains(getPackageName());
        if (now && !notifAccess) {
            // Recién concedido: pedir al sistema que conecte el servicio ya.
            try {
                NotificationListenerService.requestRebind(
                        new ComponentName(this, MediaListenerService.class));
            } catch (Exception ignored) {
            }
        }
        notifAccess = now;
        if (!notifAccess) showIdleMusic();
    }

    private void openNotificationAccessSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Este radio no tiene esa pantalla de ajustes", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- Dock y apps

    private void setupDock() {
        findViewById(R.id.dockApps).setOnClickListener(v -> openDrawer());
        findViewById(R.id.dockMaps).setOnClickListener(v ->
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))));
        findViewById(R.id.dockPhone).setOnClickListener(v ->
                launch(new Intent(Intent.ACTION_DIAL)));
        findViewById(R.id.dockSettings).setOnClickListener(v ->
                launch(new Intent(Settings.ACTION_SETTINGS)));
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
            Intent i = Intent.makeMainActivity(e.component);
            closeDrawer();
            launch(i);
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

    // ---------------------------------------------------------------- Bluetooth

    private void setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(btReceiver, filter);
        setupBluetoothProxy();
        updateBluetoothStatus();
    }

    /** Detecta el dispositivo ya conectado al abrir la app (el receiver solo ve cambios). */
    private void setupBluetoothProxy() {
        if (bluetoothAdapter == null || a2dpProxy != null) return;
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) return;
        try {
            bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    a2dpProxy = proxy;
                    try {
                        List<BluetoothDevice> connected = proxy.getConnectedDevices();
                        if (connected != null && !connected.isEmpty()) {
                            connectedBtName = deviceName(connected.get(0));
                        }
                    } catch (SecurityException ignored) {
                    }
                    updateBluetoothStatus();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    a2dpProxy = null;
                }
            }, BluetoothProfile.A2DP);
        } catch (SecurityException e) {
            Log.w(TAG, "Sin permiso Bluetooth", e);
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

    private void updateBluetoothStatus() {
        if (bluetoothAdapter == null) {
            btText.setText("Sin Bluetooth");
        } else if (!bluetoothAdapter.isEnabled()) {
            btText.setText("Bluetooth apagado");
        } else if (connectedBtName != null) {
            btText.setText("🎧 " + connectedBtName);
        } else {
            btText.setText("Bluetooth sin conexión");
        }
    }

    // ---------------------------------------------------------------- GPS y clima

    private void setupGps() {
        if (gpsStarted || !has(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, locationListener);
            gpsStarted = true;
            // Para tener clima rápido sin esperar el primer fix del GPS
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
        speedText.setText(String.format(Locale.getDefault(), "%.0f km/h", kmh));
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
                String text = weatherEmoji(cur.optInt("weather_code", 0)) + " " + temp + "°C";
                lastWeatherAt = System.currentTimeMillis();
                mainHandler.post(() -> {
                    weatherText.setText(text);
                    weatherText.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                Log.w(TAG, "Clima no disponible", e);
            } finally {
                if (conn != null) conn.disconnect();
                weatherLoading = false;
            }
        });
    }

    private static String weatherEmoji(int code) {
        if (code == 0) return "☀️";
        if (code <= 3) return "⛅";
        if (code <= 48) return "🌫️";
        if (code <= 67) return "🌧️";
        if (code <= 77) return "❄️";
        if (code <= 82) return "🌦️";
        return "⛈️";
    }
}
