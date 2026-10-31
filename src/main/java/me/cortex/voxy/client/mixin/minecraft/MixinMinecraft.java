package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void voxy$injectWorldCloseScreen(Screen screen, boolean bl, CallbackInfo ci) {
        if (VoxyCommon.isAvailable() && VoxyClientInstance.isInGame) {
            VoxyCommon.shutdownInstance();
            VoxyClientInstance.isInGame = false;
        }
    }

    @Inject(method = "disconnect()V", at = @At("HEAD"))
    private void voxy$injectWorldClose(CallbackInfo ci) {
        if (VoxyCommon.isAvailable() && VoxyClientInstance.isInGame) {
            VoxyCommon.shutdownInstance();
            VoxyClientInstance.isInGame = false;
        }
    }

    @Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void voxy$injectClearClientLevel(Screen screen, CallbackInfo ci) {
        if (VoxyCommon.isAvailable() && VoxyClientInstance.isInGame) {
            VoxyCommon.shutdownInstance();
            VoxyClientInstance.isInGame = false;
        }
    }
}
