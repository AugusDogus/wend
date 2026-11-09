package dev.augie.wend.piphook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed entry point. In system_server it starts a panel app's picture-in-picture from the panel
 * ({@link PanelPipHook}); in Google Maps and YouTube it lets Wend ask the app for its own PiP
 * ({@link PanelAppHook}).
 */
public final class PipHook implements IXposedHookLoadPackage {
    static final String TAG = "wend-pip-hook";
    private static final String SYSTEM = "android";
    private static final String MAPS = "com.google.android.apps.maps";
    private static final String YOUTUBE = "com.google.android.youtube";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        try {
            if (SYSTEM.equals(lpparam.packageName) && SYSTEM.equals(lpparam.processName)) {
                PanelPipHook.install(lpparam.classLoader);
                CardGrow.install(lpparam.classLoader);
                PanelReturn.install(lpparam.classLoader);
                CardReturn.install(lpparam.classLoader);
                PanelExpand.install(lpparam.classLoader);
                PipEdges.install(lpparam.classLoader);
            } else if (MAPS.equals(lpparam.packageName) && MAPS.equals(lpparam.processName)) {
                PanelAppHook.install(PanelAppHook.Entry.LEAVE_HINT);
                DisplayMoveCache.install();
            } else if (YOUTUBE.equals(lpparam.packageName) && YOUTUBE.equals(lpparam.processName)) {
                PanelAppHook.install(PanelAppHook.Entry.PLAYER);
                DisplayMoveCache.install();
            }
        } catch (Throwable error) {
            // A missing method means a different Android build: leave that process unhooked.
            XposedBridge.log(TAG + ": not installed in " + lpparam.processName + ": " + error);
        }
    }
}
