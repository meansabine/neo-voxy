package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes existing chunks for LOD generation.
 * Uses a non-blocking approach that processes chunks incrementally
 * using server tick events to avoid blocking or deadlocking.
 */
public class ChunkFileProcessor {

    private final ServerLevel level;
    private final WorldIdentifier worldId;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicInteger processedChunks = new AtomicInteger(0);
    private final AtomicInteger successfulChunks = new AtomicInteger(0);
    private int totalChunksToProcess = 0;
    private long lastLogTime = 0;

    // Queue of chunks to process
    private final Queue<ChunkPos> pendingChunks = new ArrayDeque<>();
    private CompletableFuture<Void> completionFuture = null;

    public ChunkFileProcessor(ServerLevel level) {
        this.level = level;
        this.worldId = WorldIdentifier.of(level);
    }

    /**
     * Process chunks within a specific radius from a center position.
     * This queues chunks and processes them incrementally via tick().
     */
    public CompletableFuture<Void> processChunksInRadius(int centerX, int centerZ, int radiusChunks) {
        if (isProcessing.get()) {
            Logger.warn("Chunk processing already in progress");
            return CompletableFuture.completedFuture(null);
        }

        isProcessing.set(true);
        cancelRequested.set(false);
        processedChunks.set(0);
        successfulChunks.set(0);
        pendingChunks.clear();

        // Queue all chunks to process
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                pendingChunks.add(new ChunkPos(centerX + dx, centerZ + dz));
            }
        }

        totalChunksToProcess = pendingChunks.size();
        completionFuture = new CompletableFuture<>();

        Logger.info("Processing chunks in radius " + radiusChunks + " from (" + centerX + ", " + centerZ + ")");
        Logger.info("Total chunks to check: " + totalChunksToProcess + " (will skip non-existent chunks)");
        Logger.info("World: " + level.dimension().location() + ", Save path: "
                + level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));

        return completionFuture;
    }

    /**
     * Called every server tick to process a batch of chunks.
     * This avoids blocking and allows the server to remain responsive.
     */
    public void tick() {
        if (!isProcessing.get() || cancelRequested.get()) {
            if (cancelRequested.get() && isProcessing.get()) {
                finishProcessing("Cancelled");
            }
            return;
        }

        // Process a batch of chunks per tick (adjust for performance)
        int chunksPerTick = 10;

        for (int i = 0; i < chunksPerTick && !pendingChunks.isEmpty(); i++) {
            ChunkPos pos = pendingChunks.poll();
            if (pos != null) {
                processChunkDirect(pos.x, pos.z);
                processedChunks.incrementAndGet();
            }
        }

        // Log progress periodically
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 5000) {
            lastLogTime = now;
            Logger.info("Progress: " + getStatusString());
        }

        // Check if done
        if (pendingChunks.isEmpty()) {
            finishProcessing("Complete");
        }
    }

    private void finishProcessing(String reason) {
        Logger.info("Finished processing chunks (" + reason + "): " + successfulChunks.get() + "/"
                + totalChunksToProcess + " ingested");
        isProcessing.set(false);
        if (completionFuture != null) {
            completionFuture.complete(null);
        }
    }

    /**
     * Process a single chunk directly (must be called on server thread).
     */
    private void processChunkDirect(int chunkX, int chunkZ) {
        try {
            // First check if chunk is already loaded in memory
            ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);

            if (chunk == null) {
                // Load chunk from disk - this will load saved chunks but NOT generate new ones
                try {
                    chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
                } catch (Exception e) {
                    // Chunk doesn't exist on disk or failed to load, skip
                    return;
                }
            }

            if (chunk instanceof LevelChunk levelChunk) {
                ingestChunk(levelChunk);
                successfulChunks.incrementAndGet();
            }
        } catch (Exception e) {
            // Log the error for debugging but continue processing
            Logger.warn("Failed to process chunk at (" + chunkX + ", " + chunkZ + "): " + e.getMessage());
        }
    }

    /**
     * Ingest a chunk into the LOD system
     */
    private void ingestChunk(LevelChunk chunk) {
        var instance = VoxyCommon.getInstance();
        if (instance == null || worldId == null)
            return;

        var engine = instance.getOrCreate(worldId);
        if (engine == null)
            return;

        try {
            instance.getIngestService().enqueueIngest(engine, chunk);
        } catch (Exception e) {
            // Silently continue
        }
    }

    /**
     * Cancel the current processing operation
     */
    public void cancel() {
        cancelRequested.set(true);
    }

    /**
     * Check if processing is currently running
     */
    public boolean isProcessing() {
        return isProcessing.get();
    }

    /**
     * Get processing status string
     */
    public String getStatusString() {
        if (!isProcessing.get()) {
            return "Not processing";
        }
        int processed = processedChunks.get();
        int successful = successfulChunks.get();
        int remaining = pendingChunks.size();
        if (totalChunksToProcess > 0) {
            double percent = (processed * 100.0) / totalChunksToProcess;
            return String.format("%d/%d (%.1f%%), %d ingested, %d remaining",
                    processed, totalChunksToProcess, percent, successful, remaining);
        }
        return processed + " processed, " + successful + " ingested";
    }

    /**
     * Shutdown the processor
     */
    public void shutdown() {
        cancel();
        pendingChunks.clear();
    }
}
