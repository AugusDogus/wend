package dev.augie.wend;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One of the head unit's own services, bound once and kept, called by its AIDL transaction codes
 * as the unit's apps call it (Wend has no copy of their interfaces). Off the main thread, which
 * delivers the binding.
 */
final class BoundService {
    /** How long the unit's service may take to bind. */
    private static final long BIND_WAIT_MS = 3_000;
    private final Context context;
    private final Intent intent;
    private final String descriptor;
    private volatile IBinder binder;
    private boolean bound;
    private CountDownLatch connected = new CountDownLatch(1);

    BoundService(Context context, Intent intent, String descriptor) {
        this.context = context;
        this.intent = intent;
        this.descriptor = descriptor;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            connected.countDown();
        }

        @Override public void onServiceDisconnected(ComponentName name) { binder = null; }
    };

    /** @return the service's binder, or null if it is not on this unit or did not bind in time */
    IBinder binder() {
        IBinder now = binder;
        if (now != null && now.isBinderAlive()) return now;
        synchronized (this) {
            if (!bound) {
                connected = new CountDownLatch(1);
                try {
                    bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                } catch (SecurityException refused) {
                    return null;
                }
                if (!bound) return null;
            }
        }
        try {
            connected.await(BIND_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return binder;
    }

    /** What one call writes after the interface token. */
    interface Arguments { void write(Parcel data); }

    /** What one call reads from its reply, after the exception. */
    interface Answer<T> { T read(Parcel reply); }

    /** @return {@code code}'s answer, as the service's AIDL stub replies; null if the service is not here */
    <T> T call(int code, Arguments arguments, Answer<T> answer) throws RemoteException {
        IBinder service = binder();
        if (service == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            arguments.write(data);
            service.transact(code, data, reply, 0);
            reply.readException();
            return answer.read(reply);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static final Arguments NONE = data -> { };
}
