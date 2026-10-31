package me.cortex.voxy.client.core;

import net.minecraft.client.Minecraft;

public interface IGetVoxyRenderSystem extends IVoxyRenderSystemHolder {
    default VoxyRenderSystem getVoxyRenderSystem() {
        return voxy$getRenderSystem();
    }

    default void shutdownRenderer() {
        voxy$shutdownRenderer();
    }

    default void createRenderer() {
        voxy$createRenderer();
    }

    static VoxyRenderSystem getNullable() {
        return IVoxyRenderSystemHolder.getNullable();
    }
}
