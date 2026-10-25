package com.junior.corsalauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

/**
 * Habla con el servicio de la radio (com.tw.service.xt) igual que lo hace la app de fábrica:
 * volumen real del equipo, y avisos del nivel de volumen.
 * Los códigos salen de la interfaz ITWCommandAidl de la propia radio.
 */
public class TwCommand {

    public interface Listener {
        void onVolume(int level);   // nivel actual del volumen de la radio
        void onConnected(boolean ok);
    }

    private static final String SERVICE_PKG = "com.tw.service.xt";
    private static final String SERVICE_CLS = "com.tw.service.xt.CommandService";
    private static final String SERVICE_ACTION = "com.tw.service.xt.CommandService.Bind";
    private static final String DESC = "com.tw.service.xt.aidl.ITWCommandAidl";
    private static final String CB_DESC = "com.tw.service.xt.aidl.ITWCommandCallbackAidl";

    private static final int T_REGISTER_CALLBACK = 1;
    private static final int T_VOLUME_UP = 40;
    private static final int T_VOLUME_DOWN = 41;
    private static final int T_VOLUME_MUTE = 42;
    private static final int T_VOLUME_VALUE = 45;
    private static final int T_GET_VOLUME = 46;
    private static final int T_GET_VOLUME_STATUS = 47;

    private final Context ctx;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private IBinder service;
    private boolean binding = false;
    public volatile int lastSystemVolume = -1;
    public volatile int lastVolumeStatus = -1;

    public TwCommand(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    public boolean isConnected() {
        return service != null && service.isBinderAlive();
    }

    // Callback que la radio llama cuando cambia el volumen (1 = onSystemVolume, 2 = onVolumeStatus)
    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == 1 || code == 2) {
                data.enforceInterface(CB_DESC);
                final int v = data.readInt();
                if (code == 1) lastSystemVolume = v;
                else lastVolumeStatus = v;
                if (reply != null) reply.writeNoException();
                if (code == 1) ui.post(() -> listener.onVolume(v));
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = b;
            binding = false;
            try {
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                d.writeInterfaceToken(DESC);
                d.writeStrongBinder(callback);
                b.transact(T_REGISTER_CALLBACK, d, r, 0);
                r.recycle();
                d.recycle();
            } catch (Exception ignored) {
            }
            call(T_GET_VOLUME, null);
            call(T_GET_VOLUME_STATUS, null);
            listener.onConnected(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
            listener.onConnected(false);
        }
    };

    public void connect() {
        if (isConnected() || binding) return;
        try {
            Intent i = new Intent(SERVICE_ACTION);
            i.setComponent(new ComponentName(SERVICE_PKG, SERVICE_CLS));
            binding = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
            if (!binding) listener.onConnected(false);
        } catch (Exception e) {
            binding = false;
            listener.onConnected(false);
        }
    }

    public void disconnect() {
        try {
            ctx.unbindService(conn);
        } catch (Exception ignored) {
        }
        service = null;
        binding = false;
    }

    private boolean call(int code, Integer arg) {
        IBinder s = service;
        if (s == null) return false;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            if (arg != null) d.writeInt(arg);
            s.transact(code, d, r, 0);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            r.recycle();
            d.recycle();
        }
    }

    public boolean volumeUp() {
        return call(T_VOLUME_UP, null);
    }

    public boolean volumeDown() {
        return call(T_VOLUME_DOWN, null);
    }

    public boolean volumeMute() {
        return call(T_VOLUME_MUTE, null);
    }

    public boolean volumeValue(int v) {
        return call(T_VOLUME_VALUE, v);
    }

    public boolean getVolume() {
        return call(T_GET_VOLUME, null);
    }
}
