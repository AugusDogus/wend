package dev.augie.wend.mapsbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/**
 * Runs inside Google Maps. Hands Wend Maps' guidance as Maps' own navigation state holds it, each
 * time that state changes: whether Maps guides, the step ahead as Maps words it (its distance and
 * its cue, "0.2 mi" and "US-101 S"), Maps' arrow for it, the meters and seconds to it, the meters
 * and time left to the next destination (also as Maps words them, "8.4 mi" and "10 min") and the
 * time zone Maps words the arrival in, whether
 * the car is on the route, and whether the route is done. No notification is read: Maps' state is.
 *
 * <p>Maps' code is obfuscated, so what this hooks and calls is found by what it does (DexKit),
 * once per Maps build:
 *
 * <ul>
 * <li>Maps' handler of each change of its navigation state is the one method that names its
 *     trace {@value #HANDLER_TRACE}. Its one parameter is the state, which holds what Maps does
 *     now as an enum ({@code OFF}, {@code GUIDING}, {@code CRUISING}); the handler gets the
 *     guidance from it with the one static method it calls that takes the state.
 * <li>Maps renders the arrow of the step ahead, within that handler, with the method that names
 *     its trace {@value #ARROW_TRACE}.
 * <li>Maps' summary of its guidance is the method that names its trace {@value #SUMMARY_TRACE}. Its
 *     class words the step's cue with its one method that takes the step and two booleans; the
 *     second is the guidance's one boolean method the summary calls. It words the distance to the
 *     step, and the distance left, with the one static method it calls that takes an int and the
 *     route's units; the time left with the one that takes Maps' resources and a Duration.
 * <li>The progress of the guidance: {@link MapsProgress}.
 * </ul>
 *
 * <p>Each report is a call of {@code guidance} on Wend's provider with the extras below; once Maps
 * guides no more, {@link #STATE} is not {@code GUIDING}.
 */
final class GuidanceState {
    static final String HANDLER_TRACE = "NotificationController.onNavigationStateChangedInternal";
    static final String ARROW_TRACE = "NavigationManeuverIndicatorUtils.getManeuverBitmap";
    static final String SUMMARY_TRACE = "NavigationSummarizer.generateSummary";
    static final String STATE = "state", DISTANCE = "distance", CUE = "cue", ARROW = "arrow", COLOR = "color",
            METERS_TO_STEP = "meters_to_step", SECONDS_TO_STEP = "seconds_to_step", METERS_REMAINING = "meters_remaining",
            SECONDS_REMAINING = "seconds_remaining", ON_ROUTE = "on_route", DONE = "done", DISTANCE_LEFT = "distance_left",
            TIME_LEFT = "time_left", TIME_ZONE = "time_zone";
    static final String GUIDING = "GUIDING";
    private static final String CACHE_VERSION = "2";

    private final TripReporter reporter;
    private final Context maps;
    private final Method guidanceOf;
    private final MapsProgress progress;
    private final Method cue, far, distance, time;
    private final Class<?> formatterType, unitsType;
    /** The handler runs on one thread at a time; the arrow Maps renders within it, on that thread. */
    private final ThreadLocal<Bitmap> arrow = new ThreadLocal<>();
    private final ThreadLocal<Boolean> handling = new ThreadLocal<>();
    /** Maps' summary, once Maps has used it: its methods word the cue and distance. */
    private volatile Object summary;
    private Field stateKind, units;
    private Object formatter;
    /** What was reported last, for Wend's asking again; null before the first report. */
    private Bundle last;

    private GuidanceState(TripReporter reporter, Context maps, Method guidanceOf, MapsProgress progress, Method cue,
            Method far, Method distance, Method time) {
        this.time = time;
        this.reporter = reporter;
        this.maps = maps;
        this.guidanceOf = guidanceOf;
        this.progress = progress;
        this.cue = cue;
        this.far = far;
        this.distance = distance;
        this.formatterType = distance.getParameterTypes()[0];
        this.unitsType = distance.getParameterTypes()[2];
    }

