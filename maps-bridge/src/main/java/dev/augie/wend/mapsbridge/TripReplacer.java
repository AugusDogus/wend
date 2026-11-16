package dev.augie.wend.mapsbridge;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

/**
 * Runs inside Google Maps. Lets Wend replace the trip Maps guides as the driver does when Maps
 * asks "Exit navigation?" and they answer yes, without the question: Maps then stops its guidance
 * and starts the trip Wend asked for in the same step.
 *
 * <p>While Maps guides, it hands each request that would replace its screen (a navigation link,
 * say) to its reset interceptor, which asks the driver first and, on yes, resets Maps' activity
 * (which stops the guidance through the interceptor) and runs the request. For a navigation link
 * Wend itself started with {@link #REPLACE} set, this answers yes at once. Android records which
 * app sent each intent it hands to Maps (as the intent's referrer); the mark is dropped then from
 * any other app's intent, so no other app can pass for Wend.
 *
 * <p>Maps' code is obfuscated; what this calls is found by what it does, with the rest of
 * {@link MapsNavigation}: the interceptor is the class that logs {@value #REGISTER_LOG}; it asks in
 * its one method that takes a Runnable, and stops guidance in its interface's one method without
 * parameters; the reset is the one method that calls that stop and the unregistering that logs
 * {@value #UNREGISTER_LOG}.
 */
final class TripReplacer {
    static final String REPLACE = "dev.augie.wend.REPLACE_TRIP";
    static final String REGISTER_LOG = "Tried to register ResetInterceptor %s when %s is still registered.";
    static final String UNREGISTER_LOG = "Tried to unregister a nonexistent ResetInterceptor.";
    private static final String WEND = "dev.augie.wend";

    private TripReplacer() { }

    /** Adds the names of Maps' reset interceptor and its activity's reset to {@code names}. */
    static void search(DexKitBridge dex, Properties names) throws NoSuchMethodException {
        MethodData register = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(REGISTER_LOG))), REGISTER_LOG);
        String interceptor = register.getClassName();
        MethodData ask = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(interceptor, StringMatchType.Equals, false).paramTypes("java.lang.Runnable"))), "its ask");
        ClassData interceptorClass = dex.getClassData(interceptor);
        if (interceptorClass == null || interceptorClass.getInterfaces().size() != 1) throw new NoSuchMethodException("Maps' reset interceptor has not one interface");
        String resetInterceptor = interceptorClass.getInterfaces().get(0).getName();
        MethodData stop = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(resetInterceptor, StringMatchType.Equals, false).paramCount(0).returnType("void", StringMatchType.Equals, false))),
                "its stop");
        MethodData unregister = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(UNREGISTER_LOG))), UNREGISTER_LOG);
        MethodData reset = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().paramCount(0)
                .addInvoke(MethodMatcher.create().declaredClass(resetInterceptor, StringMatchType.Equals, false)
                        .name(stop.getMethodName(), StringMatchType.Equals, false).paramCount(0))
                .addInvoke(MethodMatcher.create().declaredClass(unregister.getClassName(), StringMatchType.Equals, false)
                        .name(unregister.getMethodName(), StringMatchType.Equals, false).paramCount(0)))), "the activity's reset");
        names.setProperty("interceptor", interceptor);
        names.setProperty("ask", ask.getMethodName());
        names.setProperty("resetClass", reset.getClassName());
        names.setProperty("reset", reset.getMethodName());
    }

    /** Answers yes for Wend's navigation links. */
    static void install(ClassLoader maps, Properties names) throws ReflectiveOperationException {
        if (names.getProperty("interceptor") == null) throw new ClassNotFoundException("not found in this Maps");
        Class<?> interceptor = maps.loadClass(names.getProperty("interceptor"));
        Class<?> activityClass = maps.loadClass(names.getProperty("resetClass"));
        Method reset = activityClass.getDeclaredMethod(names.getProperty("reset"));
        reset.setAccessible(true);
        Field activityField = null;
        for (Field field : interceptor.getDeclaredFields()) {
            if (field.getType() == activityClass) activityField = field;
        }
        if (activityField == null) throw new NoSuchFieldException("Maps' reset interceptor holds no " + activityClass);
        activityField.setAccessible(true);
        Field activity = activityField;
        // Android hands an app each intent with the app that sent it; the mark is kept from Wend alone.
        XposedHelpers.findAndHookMethod("android.app.ActivityThread", null, "deliverNewIntents",
                "android.app.ActivityThread$ActivityClientRecord", java.util.List.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        for (Object intent : (java.util.List<?>) param.args[1]) {
                            keepFromWendOnly((Intent) intent, XposedHelpers.getObjectField(intent, "mReferrer"));
                        }
                    }
                });
        XposedHelpers.findAndHookMethod("android.app.ActivityThread", null, "performLaunchActivity",
                "android.app.ActivityThread$ActivityClientRecord", Intent.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        keepFromWendOnly((Intent) XposedHelpers.getObjectField(param.args[0], "intent"),
                                XposedHelpers.getObjectField(param.args[0], "referrer"));
                    }
                });
        XposedHelpers.findAndHookMethod(interceptor, names.getProperty("ask"), Runnable.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Activity maps = (Activity) activity.get(param.thisObject);
                if (maps == null || !askedByWend(maps)) return;
                Log.i(MapsBridge.TAG, "Replacing the trip Maps guides with Wend's, as yes to Exit navigation?");
                reset.invoke(maps);
                ((Runnable) param.args[0]).run();
                param.setResult(null);
            }
        });
    }

    private static void keepFromWendOnly(Intent intent, Object sender) {
        if (intent == null || !intent.hasExtra(REPLACE) || WEND.equals(sender)) return;
        intent.removeExtra(REPLACE);
        Log.w(MapsBridge.TAG, "Dropped Wend's replace-trip mark from an intent " + sender + " sent; Maps asks the driver as usual");
    }

    /** @return whether the intent {@code maps} handles carries Wend's mark, which only Wend's own intents keep */
    private static boolean askedByWend(Activity maps) {
        Intent intent = maps.getIntent();
        return intent != null && intent.getBooleanExtra(REPLACE, false);
    }

    private static MethodData only(List<MethodData> found, String what) throws NoSuchMethodException {
        if (found.size() != 1) throw new NoSuchMethodException("Maps has " + found.size() + " of " + what + ", not one");
        return found.get(0);
    }
}
