package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.util.HashSet;

public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();

    public static void initVoxyClient() {
        Capabilities.init(); // Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.warn("AMD broken depth sampler detected, depth sampling fallback will be used");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters;
        if (systemSupported) {
            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }
        } else {
            Logger.error("Voxy is unsupported on your system.");
        }
    }

    public static void onClientSetup() {
        // Called from Voxy mod entrypoint during client setup
        // Command registration is handled via NeoForge events in the main mod class
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;
    }
}
