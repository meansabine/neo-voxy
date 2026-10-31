package me.cortex.voxy.client.core.model;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.other.Mapper;
import net.caffeinemc.mods.sodium.client.model.color.interop.BlockColorsExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

import static me.cortex.voxy.client.core.model.ModelStore.MODEL_SIZE;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglTextureSubImage2D;
import static org.lwjgl.opengl.GL11.*;

public class ModelFactory {
    public static final int MODEL_TEXTURE_SIZE = 16;
    public static final int LAYERS = Integer.numberOfTrailingZeros(MODEL_TEXTURE_SIZE);

    private record ModelEntry(ColourDepthTextureData down, ColourDepthTextureData up, ColourDepthTextureData north, ColourDepthTextureData south, ColourDepthTextureData west, ColourDepthTextureData east, int fluidBlockStateId, int tintingColour) {
        public ModelEntry(ColourDepthTextureData[] textures, int fluidBlockStateId, int tintingColour) {
            this(textures[0], textures[1], textures[2], textures[3], textures[4], textures[5], fluidBlockStateId, tintingColour);
        }
    }

    private final Biome DEFAULT_BIOME = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value();

    public final SoftwareModelTextureBakery bakery2;
    private final long bakeScratchBuffer = MemoryUtil.nmemAlloc(MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE * 8 * 6);

    private final long[] metadataCache;
    private final int[] fluidStateLUT;

    private final int[] idMappings;
    private final Object2IntOpenHashMap<ModelEntry> modelTexture2id = new Object2IntOpenHashMap<>();

    private final IntOpenHashSet blockStatesInFlight = new IntOpenHashSet();
    private final ReentrantLock blockStatesInFlightLock = new ReentrantLock();

    private final List<Biome> biomes = new ArrayList<>();
    private record ModelBlockStatePair(int model, BlockState state) {}
    private final List<ModelBlockStatePair> modelsRequiringBiomeColours = new ArrayList<>();

    private final Mapper mapper;
    private final ModelStore storage;

    private final ConcurrentLinkedDeque<BlockBake> bakeQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<ResultUploader> uploadResults = new ConcurrentLinkedDeque<>();

    private Object2IntMap<BlockState> customBlockStateIdMapping;
    private final boolean rasterUV;

    public ModelFactory(Mapper mapper, ModelStore storage) {
        this.mapper = mapper;
        this.storage = storage;
        this.bakery2 = new SoftwareModelTextureBakery();
        this.bakery2.setupTexture();

        this.rasterUV = false;

        this.metadataCache = new long[1 << 16];
        this.fluidStateLUT = new int[1 << 16];
        this.idMappings = new int[1 << 20];
        Arrays.fill(this.idMappings, -1);
        Arrays.fill(this.fluidStateLUT, -1);

        this.modelTexture2id.defaultReturnValue(-1);
        this.addEntry(0);
    }

    public void setCustomBlockStateMapping(Object2IntMap<BlockState> mapping) {
        this.customBlockStateIdMapping = mapping;
    }

    private record BlockBake(int blockId, BlockState state) {}

    public boolean addEntry(int blockId) {
        if (this.idMappings[blockId] != -1) {
            return false;
        }

        var blockState = this.mapper.getBlockStateFromBlockId(blockId);
        if (blockState.getBlock() instanceof StairBlock sb) {
            blockState = ((me.cortex.voxy.client.mixin.minecraft.StairBlockAccessor) sb).getBaseState().getBlock().withPropertiesOf(blockState);
        }

        boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
        if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
            var fluidState = blockState.getFluidState().createLegacyBlock();
            int fluidStateId = this.mapper.getIdForBlockState(fluidState);
            if (this.idMappings[fluidStateId] == -1) {
                addEntry(fluidStateId);
            }
        }

