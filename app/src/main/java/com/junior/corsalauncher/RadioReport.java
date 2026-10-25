package com.junior.corsalauncher;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Informe completo del equipo (pantalla/radio): hardware, pantalla, códecs de video,
 * USB, Wi-Fi, Bluetooth, apps de fábrica, etc. Sirve para saber qué se puede hacer
 * (por ejemplo CarPlay/Android Auto). No incluye números de serie, IMEI ni direcciones MAC.
 */
public class RadioReport {

    public String summary = "";
    public String full = "";

    public static RadioReport build(Context c) {
        RadioReport r = new RadioReport();
        StringBuilder sum = new StringBuilder();
        StringBuilder all = new StringBuilder();
        PackageManager pm = c.getPackageManager();

        // ---------- equipo ----------
        sec(all, "EQUIPO");
        line(all, "Marca/Modelo", Build.MANUFACTURER + " / " + Build.MODEL);
        line(all, "Producto/Dispositivo", Build.PRODUCT + " / " + Build.DEVICE);
        line(all, "Placa/Hardware", Build.BOARD + " / " + Build.HARDWARE);
        line(all, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line(all, "Parche de seguridad", Build.VERSION.SECURITY_PATCH);
        line(all, "Compilación", Build.DISPLAY);
        line(all, "Huella", Build.FINGERPRINT);
        line(all, "ABIs", join(Build.SUPPORTED_ABIS));
        sum.append("Equipo: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" | ").append(Build.HARDWARE).append(" | Android ").append(Build.VERSION.RELEASE)
                .append("\n");

        // CPU y memoria
        String cpu = grep("/proc/cpuinfo", new String[]{"Hardware", "model name", "Processor"}, 3);
        int cores = Runtime.getRuntime().availableProcessors();
        line(all, "CPU", cpu + " | núcleos: " + cores);
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            line(all, "RAM total", (mi.totalMem / 1048576) + " MB");
            sum.append("RAM: ").append(mi.totalMem / 1048576).append(" MB | núcleos: ").append(cores).append("\n");
        } catch (Exception ignored) {
        }
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            line(all, "Almacenamiento (data)", (st.getTotalBytes() / 1048576) + " MB total, " + (st.getAvailableBytes() / 1048576) + " MB libres");
        } catch (Exception ignored) {
        }

        // ---------- pantalla ----------
        sec(all, "PANTALLA");
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            line(all, "Resolución real", m.widthPixels + " x " + m.heightPixels + " px");
            line(all, "Densidad", m.densityDpi + " dpi (x" + m.density + ")");
            line(all, "Frecuencia", d.getRefreshRate() + " Hz");
            line(all, "Rotación", String.valueOf(d.getRotation()));
            sum.append("Pantalla: ").append(m.widthPixels).append("x").append(m.heightPixels)
                    .append(" @").append(m.densityDpi).append("dpi ").append((int) d.getRefreshRate()).append("Hz\n");
        } catch (Exception e) {
            line(all, "Error", String.valueOf(e));
        }

