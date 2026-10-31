package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VisibleChunkCollector.class, remap = false)
public class MixinVisibleChunkCollector {
    @Inject(method = "visit", at = @At("HEAD"))
    private void voxy$injectVisibleSectionGather(RenderSection section, CallbackInfo ci) {
        VoxyRenderSystem vrs;
        if (!IrisUtil.irisShadowActive() && section != null && section.isBuilt() && (vrs = IVoxyRenderSystemHolder.getNullable()) != null && vrs.visbleSectionStream != null) {
            vrs.visbleSectionStream.put(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
        }
    }
}
