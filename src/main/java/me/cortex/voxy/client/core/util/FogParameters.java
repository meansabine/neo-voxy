package me.cortex.voxy.client.core.util;

import com.mojang.blaze3d.systems.RenderSystem;

public record FogParameters(float environmentalStart, float environmentalEnd, float red, float green, float blue, float alpha) {
    public static FogParameters capture() {
        float[] color = RenderSystem.getShaderFogColor();
        if (color == null || color.length < 4) {
            color = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        }
        return new FogParameters(
                RenderSystem.getShaderFogStart(),
                RenderSystem.getShaderFogEnd(),
                color[0], color[1], color[2], color[3]
        );
    }
}