        // ---------- códecs de video ----------
        sec(all, "CÓDECS DE VIDEO/AUDIO (CarPlay usa H.264)");
        boolean h264 = false;
        String h264max = "";
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                String[] types = ci.getSupportedTypes();
                StringBuilder t = new StringBuilder();
                for (String ty : types) {
                    t.append(ty).append(' ');
                    if (!ci.isEncoder() && ty.equalsIgnoreCase("video/avc")) {
                        try {
                            MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(ty).getVideoCapabilities();
                            if (vc != null) {
                                h264 = true;
                                h264max = vc.getSupportedWidths().getUpper() + "x" + vc.getSupportedHeights().getUpper()
                                        + ", máx " + vc.getBitrateRange().getUpper() / 1000000 + " Mbps";
                                t.append("[").append(h264max).append("] ");
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                all.append("  ").append(ci.isEncoder() ? "ENC " : "DEC ").append(ci.getName()).append(": ").append(t).append("\n");
            }
        } catch (Throwable t) {
            line(all, "Error", String.valueOf(t));
        }
        sum.append("Decodificador H.264: ").append(h264 ? "SÍ (" + h264max + ")" : "NO").append("\n");

        // ---------- USB ----------
        sec(all, "USB");
        boolean usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        boolean usbAcc = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY);
        line(all, "Modo anfitrión (USB host)", usbHost ? "SÍ" : "NO");
        line(all, "Modo accesorio", usbAcc ? "SÍ" : "NO");
        int usbCount = 0;
        try {
            UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
            if (um != null) {
                for (UsbDevice dv : um.getDeviceList().values()) {
                    usbCount++;
                    all.append(String.format(Locale.US, "  dispositivo: VID=%04X PID=%04X clase=%d %s %s%n",
                            dv.getVendorId(), dv.getProductId(), dv.getDeviceClass(),
                            dv.getManufacturerName() == null ? "" : dv.getManufacturerName(),
                            dv.getProductName() == null ? "" : dv.getProductName()));
                }
            }
        } catch (Throwable t) {
            line(all, "Error USB", String.valueOf(t));
        }
        line(all, "Dispositivos USB conectados", String.valueOf(usbCount));
        all.append("  /sys/bus/usb/devices:\n");
        try {
            File[] devs = new File("/sys/bus/usb/devices").listFiles();
            if (devs != null) {
                for (File dv : devs) {
                    String vid = readFile(new File(dv, "idVendor")), pid = readFile(new File(dv, "idProduct"));
                    String man = readFile(new File(dv, "manufacturer")), prod = readFile(new File(dv, "product"));
                    all.append("    ").append(dv.getName());
                    if (!vid.isEmpty()) all.append("  ").append(vid).append(":").append(pid).append(" ").append(man).append(" ").append(prod);
                    all.append("\n");
                }
            } else {
                all.append("    (no legible)\n");
            }
        } catch (Throwable t) {
            all.append("    error: ").append(t).append("\n");
        }
        sum.append("USB host: ").append(usbHost ? "SÍ" : "NO").append(" | dispositivos USB vistos: ").append(usbCount).append("\n");

        // ---------- Wi-Fi ----------
        sec(all, "WI-FI");
        boolean w5 = false, p2p = pm.hasSystemFeature("android.hardware.wifi.direct");
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            w5 = wm.is5GHzBandSupported();
            line(all, "Wi-Fi", pm.hasSystemFeature(PackageManager.FEATURE_WIFI) ? "SÍ" : "NO");
            line(all, "Banda de 5 GHz", w5 ? "SÍ" : "NO");
            line(all, "Wi-Fi Direct (P2P)", p2p ? "SÍ" : "NO");
            line(all, "Estado Wi-Fi", wm.isWifiEnabled() ? "encendido" : "apagado");
        } catch (Throwable t) {
            line(all, "Error", String.valueOf(t));
        }
        sum.append("Wi-Fi 5 GHz: ").append(w5 ? "SÍ" : "NO").append(" | Wi-Fi Direct: ").append(p2p ? "SÍ" : "NO").append("\n");

