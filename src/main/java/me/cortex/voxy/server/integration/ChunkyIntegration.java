package me.cortex.voxy.server.integration;

import me.cortex.voxy.common.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Soft dependency integration with Chunky pre-generation mod.
 * Uses reflection to avoid compile-time dependency.
 */
public class ChunkyIntegration {
    private static Boolean chunkyLoaded = null;
    private static Object chunkyInstance = null;
    private static Method getGenerationTasks = null;
    private static Method getApi = null;

    /**
     * Check if Chunky mod is loaded (cached).
     */
    public static boolean isChunkyLoaded() {
        if (chunkyLoaded == null) {
            try {
                Class.forName("org.popcraft.chunky.ChunkyProvider");
                chunkyLoaded = true;
                Logger.info("Chunky mod detected - integration available");
            } catch (ClassNotFoundException e) {
                chunkyLoaded = false;
            }
        }
        return chunkyLoaded;
    }

    /**
     * Get the Chunky instance via reflection.
     */
    private static Object getChunky() {
        if (!isChunkyLoaded())
            return null;
        if (chunkyInstance != null)
            return chunkyInstance;

        try {
            Class<?> providerClass = Class.forName("org.popcraft.chunky.ChunkyProvider");
            Method getMethod = providerClass.getMethod("get");
            chunkyInstance = getMethod.invoke(null);

            // Cache method references
            if (chunkyInstance != null) {
                getGenerationTasks = chunkyInstance.getClass().getMethod("getGenerationTasks");
                getApi = chunkyInstance.getClass().getMethod("getApi");
            }
            return chunkyInstance;
        } catch (Exception e) {
            // Chunky not initialized yet
            return null;
        }
    }

    /**
     * Check if any generation task is currently running.
     */
    public static boolean isAnyGenerationRunning() {
        if (!isChunkyLoaded())
            return false;

        try {
            Object chunky = getChunky();
            if (chunky == null || getGenerationTasks == null)
                return false;

            Map<?, ?> tasks = (Map<?, ?>) getGenerationTasks.invoke(chunky);
            return tasks != null && !tasks.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if generation is running for a specific world.
     */
    public static boolean isGenerationRunning(String worldName) {
        if (!isChunkyLoaded())
            return false;

        try {
            Object chunky = getChunky();
            if (chunky == null || getGenerationTasks == null)
                return false;

            Map<?, ?> tasks = (Map<?, ?>) getGenerationTasks.invoke(chunky);
            return tasks != null && tasks.containsKey(worldName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Register a completion listener for when Chunky finishes generation.
     * Uses Chunky's event API if available.
     */
    @SuppressWarnings("unchecked")
    public static void registerCompletionListener(Runnable onComplete) {
        if (!isChunkyLoaded())
            return;

        try {
            Object chunky = getChunky();
            if (chunky == null || getApi == null)
                return;

            Object api = getApi.invoke(chunky);
            if (api == null)
                return;

            // Get the onGenerationComplete method
            Method onGenerationComplete = api.getClass().getMethod("onGenerationComplete", Consumer.class);

            // Create a consumer that calls our runnable
            Consumer<Object> listener = event -> {
                Logger.info("Chunky generation complete - triggering deferred chunk processing");
                onComplete.run();
            };

            onGenerationComplete.invoke(api, listener);
            Logger.info("Registered Chunky completion listener");
        } catch (Exception e) {
            Logger.warn("Failed to register Chunky completion listener: " + e.getMessage());
        }
    }

    /**
     * Check if we should defer processing because Chunky is active.
     */
    public static boolean shouldDeferProcessing() {
        return isChunkyLoaded() && isAnyGenerationRunning();
    }
}
