package com.dripps.voxy.locator;

import java.lang.reflect.Method;

/**
 * Robust logger that routes to SLF4J when available in the Minecraft NeoForge runtime,
 * and falls back gracefully to standard output/error during tests or build tasks.
 */
public class VoxyLog {
    private static Object slf4jLogger = null;
    private static Method infoMethod = null;
    private static Method warnMethod = null;
    private static Method errorMethod = null;
    private static Method errorThrowableMethod = null;

    static {
        try {
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getLogger = loggerFactoryClass.getMethod("getLogger", String.class);
            slf4jLogger = getLogger.invoke(null, "NeoVoxy");
            Class<?> loggerClass = Class.forName("org.slf4j.Logger");
            infoMethod = loggerClass.getMethod("info", String.class, Object[].class);
            warnMethod = loggerClass.getMethod("warn", String.class, Object[].class);
            errorMethod = loggerClass.getMethod("error", String.class, Object[].class);
            errorThrowableMethod = loggerClass.getMethod("error", String.class, Throwable.class);
        } catch (Throwable ignored) {
            slf4jLogger = null;
        }
    }

    public static void info(String message, Object... args) {
        if (slf4jLogger != null && infoMethod != null) {
            try {
                infoMethod.invoke(slf4jLogger, message, args);
                return;
            } catch (Throwable ignored) {}
        }
        System.out.println(format("[NeoVoxy:INFO] " + message, args));
    }

    public static void warn(String message, Object... args) {
        if (slf4jLogger != null && warnMethod != null) {
            try {
                warnMethod.invoke(slf4jLogger, message, args);
                return;
            } catch (Throwable ignored) {}
        }
        System.out.println(format("[NeoVoxy:WARN] " + message, args));
    }

    public static void error(String message, Throwable t) {
        if (slf4jLogger != null && errorThrowableMethod != null) {
            try {
                errorThrowableMethod.invoke(slf4jLogger, message, t);
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println("[NeoVoxy:ERROR] " + message);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    public static void error(String message, Object... args) {
        if (slf4jLogger != null && errorMethod != null) {
            try {
                errorMethod.invoke(slf4jLogger, message, args);
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println(format("[NeoVoxy:ERROR] " + message, args));
    }

    private static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) return pattern;
        String result = pattern;
        for (Object arg : args) {
            int idx = result.indexOf("{}");
            if (idx != -1) {
                result = result.substring(0, idx) + String.valueOf(arg) + result.substring(idx + 2);
            }
        }
        return result;
    }
}
