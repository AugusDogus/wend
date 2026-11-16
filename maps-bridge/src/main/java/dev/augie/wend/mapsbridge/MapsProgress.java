package dev.augie.wend.mapsbridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldUsingType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/**
 * How far Maps' guidance has come, as Maps itself tracks it: the step ahead, the meters and
 * seconds to it, the meters and time left to the next destination, whether the car is on the
 * route, and whether the route is done.
 *
 * <p>Maps' code is obfuscated, so what this reads is found by what it does (DexKit), once per
 * Maps build: Maps' guidance has one method without parameters that returns its progress, a class
 * whose toString names its fields as it reads them ({@value #STEP}, {@value #TO_STEP}, ...): the
 * progress's own fields it reads, in order, pair with those names, in order.
 */
final class MapsProgress {
    static final String ROUTE = "route", STEP = "curStep", TO_STEP = "metersToNextStep", SECONDS_TO_STEP = "secondsToNextStep",
            REMAINING = "metersRemainingToNextDestination", TIME_REMAINING = "combinedSecondsRemainingToNextDestination",
            ON_ROUTE = "isOnRoute", DONE = "routeCompletedSuccessfully";
    private static final String[] LABELS = {ROUTE, STEP, TO_STEP, SECONDS_TO_STEP, REMAINING, TIME_REMAINING, ON_ROUTE, DONE};

    /** One moment of Maps' guidance. Each count is -1 when Maps does not know it. */
    static final class Snapshot {
        /** Maps' route and the step ahead on it; the step is null while Maps reroutes. */
        final Object route, step;
        final int metersToStep, secondsToStep, metersRemaining, secondsRemaining;
        final boolean onRoute, done;

        Snapshot(Object route, Object step, int metersToStep, int secondsToStep, int metersRemaining, int secondsRemaining,
                boolean onRoute, boolean done) {
            this.route = route;
            this.step = step;
            this.metersToStep = metersToStep;
            this.secondsToStep = secondsToStep;
            this.metersRemaining = metersRemaining;
            this.secondsRemaining = secondsRemaining;
            this.onRoute = onRoute;
            this.done = done;
        }
    }

    private final Method progress;
    private final Field route, step, toStep, secondsToStep, remaining, timeRemaining, onRoute, done;

    private MapsProgress(Method progress, Field route, Field step, Field toStep, Field secondsToStep, Field remaining,
            Field timeRemaining, Field onRoute, Field done) {
        this.progress = progress;
        this.route = route;
        this.step = step;
        this.toStep = toStep;
        this.secondsToStep = secondsToStep;
        this.remaining = remaining;
        this.timeRemaining = timeRemaining;
        this.onRoute = onRoute;
        this.done = done;
    }

    /** @param names what {@link #search} found, kept with the other names of this Maps build */
    static MapsProgress from(Class<?> guidance, Properties names) throws ReflectiveOperationException {
        Method progress = guidance.getDeclaredMethod(names.getProperty("progress"));
        progress.setAccessible(true);
        Class<?> type = progress.getReturnType();
        return new MapsProgress(progress, field(type, names.getProperty(ROUTE), null), field(type, names.getProperty(STEP), null),
                field(type, names.getProperty(TO_STEP), int.class), field(type, names.getProperty(SECONDS_TO_STEP), int.class),
                field(type, names.getProperty(REMAINING), int.class), field(type, names.getProperty(TIME_REMAINING), null),
                field(type, names.getProperty(ON_ROUTE), boolean.class), field(type, names.getProperty(DONE), boolean.class));
    }

    /** @return the class of the step ahead, as Maps' progress holds it */
    Class<?> stepType() { return step.getType(); }

    /** @return the class of Maps' route, as Maps' progress holds it */
    Class<?> routeType() { return route.getType(); }

    /** @return how far {@code guidance} has come, or null if it has no progress yet */
    Snapshot of(Object guidance) throws ReflectiveOperationException {
        Object now = progress.invoke(guidance);
        if (now == null) return null;
        return new Snapshot(route.get(now), step.get(now), toStep.getInt(now), secondsToStep.getInt(now), remaining.getInt(now),
                seconds(timeRemaining.get(now)), onRoute.getBoolean(now), done.getBoolean(now));
    }

    /**
     * @param time Maps' time left, which gives Maps' own copy of java.time.Duration from its one
     *     method without parameters that returns one
     * @return its seconds, or -1 if there is none
     */
    private static int seconds(Object time) throws ReflectiveOperationException {
        if (time == null) return -1;
        for (Method method : time.getClass().getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0 || !method.getReturnType().getName().endsWith(".time.Duration")) continue;
            method.setAccessible(true);
            Object duration = method.invoke(time);
            if (duration == null) return -1;
            return (int) ((Number) duration.getClass().getMethod("getSeconds").invoke(duration)).longValue();
        }
        throw new NoSuchMethodException("Maps' time left " + time.getClass().getName() + " gives no Duration");
    }

    /** Puts what it finds for {@code guidance} into {@code names}. */
    static void search(DexKitBridge dex, Class<?> guidance, Properties names) throws NoSuchMethodException {
        MethodData described = null;
        String getter = null;
        for (MethodData toString : dex.findMethod(FindMethod.create().matcher(MethodMatcher.create().name("toString")
                .usingStrings(STEP, TO_STEP, SECONDS_TO_STEP, REMAINING, ON_ROUTE)))) {
            for (Method method : guidance.getDeclaredMethods()) {
                if (method.getParameterTypes().length == 0 && method.getReturnType().getName().equals(toString.getClassName())) {
                    if (getter != null) throw new NoSuchMethodException("Maps' guidance has more than one progress getter");
                    getter = method.getName();
                    described = toString;
                }
            }
        }
        if (described == null) throw new NoSuchMethodException("Maps' guidance " + guidance.getName() + " has no getter of a progress that names " + TO_STEP);
        String type = described.getClassName();
        List<String> fields = new ArrayList<>();
        for (UsingFieldData using : described.getUsingFields()) {
            if (using.getUsingType() == FieldUsingType.Read && using.getField().getClassName().equals(type)) {
                fields.add(using.getField().getFieldName());
            }
        }
        String simpleName = type.substring(type.lastIndexOf('.') + 1);
        List<String> labels = new ArrayList<>();
        for (String label : described.getUsingStrings()) if (!label.equals(simpleName)) labels.add(label);
        if (fields.size() != labels.size()) throw new NoSuchMethodException("Maps' progress " + type + " names " + labels + " but reads " + fields);
        names.setProperty("progress", getter);
        for (String label : LABELS) {
            int at = labels.indexOf(label);
            if (at < 0) throw new NoSuchMethodException("Maps' progress " + type + " names no " + label + ": " + labels);
            names.setProperty(label, fields.get(at));
        }
    }

    /** @param expected the field's type, or null for any */
    private static Field field(Class<?> type, String name, Class<?> expected) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        if (expected != null && field.getType() != expected) {
            throw new NoSuchFieldException("Maps' progress field " + type.getName() + "." + name + " is a " + field.getType() + ", not a " + expected);
        }
        field.setAccessible(true);
        return field;
    }
}
