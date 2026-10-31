package me.cortex.voxy.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side admin commands for Neo-Voxy.
 * Provides commands for LOD generation, status checking, and management.
 * 
 * Commands:
 * - /voxyadmin status - Show WorldEngine status and metrics
 * - /voxyadmin keep <seconds> - Keep WorldEngine alive for specified duration
 * - /voxyadmin generate [radius] - Generate LODs around executor position
 * - /voxyadmin generate <radius> <x> <z> - Generate LODs around coordinates
 * - /voxyadmin cancel - Cancel current generation
 * - /voxyadmin broadcast - Send sync request to all connected players
 */
public class VoxyServerCommands {

    // Track processors per level
    private static final Map<ServerLevel, ChunkFileProcessor> processors = new HashMap<>();

    /**
     * Register server commands with the command dispatcher
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> voxyCommand = Commands.literal("voxyadmin")
                .requires(source -> source.hasPermission(2)) // Require op level 2

                // /voxyadmin status - Show status
                .then(Commands.literal("status")
                        .executes(context -> showStatus(context.getSource())))

                // /voxyadmin keep <seconds> - Keep WorldEngine alive
                .then(Commands.literal("keep")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .executes(context -> keepAlive(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds")))))

                // /voxyadmin generate - Generate LODs around position
                .then(Commands.literal("generate")
                        .executes(context -> generateAroundPlayer(context.getSource(), 100))

                        // /voxyadmin generate <radius> - Generate LODs in radius around player
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 1000))
                                .executes(context -> generateAroundPlayer(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius")))

                                // /voxyadmin generate <radius> <x> <z> - Generate at specific coords
                                .then(Commands.argument("centerX", IntegerArgumentType.integer(-30000000, 30000000))
                                        .then(Commands
                                                .argument("centerZ", IntegerArgumentType.integer(-30000000, 30000000))
                                                .executes(context -> generateAtCoords(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        IntegerArgumentType.getInteger(context, "centerX"),
                                                        IntegerArgumentType.getInteger(context, "centerZ")))))))

                // /voxyadmin cancel - Cancel processing
                .then(Commands.literal("cancel")
                        .executes(context -> cancelProcessing(context.getSource())))

                // /voxyadmin broadcast - Broadcast sync to all players
                .then(Commands.literal("broadcast")
                        .executes(context -> broadcastSync(context.getSource())));

        dispatcher.register(voxyCommand);
        Logger.info("Registered VoxyAdmin server commands");
    }

    /**
     * Show status of WorldEngine and processing
     */
    private static int showStatus(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        WorldIdentifier worldId = WorldIdentifier.of(serverLevel);
        var instance = VoxyCommon.getInstance();

        source.sendSuccess(() -> Component.literal("=== NeoVoxy Server Status ==="), false);
        source.sendSuccess(() -> Component.literal("Level: " + serverLevel.dimension().location()), false);

        if (instance == null) {
            source.sendSuccess(() -> Component.literal("VoxyCommon: Not initialized"), false);
            return 1;
        }

        WorldEngine engine = instance.getNullable(worldId);
        if (engine == null) {
            source.sendSuccess(() -> Component.literal("WorldEngine: Not created (no LOD data yet)"), false);
        } else {
            source.sendSuccess(() -> Component.literal("WorldEngine: Active"), false);
            source.sendSuccess(() -> Component.literal("  Live: " + engine.isLive()), false);
            source.sendSuccess(() -> Component.literal("  Active sections: " + engine.getActiveSectionCount()), false);
            source.sendSuccess(() -> Component.literal("  World used: " + engine.isWorldUsed()), false);
            source.sendSuccess(() -> Component.literal("  World idle: " + engine.isWorldIdle()), false);
        }

        // Show processing status
        ChunkFileProcessor processor = processors.get(serverLevel);
        if (processor != null && processor.isProcessing()) {
            source.sendSuccess(() -> Component.literal("Processing: " + processor.getStatusString()), false);
        } else {
            source.sendSuccess(() -> Component.literal("Processing: Idle"), false);
        }

        return 1;
    }

