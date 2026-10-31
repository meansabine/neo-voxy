package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.*;
import me.cortex.voxy.common.world.SectionSerializer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side service that manages streaming LOD sections to connected players.
 * <p>
 * Uses a unified server-driven architecture:
 * <ul>
 * <li>Server iterates sections it has (ring-based expansion from player)</li>
 * <li>Uses client bloom filter hints to skip already-received sections</li>
 * <li>Prioritizes by distance and LOD level (lower LOD = higher priority)</li>
 * </ul>
 * Uses {@link ChunkedLodSender} for bandwidth-limited transfer.
 */
public class LodStreamingService implements AutoCloseable {

    private final WorldEngine worldEngine;
    private final SharedBandwidthLimit sharedBandwidthLimit;
    private final ConcurrentHashMap<UUID, PlayerStreamingState> playerStates = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // Config
    private final int perPlayerLimitKBps;
    private final int maxStreamingRadius;
    private final Path cacheDir;

    /**
     * Create streaming service with default settings.
     */
    public LodStreamingService(WorldEngine worldEngine) {
        this(worldEngine, new SharedBandwidthLimit(),
                SharedBandwidthLimit.DEFAULT_PLAYER_LIMIT_KBPS, 32);
    }

    /**
     * Create streaming service with custom settings.
     */
    public LodStreamingService(WorldEngine worldEngine, SharedBandwidthLimit sharedBandwidthLimit,
            int perPlayerLimitKBps, int maxStreamingRadius) {
        this.worldEngine = worldEngine;
        this.sharedBandwidthLimit = sharedBandwidthLimit;
        this.perPlayerLimitKBps = perPlayerLimitKBps;
        this.maxStreamingRadius = maxStreamingRadius;
        this.cacheDir = null; // Use default path

        // Keep the world engine alive while streaming service exists
        this.worldEngine.acquireRef();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VoxyLodStreaming");
            t.setDaemon(true);
            return t;
        });

        Logger.info("LodStreamingService initialized with server-driven streaming");
    }

    /**
     * Get the cache directory for bloom filters.
     */
    private Path getCacheDir() {
        if (cacheDir == null) {
            return Path.of("voxy_cache", "player_bloom_filters");
        }
        return cacheDir.resolve("player_bloom_filters");
    }

    /**
     * Save a player's bloom filter to disk.
     */
    private void savePlayerCache(UUID playerId, BloomFilter filter) {
        if (filter == null)
            return;
        try {
            Path cacheDir = getCacheDir();
            Files.createDirectories(cacheDir);
            Path cacheFile = cacheDir.resolve(playerId.toString() + ".bloom");
            Files.write(cacheFile, filter.toBytes());
            Logger.info("Saved bloom filter for " + playerId + " (" + filter.getSerializedSize() + " bytes)");
        } catch (IOException e) {
            Logger.error("Failed to save bloom filter for " + playerId + ": " + e.getMessage());
        }
    }

    /**
     * Load a player's bloom filter from disk.
     * Returns null if no filter exists or if the saved filter is too small to be
     * useful.
     */
    private BloomFilter loadPlayerCache(UUID playerId) {
        try {
            Path cacheFile = getCacheDir().resolve(playerId.toString() + ".bloom");
            if (Files.exists(cacheFile)) {
                byte[] data = Files.readAllBytes(cacheFile);
                // Reject filters that are too small (high false positive rate)
                // 10000 expected elements * 10 bits = 100000 bits = 12500 bytes minimum
                if (data.length < 1000) {
                    Logger.info("Saved bloom filter for " + playerId + " too small (" + data.length +
                            " bytes), discarding");
                    Files.delete(cacheFile); // Remove the bad filter
                    return null;
                }
                BloomFilter filter = BloomFilter.fromBytes(data);
                Logger.info("Loaded saved bloom filter for " + playerId + " (" + data.length + " bytes)");
                return filter;
            }
        } catch (IOException e) {
            Logger.error("Failed to load bloom filter for " + playerId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Handle rate update from client.
     */
    public void handleRateUpdate(ServerPlayer player, VoxyPacketPayload payload) {
        int desiredRate = payload.parseRate();
        PlayerStreamingState state = playerStates.get(player.getUUID());
        if (state != null) {
            state.clientDesiredRate = desiredRate;
        }
    }

    /**
     * Handle section request from client (deprecated - pull mode removed).
     * <p>
     * This method is kept for backward compatibility with older clients but no
     * longer
     * processes requests. The unified architecture uses server-driven streaming
     * only.
     * 
     * @deprecated Pull mode has been removed. Server now drives all section
     *             streaming.
     */
    @Deprecated
    public void handleSectionRequest(ServerPlayer player, VoxyPacketPayload payload) {
        // Pull mode deprecated - server now drives all streaming
        // Log once per player to help diagnose old clients
        Logger.info("Ignoring pull request from " + player.getName().getString() +
                " (pull mode deprecated, using server-driven streaming)");
    }

    /**
     * Start LOD sync for a player. Called by VoxyServer when it receives a sync
     * request.
     */
    public void startSyncForPlayer(ServerPlayer player) {
        Logger.info("Received sync request from " + player.getName().getString());

        PlayerStreamingState state = playerStates.computeIfAbsent(
                player.getUUID(),
                uuid -> new PlayerStreamingState(player, sharedBandwidthLimit, perPlayerLimitKBps));

        // Load saved bloom filter from previous session
        BloomFilter savedFilter = loadPlayerCache(player.getUUID());
        if (savedFilter != null) {
            state.clientCacheFilter = savedFilter;
            Logger.info("Using saved bloom filter for " + player.getName().getString());
        }

        // Send mapper data
        sendMapperSync(player);

        // Request bloom filter from client to skip sections they already have
        VoxyNetworkHandler.sendToPlayer(player, VoxyPacketPayload.cacheQuery(new long[0]));

        // Start server-driven streaming (ring-based expansion from player position)
        scheduler.submit(() -> startStreaming(state));

        Logger.info("Server-driven streaming enabled for " + player.getName().getString());
    }

    /**
     * Send the mapper sync packet to a player.
     */
    private void sendMapperSync(ServerPlayer player) {
        byte[] mapperData = IdRemapper.serializeMapper(worldEngine.getMapper());
        VoxyNetworkHandler.sendToPlayer(player, VoxyPacketPayload.mapperSync(mapperData));
        Logger.info("Sent mapper sync to " + player.getName().getString() +
                " (" + mapperData.length + " bytes)");
    }

    /**
     * Server-driven streaming - continuously streams sections from player position.
     * Uses bloom filter to skip sections the client already has.
     * Continues until the edge of available LODs, then periodically rescans for new
     * data.
     */
    private void startStreaming(PlayerStreamingState state) {
        if (!isActive.get() || !state.player.isAlive()) {
            return;
        }

        // Keep the world engine marked as active during streaming
        worldEngine.markActive();

        // Ensure bloom filter exists for tracking sent sections
        if (state.clientCacheFilter == null) {
            state.clientCacheFilter = BloomFilter.forExpectedElements(10000);
        }

        int sectionsQueued = 0;
        int sectionsFound = 0; // Tracks server sections found (even if already sent/in bloom)
        int currentRing = state.currentRing;

        // Get player chunk position
        int playerChunkX = state.player.getBlockX() >> 5; // 32-block sections
        int playerChunkZ = state.player.getBlockZ() >> 5;

        // Stream sections in current ring
        for (int dx = -currentRing; dx <= currentRing; dx++) {
            for (int dz = -currentRing; dz <= currentRing; dz++) {
                // Only process ring boundary (or ring 0 which is just center)
                if (currentRing > 0 && Math.abs(dx) != currentRing && Math.abs(dz) != currentRing) {
                    continue;
                }

                int sectionX = playerChunkX + dx;
                int sectionZ = playerChunkZ + dz;

                // Stream all Y levels and LOD levels
                for (int lvl = WorldEngine.MAX_LOD_LAYER; lvl >= 0; lvl--) {
                    for (int y = -4; y < 20; y++) { // Reasonable Y range
                        long key = WorldEngine.getWorldSectionId(lvl, sectionX, y, sectionZ);

                        // Check if section exists on server (regardless of bloom filter)
                        WorldSection section = worldEngine.acquireIfExists(key);
                        if (section != null) {
                            try {
                                // For Level 0: check block count
                                // For Level 1+: check child existence mask to ensure it's non-zero
                                boolean hasContent;
                                if (lvl == 0) {
                                    hasContent = section.getNonEmptyBlockCount() > 0;
                                } else {
                                    // Level 1+ sections must have a non-zero child existence mask
                                    // This prevents sending sections that would cause "existence mask of 0"
                                    // warnings on client
                                    byte childMask = section.getNonEmptyChildren();
                                    hasContent = childMask != 0;
                                }

                                if (hasContent) {
                                    sectionsFound++; // Server has this section

                                    // Skip if already sent or in bloom filter
                                    if (state.sentSections.contains(key) ||
                                            state.clientCacheFilter.mightContain(key)) {
                                        continue;
                                    }

                                    // Send this section
                                    byte[] data = SectionSerializer.serialize(section);
                                    state.sender.queueSection(data, (int) key);
                                    state.sentSections.add(key);
                                    state.clientCacheFilter.add(key); // Track in bloom filter for persistence
                                    sectionsQueued++;
                                }
                            } finally {
                                section.release();
                            }
                        }
                    }
                }
            }
        }

        // Track empty rings to detect edge of available data
        // Only count as "empty" if server has NO sections in this ring at all
        if (sectionsFound > 0) {
            state.consecutiveEmptyRings = 0;
            if (sectionsQueued > 0) {
                Logger.info("Queued " + sectionsQueued + " sections for " +
                        state.player.getName().getString() + " (ring " + currentRing +
                        ", found " + sectionsFound + ")");
            }
        } else {
            state.consecutiveEmptyRings++;
        }

        state.currentRing++;

        // Calculate delay based on distance (further rings = slower)
        // Base: 100ms, increases with distance up to max 2000ms
        long delayMs = Math.min(100 + (currentRing * 20L), 2000);

        // If we've hit several consecutive empty rings, switch to maintenance mode
        if (state.consecutiveEmptyRings >= 5) {
            // Save bloom filter before entering maintenance mode
            savePlayerCache(state.player.getUUID(), state.clientCacheFilter);
            Logger.info("Entering maintenance mode for " + state.player.getName().getString() +
                    " (reached edge of LOD data at ring " + currentRing + ")");

            // Schedule periodic rescan (every 30 seconds) to pick up new LODs
            scheduleMaintenanceScan(state);
        } else if (isActive.get()) {
            // Continue streaming next ring
            scheduler.schedule(() -> startStreaming(state), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Maintenance mode - periodically rescans from player position for new LODs.
     */
    private void scheduleMaintenanceScan(PlayerStreamingState state) {
        if (!isActive.get() || !state.player.isAlive()) {
            return;
        }

        // Schedule a rescan starting from ring 0 after 30 seconds
        scheduler.schedule(() -> {
            if (isActive.get() && state.player.isAlive()) {
                state.currentRing = 0;
                state.consecutiveEmptyRings = 0;
                Logger.info("Starting maintenance scan for " + state.player.getName().getString());
                startStreaming(state);
            }
        }, 30, TimeUnit.SECONDS);
    }

    /**
     * Handle cache response from client (bloom filter).
     */
    public void handleCacheResponse(ServerPlayer player, VoxyPacketPayload payload) {
        BloomFilter clientCache = payload.parseCacheResponseBloomFilter();
        PlayerStreamingState state = playerStates.get(player.getUUID());

        if (state != null) {
            // Ensure we have a properly-sized filter
            if (state.clientCacheFilter == null) {
                state.clientCacheFilter = BloomFilter.forExpectedElements(10000);
            }

            // Merge client's filter into our properly-sized one
            // This picks up any sections the client already has (if they report them)
            if (clientCache != null && clientCache.getSerializedSize() > 100) {
                state.clientCacheFilter.merge(clientCache);
                Logger.info("Merged client bloom filter for " + player.getName().getString());
            } else {
                Logger.info("Client bloom filter too small, using server-side only for " +
                        player.getName().getString());
            }
            // Don't save immediately - wait until actual sections are sent
        }
    }

    /**
     * Called when a player disconnects.
     */
    public void onPlayerDisconnect(UUID playerId) {
        PlayerStreamingState state = playerStates.remove(playerId);
        if (state != null) {
            // Save bloom filter before closing
            if (state.clientCacheFilter != null) {
                savePlayerCache(playerId, state.clientCacheFilter);
            }
            state.close();
        }
        VoxyNetworkHandler.removePlayer(playerId);
    }

    /**
     * Get streaming stats for a player.
     */
    public String getPlayerStats(UUID playerId) {
        PlayerStreamingState state = playerStates.get(playerId);
        if (state == null) {
            return "No active streaming";
        }
        return state.sender.getStatsString() +
                ", Sent: " + state.sentSections.size();
    }

    @Override
    public void close() {
        isActive.set(false);
        scheduler.shutdown();

        for (PlayerStreamingState state : playerStates.values()) {
            state.close();
        }
        playerStates.clear();

        // Release the world engine reference
        try {
            worldEngine.releaseRef();
        } catch (Exception e) {
            Logger.error("Error releasing world engine ref", e);
        }

        Logger.info("LodStreamingService closed");
    }

    /**
     * Per-player streaming state.
     */
    private static class PlayerStreamingState {
        ServerPlayer player;
        final ChunkedLodSender sender;
        final Set<Long> sentSections = ConcurrentHashMap.newKeySet();

        // Streaming state
        int currentRing = 0;
        int consecutiveEmptyRings = 0;
        int clientDesiredRate = SharedBandwidthLimit.DEFAULT_PLAYER_LIMIT_KBPS;
        BloomFilter clientCacheFilter = null;

        PlayerStreamingState(ServerPlayer player, SharedBandwidthLimit sharedLimit, int limitKBps) {
            this.player = player;
            this.sender = new ChunkedLodSender(player, sharedLimit, limitKBps);
        }

        void close() {
            sender.close();
        }
    }
}
