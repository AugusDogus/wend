package dev.augie.wend.mapsbridge;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldUsingType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/**
 * Maps' routes for the trip it guides: the one it guides along and the alternatives it offers,
 * each with its id, travel time and the roads it goes by, as Maps describes an alternative ("Alternate
 * route 12 min via US-101 S"); and Maps' own switch to one of them. Maps' code is obfuscated, so
 * what this reads and calls is found by what it does (DexKit), once per Maps build, and kept with
 * {@link MapsNavigation}'s names:
 *
 * <ul>
 * <li>the switch is the NavigationControllerAdapter's method that names its trace
 *     {@value #SWITCH_TRACE};
 * <li>the adapter's other method that takes a route id and says {@value #NOT_GUIDING} reads the
 *     route set Maps guides by: a field of the adapter, a field of that, a static method that
 *     returns the guidance, and the guidance's method that returns the route set;
 * <li>the route set's guided route is what the adapter's waypoint removal ({@value #REMOVE_LOG})
 *     takes from it; each route's id is the String field the adapter's route switch reads;
 * <li>each route's travel time and roads are what Maps' description of an alternative route
 *     (string resource {@value #DESCRIPTION}, "Alternate route %1$s via %2$s") reads from it: its one
 *     Duration method (Maps' own copy of java.time), and the String field that the code of
 *     {@value #VIA} ("via %1$s") also reads.
 * </ul>
 */
final class MapsRoutes {
    static final String SWITCH_TRACE = "NavigationControllerAdapter.switchRoute.GuidanceSDK";
    static final String NOT_GUIDING = "Guidance not running";
    static final String REMOVE_LOG = "Waypoint index to remove: %d was not in the range of the current route's waypoints.";
    static final String DESCRIPTION = "ACCESSIBILITY_ALTERNATE_ROUTE_DESCRIPTION", VIA = "VIA_ROADS";
    private static final String CACHE_VERSION = "1";

    private final Field state, guidanceHolder;
    private final Method guidance, routeSet, guided, travelTime, switchRoute;
    private final Field id, roads;

    private MapsRoutes(Field state, Field guidanceHolder, Method guidance, Method routeSet, Method guided, Field id,
            Method travelTime, Field roads, Method switchRoute) {
        this.state = state;
        this.guidanceHolder = guidanceHolder;
        this.guidance = guidance;
        this.routeSet = routeSet;
        this.guided = guided;
        this.id = id;
        this.travelTime = travelTime;
        this.roads = roads;
        this.switchRoute = switchRoute;
    }

