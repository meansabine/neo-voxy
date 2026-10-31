package me.cortex.voxy.client.mixin.colorwheel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

@Mixin(targets = "dev.djefrey.colorwheel.ClrwlBackend", remap = false)
public class MixinClrwlBackend {
    @Inject(method = "isUsingCompatibleShaderPack", at = @At("HEAD"), cancellable = true)
    private static void voxy$fixClassCastCrash(CallbackInfoReturnable<Boolean> cir) {
        try {
            var pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(pipeline instanceof IrisRenderingPipeline)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            cir.setReturnValue(false);
        }
    }
}
