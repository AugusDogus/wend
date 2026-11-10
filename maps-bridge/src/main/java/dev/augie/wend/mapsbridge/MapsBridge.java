package dev.augie.wend.mapsbridge;

import android.app.Application;
import android.app.Instrumentation;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed entry point, for Google Maps' main process only. Once Maps' application exists it starts
 * {@link TripReporter} there. It changes nothing Maps does.
 */
public final class MapsBridge implements IXposedHookLoadPackage {
    static final String TAG = "wend-maps-bridge";
    private static final String MAPS = "com.google.android.apps.maps";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!MAPS.equals(lpparam.packageName) || !MAPS.equals(lpparam.processName)) return;
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try { TripReporter.start((Application) param.args[0]); }
                            catch (Throwable error) {
                                XposedBridge.log(TAG + ": not started; Wend resumes trips as without it: " + error);
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": not installed; Wend resumes trips as without it: " + error);
        }
    }
}
