package dev.augie.wend.piphook;

import android.content.res.Configuration;
import android.view.Display;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.IdentityHashMap;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Runs in Google Maps. When an activity moves to another display (out of Wend's panel into the PiP,
 * and back), Android 10 tells every view ({@code View.dispatchMovedToDisplay}), and each view asks
 * DisplayManager for the display's state over binder ({@code Display.getState}). Maps has thousands
 * of views, so that held its main thread for hundreds of milliseconds while it entered its PiP. A
 * display's state cannot change within one configuration change, so while one is handled it is
 * read once per display. The hook is on {@code dispatchMovedToDisplay} because Android inlines the
 * display's own refresh into its compiled callers, where a hook does not run.
 */
final class DisplayMoveCache {
    /** Each display's state in the configuration change this thread is handling, or null outside one. */
    private static final ThreadLocal<Map<Display, Integer>> states = new ThreadLocal<>();

    private DisplayMoveCache() { }

    static void install() throws NoSuchMethodException {
        final Method onMovedToDisplay = View.class.getMethod("onMovedToDisplay", int.class, Configuration.class);
        XposedBridge.hookAllMethods(XposedHelpers.findClass("android.app.ActivityThread", null),
                "handleActivityConfigurationChanged", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (states.get() == null) {
                            states.set(new IdentityHashMap<>());
                            param.setObjectExtra("outermost", true);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getObjectExtra("outermost") != null) states.remove();
                    }
                });
        XposedHelpers.findAndHookMethod(View.class, "dispatchMovedToDisplay", Display.class, Configuration.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Map<Display, Integer> known = states.get();
                        if (known == null) return;
                        Display display = (Display) param.args[0];
                        Integer state = known.get(display);
                        if (state == null) {
                            state = display.getState();
                            known.put(display, state);
                        }
                        try {
                            Object attachInfo = XposedHelpers.getObjectField(param.thisObject, "mAttachInfo");
                            XposedHelpers.setObjectField(attachInfo, "mDisplay", display);
                            XposedHelpers.setIntField(attachInfo, "mDisplayState", state);
                            onMovedToDisplay.invoke(param.thisObject, display.getDisplayId(), param.args[1]);
                            param.setResult(null);
                        } catch (ReflectiveOperationException | RuntimeException error) {
                            // Android's own dispatch runs instead, as without this module.
                            XposedBridge.log(PipHook.TAG + ": display move shortcut failed; Android dispatches it itself: " + error);
                        }
                    }
                });
    }
}
