package me.cortex.voxy.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.integration.ChunkyIntegration;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.system.MemoryUtil;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.mojang.serialization.Codec;

/**
 * Background service that automatically scans and ingests all existing chunks.
 * Designed for maximum efficiency with TPS-based throttling and smart skipping.
 */
public class BackgroundAutoIngestor {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal
            .withInitial(VoxelizedSection::createEmpty);

    // Configuration
    private int maxChunksPerTick = 5;
    private double minTPS = 18.0;
    private boolean enabled = true;

    // State
    private final ServerLevel level;
    private final WorldIdentifier worldId;
    private final Path regionDir;
    private final Path stateFile;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicInteger totalRegions = new AtomicInteger(0);
    private final AtomicInteger processedRegions = new AtomicInteger(0);
    private final AtomicInteger totalChunksProcessed = new AtomicInteger(0);
    private final AtomicInteger chunksSkipped = new AtomicInteger(0);

    // Worker thread for async file reading
    private final ExecutorService fileReader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Voxy-AutoIngestor-Reader");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // Queue for chunks ready to ingest
    private final ConcurrentLinkedQueue<ChunkData> readyQueue = new ConcurrentLinkedQueue<>();

    // Codec for NBT parsing
    private final Codec<PalettedContainer<BlockState>> blockStateCodec;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final PalettedContainerRO<Holder<Biome>> defaultBiomeProvider;

    // Persistent scan state
    private String lastRegionFile = null;
    private int lastChunkIdx = 0;
    private long scanStartTime = 0;

    // Timing
    private long lastLogTime = 0;
    private long lastTickMs = 0;
    private double recentTPS = 20.0;

    // Cached world engine reference
    private volatile WorldEngine cachedEngine = null;

    private record ChunkData(int x, int y, int z, PalettedContainer<BlockState> states,
            PalettedContainerRO<Holder<Biome>> biomes, DataLayer blockLight, DataLayer skyLight) {
    }