    /**
     * Finds Maps' navigation state in Maps, or in what was kept of an earlier search of this Maps
     * build, and hooks it. Off the main thread: a first search takes seconds.
     */
    static GuidanceState install(TripReporter reporter, Context maps, String apk) throws ReflectiveOperationException {
        File apkFile = new File(apk);
        String mapsBuild = apk + ":" + apkFile.length() + ":" + apkFile.lastModified() + ":" + CACHE_VERSION;
        File cache = new File(maps.getFilesDir(), "wend-maps-bridge-guidance.properties");
        Properties names = new Properties();
        try { names.load(new java.io.StringReader(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8))); }
        catch (IOException | RuntimeException none) { names.clear(); }
        ClassLoader loader = maps.getClassLoader();
        if (!mapsBuild.equals(names.getProperty("mapsBuild"))) {
            long started = System.currentTimeMillis();
            names = search(apk, loader);
            names.setProperty("mapsBuild", mapsBuild);
            try (java.io.Writer out = Files.newBufferedWriter(cache.toPath(), StandardCharsets.UTF_8)) {
                names.store(out, "What Wend's Maps bridge found of Maps' navigation state in this Maps build; deleted, it is found again");
            } catch (IOException | RuntimeException error) {
                Log.w(MapsBridge.TAG, "Could not keep what was found of Maps' navigation state; it is found again next time", error);
            }
            Log.i(MapsBridge.TAG, "Found Maps' navigation state in " + (System.currentTimeMillis() - started) + " ms: " + names);
        }
        Class<?> state = loader.loadClass(names.getProperty("state"));
        Method handler = declared(loader.loadClass(names.getProperty("handlerClass")), names.getProperty("handler"), state);
        Method guidanceOf = declared(loader.loadClass(names.getProperty("guidanceOfClass")), names.getProperty("guidanceOf"), state);
        Class<?> guidance = guidanceOf.getReturnType();
        MapsProgress progress = MapsProgress.from(guidance, names);
        Class<?> summaryClass = loader.loadClass(names.getProperty("summaryClass"));
        Method summarize = declared(summaryClass, names.getProperty("summarize"), guidance);
        Method cue = declared(summaryClass, names.getProperty("cue"), progress.stepType(), boolean.class, boolean.class);
        Method far = declared(guidance, names.getProperty("far"));
        Method distance = null;
        for (Method method : loader.loadClass(names.getProperty("distanceClass")).getDeclaredMethods()) {
            if (method.getName().equals(names.getProperty("distance")) && Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == 3 && method.getParameterTypes()[1] == int.class) {
                distance = method;
            }
        }
        if (distance == null) throw new NoSuchMethodException("Maps' distance wording " + names.getProperty("distance") + " is gone");
        distance.setAccessible(true);
        Method time = null;
        for (Method method : loader.loadClass(names.getProperty("timeClass")).getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getName().equals(names.getProperty("time")) && Modifier.isStatic(method.getModifiers()) && params.length == 4
                    && params[0] == android.content.res.Resources.class && params[2] == int.class) {
                time = method;
            }
        }
        if (time == null) throw new NoSuchMethodException("Maps' time wording " + names.getProperty("time") + " is gone");
        time.setAccessible(true);
        Method arrowMethod = null;
        for (Method method : loader.loadClass(names.getProperty("arrowClass")).getDeclaredMethods()) {
            if (method.getName().equals(names.getProperty("arrow")) && method.getReturnType() == Bitmap.class) arrowMethod = method;
        }
        if (arrowMethod == null) throw new NoSuchMethodException("Maps' arrow renderer " + names.getProperty("arrow") + " is gone");

        GuidanceState guidanceState = new GuidanceState(reporter, maps, guidanceOf, progress, cue, far, distance, time);
        XposedBridge.hookMethod(summarize, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { guidanceState.summary = param.thisObject; }
        });
        XposedBridge.hookMethod(arrowMethod, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(guidanceState.handling.get()) && param.getResult() instanceof Bitmap) {
                    guidanceState.arrow.set((Bitmap) param.getResult());
                }
            }
        });
        XposedBridge.hookMethod(handler, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                guidanceState.handling.set(true);
                guidanceState.arrow.remove();
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                guidanceState.handling.remove();
                Bitmap drawn = guidanceState.arrow.get();
                guidanceState.arrow.remove();
                guidanceState.changed(param.args[0], drawn);
            }
        });
        return guidanceState;
    }

    /** Maps' navigation state changed to {@code state}, and Maps drew {@code drawn} for its step, or nothing. Maps' thread. */
    private synchronized void changed(Object state, Bitmap drawn) {
        Bundle report = new Bundle();
        try {
            String kind = kind(state);
            report.putString(STATE, kind);
            if (GUIDING.equals(kind)) describe(guidanceOf.invoke(null, state), drawn, report);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(MapsBridge.TAG, "Could not read Maps' navigation state; Wend shows the guidance it last heard", error);
            return;
        }
        // Maps' state changes on and off while it guides nowhere: one report says so.
        if (!GUIDING.equals(report.getString(STATE)) && last != null && !GUIDING.equals(last.getString(STATE))) return;
        last = report;
        reporter.tellWend("guidance", null, report);
        reporter.guidanceUpdated();
    }

    private void describe(Object guidance, Bitmap drawn, Bundle report) throws ReflectiveOperationException {
        if (guidance == null) return;
        MapsProgress.Snapshot now = progress.of(guidance);
        if (now == null) return;
        report.putInt(METERS_TO_STEP, now.metersToStep);
        report.putInt(SECONDS_TO_STEP, now.secondsToStep);
        report.putInt(METERS_REMAINING, now.metersRemaining);
        report.putInt(SECONDS_REMAINING, now.secondsRemaining);
        report.putBoolean(ON_ROUTE, now.onRoute);
        report.putBoolean(DONE, now.done);
        if (drawn != null) report.putParcelable(ARROW, drawn);
        int color = maps.getResources().getIdentifier("nav_turn_card_step_background", "color", maps.getPackageName());
        if (color != 0) report.putInt(COLOR, maps.getColor(color));
        Object words = summary;
        if (now.step == null) {
            int rerouting = maps.getResources().getIdentifier("DA_REROUTING", "string", maps.getPackageName());
            if (rerouting != 0) report.putCharSequence(CUE, maps.getString(rerouting));
            return;
        }
        if (words == null) return;
        report.putCharSequence(CUE, (CharSequence) cue.invoke(words, now.step, false, (boolean) far.invoke(guidance)));
        String zone = zone(now.route);
        if (zone != null) report.putString(TIME_ZONE, zone);
        Object formatter = formatter(words);
        Object units = units(now.route);
        if (now.metersToStep >= 0 && formatter != null) {
            report.putCharSequence(DISTANCE, (CharSequence) distance.invoke(null, formatter, now.metersToStep, units));
        }
        if (now.metersRemaining >= 0 && formatter != null) {
            report.putCharSequence(DISTANCE_LEFT, (CharSequence) distance.invoke(null, formatter, now.metersRemaining, units));
        }
        if (now.secondsRemaining >= 0) {
            Class<?> duration = time.getParameterTypes()[1];
            Object left = duration.getMethod("ofSeconds", long.class).invoke(null, (long) now.secondsRemaining);
            report.putCharSequence(TIME_LEFT, (CharSequence) time.invoke(null, maps.getResources(), left, 1, null));
        }
    }

    /** @return what Maps' state says Maps does now: {@code OFF}, {@code GUIDING} or {@code CRUISING} */
    private String kind(Object state) throws IllegalAccessException {
        if (stateKind == null) {
            for (Field field : state.getClass().getDeclaredFields()) {
                if (!field.getType().isEnum()) continue;
                for (Object constant : field.getType().getEnumConstants()) {
                    if (GUIDING.equals(((Enum<?>) constant).name())) stateKind = field;
                }
            }
            if (stateKind == null) throw new IllegalStateException("Maps' navigation state " + state.getClass().getName() + " says nothing of guiding");
            stateKind.setAccessible(true);
        }
        Object kind = stateKind.get(state);
        return kind == null ? "OFF" : ((Enum<?>) kind).name();
    }

    /**
     * @return Maps' distance wording's first argument, as Maps' summary holds it: itself, or a lazy
     *     holder of it (an interface with one method without parameters); null if it holds none
     */
    private Object formatter(Object words) throws ReflectiveOperationException {
        if (formatter != null) return formatter;
        for (Field field : words.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object held = field.get(words);
            if (formatterType.isInstance(held)) return formatter = held;
            if (held == null || !field.getType().isInterface()) continue;
            Method[] methods = field.getType().getMethods();
            if (methods.length != 1 || methods[0].getParameterTypes().length != 0) continue;
            Object lazy = methods[0].invoke(held);
            if (formatterType.isInstance(lazy)) return formatter = lazy;
        }
        return null;
    }

    /** @return the units of {@code route}: its one field of the units' type */
    private Object units(Object route) throws IllegalAccessException {
        if (route == null) return null;
        if (units == null) {
            for (Field field : route.getClass().getDeclaredFields()) {
                if (field.getType() == unitsType) {
                    if (units != null) throw new IllegalStateException("Maps' route has more than one field of its units");
                    units = field;
                }
            }
            if (units == null) throw new IllegalStateException("Maps' route " + route.getClass().getName() + " has no units");
            units.setAccessible(true);
        }
        return units.get(route);
    }

    /**
     * @return the id of the time zone Maps words {@code route}'s arrival in: what its one method
     *     without parameters that returns a ZoneId gives; null if it has none
     */
    private static String zone(Object route) throws ReflectiveOperationException {
        if (route == null) return null;
        Method found = null;
        for (Method method : route.getClass().getDeclaredMethods()) {
            if (method.getParameterTypes().length == 0 && method.getReturnType().getName().endsWith(".time.ZoneId")) {
                if (found != null) return null;
                found = method;
            }
        }
        if (found == null) return null;
        found.setAccessible(true);
        Object zone = found.invoke(route);
        return zone == null ? null : String.valueOf(zone.getClass().getMethod("getId").invoke(zone));
    }

    /** Wend asked again, as it does when it starts. */
    synchronized void again() {
        if (last != null) reporter.tellWend("guidance", null, last);
    }

    private static Properties search(String apk, ClassLoader loader) throws ReflectiveOperationException {
        System.loadLibrary("dexkit");
        try (DexKitBridge dex = DexKitBridge.create(apk)) {
            Properties names = new Properties();
            MethodData handler = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(HANDLER_TRACE))), HANDLER_TRACE);
            if (handler.getParamTypeNames().size() != 1) throw new NoSuchMethodException("Maps' state handler " + handler + " takes not one state");
            String state = handler.getParamTypeNames().get(0);
            names.setProperty("handlerClass", handler.getClassName());
            names.setProperty("handler", handler.getMethodName());
            names.setProperty("state", state);
            Set<MethodData> getters = new HashSet<>();
            for (MethodData invoked : handler.getInvokes()) {
                if (Modifier.isStatic(invoked.getModifiers()) && invoked.getParamTypeNames().size() == 1
                        && invoked.getParamTypeNames().get(0).equals(state) && !invoked.getReturnTypeName().equals("void")) {
                    getters.add(invoked);
                }
            }
            MethodData guidanceOf = only(new java.util.ArrayList<>(getters), "a static getter of the guidance from the state in the handler");
            names.setProperty("guidanceOfClass", guidanceOf.getClassName());
            names.setProperty("guidanceOf", guidanceOf.getMethodName());
            String guidance = guidanceOf.getReturnTypeName();
            MapsProgress.search(dex, loader.loadClass(guidance), names);

            MethodData summarize = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(SUMMARY_TRACE))), SUMMARY_TRACE);
            names.setProperty("summaryClass", summarize.getClassName());
            names.setProperty("summarize", summarize.getMethodName());
            String step = MapsProgress.from(loader.loadClass(guidance), names).stepType().getName();
            MethodData cue = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(summarize.getClassName(), StringMatchType.Equals, false).paramTypes(step, "boolean", "boolean")
                    .returnType("java.lang.CharSequence", StringMatchType.Equals, false))), "the summary's cue of a step");
            names.setProperty("cue", cue.getMethodName());
            Set<String> far = new HashSet<>();
            MethodData distance = null, time = null;
            for (MethodData invoked : summarize.getInvokes()) {
                if (invoked.getClassName().equals(guidance) && invoked.getParamTypeNames().isEmpty() && invoked.getReturnTypeName().equals("boolean")) {
                    far.add(invoked.getMethodName());
                }
                List<String> params = invoked.getParamTypeNames();
                if (Modifier.isStatic(invoked.getModifiers()) && params.size() == 3 && params.get(1).equals("int")
                        && invoked.getReturnTypeName().equals("java.lang.CharSequence")) {
                    if (distance != null && !distance.equals(invoked)) throw new NoSuchMethodException("Maps' summary words a distance two ways");
                    distance = invoked;
                }
                if (Modifier.isStatic(invoked.getModifiers()) && params.size() == 4 && params.get(0).equals("android.content.res.Resources")
                        && params.get(1).endsWith(".time.Duration") && params.get(2).equals("int")) {
                    if (time != null && !time.equals(invoked)) throw new NoSuchMethodException("Maps' summary words a time two ways");
                    time = invoked;
                }
            }
            if (far.size() != 1) throw new NoSuchMethodException("Maps' summary asks the guidance " + far + ", not one boolean");
            if (distance == null) throw new NoSuchMethodException("Maps' summary words no distance");
            if (time == null) throw new NoSuchMethodException("Maps' summary words no time");
            names.setProperty("timeClass", time.getClassName());
            names.setProperty("time", time.getMethodName());
            names.setProperty("far", far.iterator().next());
            names.setProperty("distanceClass", distance.getClassName());
            names.setProperty("distance", distance.getMethodName());

            MethodData arrow = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(ARROW_TRACE))), ARROW_TRACE);
            if (!arrow.getReturnTypeName().equals("android.graphics.Bitmap")) throw new NoSuchMethodException("Maps' arrow renderer " + arrow + " returns no Bitmap");
            names.setProperty("arrowClass", arrow.getClassName());
            names.setProperty("arrow", arrow.getMethodName());
            return names;
        }
    }

    private static MethodData only(List<MethodData> found, String what) throws NoSuchMethodException {
        if (found.size() != 1) throw new NoSuchMethodException("Maps has " + found.size() + " of " + what + ", not one");
        return found.get(0);
    }

    private static Method declared(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
}