    /**
     * Keep the WorldEngine alive for specified seconds
     */
    private static int keepAlive(CommandSourceStack source, int seconds) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        WorldIdentifier worldId = WorldIdentifier.of(serverLevel);
        var instance = VoxyCommon.getInstance();

        if (instance == null) {
            source.sendFailure(Component.literal("VoxyCommon not initialized"));
            return 0;
        }

        // Get or create the engine
        WorldEngine engine = instance.getOrCreate(worldId);
        if (engine == null) {
            source.sendFailure(Component.literal("Failed to create WorldEngine"));
            return 0;
        }

        // Keep the engine alive
        engine.keepAlive(seconds * 1000L);
        source.sendSuccess(() -> Component.literal("WorldEngine will be kept alive for " + seconds + " seconds"), true);

        return 1;
    }

    /**
     * Generate LODs around the command sender's position
     */
    private static int generateAroundPlayer(CommandSourceStack source, int radius) {
        BlockPos center = BlockPos.containing(source.getPosition());
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        return generateInRadius(source, radius, centerChunkX, centerChunkZ);
    }

    /**
     * Generate LODs at specific block coordinates
     */
    private static int generateAtCoords(CommandSourceStack source, int radius, int blockX, int blockZ) {
        int centerChunkX = blockX >> 4;
        int centerChunkZ = blockZ >> 4;

        return generateInRadius(source, radius, centerChunkX, centerChunkZ);
    }

    /**
     * Generate LODs within a radius of specified chunk coordinates
     */
    private static int generateInRadius(CommandSourceStack source, int radius, int centerChunkX, int centerChunkZ) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        ChunkFileProcessor processor = getOrCreateProcessor(serverLevel);

        if (processor.isProcessing()) {
            source.sendFailure(Component.literal(
                    "Processing already in progress. Use '/voxyadmin status' to check progress or '/voxyadmin cancel' to stop."));
            return 0;
        }

        int blockX = centerChunkX << 4;
        int blockZ = centerChunkZ << 4;
        int totalChunks = (2 * radius + 1) * (2 * radius + 1);

        source.sendSuccess(() -> Component.literal(
                "Starting LOD generation for " + totalChunks + " chunks around (" + blockX + ", " + blockZ + ")..."),
                true);

        processor.processChunksInRadius(centerChunkX, centerChunkZ, radius).thenRun(() -> {
            source.sendSuccess(() -> Component.literal("Finished: " + processor.getStatusString()), true);
        });

        return 1;
    }

    /**
     * Cancel current processing
     */
    private static int cancelProcessing(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        ChunkFileProcessor processor = processors.get(serverLevel);

        if (processor == null || !processor.isProcessing()) {
            source.sendFailure(Component.literal("No processing in progress to cancel"));
            return 0;
        }

        processor.cancel();
        source.sendSuccess(() -> Component.literal("Cancelling chunk processing..."), true);

        return 1;
    }

    /**
     * Broadcast sync request to all players in the level
     */
    private static int broadcastSync(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        Collection<ServerPlayer> players = serverLevel.players();
        if (players.isEmpty()) {
            source.sendFailure(Component.literal("No players in this level"));
            return 0;
        }

        VoxyServer.broadcastSync(serverLevel);
        source.sendSuccess(() -> Component.literal("Broadcast sync request to " + players.size() + " players"), true);

        return 1;
    }

    /**
     * Get or create a processor for the given level
     */
    private static ChunkFileProcessor getOrCreateProcessor(ServerLevel level) {
        return processors.computeIfAbsent(level, ChunkFileProcessor::new);
    }

    /**
     * Tick all processors (called from server tick event)
     */
    public static void tickProcessors() {
        for (ChunkFileProcessor processor : processors.values()) {
            if (processor.isProcessing()) {
                processor.tick();
            }
        }
    }

    /**
     * Cleanup processors when server stops
     */
    public static void cleanup() {
        for (ChunkFileProcessor processor : processors.values()) {
            processor.shutdown();
        }
        processors.clear();
    }
}
