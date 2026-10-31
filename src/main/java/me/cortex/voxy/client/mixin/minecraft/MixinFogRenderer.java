package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FogRenderer.class, priority = 900)
public class MixinFogRenderer {
    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void voxy$overrideFog(
        Camera camera,
        FogMode fogMode,
        float viewDistance,
        boolean thickFog,
        float tickDelta,
        CallbackInfo ci
    ) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) return;

        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) return;

        var mode = VoxyConfig.CONFIG.getFogMode();
        float envEnd = RenderSystem.getShaderFogEnd();
        boolean fogIsDamnClose = envEnd < 10;
        if (mode.removesVanillaEnvFog && !fogIsDamnClose) {
            RenderSystem.setShaderFogStart(999999999);
            RenderSystem.setShaderFogEnd(999999999);
        } else if (!mode.removesVanillaEnvFog) {
            if (fogMode == FogMode.FOG_TERRAIN) {
                RenderSystem.setShaderFogStart(999999999);
                RenderSystem.setShaderFogEnd(999999999);
            }
        }
    }
}
