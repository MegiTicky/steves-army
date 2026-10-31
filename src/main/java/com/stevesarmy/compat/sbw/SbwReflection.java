package com.stevesarmy.compat.sbw;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version-tolerant reflective access to Superb Warfare without compile-time imports.
 */
public final class SbwReflection {

    private static final Object MISS = new Object();

    private static final Map<String, Object> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, Object> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Object> ARG_METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Object> FIELDS = new ConcurrentHashMap<>();

    private SbwReflection() {
    }

    public static Class<?> cls(String name) {
        Object cached = CLASSES.computeIfAbsent(name, n -> {
            try {
                return Class.forName(n);
            } catch (Throwable t) {
                return MISS;
            }
        });
        return cached == MISS ? null : (Class<?>) cached;
    }

    public static Method method(Class<?> owner, int argCount, String... names) {
        if (owner == null) return null;
        String key = owner.getName() + "#" + argCount + "#" + String.join(",", names);
        Object cached = METHODS.computeIfAbsent(key, k -> {
            for (String name : names) {
                for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals(name) && m.getParameterCount() == argCount) {
                            try {
                                m.setAccessible(true);
                                return m;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
            return MISS;
        });
        return cached == MISS ? null : (Method) cached;
    }

    public static Field field(Class<?> owner, String... names) {
        if (owner == null) return null;
        String key = owner.getName() + "$" + String.join(",", names);
        Object cached = FIELDS.computeIfAbsent(key, k -> {
            for (String name : names) {
                for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        return f;
                    } catch (Throwable ignored) {
                    }
                }
            }
            return MISS;
        });
        return cached == MISS ? null : (Field) cached;
    }

    public static Object call(Object target, String[] names, Object... args) {
        if (target == null) return null;
        Method m = methodForArguments(target.getClass(), args, false, names);
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object callStatic(Class<?> owner, String[] names, Object... args) {
        Method m = methodForArguments(owner, args, true, names);
        if (m == null) return null;
        try {
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invoke a possibly void method, reporting whether a compatible overload ran. */
    public static boolean invoke(Object target, String[] names, Object... args) {
        if (target == null) return false;
        Method m = methodForArguments(target.getClass(), args, false, names);
        if (m == null) return false;
        try {
            m.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method methodForArguments(Class<?> owner, Object[] args,
                                             boolean requireStatic, String... names) {
        if (owner == null) return null;
        StringBuilder key = new StringBuilder(owner.getName())
            .append(requireStatic ? "#static#" : "#instance#");
        for (String name : names) key.append(name).append(',');
        key.append('(');
        for (Object arg : args) {
            key.append(arg == null ? "null" : arg.getClass().getName()).append(',');
        }
        key.append(')');
        Object cached = ARG_METHODS.computeIfAbsent(key.toString(), ignored -> {
            Method found = findMethodForArguments(owner, args, requireStatic, names);
            return found == null ? MISS : found;
        });
        return cached == MISS ? null : (Method) cached;
    }

    private static Method findMethodForArguments(Class<?> owner, Object[] args,
                                                 boolean requireStatic, String... names) {
        for (String name : names) {
            Method best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                for (Method candidate : c.getDeclaredMethods()) {
                    if (!candidate.getName().equals(name)
                        || candidate.getParameterCount() != args.length
                        || requireStatic != Modifier.isStatic(candidate.getModifiers())) {
                        continue;
                    }
                    Class<?>[] parameterTypes = candidate.getParameterTypes();
                    int score = 0;
                    boolean compatible = true;
                    for (int i = 0; i < parameterTypes.length; i++) {
                        Class<?> parameter = boxed(parameterTypes[i]);
                        Object argument = args[i];
                        if (argument == null) {
                            if (parameterTypes[i].isPrimitive()) {
                                compatible = false;
                                break;
                            }
                        } else if (parameter == argument.getClass()) {
                            score += 4;
                        } else if (parameter.isAssignableFrom(argument.getClass())) {
                            score += 2;
                        } else {
                            compatible = false;
                            break;
                        }
                    }
                    if (!compatible || score <= bestScore) continue;
                    try {
                        candidate.setAccessible(true);
                        best = candidate;
                        bestScore = score;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    public static Object get(Object target, String... names) {
        if (target == null) return null;
        Object viaMethod = call(target, names);
        if (viaMethod != null) return viaMethod;
        Method m = method(target.getClass(), 0, names);
        if (m != null) return null;
        Field f = field(target.getClass(), names);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean set(Object target, Object value, String... names) {
        if (target == null) return false;
        String[] setters = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            setters[i] = "set" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }
        Method m = methodForArguments(target.getClass(), new Object[]{value}, false, setters);
        if (m != null) {
            try {
                m.invoke(target, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        Field f = field(target.getClass(), names);
        if (f == null) return false;
        try {
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean asBool(Object o, boolean fallback) {
        return o instanceof Boolean b ? b : fallback;
    }

    public static int asInt(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    public static float asFloat(Object o, float fallback) {
        return o instanceof Number n ? n.floatValue() : fallback;
    }

    public static double asDouble(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    public static String asString(Object o, String fallback) {
        return o instanceof String s ? s : fallback;
    }
}
