package me.cortex.voxy.server.integration;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Queue for storing chunk positions to process after Chunky completes
 * generation.
 * Chunks are stored by position rather than reference to avoid memory bloat.
 */
public class DeferredChunkQueue {

    private record DeferredChunk(WorldIdentifier worldId, int x, int z, long queuedTime) {
    }

    private static final ConcurrentLinkedQueue<DeferredChunk> queue = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private static final AtomicInteger deferredCount = new AtomicInteger(0);
    private static final AtomicInteger processedCount = new AtomicInteger(0);

    private static MinecraftServer server;
    private static int chunksPerTick = 10;
    private static long lastLogTime = 0;

    /**
     * Initialize the deferred queue.
     */
    public static void initialize(MinecraftServer mcServer) {
        server = mcServer;
        queue.clear();
        deferredCount.set(0);
        processedCount.set(0);
        isProcessing.set(false);

        // Register Chunky completion listener
        ChunkyIntegration.registerCompletionListener(DeferredChunkQueue::startProcessing);
    }

    /**
     * Enqueue a chunk position for deferred processing.
     */
    public static void enqueue(WorldIdentifier worldId, int x, int z) {
        queue.add(new DeferredChunk(worldId, x, z, System.currentTimeMillis()));
        int count = deferredCount.incrementAndGet();

        // Log periodically
        if (count % 100 == 0) {
            Logger.info("Deferred " + count + " chunks for later processing (Chunky active)");
        }
    }

    /**
     * Start processing deferred chunks.
     */
    public static void startProcessing() {
        if (queue.isEmpty()) {
            Logger.info("No deferred chunks to process");
            return;
        }

        isProcessing.set(true);
        processedCount.set(0);
        Logger.info("Starting deferred chunk processing: " + queue.size() + " chunks queued");
    }

    /**
     * Called every server tick to process a batch of deferred chunks.
     */
    public static void tick() {
        if (!isProcessing.get() || queue.isEmpty() || server == null) {
            if (isProcessing.get() && queue.isEmpty()) {
                finishProcessing();
            }
            return;
        }

        // Don't process if Chunky starts again
        if (ChunkyIntegration.shouldDeferProcessing()) {
            return;
        }

        int processed = 0;
        for (int i = 0; i < chunksPerTick && !queue.isEmpty(); i++) {
            DeferredChunk deferred = queue.poll();
            if (deferred != null) {
                processChunk(deferred);
                processed++;
                processedCount.incrementAndGet();
            }
        }

        // Log progress periodically
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 5000 && processed > 0) {
            lastLogTime = now;
            int remaining = queue.size();
            Logger.info("Deferred queue progress: " + processedCount.get() + " processed, " + remaining + " remaining");
        }
    }

    private static void processChunk(DeferredChunk deferred) {
        try {
            // Get the server level for this world
            ServerLevel level = findLevel(deferred.worldId);
            if (level == null)
                return;

            // Try to get the chunk (may need to load from disk)
            ChunkAccess chunk = level.getChunkSource().getChunkNow(deferred.x, deferred.z);
            if (chunk == null) {
                // Load from disk - won't generate new chunks
                try {
                    chunk = level.getChunkSource().getChunk(deferred.x, deferred.z, ChunkStatus.FULL, true);
                } catch (Exception e) {
                    return; // Chunk doesn't exist
                }
            }

            if (chunk instanceof LevelChunk levelChunk) {
                var instance = VoxyCommon.getInstance();
                if (instance == null)
                    return;

                var engine = instance.getNullable(deferred.worldId);
                if (engine == null)
                    return;

                instance.getIngestService().enqueueIngest(engine, levelChunk);
            }
        } catch (Exception e) {
            // Silently continue - chunk may have been unloaded
        }
    }

    private static ServerLevel findLevel(WorldIdentifier worldId) {
        if (server == null)
            return null;

        for (ServerLevel level : server.getAllLevels()) {
            if (WorldIdentifier.of(level).equals(worldId)) {
                return level;
            }
        }
        return null;
    }

    private static void finishProcessing() {
        isProcessing.set(false);
        Logger.info("Deferred chunk processing complete: " + processedCount.get() + " chunks processed");
        deferredCount.set(0);
    }

    /**
     * Shutdown the deferred queue.
     */
    public static void shutdown() {
        isProcessing.set(false);
        int remaining = queue.size();
        if (remaining > 0) {
            Logger.info("Shutdown with " + remaining + " deferred chunks unprocessed");
        }
        queue.clear();
        server = null;
    }

    /**
     * Get current queue size.
     */
    public static int getQueueSize() {
        return queue.size();
    }

    /**
     * Check if currently processing deferred chunks.
     */
    public static boolean isProcessing() {
        return isProcessing.get();
    }
}