    public BackgroundAutoIngestor(ServerLevel level) {
        this.level = level;
        this.worldId = WorldIdentifier.of(level);

        // Find region directory
        Path worldDir = level.getServer().getWorldPath(LevelResource.ROOT);
        String dimPath = level.dimension().location().toString().replace(":", "/");
        if (dimPath.equals("minecraft/overworld")) {
            this.regionDir = worldDir.resolve("region");
        } else if (dimPath.equals("minecraft/the_nether")) {
            this.regionDir = worldDir.resolve("DIM-1/region");
        } else if (dimPath.equals("minecraft/the_end")) {
            this.regionDir = worldDir.resolve("DIM1/region");
        } else {
            this.regionDir = worldDir.resolve("dimensions").resolve(dimPath).resolve("region");
        }

        this.stateFile = worldDir.resolve("voxy_autoingest_" + dimPath.replace("/", "_") + ".json");

        // Initialize codecs
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getHolder(Biomes.PLAINS).orElseThrow();

        this.defaultBiomeProvider = new DefaultBiomeProvider(defaultBiome);
        this.biomeCodec = PalettedContainer.codecRO(biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
        this.blockStateCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

        // Load saved state
        loadState();
    }

    /**
     * Start the background scan.
     */
    public void start() {
        if (isRunning.get())
            return;
        if (!enabled)
            return;

        if (!Files.exists(regionDir)) {
            Logger.info("No region directory found for " + level.dimension().location() + ", skipping auto-ingest");
            return;
        }

        isRunning.set(true);
        isPaused.set(false);
        scanStartTime = System.currentTimeMillis();

        Logger.info("Starting background auto-ingest for " + level.dimension().location());

        // Start async scan
        fileReader.submit(this::scanRegions);
    }

    /**
     * Main scan loop - runs on worker thread.
     */
    private void scanRegions() {
        try {
            // Wait for world engine to become available (up to 60 seconds)
            // On singleplayer, engine is created when player joins
            WorldEngine engine = null;
            for (int i = 0; i < 600 && isRunning.get(); i++) {
                engine = getWorldEngine();
                if (engine != null)
                    break;
                Thread.sleep(100);
            }

            if (engine == null) {
                Logger.info("[AutoIngest] No WorldEngine available after waiting, will retry when player joins");
                isRunning.set(false);
                return;
            }

            Logger.info("[AutoIngest] WorldEngine available, starting region scan");

            File[] regionFiles = regionDir.toFile().listFiles((dir, name) -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));

            if (regionFiles == null || regionFiles.length == 0) {
                Logger.info("No region files found in " + regionDir);
                isRunning.set(false);
                return;
            }

            Arrays.sort(regionFiles, Comparator.comparing(File::getName));
            totalRegions.set(regionFiles.length);

            // Find resume point
            int startIdx = 0;
            if (lastRegionFile != null) {
                for (int i = 0; i < regionFiles.length; i++) {
                    if (regionFiles[i].getName().equals(lastRegionFile)) {
                        startIdx = i;
                        break;
                    }
                }
            }
            processedRegions.set(startIdx);

            Logger.info("Found " + regionFiles.length + " region files, starting from " +
                    (startIdx > 0 ? regionFiles[startIdx].getName() : "beginning"));

            for (int i = startIdx; i < regionFiles.length && isRunning.get(); i++) {
                while (isPaused.get() && isRunning.get()) {
                    Thread.sleep(100);
                }
                if (!isRunning.get())
                    break;

                // Defer to Chunky if it's actively generating
                // Chunky is the primary LOD propagation method when present
                while (ChunkyIntegration.shouldDeferProcessing() && isRunning.get()) {
                    Thread.sleep(500);
                }

                // Throttle if queue is too full
                while (readyQueue.size() > 1000 && isRunning.get()) {
                    Thread.sleep(10);
                }

                processRegionFile(regionFiles[i]);
                processedRegions.incrementAndGet();
                lastRegionFile = regionFiles[i].getName();
                lastChunkIdx = 0;
                saveState();
            }

            if (isRunning.get()) {
                Logger.info("Background auto-ingest complete for " + level.dimension().location() +
                        ": " + totalChunksProcessed.get() + " chunks processed, " +
                        chunksSkipped.get() + " skipped");
            }

        } catch (Exception e) {
            Logger.error("Error in background auto-ingest", e);
        } finally {
            isRunning.set(false);
            saveState();
        }
    }

