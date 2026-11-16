package dev.augie.wend.mapsbridge;

import android.app.Application;
import android.app.Instrumentation;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed entry point, for Google Maps' main process only. It finds Maps' navigation
 * ({@link MapsNavigation}) as Maps loads, and once Maps' application exists it starts
 * {@link TripReporter} there. It changes nothing Maps does unless Wend asks it to add a stop.
 */
public final class MapsBridge implements IXposedHookLoadPackage {
    static final String TAG = "wend-maps-bridge";
    private static final String MAPS = "com.google.android.apps.maps";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!MAPS.equals(lpparam.packageName) || !MAPS.equals(lpparam.processName)) return;
        MapsNavigation found = null;
        String notFound = null;
        try {
            found = MapsNavigation.find(lpparam.classLoader, lpparam.appInfo.sourceDir, lpparam.appInfo.dataDir);
        } catch (Throwable error) {
            notFound = String.valueOf(error);
            XposedBridge.log(TAG + ": Maps' navigation not found; Wend cannot add stops, all else works: " + error);
        }
        MapsNavigation navigation = found;
        String why = notFound;
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try { TripReporter.start((Application) param.args[0], navigation, why); }
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
