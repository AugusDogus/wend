package dev.augie.wend;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone connected to the head unit over Bluetooth, through the unit's Bluetooth service
 * ({@code android.microntek.mtcser/.BlueToothService}), which drives its Bluetooth module and
 * which its dialer uses: called by its {@code BTServiceInf} transaction codes, one call at a time,
 * since the service writes each to the module's serial port unguarded. See
 * {@code build/px6/rom/notes/bluetooth.md}.
 */
final class HeadUnitPhone {
    /** {@code getBTState}'s states. */
    static final int DISCONNECTED = 0, IDLE = 1, DIALING = 2, RINGING = 3, IN_CALL = 5;

    interface Source {
        /** @return the call state ({@link #IDLE}, ...), or null when the unit has no Bluetooth service */
        Integer state() throws RemoteException;
        /** @return the current call's number */
        String number() throws RemoteException;
        /** @return the connected phone's Bluetooth name, "" when none */
        String phoneName() throws RemoteException;
        /** @return the connected phone's address, 0 when none */
        long phoneAddress() throws RemoteException;
        /** @return the phone book as the module synced it: "name^number^Local" */
        List<String> phoneBook() throws RemoteException;
        /** @return the call log, oldest first: "MAC^number^date^time^locale^type" */
        List<String> calls() throws RemoteException;
        void dial(String number) throws RemoteException;
        void answer() throws RemoteException;
        void hangUp() throws RemoteException;
    }

    private static final int INIT = 1, STATE = 2, NUMBER = 6, ADDRESS = 11, NAME = 12, ANSWER = 19, HANG_UP = 20,
            CALLS = 45, PHONE_BOOK = 46, DIAL = 54;

    private HeadUnitPhone() { }

    /** @return the unit's Bluetooth service, bound on first use */
    static Source of(Context context) {
        BoundService service = new BoundService(context, new Intent().setComponent(new ComponentName("android.microntek.mtcser",
                "android.microntek.mtcser.BlueToothService")), "android.microntek.mtcser.BTServiceInf");
        return new Source() {
            private boolean initialized;

            private synchronized <T> T call(int code, BoundService.Arguments arguments, BoundService.Answer<T> answer) throws RemoteException {
                if (!initialized && service.binder() != null) {
                    // Every client of the service calls init first.
                    service.call(INIT, BoundService.NONE, reply -> null);
                    initialized = true;
                }
                return service.call(code, arguments, answer);
            }

            @Override public Integer state() throws RemoteException {
                Byte state = call(STATE, BoundService.NONE, Parcel::readByte);
                return state == null ? null : (int) state;
            }
            @Override public String number() throws RemoteException { return call(NUMBER, BoundService.NONE, Parcel::readString); }
            @Override public String phoneName() throws RemoteException { return call(NAME, BoundService.NONE, Parcel::readString); }
            @Override public long phoneAddress() throws RemoteException {
                Long address = call(ADDRESS, BoundService.NONE, Parcel::readLong);
                return address == null ? 0 : address;
            }
            @Override public List<String> phoneBook() throws RemoteException { return list(call(PHONE_BOOK, BoundService.NONE, Parcel::createStringArrayList)); }
            @Override public List<String> calls() throws RemoteException { return list(call(CALLS, BoundService.NONE, Parcel::createStringArrayList)); }
            @Override public void dial(String number) throws RemoteException { call(DIAL, data -> data.writeString(number), reply -> null); }
            @Override public void answer() throws RemoteException { call(ANSWER, BoundService.NONE, reply -> null); }
            @Override public void hangUp() throws RemoteException { call(HANG_UP, BoundService.NONE, reply -> null); }
        };
    }

    private static List<String> list(List<String> list) { return list == null ? new ArrayList<>() : list; }

    /** @return {@code number}'s digits, as the phone book and call log keep them in different forms */
    static String digits(String number) { return number == null ? "" : number.replaceAll("[^0-9]", ""); }

    /**
     * @return the name {@code phoneBook} gives {@code number}, or null: the same digits, or the same
     *     last digits when one has a country code the other lacks, as the unit's dialer matches them
     */
    static String nameOf(String number, List<String> phoneBook) {
        String wanted = digits(number);
        if (wanted.length() < 3) return null;
        for (String entry : phoneBook) {
            String[] fields = entry.split("\\^");
            if (fields.length < 2) continue;
            String known = digits(fields[1]);
            if (known.isEmpty()) continue;
            boolean same = known.equals(wanted)
                    || Math.min(known.length(), wanted.length()) >= 7 && (known.endsWith(wanted) || wanted.endsWith(known));
            if (same) return fields[0].isEmpty() ? null : fields[0];
        }
        return null;
    }
}