    /**
     * Process a single region file.
     */
    private void processRegionFile(File file) throws IOException {
        if (file.length() < 8192) {
            return;
        }

        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            MemoryBuffer buffer = new MemoryBuffer(channel.size());
            channel.read(buffer.asByteBuffer(), 0);

            int startChunk = (lastRegionFile != null && lastRegionFile.equals(file.getName())) ? lastChunkIdx : 0;

            for (int idx = startChunk; idx < 1024 && isRunning.get(); idx++) {
                if (!isRunning.get())
                    break;

                int sectorMeta = Integer.reverseBytes(MemoryUtil.memGetInt(buffer.address + idx * 4));
                if (sectorMeta == 0)
                    continue; // Empty chunk slot

                int sectorStart = sectorMeta >>> 8;
                int sectorCount = sectorMeta & 0xFF;
                if (sectorCount == 0)
                    continue; // No sectors allocated

                if (buffer.size < ((long) (sectorCount - 1) + sectorStart) * 4096L)
                    continue;

                long base = buffer.address + sectorStart * 4096L;
                int m = Integer.reverseBytes(MemoryUtil.memGetInt(base));
                byte flags = MemoryUtil.memGetByte(base + 4L);

                if (m <= 0)
                    continue;
                int n = m - 1;
                if (n <= 0 || (flags & 128) != 0)
                    continue;

                try {
                    MemoryBuffer chunkData = new MemoryBuffer(n).cpyFrom(base + 5);
                    processChunkData(chunkData, flags, file.getName(), idx);
                    chunkData.free();
                } catch (Exception e) {
                    Logger.warn("[AutoIngest] Failed to process chunk at index " + idx + " in " + file.getName() + ": "
                            + e.getMessage());
                    e.printStackTrace();
                }

                lastChunkIdx = idx;
            }

            buffer.free();
        }
    }

    /**
     * Process chunk NBT data.
     */
    private void processChunkData(MemoryBuffer data, byte flags, String regionName, int idx) throws IOException {
        RegionFileVersion version = RegionFileVersion.fromId(flags);
        if (version == null)
            return;

        try (DataInputStream dis = new DataInputStream(version.wrap(createInputStream(data)))) {
            CompoundTag nbt = NbtIo.read(dis);

            if (!nbt.contains("Status")) {
                return;
            }
            var status = ChunkStatus.byName(nbt.getString("Status"));
            // Accept only FULL chunks
            if (status != ChunkStatus.FULL) {
                return;
            }

            int x = nbt.getInt("xPos");
            int z = nbt.getInt("zPos");

            // Get engine for deduplication check
            var engine = getWorldEngine();

            // Parse sections and queue for ingestion
            for (var sectionE : nbt.getList("sections", Tag.TAG_COMPOUND)) {
                var section = (CompoundTag) sectionE;
                int y = section.getInt("Y");

                if (section.getCompound("block_states").isEmpty()) {
                    continue;
                }

                // Check if LOD already exists (deduplication)
                if (engine != null) {
                    var existingSection = engine.acquireIfExists(0, x, y, z);
                    if (existingSection != null) {
                        existingSection.release();
                        continue;
                    }
                }

                ChunkData chunkData = parseSection(x, y, z, section);
                if (chunkData != null) {
                    readyQueue.add(chunkData);
                }
            }
        }
    }

    /**
     * Parse a section from NBT.
     */
    private ChunkData parseSection(int x, int y, int z, CompoundTag section) {
        try {
            var blockStatesRes = blockStateCodec.parse(NbtOps.INSTANCE, section.getCompound("block_states"));
            if (!blockStatesRes.hasResultOrPartial()) {
                return null;
            }
            var blockStates = blockStatesRes.getPartialOrThrow();

            var biomes = this.defaultBiomeProvider;
            var optBiomes = section.getCompound("biomes");
            if (!optBiomes.isEmpty()) {
                biomes = biomeCodec.parse(NbtOps.INSTANCE, optBiomes).result().orElse(defaultBiomeProvider);
            }

            byte[] blockLightData = section.getByteArray("BlockLight");
            byte[] skyLightData = section.getByteArray("SkyLight");

            DataLayer blockLight = blockLightData.length != 0 ? new DataLayer(blockLightData) : null;
            DataLayer skyLight = skyLightData.length != 0 ? new DataLayer(skyLightData) : null;

            return new ChunkData(x, y, z, blockStates, biomes, blockLight, skyLight);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Called every server tick - process queued chunks.
     */
    public void tick() {
        if (!isRunning.get() && readyQueue.isEmpty())
            return;

        // Defer to Chunky if it's actively generating
        if (ChunkyIntegration.shouldDeferProcessing()) {
            return;
        }

        // Calculate TPS
        long now = System.currentTimeMillis();
        if (lastTickMs > 0) {
            long elapsed = now - lastTickMs;
            double instantTPS = 1000.0 / Math.max(1, elapsed);
            recentTPS = recentTPS * 0.9 + instantTPS * 0.1;
        }
        lastTickMs = now;

        // Check TPS threshold
        if (recentTPS < minTPS) {
            return; // Too much lag, skip this tick
        }

        // Get world engine
        var instance = VoxyCommon.getInstance();
        if (instance == null)
            return;
        var engine = instance.getNullable(worldId);
        if (engine == null)
            return;

        // Process chunks
        for (int i = 0; i < maxChunksPerTick && !readyQueue.isEmpty(); i++) {
            ChunkData chunk = readyQueue.poll();
            if (chunk != null) {
                ingestChunk(engine, chunk);
                totalChunksProcessed.incrementAndGet();
            }
        }

        // Log progress periodically
        if (now - lastLogTime > 10000 && isRunning.get()) {
            lastLogTime = now;
            Logger.info("Auto-ingest progress: " + processedRegions.get() + "/" + totalRegions.get() +
                    " regions, " + totalChunksProcessed.get() + " chunks, queue: " + readyQueue.size() +
                    ", TPS: " + String.format("%.1f", recentTPS));
        }
    }

    /**
     * Ingest a chunk into the LOD system.
     */
    private void ingestChunk(WorldEngine engine, ChunkData chunk) {
        try {
            VoxelizedSection vs = SECTION_CACHE.get().setPosition(chunk.x, chunk.y, chunk.z);

            VoxelizedSection converted = WorldConversionFactory.convert(
                    vs,
                    engine.getMapper(),
                    chunk.states,
                    chunk.biomes,
                    (bx, by, bz) -> {
                        int block = 0, sky = 0;
                        if (chunk.blockLight != null)
                            block = chunk.blockLight.get(bx, by, bz);
                        if (chunk.skyLight != null)
                            sky = chunk.skyLight.get(bx, by, bz);
                        return (byte) (sky | (block << 4));
                    });

            WorldConversionFactory.mipSection(converted, engine.getMapper());
            WorldUpdater.insertUpdate(engine, converted);
        } catch (Exception e) {
            Logger.warn("[AutoIngest] Failed to ingest chunk [" + chunk.x + ", " + chunk.y + ", " + chunk.z + "]: "
                    + e.getMessage());
        }
    }

    /**
     * Load saved scan state.
     */
    private void loadState() {
        if (!Files.exists(stateFile))
            return;

        try {
            String json = Files.readString(stateFile);
            var state = GSON.fromJson(json, ScanState.class);
            if (state != null) {
                lastRegionFile = state.lastRegion;
                lastChunkIdx = state.lastChunkIdx;
                Logger.info("Resuming auto-ingest from " + lastRegionFile + " chunk " + lastChunkIdx);
            }
        } catch (Exception e) {
            Logger.warn("Failed to load auto-ingest state: " + e.getMessage());
        }
    }

    /**
     * Save scan state for resume after restart.
     */
    private void saveState() {
        try {
            var state = new ScanState();
            state.lastRegion = lastRegionFile;
            state.lastChunkIdx = lastChunkIdx;
            state.timestamp = System.currentTimeMillis();
            state.totalProcessed = totalChunksProcessed.get();

            Files.writeString(stateFile, GSON.toJson(state));
        } catch (Exception e) {
            // Silently continue
        }
    }

    /**
     * Shutdown the ingestor.
     */
    public void shutdown() {
        isRunning.set(false);
        cachedEngine = null;
        fileReader.shutdownNow();
        saveState();
        readyQueue.clear();
    }

    /**
     * Called when a chunk is saved - allows event-driven processing.
     * This processes the chunk directly from memory, avoiding file I/O.
     */
    public void onChunkSaved(net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (!enabled)
            return;

        try {
            int chunkX = chunk.getPos().x;
            int chunkZ = chunk.getPos().z;

            // Get world engine - use getOrCreate to ensure engine exists for newly
            // generated chunks
            var instance = me.cortex.voxy.commonImpl.VoxyCommon.getInstance();
            if (instance == null)
                return;
            var engine = instance.getOrCreate(worldId);
            if (engine == null)
                return;

            // Process each section in the chunk
            int sectionsQueued = 0;
            var sections = chunk.getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                var section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir())
                    continue;

                // Calculate section Y coordinate
                int sectionY = chunk.getMinSection() + sectionIndex;

                // Check if LOD already exists for this section (deduplication)
                var existingSection = engine.acquireIfExists(0, chunkX, sectionY, chunkZ);
                if (existingSection != null) {
                    existingSection.release();
                    continue; // Already has LOD, skip
                }

                // Get block states and biomes from the section
                var blockStates = section.getStates();
                var biomes = section.getBiomes();

                // Get light data
                byte[] blockLightData = null;
                byte[] skyLightData = null;

                var lightEngine = level.getChunkSource().getLightEngine();
                var blockLightLayer = lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
                var skyLightLayer = lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);

                if (blockLightLayer != null) {
                    var blockLight = blockLightLayer
                            .getDataLayerData(net.minecraft.core.SectionPos.of(chunkX, sectionY, chunkZ));
                    if (blockLight != null)
                        blockLightData = blockLight.getData();
                }

                if (skyLightLayer != null) {
                    var skyLight = skyLightLayer
                            .getDataLayerData(net.minecraft.core.SectionPos.of(chunkX, sectionY, chunkZ));
                    if (skyLight != null)
                        skyLightData = skyLight.getData();
                }

                net.minecraft.world.level.chunk.DataLayer blockLight = blockLightData != null
                        ? new net.minecraft.world.level.chunk.DataLayer(blockLightData)
                        : null;
                net.minecraft.world.level.chunk.DataLayer skyLight = skyLightData != null
                        ? new net.minecraft.world.level.chunk.DataLayer(skyLightData)
                        : null;

                // Create chunk data and queue
                ChunkData chunkData = new ChunkData(chunkX, sectionY, chunkZ, blockStates, biomes, blockLight,
                        skyLight);
                readyQueue.add(chunkData);
                sectionsQueued++;
            }

            if (sectionsQueued > 0) {
                // Only count, don't log per-chunk to reduce spam
                totalChunksProcessed.addAndGet(sectionsQueued);
            }
        } catch (Exception e) {
            Logger.warn("[AutoIngest] Failed to process saved chunk: " + e.getMessage());
        }
    }

    /**
     * Pause/resume scanning.
     */
    public void setPaused(boolean paused) {
        isPaused.set(paused);
    }

    /**
     * Get status string.
     */
    public String getStatusString() {
        if (!isRunning.get() && readyQueue.isEmpty()) {
            return "Idle";
        }
        return String.format("%d/%d regions, %d chunks, queue: %d, TPS: %.1f",
                processedRegions.get(), totalRegions.get(), totalChunksProcessed.get(),
                readyQueue.size(), recentTPS);
    }

    // Helper methods

    /**
     * Get or cache the world engine for this world.
     */
    private WorldEngine getWorldEngine() {
        if (cachedEngine != null)
            return cachedEngine;

        var instance = VoxyCommon.getInstance();
        if (instance == null)
            return null;

        cachedEngine = instance.getNullable(worldId);
        return cachedEngine;
    }

    // Helper classes

    private static InputStream createInputStream(MemoryBuffer data) {
        return new InputStream() {
            private long offset = 0;

            @Override
            public int read() {
                return MemoryUtil.memGetByte(data.address + (offset++)) & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                len = Math.min(len, available());
                if (len <= 0)
                    return -1;
                for (int i = 0; i < len; i++)
                    b[off + i] = MemoryUtil.memGetByte(data.address + offset++);
                return len;
            }

            @Override
            public int available() {
                return (int) (data.size - offset);
            }
        };
    }

    private static class ScanState {
        String lastRegion;
        int lastChunkIdx;
        long timestamp;
        int totalProcessed;
    }

    private record DefaultBiomeProvider(Holder<Biome> defaultBiome) implements PalettedContainerRO<Holder<Biome>> {
        @Override
        public Holder<Biome> get(int x, int y, int z) {
            return defaultBiome;
        }

        @Override
        public void getAll(java.util.function.Consumer<Holder<Biome>> action) {
        }

        @Override
        public void write(net.minecraft.network.FriendlyByteBuf buf) {
        }

        @Override
        public int getSerializedSize() {
            return 0;
        }

        @Override
        public boolean maybeHas(java.util.function.Predicate<Holder<Biome>> p) {
            return false;
        }

        @Override
        public void count(PalettedContainer.CountConsumer<Holder<Biome>> c) {
        }

        @Override
        public PalettedContainer<Holder<Biome>> recreate() {
            return null;
        }

        @Override
        public PalettedContainerRO.PackedData<Holder<Biome>> pack(net.minecraft.core.IdMap<Holder<Biome>> m,
                PalettedContainer.Strategy s) {
            return null;
        }
    }
}
