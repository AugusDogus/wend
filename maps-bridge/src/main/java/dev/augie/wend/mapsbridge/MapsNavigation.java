package dev.augie.wend.mapsbridge;

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
import java.util.concurrent.Future;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.MatchType;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldUsingType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/**
 * Maps' own navigation, as its "Add stop" uses it: its NavigationControllerAdapter, which adds a
 * waypoint to the route it guides, and its waypoints. Maps' code is obfuscated, with new names in
 * each release, so they are found by what they do (DexKit), once per Maps build, and kept in a file
 * in Maps' own data:
 *
 * <ul>
 * <li>the adapter's add method is the one method that names its trace
 *     {@value #ADD_WAYPOINT_TRACE}; its one parameter is Maps' waypoint;
 * <li>its remove method is the one that names {@value #REMOVE_WAYPOINT_TRACE} and takes an index;
 * <li>its stop is its one method that takes a boolean and returns nothing;
 * <li>a waypoint is made by a builder, which the waypoint's one static method without parameters
 *     returns, and which has one method without parameters that returns the waypoint;
 * <li>a waypoint to a place Maps finds itself from text, as for a directions shortcut, has that
 *     text in the builder's String field written beside {@value #SHORTCUT_NAME};
 * <li>a waypoint at a point, as for the car's own location, is made by the waypoint's one static
 *     method that takes a String and a point and returns a builder: it writes the name to show in
 *     one String field of the builder and the point in its one field of the point's type, a class
 *     made from latitude and longitude in degrees.
 * </ul>
 *
 * Maps makes adapters as it guides; the newest one adds the waypoint (checked on Maps 26.43).
 */
final class MapsNavigation {
    static final String ADD_WAYPOINT_TRACE = "NavigationControllerAdapter.addWaypointToRoute.GuidanceSDK";
    static final String REMOVE_WAYPOINT_TRACE = "NavigationControllerAdapter.removeWaypointFromRoute.GuidanceSDK";
    static final String SHORTCUT_NAME = "directionsShortcut_%s";
    private static final String CACHE_VERSION = "5";

    private final Method add;
    /** Removes the route's waypoint at an index: 0 is where the car is, 1 the next stop, the last the destination. */
    private final Method removeAt;
    /** Stops guidance; Maps' own end-navigation button passes true. */
    private final Method stop;
    private final Method newBuilder;
    private final Method build;
    private final Field query;
    /** A point's name, its point, and how a point is made; null if this Maps had none found. */
    private final Field name, at;
    private final java.lang.reflect.Constructor<?> point;
    private volatile Object adapter;
    private final String apk;

    private MapsNavigation(String apk, Method add, Method removeAt, Method stop, Method newBuilder, Method build, Field query,
            Field name, Field at, java.lang.reflect.Constructor<?> point) {
        this.apk = apk;
        this.name = name;
        this.at = at;
        this.point = point;
        this.add = add;
        this.removeAt = removeAt;
        this.stop = stop;
        this.newBuilder = newBuilder;
        this.build = build;
        this.query = query;
    }

