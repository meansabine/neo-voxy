package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
    @Shadow
    volatile ClientChunkCache.Storage storage;

    @Override
    public @Nullable LevelChunk voxy$cheekyGetChunk(int x, int z) {
        var chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk == null) {
            return null;
        }
        if (chunk.getPos().x == x && chunk.getPos().z == z) {
            return chunk;
        }
        return null;
    }

    /**
     * Ingest chunks into LOD system when they are received/loaded on the client.
     * This is the primary path for client-side LOD generation.
     */
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    public void voxy$captureChunkOnLoad(int x, int z,
            net.minecraft.network.FriendlyByteBuf buf,
            net.minecraft.nbt.CompoundTag tag,
            java.util.function.Consumer<?> consumer,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (VoxyConfig.CONFIG.ingestEnabled) {
            var chunk = cir.getReturnValue();
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }

    /**
     * Also capture chunks before they are unloaded as a fallback.
     */
    @Inject(method = "drop", at = @At("HEAD"))
    public void voxy$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled) {
            var chunk = this.voxy$cheekyGetChunk(pos.x, pos.z);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }
}
