package me.cortex.voxy.client.mixin.nvidium;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.FogParameters;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glGetIntegerv;

@Mixin(targets = "me.cortex.nvidium.RenderPipeline", remap = false)
public class MixinRenderPipeline {
    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void voxy$injectRender(net.caffeinemc.mods.sodium.client.render.viewport.Viewport frustum, ChunkRenderMatrices crm, double px, double py, double pz, CallbackInfo ci) {
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer != null) {
            Viewport<?> viewport = null;
            if (IrisUtil.USED_IRIS_VIEWPORT) {
                viewport = renderer.getViewport();
                IrisUtil.USED_IRIS_VIEWPORT = false;
            } else {
                int[] dims = new int[4];
                glGetIntegerv(GL_VIEWPORT, dims);
                viewport = renderer.setupViewport(crm.projection(), crm.modelView(), FogParameters.capture(), dims[2], dims[3], px, py, pz);
            }
            var mc = Minecraft.getInstance();
            var mainTarget = mc.getMainRenderTarget();
            renderer.renderOpaque(viewport, mainTarget.getDepthTextureId(), mainTarget.getColorTextureId());
        }
    }
}