    /**
     * Finds them in Maps, or in what was kept of an earlier search of this Maps build. Off the main
     * thread: a first search takes a second.
     *
     * @param maps Maps' application, for the resource that anchors the travel time and roads
     * @param adapter Maps' NavigationControllerAdapter class, as {@link MapsNavigation} found it
     */
    static MapsRoutes find(Context maps, Class<?> adapter, String apk) throws ReflectiveOperationException {
        File apkFile = new File(apk);
        String mapsBuild = apk + ":" + apkFile.length() + ":" + apkFile.lastModified() + ":" + CACHE_VERSION;
        File cache = new File(maps.getFilesDir(), "wend-maps-bridge-routes.properties");
        Properties names = new Properties();
        try { names.load(new java.io.StringReader(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8))); }
        catch (IOException | RuntimeException none) { names.clear(); }
        if (!mapsBuild.equals(names.getProperty("mapsBuild"))) {
            int description = maps.getResources().getIdentifier(DESCRIPTION, "string", maps.getPackageName());
            int via = maps.getResources().getIdentifier(VIA, "string", maps.getPackageName());
            if (description == 0 || via == 0) throw new NoSuchFieldException("Maps has no string " + DESCRIPTION + " or " + VIA);
            long started = System.currentTimeMillis();
            names = search(apk, adapter.getName(), description, via);
            names.setProperty("mapsBuild", mapsBuild);
            try (java.io.Writer out = Files.newBufferedWriter(cache.toPath(), StandardCharsets.UTF_8)) {
                names.store(out, "What Wend's Maps bridge found of Maps' routes in this Maps build; deleted, it is found again");
            } catch (IOException | RuntimeException error) {
                Log.w(MapsBridge.TAG, "Could not keep what was found of Maps' routes; it is found again next time", error);
            }
            Log.i(MapsBridge.TAG, "Found Maps' routes in " + (System.currentTimeMillis() - started) + " ms: " + names);
        }
        ClassLoader loader = adapter.getClassLoader();
        Field state = field(adapter, names.getProperty("state"));
        Field guidanceHolder = field(state.getType(), names.getProperty("guidanceHolder"));
        Method guidance = method(loader.loadClass(names.getProperty("guidanceClass")), names.getProperty("guidance"), guidanceHolder.getType());
        Method routeSet = method(guidance.getReturnType(), names.getProperty("routeSet"));
        Method guided = method(routeSet.getReturnType(), names.getProperty("guided"));
        Class<?> route = guided.getReturnType();
        return new MapsRoutes(state, guidanceHolder, guidance, routeSet, guided, field(route, names.getProperty("id")),
                method(route, names.getProperty("travelTime")), field(route, names.getProperty("roads")),
                method(adapter, names.getProperty("switchRoute"), String.class));
    }

    /**
     * @param adapter Maps' NavigationControllerAdapter
     * @return the routes Maps has for the trip it guides, the guided one first: each with its
     *     {@code id}, {@code guided}, {@code minutes} and {@code via} (the roads, when Maps names them)
     * @throws IllegalStateException if Maps is not guiding
     */
    JSONArray routes(Object adapter) throws ReflectiveOperationException, JSONException {
        Object holder = guidanceHolder.get(state.get(adapter));
        Object current = holder == null ? null : guidance.invoke(null, holder);
        if (current == null) throw new IllegalStateException("Maps is not guiding");
        Object set = routeSet.invoke(current);
        Object guidedRoute = guided.invoke(set);
        List<JSONObject> routes = new ArrayList<>();
        for (Object route : (Iterable<?>) set) {
            JSONObject described = new JSONObject().put("id", id.get(route)).put("guided", route == guidedRoute);
            // Maps' own copy of java.time.Duration, with the same getSeconds().
            Object time = travelTime.invoke(route);
            if (time != null) described.put("minutes", Math.round(((Number) time.getClass().getMethod("getSeconds").invoke(time)).longValue() / 60.0));
            Object via = roads.get(route);
            if (via != null && !via.toString().isEmpty()) described.put("via", via.toString());
            if (route == guidedRoute) routes.add(0, described);
            else routes.add(described);
        }
        return new JSONArray(routes);
    }

    /** Asks Maps to guide along the route {@code routeId} of its route set, as tapping it does. */
    Future<?> switchTo(Object adapter, String routeId) throws ReflectiveOperationException {
        return (Future<?>) switchRoute.invoke(adapter, routeId);
    }

    private static Properties search(String apk, String adapter, int description, int via) throws NoSuchMethodException {
        System.loadLibrary("dexkit");
        try (DexKitBridge dex = DexKitBridge.create(apk)) {
            Properties names = new Properties();
            MethodData switchRoute = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(SWITCH_TRACE)
                    .declaredClass(adapter, StringMatchType.Equals, false).paramTypes("java.lang.String"))), SWITCH_TRACE);
            names.setProperty("switchRoute", switchRoute.getMethodName());

            // The route set Maps guides by, as the adapter's switch reads it.
            MethodData byId = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(NOT_GUIDING)
                    .declaredClass(adapter, StringMatchType.Equals, false).paramTypes("java.lang.String"))), NOT_GUIDING + " with an id");
            String state = null, stateType = null, holder = null;
            for (UsingFieldData using : byId.getUsingFields()) {
                if (using.getUsingType() != FieldUsingType.Read) continue;
                if (using.getField().getClassName().equals(adapter)) {
                    state = using.getField().getFieldName();
                    stateType = using.getField().getTypeName();
                }
            }
            for (UsingFieldData using : byId.getUsingFields()) {
                if (using.getUsingType() == FieldUsingType.Read && using.getField().getClassName().equals(stateType)) {
                    holder = using.getField().getFieldName();
                }
            }
            if (state == null || holder == null) throw new NoSuchMethodException("Maps' route switch reads no guidance state");
            MethodData guidance = null, routeSet = null;
            for (MethodData invoked : byId.getInvokes()) {
                if (Modifier.isStatic(invoked.getModifiers()) && invoked.getParamTypeNames().size() == 1) guidance = invoked;
            }
            if (guidance == null) throw new NoSuchMethodException("Maps' route switch calls no guidance getter");
            for (MethodData invoked : byId.getInvokes()) {
                if (invoked.getClassName().equals(guidance.getReturnTypeName()) && invoked.getParamTypeNames().isEmpty()) routeSet = invoked;
            }
            if (routeSet == null) throw new NoSuchMethodException("Maps' guidance has no route set getter");
            names.setProperty("state", state);
            names.setProperty("guidanceHolder", holder);
            names.setProperty("guidanceClass", guidance.getClassName());
            names.setProperty("guidance", guidance.getMethodName());
            names.setProperty("routeSet", routeSet.getMethodName());

            // The guided route, as the adapter's waypoint removal takes it from the route set.
            MethodData removal = only(dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(REMOVE_LOG)
                    .declaredClass(adapter, StringMatchType.Equals, false))), REMOVE_LOG);
            Set<String> guided = new HashSet<>();
            String route = null;
            for (MethodData invoked : removal.getInvokes()) {
                if (invoked.getClassName().equals(routeSet.getReturnTypeName()) && invoked.getParamTypeNames().isEmpty()
                        && !invoked.getReturnTypeName().equals("int")) {
                    guided.add(invoked.getMethodName());
                    route = invoked.getReturnTypeName();
                }
            }
            if (guided.size() != 1) throw new NoSuchMethodException("Maps' route set has " + guided + " as its guided route, not one");
            names.setProperty("guided", guided.iterator().next());

            // Each route's id, as the switch hands Maps the routes it leaves.
            Set<String> ids = new HashSet<>();
            for (MethodData method : dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(NOT_GUIDING)
                    .declaredClass(adapter, StringMatchType.Equals, false).paramTypes(routeSet.getReturnTypeName(), "java.lang.String")))) {
                for (UsingFieldData using : method.getUsingFields()) {
                    if (using.getUsingType() == FieldUsingType.Read && using.getField().getClassName().equals(route)
                            && using.getField().getTypeName().equals("java.lang.String")) {
                        ids.add(using.getField().getFieldName());
                    }
                }
            }
            if (ids.size() != 1) throw new NoSuchFieldException("Maps' routes have " + ids + " as their id, not one field");
            names.setProperty("id", ids.iterator().next());

            // Each route's travel time and roads, as Maps describes an alternative route.
            Set<String> times = new HashSet<>(), roads = new HashSet<>();
            for (MethodData describer : dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingNumbers(description)))) {
                for (MethodData invoked : describer.getInvokes()) {
                    if (invoked.getClassName().equals(route) && invoked.getParamTypeNames().isEmpty()
                            && invoked.getReturnTypeName().endsWith(".time.Duration")) {
                        times.add(invoked.getMethodName());
                    }
                }
                for (UsingFieldData using : describer.getUsingFields()) {
                    if (using.getUsingType() == FieldUsingType.Read && using.getField().getClassName().equals(route)
                            && using.getField().getTypeName().equals("java.lang.String")) {
                        roads.add(using.getField().getFieldName());
                    }
                }
            }
            Set<String> viaRoads = new HashSet<>();
            for (MethodData describer : dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingNumbers(via)))) {
                for (UsingFieldData using : describer.getUsingFields()) {
                    if (using.getUsingType() == FieldUsingType.Read && using.getField().getClassName().equals(route)) {
                        viaRoads.add(using.getField().getFieldName());
                    }
                }
            }
            roads.retainAll(viaRoads);
            if (times.size() != 1 || roads.size() != 1) {
                throw new NoSuchMethodException("Maps' description of an alternative route reads " + times + " as its time and "
                        + roads + " as its roads, not one each");
            }
            names.setProperty("travelTime", times.iterator().next());
            names.setProperty("roads", roads.iterator().next());
            return names;
        } catch (NoSuchFieldException missing) {
            throw new NoSuchMethodException(missing.getMessage());
        }
    }

    private static MethodData only(List<MethodData> found, String what) throws NoSuchMethodException {
        if (found.size() != 1) throw new NoSuchMethodException("Maps has " + found.size() + " of " + what + ", not one");
        return found.get(0);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
}
