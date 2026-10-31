package me.cortex.voxy.common.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(targets = "org.popcraft.chunky.platform.NeoForgeWorld")
public class MixinNeoForgeWorld {
    @WrapOperation(
            method = "getChunkAtAsync",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
                    remap = false
            ),
            remap = false
    )
    private CompletableFuture<ChunkResult<ChunkAccess>> voxy$captureGeneratedChunk(
            ServerChunkCache instance,
            int i,
            int j,
            ChunkStatus chunkStatus,
            boolean b,
            Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
        var future = original.call(instance, i, j, chunkStatus, b);
        return future.thenApply(res -> {
            res.ifSuccess(chunk -> {
                if (chunk instanceof LevelChunk worldChunk) {
                    VoxelIngestService.tryAutoIngestChunk(worldChunk);
                }
            });
            return res;
        });
    }
}
