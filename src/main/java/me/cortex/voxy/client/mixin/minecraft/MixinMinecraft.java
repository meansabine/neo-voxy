package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Shadow
    public net.minecraft.client.renderer.LevelRenderer levelRenderer;

    @Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void voxy$injectClearClientLevel(Screen screen, CallbackInfo ci) {
        if (this.levelRenderer instanceof IVoxyRenderSystemHolder holder) {
            holder.voxy$shutdownRenderer();
        }
        if (VoxyCommon.isAvailable() && VoxyClientInstance.isInGame) {
            VoxyClientInstance.isInGame = false;
            VoxyCommon.shutdownInstance();
        }
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void voxy$injectDestroy(CallbackInfo ci) {
        if (this.levelRenderer instanceof IVoxyRenderSystemHolder holder) {
            holder.voxy$shutdownRenderer();
        }
        if (VoxyCommon.isAvailable() && VoxyCommon.getInstance() != null) {
            VoxyClientInstance.isInGame = false;
            VoxyCommon.shutdownInstance();
        }
    }
}
