package dev.augie.wend;

import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * The tire pressures the head unit's TPMS app ({@code com.microntek.tpms}) reads from a TPMS box,
 * through its exported service {@code ITpmsService} by that interface's transaction codes. See
 * {@code build/px6/rom/notes/car.md}.
 */
final class HeadUnitTires {
    /** One tire: pressure and temperature as the TPMS app words them, and its alarm (0 for none). */
    static final class Tire {
        final String pressure, temperature;
        final int alarm;

        Tire(String pressure, String temperature, int alarm) {
            this.pressure = pressure;
            this.temperature = temperature;
            this.alarm = alarm;
        }
    }

    /** The four tires, front left, front right, rear left, rear right, and their units. */
    static final class Reading {
        final Tire[] tires;
        /** 0 kPa, 1 bar, 2 psi; 0 °C, 1 °F. */
        final int pressureUnit, temperatureUnit;

        Reading(Tire[] tires, int pressureUnit, int temperatureUnit) {
            this.tires = tires;
            this.pressureUnit = pressureUnit;
            this.temperatureUnit = temperatureUnit;
        }
    }

    interface Source {
        /** @return the tires now, or null when the unit has no TPMS service */
        Reading read() throws RemoteException;
    }

    /** {@code ITpmsService}'s transaction codes for each tire: pressure, temperature, alarm. */
    private static final int[][] CODES = {{5, 6, 9}, {10, 11, 14}, {15, 16, 19}, {20, 21, 24}};
    private static final int PRESSURE_UNIT = 37, TEMPERATURE_UNIT = 39;

    private HeadUnitTires() { }

    /** @return the TPMS app's service, bound on first use */
    static Source of(Context context) {
        BoundService service = new BoundService(context, new Intent("com.microntek.tpms.MyService").setPackage("com.microntek.tpms"),
                "android.microntek.tpms.aidl.ITpmsService");
        return () -> {
            Tire[] tires = new Tire[4];
            for (int i = 0; i < 4; i++) {
                String pressure = service.call(CODES[i][0], BoundService.NONE, Parcel::readString);
                if (pressure == null && service.binder() == null) return null;
                tires[i] = new Tire(pressure, service.call(CODES[i][1], BoundService.NONE, Parcel::readString),
                        service.call(CODES[i][2], BoundService.NONE, Parcel::readInt));
            }
            return new Reading(tires, service.call(PRESSURE_UNIT, BoundService.NONE, Parcel::readInt),
                    service.call(TEMPERATURE_UNIT, BoundService.NONE, Parcel::readInt));
        };
    }
}