        this.blockStatesInFlightLock.lock();
        try {
            if (!this.blockStatesInFlight.add(blockId)) {
                return false;
            }

            VarHandle.loadLoadFence();

            if (this.idMappings[blockId] != -1) {
                return false;
            }
            this.bakeQueue.add(new BlockBake(blockId, blockState));
            return true;
        } finally {
            this.blockStatesInFlightLock.unlock();
        }
    }

    private boolean processModelResult() {
        var bake = this.bakeQueue.poll();
        if (bake == null) return false;
        ColourDepthTextureData[] textureData = new ColourDepthTextureData[6];

        int flags = this.bakery2.renderToOutput(bake.state, this.bakeScratchBuffer, this.rasterUV);

        {
            long ptr = this.bakeScratchBuffer;
            final int FACE_SIZE = MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
            for (int face = 0; face < 6; face++) {
                long faceDataPtr = ptr + (FACE_SIZE * 4L) * face * 2L;
                int[] colour = new int[FACE_SIZE];
                int[] depth = new int[FACE_SIZE];

                for (int i = 0; i < FACE_SIZE; i++) {
                    long value = MemoryUtil.memGetLong(faceDataPtr + i * 8L);
                    colour[i] = (int) value;
                    depth[i] = (int) (value >>> 32);
                }
                textureData[face] = new ColourDepthTextureData(colour, depth, MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE);
            }
        }

        boolean hasDarkenedTextures = (flags & 2) != 0;
        boolean isShaded = (flags & 1) != 0;
        RenderType layer = null;
        if (layer == null && (flags & 4) != 0) {
            boolean anyTranslucent = false;
            for (var face : textureData) {
                anyTranslucent |= TextureUtils.hasTranslucentPixel(face);
                if (anyTranslucent) break;
            }
            if (anyTranslucent) {
                layer = RenderType.translucent();
            } else {
                boolean solid = true;
                for (var face : textureData) {
                    solid &= TextureUtils.isSolidWhereDrawn(face);
                    if (!solid) break;
                }
                if (solid) {
                    layer = RenderType.solid();
                } else {
                    layer = RenderType.cutout();
                }
            }
        }
        if (layer == null && (flags & 8) != 0) {
            layer = RenderType.cutout();
        }
        if (bake.state.is(BlockTags.LEAVES)) {
            layer = RenderType.solid();
        }
        if (layer == null) {
            layer = RenderType.solid();
        }

        var bakeResult = this.processTextureBakeResult(bake.blockId, bake.state, textureData, isShaded, hasDarkenedTextures, layer);
        if (bakeResult != null) {
            this.uploadResults.add(bakeResult);
        }
        return !this.bakeQueue.isEmpty();
    }

    private final ConcurrentLinkedDeque<Mapper.BiomeEntry> biomeQueue = new ConcurrentLinkedDeque<>();
    public void addBiome(Mapper.BiomeEntry biome) {
        this.biomeQueue.add(biome);
    }

    public boolean processAllThings() {
        var biomeEntry = this.biomeQueue.poll();
        while (biomeEntry != null) {
            var biomeRegistry = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME);
            var mcbiomeEntry = biomeRegistry.get(net.minecraft.resources.ResourceKey.create(Registries.BIOME, ResourceLocation.parse(biomeEntry.biome)));
            if (!mcbiomeEntry.isPresent()) {
                Logger.warn("Could not find biome: " + biomeEntry.biome + " using default");
            }
            var res = this.addBiome0(biomeEntry.id, mcbiomeEntry.isPresent() ? mcbiomeEntry.orElseThrow().value() : DEFAULT_BIOME);
            if (res != null) {
                this.uploadResults.add(res);
            }
            biomeEntry = this.biomeQueue.poll();
        }

        while (this.processModelResult());
        return (this.blockStatesInFlight.size() != 0) || (!this.bakeQueue.isEmpty()) || !this.biomeQueue.isEmpty();
    }

    public void processUploads() {
        var upload = this.uploadResults.poll();
        if (upload == null) return;

        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        do {
            upload.upload(this.storage);
            upload.free();
            upload = this.uploadResults.poll();
        } while (upload != null);
        UploadStream.INSTANCE.commit();
    }

    private interface ResultUploader {
        void upload(ModelStore store);
        void free();
    }

    private static final class ModelBakeResultUpload implements ResultUploader {
        private final MemoryBuffer model = new MemoryBuffer(MODEL_SIZE).zero();
        private final MemoryBuffer texture;
        private final boolean hasMips;

        public int modelId = -1;

        public int biomeUploadIndex = -1;
        public @Nullable MemoryBuffer biomeUpload;

        private ModelBakeResultUpload(boolean useMips) {
            this.hasMips = useMips;
            this.texture = new MemoryBuffer((2L * 3 * (useMips ? computeSizeWithMips(MODEL_TEXTURE_SIZE) : MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE)) * 4);
        }

        public void upload(ModelStore store) {
            this.upload(store.modelBuffer, store.modelColourBuffer, store.textures);
        }

        public void upload(GlBuffer modelBuffer, GlBuffer colourBuffer, GlTexture atlas) {
            this.model.cpyTo(UploadStream.INSTANCE.upload(modelBuffer, (long) this.modelId * MODEL_SIZE, MODEL_SIZE));
            if (this.biomeUploadIndex != -1) {
                this.biomeUpload.cpyTo(UploadStream.INSTANCE.upload(colourBuffer, this.biomeUploadIndex * 4L, this.biomeUpload.size));
                this.biomeUploadIndex = -1;
                this.biomeUpload.free();
                this.biomeUpload = null;
            }

            int X = (this.modelId & 0xFF) * MODEL_TEXTURE_SIZE * 3;
            int Y = ((this.modelId >> 8) & 0xFF) * MODEL_TEXTURE_SIZE * 2;

            long cAddr = this.texture.address;
            for (int lvl = 0; lvl < (this.hasMips ? LAYERS : 1); lvl++) {
                nglTextureSubImage2D(atlas.id, lvl, X >> lvl, Y >> lvl, (MODEL_TEXTURE_SIZE * 3) >> lvl, (MODEL_TEXTURE_SIZE * 2) >> lvl, GL_RGBA, GL_UNSIGNED_BYTE, cAddr);
                cAddr += (MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE * 3 * 2 * 4) >> (lvl << 1);
            }

            this.modelId = -1;
        }

        public void free() {
            this.model.free();
            this.texture.free();
            if (this.biomeUpload != null) {
                this.biomeUpload.free();
            }
        }
    }

    private ModelBakeResultUpload processTextureBakeResult(int blockId, BlockState blockState, ColourDepthTextureData[] textureData, boolean isShaded, boolean darkenedTinting, RenderType layer) {
        if (this.idMappings[blockId] != -1) {
            throw new IllegalStateException("Block id already added: " + blockId + " for state: " + blockState);
        }

        this.blockStatesInFlightLock.lock();
        if (!this.blockStatesInFlight.contains(blockId)) {
            this.blockStatesInFlightLock.unlock();
            throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
        }
        this.blockStatesInFlightLock.unlock();

        boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
        int modelId = -1;
        int clientFluidStateId = -1;

        if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
            var fluidState = blockState.getFluidState().createLegacyBlock();
            int fluidStateId = this.mapper.getIdForBlockState(fluidState);
            clientFluidStateId = this.idMappings[fluidStateId];
            if (clientFluidStateId == -1) {
                throw new IllegalStateException("Block has a fluid state but fluid state is not already baked!!!");
            }
        }

        var colourProvider = getColourProvider(blockState.getBlock());
        boolean isBiomeColourDependent = false;
        if (colourProvider != null) {
            isBiomeColourDependent = isBiomeDependentColour(colourProvider, blockState);
        }

        ModelEntry entry;
        {
            entry = new ModelEntry(textureData, clientFluidStateId, isBiomeColourDependent || colourProvider == null ? -1 : captureColourConstant(colourProvider, blockState, DEFAULT_BIOME) | 0xFF000000);
            int possibleDuplicate = this.modelTexture2id.getInt(entry);
            if (possibleDuplicate != -1) {
                this.idMappings[blockId] = possibleDuplicate;
                modelId = possibleDuplicate;
                this.blockStatesInFlightLock.lock();
                if (!this.blockStatesInFlight.remove(blockId)) {
                    this.blockStatesInFlightLock.unlock();
                    throw new IllegalStateException();
                }
                this.blockStatesInFlightLock.unlock();
                return null;
            } else {
                modelId = this.modelTexture2id.size();
                this.modelTexture2id.put(entry, modelId);
            }
        }

        if (isFluid) {
            this.fluidStateLUT[modelId] = modelId;
        } else if (clientFluidStateId != -1) {
            this.fluidStateLUT[modelId] = clientFluidStateId;
        }

        int checkMode = layer == RenderType.solid() ? TextureUtils.WRITE_CHECK_STENCIL : TextureUtils.WRITE_CHECK_ALPHA;

        ModelBakeResultUpload uploadResult = new ModelBakeResultUpload(!this.rasterUV);
        uploadResult.modelId = modelId;
        long uploadPtr = uploadResult.model.address;

        if ((!isFluid) && (!blockState.getFluidState().isEmpty()) && clientFluidStateId != -1) {
            isBiomeColourDependent |= ModelQueries.isBiomeColoured(this.getModelMetadataFromClientId(clientFluidStateId));
        }

        var depths = computeModelDepth(textureData, checkMode, layer != RenderType.solid() ? TextureUtils.DEPTH_MODE_MIN : TextureUtils.DEPTH_MODE_AVG);

        boolean needsDoubleSidedQuads = (depths[0] < -0.1 && depths[1] < -0.1) || (depths[2] < -0.1 && depths[3] < -0.1) || (depths[4] < -0.1 && depths[5] < -0.1);

        boolean cullsSame = false;
        {
            boolean allTrue = true;
            boolean allFalse = true;
            for (var dir : Direction.values()) {
                if (blockState.skipRendering(blockState, dir)) {
                    allFalse = false;
                } else {
                    allTrue = false;
                }
            }

            if (allFalse == allTrue) {
                cullsSame = false;
            }

            if (allTrue) {
                cullsSame = true;
            }
        }

        long metadata = 0;
        metadata |= isBiomeColourDependent ? 1 : 0;
        metadata |= layer == RenderType.translucent() ? 2 : 0;
        metadata |= needsDoubleSidedQuads ? 4 : 0;
        metadata |= ((!isFluid) && !blockState.getFluidState().isEmpty()) ? 8 : 0;
        metadata |= isFluid ? 16 : 0;
        metadata |= cullsSame ? 32 : 0;

        boolean fullyOpaque = true;

        for (int face = 5; face != -1; face--) {
            long faceUploadPtr = uploadPtr + 4L * face;
            metadata <<= 8;
            float offset = depths[face];
            if (offset < -0.1) {
                metadata |= 0xFF;
                MemoryUtil.memPutInt(faceUploadPtr, -1);
                fullyOpaque = false;
                continue;
            }
            var faceSize = TextureUtils.computeBounds(textureData[face], checkMode);
            int writeCount = TextureUtils.getWrittenPixelCount(textureData[face], checkMode);

            boolean faceCoversFullBlock = faceSize[0] == 0 && faceSize[2] == 0 &&
                    faceSize[1] == (MODEL_TEXTURE_SIZE - 1) && faceSize[3] == (MODEL_TEXTURE_SIZE - 1);

            metadata |= faceCoversFullBlock ? 2 : 0;

            boolean occludesFace = true;
            occludesFace &= layer != RenderType.translucent();
            occludesFace &= offset < 0.1;

            if (occludesFace) {
                occludesFace &= ((float) writeCount) / (MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE) > 0.9;
            }
            metadata |= occludesFace ? 1 : 0;
            fullyOpaque &= occludesFace;

            boolean canBeOccluded = true;
            canBeOccluded &= offset < 0.3;

            metadata |= canBeOccluded ? 4 : 0;
            metadata |= (offset > 0.01 || layer == RenderType.translucent()) ? 0b1000 : 0;

            if (MODEL_TEXTURE_SIZE - 1 != 15) {
                for (int i = 0; i < 4; i++) {
                    faceSize[i] = Math.round((((float) faceSize[i]) / (MODEL_TEXTURE_SIZE - 1)) * 15);
                }
            }

            int faceModelData = 0;
            faceModelData |= faceSize[0] | (faceSize[1] << 4) | (faceSize[2] << 8) | (faceSize[3] << 12);
            int enc = Math.round(offset * 64);
            faceModelData |= Math.min(enc, 62) << 16;

            int area = (faceSize[1] - faceSize[0] + 1) * (faceSize[3] - faceSize[2] + 1);
            boolean needsAlphaDiscard = ((float) writeCount) / area < 0.9;

            needsAlphaDiscard |= layer != RenderType.solid();
            needsAlphaDiscard &= layer != RenderType.translucent();
            faceModelData |= needsAlphaDiscard ? 1 << 22 : 0;

            faceModelData |= ((!faceCoversFullBlock) && layer != RenderType.translucent()) ? 1 << 23 : 0;

            if (colourProvider != null) {
                int tintState = TextureUtils.computeFaceTint(textureData[face], checkMode);
                if (tintState == 2) {
                    faceModelData |= 1 << 24;
                } else if (tintState == 3) {
                    faceModelData |= 2 << 24;
                }
            }

            MemoryUtil.memPutInt(faceUploadPtr, faceModelData);
        }

        metadata |= fullyOpaque ? (1L << (48 + 6)) : 0;
        metadata |= ((long) getBlockLightEmission(blockState)) << (48 + 7);

        this.metadataCache[modelId] = metadata;

        uploadPtr += 4 * 6;
        int modelFlags = 0;
        modelFlags |= colourProvider != null ? 1 : 0;
        modelFlags |= isBiomeColourDependent ? 2 : 0;
        modelFlags |= layer == RenderType.translucent() ? 4 : 0;
        modelFlags |= isShaded ? 8 : 0;

        MemoryUtil.memPutInt(uploadPtr, modelFlags); uploadPtr += 4;

        if (colourProvider == null) {
            MemoryUtil.memPutInt(uploadPtr, -1);
        } else if (!isBiomeColourDependent) {
            MemoryUtil.memPutInt(uploadPtr, entry.tintingColour);
        } else {
            int biomeIndex = this.modelsRequiringBiomeColours.size() * this.biomes.size();
            MemoryUtil.memPutInt(uploadPtr, biomeIndex);
            this.modelsRequiringBiomeColours.add(new ModelBlockStatePair(modelId, blockState));
            if (!this.biomes.isEmpty()) {
                uploadResult.biomeUploadIndex = biomeIndex;
                long clrUploadPtr = (uploadResult.biomeUpload = new MemoryBuffer(4L * this.biomes.size())).address;
                for (var biome : this.biomes) {
                    Biome biomeToUse = biome != null ? biome : DEFAULT_BIOME;
                    MemoryUtil.memPutInt(clrUploadPtr, captureColourConstant(colourProvider, blockState, biomeToUse) | 0xFF000000);
                    clrUploadPtr += 4;
                }
            }
        }
        uploadPtr += 4;

        if (this.customBlockStateIdMapping != null && this.customBlockStateIdMapping.containsKey(blockState)) {
            MemoryUtil.memPutInt(uploadPtr, this.customBlockStateIdMapping.getInt(blockState));
        } else {
            MemoryUtil.memPutInt(uploadPtr, 0);
        }
        uploadPtr += 4;

        if (uploadResult.hasMips)
            MipGen.putTextures(darkenedTinting, textureData, uploadResult.texture);

        this.idMappings[blockId] = modelId;

        this.blockStatesInFlightLock.lock();
        if (!this.blockStatesInFlight.remove(blockId)) {
            this.blockStatesInFlightLock.unlock();
            throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
        }
        this.blockStatesInFlightLock.unlock();

        return uploadResult;
    }

    private static int getBlockLightEmission(BlockState state) {
        boolean isEmissive = state.emissiveRendering(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (isEmissive) {
            return 15;
        }
        return Math.clamp(state.getLightEmission(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO), 0, 15);
    }

    private static final class BiomeUploadResult implements ResultUploader {
        private final MemoryBuffer biomeColourBuffer;
        private final MemoryBuffer modelBiomeIndexPairs;
        private BiomeUploadResult(int biomes, int models) {
            this.biomeColourBuffer = new MemoryBuffer(biomes * models * 4L);
            this.modelBiomeIndexPairs = new MemoryBuffer(models * 8L);
        }

        public void upload(ModelStore store) {
            this.upload(store.modelBuffer, store.modelColourBuffer);
        }

        public void upload(GlBuffer modelBuffer, GlBuffer modelColourBuffer) {
            this.biomeColourBuffer.cpyTo(UploadStream.INSTANCE.upload(modelColourBuffer, 0, this.biomeColourBuffer.size));

            long ptr = this.modelBiomeIndexPairs.address;
            for (long offset = 0; offset < this.modelBiomeIndexPairs.size; offset += 8) {
                long v = MemoryUtil.memGetLong(ptr); ptr += 8;
                MemoryUtil.memPutInt(UploadStream.INSTANCE.upload(modelBuffer, (MODEL_SIZE * (v & ((1L << 32) - 1))) + 4 * 6 + 4, 4), (int) (v >>> 32));
            }

            this.biomeColourBuffer.free();
            this.modelBiomeIndexPairs.free();
        }

        public void free() {
            if (!this.biomeColourBuffer.isFreed()) {
                this.biomeColourBuffer.free();
                this.modelBiomeIndexPairs.free();
            }
        }
    }

    private BiomeUploadResult addBiome0(int id, Biome biome) {
        if (biome == null) {
            throw new IllegalStateException("Null biome");
        }
        for (int i = this.biomes.size(); i <= id; i++) {
            this.biomes.add(null);
        }
        var oldBiome = this.biomes.set(id, biome);

        if (oldBiome != null && oldBiome != biome) {
            throw new IllegalStateException("Biome was put in an id that was not null");
        }
        if (oldBiome == biome) {
            Logger.error("Biome added was a duplicate: " + id);
            return null;
        }

        if (this.modelsRequiringBiomeColours.isEmpty()) return null;

        var result = new BiomeUploadResult(this.biomes.size(), this.modelsRequiringBiomeColours.size());

        int i = 0;
        long modelUpPtr = result.modelBiomeIndexPairs.address;
        for (var entry : this.modelsRequiringBiomeColours) {
            var colourProvider = getColourProvider(entry.state().getBlock());
            if (colourProvider == null) {
                throw new IllegalStateException();
            }
            int biomeIndex = (i++) * this.biomes.size();
            MemoryUtil.memPutLong(modelUpPtr, Integer.toUnsignedLong(entry.model()) | (Integer.toUnsignedLong(biomeIndex) << 32));
            modelUpPtr += 8;
            long clrUploadPtr = result.biomeColourBuffer.address + biomeIndex * 4L;
            for (var biomeE : this.biomes) {
                Biome biomeToUse = biomeE != null ? biomeE : DEFAULT_BIOME;
                MemoryUtil.memPutInt(clrUploadPtr, captureColourConstant(colourProvider, entry.state(), biomeToUse) | 0xFF000000);
                clrUploadPtr += 4;
            }
        }

        return result;
    }

    private static BlockColor getColourProvider(Block block) {
        var blockColors = Minecraft.getInstance().getBlockColors();
        var providers = BlockColorsExtension.getProviders(blockColors);
        if (providers.containsKey(block)) {
            return providers.get(block);
        }
        return null;
    }

    private static int captureColourConstant(BlockColor colorProvider, BlockState state, Biome biome) {
        return colorProvider.getColor(state, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return colorResolver.getColor(biome, 0, 0);
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        }, BlockPos.ZERO, 0);
    }

    private static boolean isBiomeDependentColour(BlockColor colorProvider, BlockState state) {
        boolean[] biomeDependent = new boolean[1];
        colorProvider.getColor(state, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                biomeDependent[0] = true;
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        }, BlockPos.ZERO, 0);
        return biomeDependent[0];
    }

    private static float[] computeModelDepth(ColourDepthTextureData[] textures, int checkMode) {
        return computeModelDepth(textures, checkMode, TextureUtils.DEPTH_MODE_AVG);
    }

    private static float[] computeModelDepth(ColourDepthTextureData[] textures, int checkMode, int computeMode) {
        float[] res = new float[6];
        for (var dir : Direction.values()) {
            var data = textures[dir.get3DDataValue()];
            float fd = TextureUtils.computeDepth(data, computeMode, checkMode);
            if (fd < -0.1) {
                res[dir.ordinal()] = -1;
            } else {
                res[dir.ordinal()] = fd;
            }
        }
        return res;
    }

    public int[] _unsafeRawAccess() {
        return this.idMappings;
    }

    public int getModelId(int blockId) {
        int map = this.idMappings[blockId];
        if (map == -1) {
            throw new IdNotYetComputedException(blockId, true);
        }
        return map;
    }

    public boolean hasModelForBlockId(int blockId) {
        return this.idMappings[blockId] != -1;
    }

    public int getFluidClientStateId(int clientBlockStateId) {
        int map = this.fluidStateLUT[clientBlockStateId];
        if (map == -1) {
            throw new IdNotYetComputedException(clientBlockStateId, false);
        }
        return map;
    }

    public final long getModelMetadataFromClientId(int clientId) {
        return this.metadataCache[clientId];
    }

    public void free() {
        this.bakery2.free();
        MemoryUtil.nmemFree(this.bakeScratchBuffer);
        while (!this.uploadResults.isEmpty()) {
            this.uploadResults.poll().free();
        }
    }

    public int getBakedCount() {
        return this.modelTexture2id.size();
    }

    public int getInflightCount() {
        int size = this.blockStatesInFlight.size();
        size += this.uploadResults.size();
        size += this.biomeQueue.size();
        size += this.bakeQueue.size();
        return size;
    }

    private static int computeSizeWithMips(int size) {
        int total = 0;
        for (; size != 0; size >>= 1) total += size * size;
        return total;
    }
}
