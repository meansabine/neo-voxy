package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.api.distmarker.Dist;

public class VoxyCommon {
    public static String MOD_VERSION = "UNKNOWN";
    public static boolean IS_DEDICATED_SERVER = false;
    public static boolean IS_IN_MINECRAFT = false;

    public static void cleanInit(String version, boolean isDedicatedServer) {
        IS_IN_MINECRAFT = true;
        MOD_VERSION = version;
        IS_DEDICATED_SERVER = isDedicatedServer;
        Serialization.init();
    }

    // This is hardcoded like this because people do not understand what they are
    // doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy." + name, defaultOn ? "true" : "false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {
        VoxyInstance create();
    }

    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            return;
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null; // Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        try {
            INSTANCE = FACTORY.create();
        } catch (DontCreateInstance e) {
            Logger.info("Not creating instance due to DontCreateInstance");
        }
    }

    // Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}