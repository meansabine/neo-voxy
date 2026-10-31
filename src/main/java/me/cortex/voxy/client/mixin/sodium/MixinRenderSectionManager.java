package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Unique
    private static boolean voxy$isBobbyInstalled() {
        try {
            return ModList.get() != null && ModList.get().isLoaded("bobby");
        } catch (Throwable t) {
            return false;
        }
    }

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    @Final
    private ChunkBuilder builder;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, CommandList commandList,
            CallbackInfo ci) {
        this.bottomSectionY = this.level.getMinSection();
    }

    @Inject(method = "createTerrainRenderList", at = @At("HEAD"))
    private void voxy$resetVisibleSections(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null && !IrisUtil.irisShadowActive() && vrs.visbleSectionStream != null) {
            vrs.visbleSectionStream.reset();
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngest(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && !voxy$isBobbyInstalled()) {
            var cccm = (ICheekyClientChunkCache) this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                    Logger.debug("[Client] Chunk " + x + "," + z + " removed");
                }
            }
        }
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (this.level.levelRenderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            var cccm = this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    var ch = this.level.getChunk(x, z);
                    if (ch instanceof net.minecraft.world.level.chunk.LevelChunk && !(ch instanceof net.minecraft.world.level.chunk.EmptyLevelChunk)) {
                        chunk = ch;
                    }
                }
                if (chunk != null) {
                    boolean result = VoxelIngestService.tryAutoIngestChunk(chunk);
                    if (!result) {
                        Logger.debug("[Client] Chunk " + x + "," + z + " ingest returned false (likely missing lighting)");
                    } else {
                        Logger.debug("[Client] Chunk " + x + "," + z + " added and ingested");
                    }
                }
            }
        }
    }

    @Unique
    private long cachedChunkPos = -1;
    @Unique
    private int cachedChunkStatus;
    @Unique
    private int bottomSectionY;

    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)Z"))
    private boolean voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean wasBuilt = instance.getFlags() != 0;
        if (!instance.setInfo(info)) {
            return false;
        }
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (wasBuilt == (instance.getFlags() != 0) || vrs == null) {
            return true;
        }
        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();

        if (wasBuilt && VoxyConfig.CONFIG.ingestEnabled) {
            var tracker = ((AccessorChunkTracker) ChunkTrackerHolder.get(this.level)).getChunkStatus();
            long key = ChunkPos.asLong(x, z);
            if (key != this.cachedChunkPos) {
                this.cachedChunkPos = key;
                this.cachedChunkStatus = tracker.getOrDefault(key, 0);
            }
            if (this.cachedChunkStatus == 3) {
                var cccm = this.level.getChunkSource();
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    var ch = this.level.getChunk(x, z);
                    if (ch instanceof net.minecraft.world.level.chunk.LevelChunk && !(ch instanceof net.minecraft.world.level.chunk.EmptyLevelChunk)) {
                        chunk = ch;
                    }
                }
                if (chunk != null) {
                    var section = chunk.getSection(y - this.bottomSectionY);
                    var lp = this.level.getLightEngine();

                    var csp = SectionPos.of(x, y, z);
                    var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                    var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);

                    VoxelIngestService.rawIngest(vrs.getEngine(), section, x, y, z, blp == null ? null : blp.copy(),
                            slp == null ? null : slp.copy());
                }
            }
        }

        return true;
    }
}