    /**
     * Finds Maps' navigation in its classes and watches for its adapters. Call as Maps' process
     * loads, before Maps guides.
     *
     * @param apk Maps' APK, whose path and size tell one Maps build from another
     * @param dataDir Maps' data directory, where what was found is kept
     * @throws ReflectiveOperationException if this Maps has no such code, as a later Maps may not
     */
    static MapsNavigation find(ClassLoader maps, String apk, String dataDir) throws ReflectiveOperationException {
        File apkFile = new File(apk);
        String mapsBuild = apk + ":" + apkFile.length() + ":" + apkFile.lastModified() + ":" + CACHE_VERSION;
        File cache = new File(dataDir, "files/wend-maps-bridge-names.properties");
        Properties names = read(cache);
        if (!mapsBuild.equals(names.getProperty("mapsBuild"))) {
            long started = System.currentTimeMillis();
            names = search(apk);
            names.setProperty("mapsBuild", mapsBuild);
            write(cache, names);
            Log.i(MapsBridge.TAG, "Found Maps' navigation in " + (System.currentTimeMillis() - started) + " ms: " + names);
        }
        Class<?> adapterClass = maps.loadClass(names.getProperty("adapter"));
        Class<?> waypoint = maps.loadClass(names.getProperty("waypoint"));
        Method add = adapterClass.getDeclaredMethod(names.getProperty("add"), waypoint);
        Method removeAt = adapterClass.getDeclaredMethod(names.getProperty("removeAt"), int.class);
        Method stop = adapterClass.getDeclaredMethod(names.getProperty("stop"), boolean.class);
        Method newBuilder = waypoint.getDeclaredMethod(names.getProperty("newBuilder"));
        Method buildMethod = newBuilder.getReturnType().getDeclaredMethod(names.getProperty("build"));
        Field query = newBuilder.getReturnType().getDeclaredField(names.getProperty("query"));
        for (java.lang.reflect.AccessibleObject member : new java.lang.reflect.AccessibleObject[]{add, removeAt, stop, newBuilder, buildMethod, query}) {
            member.setAccessible(true);
        }
        Field name = null, at = null;
        java.lang.reflect.Constructor<?> point = null;
        if (names.getProperty("at") != null) {
            try {
                name = newBuilder.getReturnType().getDeclaredField(names.getProperty("name"));
                at = newBuilder.getReturnType().getDeclaredField(names.getProperty("at"));
                point = at.getType().getDeclaredConstructor(double.class, double.class);
                name.setAccessible(true);
                at.setAccessible(true);
                point.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(MapsBridge.TAG, "Maps' waypoint at a point was not found; Wend adds stops from text only", error);
                name = at = null;
                point = null;
            }
        }
        MapsNavigation navigation = new MapsNavigation(apk, add, removeAt, stop, newBuilder, buildMethod, query, name, at, point);
        try {
            TripReplacer.install(maps, names);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(MapsBridge.TAG, "Maps asks the driver before Wend replaces a trip it guides: its reset interceptor was not found", error);
        }
        XposedBridge.hookAllConstructors(adapterClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { navigation.adapter = param.thisObject; }
        });
        return navigation;
    }

    /**
     * Asks Maps to add a stop at what {@code place} names, before its destination, while it
     * guides; Maps finds the place itself, as for the text of a directions link.
     *
     * @return Maps' answer to come: true once the stop is on the route
     * @throws IllegalStateException if Maps has not guided since its process started
     */
    Future<?> addStop(String place) throws ReflectiveOperationException {
        Object current = adapter;
        if (current == null) throw new IllegalStateException("Maps has not guided since it started");
        Object builder = newBuilder.invoke(null);
        query.set(builder, place);
        return (Future<?>) add.invoke(current, build.invoke(builder));
    }

    /**
     * Asks Maps to add a stop at a point, before its destination, while it guides; Maps shows it
     * as {@code place}, and finds nothing from text.
     *
     * @return Maps' answer to come: true once the stop is on the route
     * @throws IllegalStateException if Maps has not guided since its process started
     * @throws NoSuchFieldException if this Maps had no waypoint at a point found
     */
    Future<?> addStop(String place, double latitude, double longitude) throws ReflectiveOperationException {
        Object current = adapter;
        if (current == null) throw new IllegalStateException("Maps has not guided since it started");
        if (at == null) throw new NoSuchFieldException("Wend's Maps bridge found no waypoint at a point in this Maps");
        Object builder = newBuilder.invoke(null);
        name.set(builder, place);
        at.set(builder, point.newInstance(latitude, longitude));
        return (Future<?>) add.invoke(current, build.invoke(builder));
    }

    /**
     * Asks Maps to remove the stop at {@code position} ahead, 1 being the next, as Maps' own remove
     * does; Maps keeps its destination and reroutes. Removing past the stops would remove the
     * destination, which Maps allows, so the caller makes sure there is such a stop.
     *
     * @return Maps' answer to come: true once the stop is off the route
     * @throws IllegalStateException if Maps has not guided since its process started
     */
    Future<?> removeStop(int position) throws ReflectiveOperationException {
        Object current = adapter;
        if (current == null) throw new IllegalStateException("Maps has not guided since it started");
        return (Future<?>) removeAt.invoke(current, position);
    }

    /** @return Maps' NavigationControllerAdapter it made last, which acts on its guidance */
    Object adapter() {
        Object current = adapter;
        if (current == null) throw new IllegalStateException("Maps has not guided since it started");
        return current;
    }

    /** @return Maps' NavigationControllerAdapter class */
    Class<?> adapterClass() { return add.getDeclaringClass(); }

    /** @return Maps' APK */
    String apk() { return apk; }

    /**
     * Ends Maps' guidance as its own end-navigation button does; Maps then shows the trip's route
     * with Start, as after that button.
     *
     * @throws IllegalStateException if Maps has not guided since its process started
     */
    void endGuidance() throws ReflectiveOperationException {
        Object current = adapter;
        if (current == null) throw new IllegalStateException("Maps has not guided since it started");
        stop.invoke(current, true);
    }

    private static Properties search(String apk) throws ReflectiveOperationException {
        System.loadLibrary("dexkit");
        try (DexKitBridge dex = DexKitBridge.create(apk)) {
            MethodData add = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(ADD_WAYPOINT_TRACE))),
                    "a method that uses " + ADD_WAYPOINT_TRACE);
            if (add.getParamTypeNames().size() != 1) throw new NoSuchMethodException("Maps' add-waypoint method " + add + " takes not one waypoint");
            String waypoint = add.getParamTypeNames().get(0);
            Properties names = new Properties();
            names.setProperty("adapter", add.getClassName());
            names.setProperty("add", add.getMethodName());
            names.setProperty("waypoint", waypoint);
            MethodData removeAt = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(REMOVE_WAYPOINT_TRACE)
                    .declaredClass(add.getClassName(), StringMatchType.Equals, false).paramTypes("int"))),
                    "a method that uses " + REMOVE_WAYPOINT_TRACE + " with an index");
            names.setProperty("removeAt", removeAt.getMethodName());
            MethodData stop = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(add.getClassName(), StringMatchType.Equals, false).paramTypes("boolean")
                    .returnType("void", StringMatchType.Equals, false))), "the adapter's one method that takes a boolean");
            names.setProperty("stop", stop.getMethodName());
            Set<String> builders = new HashSet<>();
            for (MethodData method : dex.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(waypoint, StringMatchType.Equals, false).modifiers(Modifier.STATIC, MatchType.Contains).paramCount(0)))) {
                String builder = method.getReturnTypeName();
                if (builder.equals(waypoint) || builder.equals("void")) continue;
                MethodData build = dex.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(builder, StringMatchType.Equals, false).paramCount(0).returnType(waypoint, StringMatchType.Equals, false))).singleOrNull();
                if (build == null) continue;
                builders.add(builder);
                names.setProperty("newBuilder", method.getMethodName());
                names.setProperty("build", build.getMethodName());
            }
            if (builders.size() != 1) throw new NoSuchMethodException("Maps' waypoint " + waypoint + " has " + builders.size() + " builders, not one");
            String builder = builders.iterator().next();
            Set<String> fields = new HashSet<>();
            for (MethodData method : dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(SHORTCUT_NAME)))) {
                for (UsingFieldData using : method.getUsingFields()) {
                    if (using.getUsingType() == FieldUsingType.Write && using.getField().getClassName().equals(builder)
                            && using.getField().getTypeName().equals("java.lang.String")) {
                        fields.add(using.getField().getFieldName());
                    }
                }
            }
            if (fields.size() != 1) throw new NoSuchFieldException("Maps' waypoint builder " + builder + " has " + fields + " as its text, not one field");
            names.setProperty("query", fields.iterator().next());
            try {
                searchPoint(dex, waypoint, builder, names);
            } catch (NoSuchMethodException | NoSuchFieldException | RuntimeException error) {
                Log.w(MapsBridge.TAG, "Maps' waypoint at a point was not found; Wend adds stops from text only", error);
            }
            try {
                TripReplacer.search(dex, names);
            } catch (NoSuchMethodException | RuntimeException error) {
                Log.w(MapsBridge.TAG, "Maps' reset interceptor was not found; Maps will ask the driver before Wend replaces a trip", error);
            }
            return names;
        }
    }

    /** Finds a waypoint's name and point fields in its builder, as {@code names}' "name" and "at". */
    private static void searchPoint(DexKitBridge dex, String waypoint, String builder, Properties names)
            throws NoSuchMethodException, NoSuchFieldException {
        Set<String> found = new HashSet<>();
        for (MethodData method : dex.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(waypoint, StringMatchType.Equals, false).modifiers(Modifier.STATIC, MatchType.Contains)
                .paramCount(2).returnType(builder, StringMatchType.Equals, false)))) {
            List<String> params = method.getParamTypeNames();
            if (!params.get(0).equals("java.lang.String")) continue;
            Set<String> name = new HashSet<>(), at = new HashSet<>();
            for (UsingFieldData using : method.getUsingFields()) {
                if (using.getUsingType() != FieldUsingType.Write || !using.getField().getClassName().equals(builder)) continue;
                String type = using.getField().getTypeName();
                if (type.equals("java.lang.String")) name.add(using.getField().getFieldName());
                else if (type.equals(params.get(1))) at.add(using.getField().getFieldName());
            }
            if (name.size() != 1 || at.size() != 1) {
                throw new NoSuchFieldException("Maps' waypoint method " + method + " writes " + name + " as its name and " + at + " as its point, not one of each");
            }
            found.add(name.iterator().next() + " " + at.iterator().next());
        }
        if (found.size() != 1) throw new NoSuchMethodException("Maps' waypoint " + waypoint + " has " + found + " as its name and point, not one pair");
        String[] pair = found.iterator().next().split(" ");
        names.setProperty("name", pair[0]);
        names.setProperty("at", pair[1]);
    }

    private static MethodData only(List<MethodData> found, String what) throws NoSuchMethodException {
        if (found.size() != 1) throw new NoSuchMethodException("Maps has " + found.size() + " of " + what + ", not one");
        return found.get(0);
    }

    private static Properties read(File cache) {
        Properties names = new Properties();
        try { names.load(new java.io.StringReader(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8))); }
        catch (IOException | RuntimeException none) { names.clear(); }
        return names;
    }

    private static void write(File cache, Properties names) {
        try (java.io.Writer out = Files.newBufferedWriter(cache.toPath(), StandardCharsets.UTF_8)) {
            names.store(out, "What Wend's Maps bridge found in this Maps build; deleted, it is found again");
        } catch (IOException | RuntimeException error) {
            Log.w(MapsBridge.TAG, "Could not keep what was found of Maps' navigation; it is found again as Maps next starts", error);
        }
    }
}
