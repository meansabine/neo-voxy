package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IVoxyRenderSystemHolder, IGetVoxyRenderSystem {
    @Shadow
    private @Nullable ClientLevel level;

    @Unique
    @Nullable
    private WorldIdentifier identifier;

    @Unique
    private @Nullable VoxyRenderSystem renderer;

    @Override
    public VoxyRenderSystem voxy$getRenderSystem() {
        if (this.renderer == null && this.level != null) {
            this.voxy$createRenderer();
        }
        return this.renderer;
    }

    @Override
    public VoxyRenderSystem getVoxyRenderSystem() {
        return this.voxy$getRenderSystem();
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)
    private void reloadVoxyRenderer(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
        if (this.level != null) {
            this.voxy$setWorld(this.level);
            this.voxy$createRenderer();
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        this.voxy$setWorld(world);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$injectClose(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
    }

    @Override
    public void voxy$shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void voxy$setWorld(Level level) {
        WorldIdentifier identifier = level == null ? null : WorldIdentifier.of(level);
        if (Objects.equals(this.identifier, identifier)) return;
        this.voxy$shutdownRenderer();
        this.identifier = identifier;
    }

    @Override
    public void voxy$createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.identifier == null && this.level != null) {
            this.identifier = WorldIdentifier.of(this.level);
        }
        if (this.identifier == null) {
            Logger.info("Not creating renderer due to null identifier");
            return;
        }
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            Logger.info("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = this.identifier.getOrCreateEngine(true);
        if (world == null) {
            Logger.warn("Not creating renderer due to null engine");
            return;
        }
        this.voxy$createEngineDirect(world);
    }

    @Unique
    private void voxy$createEngineDirect(WorldEngine world) {
        var instance = world.instanceIn;
        if (instance == null) throw new IllegalStateException();
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            Logger.error("Error creating render system", e);
            if (this.renderer != null) {
                this.renderer.shutdown();
                this.renderer = null;
            }
        }
    }
}