        // ---------- Bluetooth ----------
        sec(all, "BLUETOOTH");
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            line(all, "Bluetooth", ad == null ? "no existe" : (ad.isEnabled() ? "encendido" : "apagado"));
            if (ad != null) line(all, "Nombre", String.valueOf(ad.getName()));
            line(all, "BLE", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ? "SÍ" : "NO");
        } catch (Throwable t) {
            line(all, "Error", String.valueOf(t));
        }

        // ---------- audio ----------
        sec(all, "AUDIO");
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            line(all, "Frecuencia de salida", am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            line(all, "Tamaño de buffer", am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            line(all, "Volumen música (Android)", am.getStreamVolume(AudioManager.STREAM_MUSIC) + " / " + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            for (AudioDeviceInfo di : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                all.append("  dispositivo de audio: tipo ").append(di.getType()).append(di.isSink() ? " salida" : " entrada").append("\n");
            }
        } catch (Throwable t) {
            line(all, "Error", String.valueOf(t));
        }
        line(all, "Micrófono", pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) ? "SÍ" : "NO");

        // ---------- sensores y funciones ----------
        sec(all, "SENSORES");
        try {
            SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) all.append("  ").append(s.getName()).append("\n");
        } catch (Throwable ignored) {
        }
        line(all, "GPS", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) ? "SÍ" : "NO");
        sec(all, "FUNCIONES DEL SISTEMA");
        try {
            List<String> feats = new ArrayList<>();
            for (FeatureInfo fi : pm.getSystemAvailableFeatures()) if (fi.name != null) feats.add(fi.name);
            Collections.sort(feats);
            for (String f : feats) all.append("  ").append(f).append("\n");
        } catch (Throwable ignored) {
        }

        // ---------- apps ----------
        sec(all, "APPS DE SISTEMA / RELACIONADAS (CarPlay, Android Auto, espejo)");
        String[] kw = {"carplay", "zlink", "carlink", "autokit", "androidauto", "projection", "mirror", "mhl", "airplay",
                "mfi", "carbit", "autolink", "easyconn", "hicar", "link", "cast", "dongle", "usbmux"};
        List<String> related = new ArrayList<>();
        List<String> sys = new ArrayList<>();
        try {
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                ApplicationInfo ai = pi.applicationInfo;
                boolean isSys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                String label = String.valueOf(ai.loadLabel(pm));
                String entry = pi.packageName + " (" + label + ") v" + pi.versionName + (isSys ? "" : " [usuario]");
                String low = (pi.packageName + " " + label).toLowerCase(Locale.ROOT);
                for (String k : kw) if (low.contains(k)) {
                    related.add(entry);
                    break;
                }
                if (isSys || pi.packageName.startsWith("com.tw")) sys.add(entry);
            }
        } catch (Throwable t) {
            all.append("error: ").append(t).append("\n");
        }
        Collections.sort(sys);
        Collections.sort(related);
        all.append(" Posiblemente relacionadas con CarPlay/Android Auto:\n");
        if (related.isEmpty()) all.append("   (ninguna encontrada)\n");
        for (String s : related) all.append("   ").append(s).append("\n");
        all.append(" Todas las de sistema:\n");
        for (String s : sys) all.append("   ").append(s).append("\n");
        sum.append("Apps de CarPlay/espejo: ").append(related.isEmpty() ? "ninguna" : related.size() + " → " + related.get(0)).append("\n");
        sum.append("Apps de sistema: ").append(sys.size()).append("\n");

        // ---------- propiedades ----------
        sec(all, "PROPIEDADES DEL SISTEMA (sin serie/MAC/IMEI)");
        try {
            Process p = Runtime.getRuntime().exec("getprop");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String ln;
                List<String> props = new ArrayList<>();
                while ((ln = br.readLine()) != null) {
                    String l = ln.toLowerCase(Locale.ROOT);
                    if (l.contains("serial") || l.contains("mac") || l.contains("imei") || l.contains("address")
                            || l.contains("meid") || l.contains("imsi") || l.contains("wifi.interface")) continue;
                    props.add(ln);
                }
                Collections.sort(props);
                for (String s : props) all.append("  ").append(s).append("\n");
            }
        } catch (Throwable t) {
            all.append("  getprop no disponible: ").append(t).append("\n");
        }

        r.summary = sum.toString();
        r.full = all.toString();
        return r;
    }

    private static void sec(StringBuilder sb, String t) {
        sb.append("\n==== ").append(t).append(" ====\n");
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append("  ").append(k).append(": ").append(v).append("\n");
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        if (a != null) for (String s : a) sb.append(s).append(' ');
        return sb.toString().trim();
    }

    private static String readFile(File f) {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String s = br.readLine();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String grep(String path, String[] keys, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String ln;
            while ((ln = br.readLine()) != null && n < max) {
                for (String k : keys) {
                    if (ln.startsWith(k)) {
                        sb.append(ln.replaceAll("\\s+", " ")).append("; ");
                        n++;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
