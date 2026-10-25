package com.junior.corsalauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.app.AppOpsManager;
import android.os.Process;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Se queda vivo en segundo plano para:
 *  1) recibir los avisos de música aunque la pantalla principal esté cerrada,
 *  2) devolver al usuario a ESTE launcher cuando la radio despierta y la app de
 *     fábrica intenta quedarse con la pantalla de inicio.
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "corsa_keepalive";
    private final Handler h = new Handler(Looper.getMainLooper());
    private long screenOffAt = 0;
    private boolean receiversOn = false;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    /** ¿Está concedido el permiso "Acceso de uso" que permite saber qué app está al frente? */
    public static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MediaListenerService.handleAvrcpIntent(intent);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (a == null) return;
            long now = SystemClock.elapsedRealtime();
            if (Intent.ACTION_SCREEN_OFF.equals(a) || a.endsWith("ACC_OFF")
                    || a.endsWith("DO_SLEEP") || a.endsWith("DO_SHUTDOWN")) {
                screenOffAt = now;
            } else if (a.endsWith("ACC_ON") || a.endsWith("DO_WAKEUP")) {
                // la radio avisa que el carro encendió: adelantarse al launcher de fábrica
                goHomeSoon();
                screenOffAt = 0;
            } else {
                // despertó (pantalla, desbloqueo, ACC/energía): si estuvo apagada un rato, volver al launcher
                boolean longSleep = screenOffAt != 0 && now - screenOffAt > 15000;
                if (longSleep || Intent.ACTION_BOOT_COMPLETED.equals(a)) {
                    goHomeSoon();
                }
                if (Intent.ACTION_USER_PRESENT.equals(a) || Intent.ACTION_SCREEN_ON.equals(a)) screenOffAt = 0;
            }
        }
    };

    private final Runnable homeNow = new Runnable() {
        @Override
        public void run() {
            bringHome();
        }
    };

    private void goHomeSoon() {
        h.removeCallbacks(homeNow);
        long[] delays = {0, 400, 1200, 3000, 6000, 10000};
        for (long d : delays) h.postDelayed(homeNow, d);
    }

    private void bringHome() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ---- vigilante: si la pantalla de inicio de fábrica queda al frente, volver ----
    private Set<String> factoryHomes = new HashSet<>();
    private long lastCheck = 0;
    private String lastTop = "";

    private void loadFactoryHomes() {
        factoryHomes.clear();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
                factoryHomes.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name);
            }
        } catch (Exception ignored) {
        }
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                checkForeground();
            } catch (Exception ignored) {
            }
            h.postDelayed(this, 2000);
        }
    };

    private void checkForeground() {
        if (!hasUsageAccess(this)) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;
        long end = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(end - 15000, end);
        UsageEvents.Event e = new UsageEvents.Event();
        String top = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                top = e.getPackageName() + "/" + e.getClassName();
            }
        }
        if (top == null) return;
        lastTop = top;
        if (factoryHomes.isEmpty() || SystemClock.elapsedRealtime() - lastCheck > 60000) {
            loadFactoryHomes();
            lastCheck = SystemClock.elapsedRealtime();
        }
        if (factoryHomes.contains(top)) bringHome();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotification();
        registerAll();
        loadFactoryHomes();
        h.postDelayed(watchdog, 3000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotification();
        return START_STICKY;
    }

    private void createNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Corsa Launcher", NotificationManager.IMPORTANCE_MIN);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            Notification n = b.setSmallIcon(R.drawable.ic_note)
                    .setContentTitle("Corsa Launcher activo")
                    .setOngoing(true)
                    .build();
            startForeground(1, n);
        } catch (Exception ignored) {
        }
    }

    private void registerAll() {
        if (receiversOn) return;
        receiversOn = true;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            f.addAction("com.unisound.intent.action.ACC_ON");
            f.addAction("com.unisound.intent.action.ACC_OFF");
            f.addAction("com.unisound.intent.action.DO_WAKEUP");
            f.addAction("com.unisound.intent.action.DO_SLEEP");
            f.addAction("com.unisound.intent.action.DO_SHUTDOWN");
            registerReceiver(screenReceiver, f);
        } catch (Exception ignored) {
        }
        try {
            IntentFilter m = new IntentFilter();
            for (String a : MediaListenerService.AVRCP_ACTIONS) m.addAction(a);
            registerReceiver(mediaReceiver, m);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(mediaReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static String lastTop(KeepAliveService s) {
        return s == null ? "" : s.lastTop;
    }
}
