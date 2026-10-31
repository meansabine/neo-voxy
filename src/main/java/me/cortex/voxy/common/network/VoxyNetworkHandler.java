package me.cortex.voxy.common.network;

import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Central network handler for Voxy LOD streaming.
 * <p>
 * Handles registration of custom payloads with NeoForge and dispatches
 * incoming messages to appropriate handlers.
 */
public class VoxyNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    // Server-side: handlers for client→server messages
    private static BiConsumer<ServerPlayer, VoxyPacketPayload> serverMessageHandler;

    // Client-side: handler for server→client messages
    private static java.util.function.Consumer<VoxyPacketPayload> clientMessageHandler;

    // Track which players have LOD streaming enabled
    private static final ConcurrentHashMap<UUID, Boolean> playerCapabilities = new ConcurrentHashMap<>();

    /**
     * Register the payload handler with NeoForge.
     * Call this from the mod's RegisterPayloadHandlersEvent.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();

        registrar.playBidirectional(
                VoxyPacketPayload.TYPE,
                VoxyPacketPayload.Codec.INSTANCE,
                VoxyNetworkHandler::handlePayload);

        Logger.info("Registered Voxy LOD streaming network handler");
    }

    /**
     * Set the server-side message handler.
     * Called when a client sends a message to the server.
     */
    public static void setServerMessageHandler(BiConsumer<ServerPlayer, VoxyPacketPayload> handler) {
        serverMessageHandler = handler;
    }

    /**
     * Set the client-side message handler.
     * Called when the server sends a message to the client.
     */
    public static void setClientMessageHandler(java.util.function.Consumer<VoxyPacketPayload> handler) {
        clientMessageHandler = handler;
    }

    /**
     * Handle incoming payload from either direction.
     */
    private static void handlePayload(VoxyPacketPayload payload, IPayloadContext context) {
        // Determine if this is server or client side
        if (context.player() instanceof ServerPlayer serverPlayer) {
            // Server receiving from client
            handleServerbound(serverPlayer, payload);
        } else {
            // Client receiving from server
            handleClientbound(payload);
        }
    }

    /**
     * Handle messages received on the server from clients.
     */
    private static void handleServerbound(ServerPlayer player, VoxyPacketPayload payload) {
        if (serverMessageHandler != null) {
            try {
                serverMessageHandler.accept(player, payload);
            } catch (Exception e) {
                Logger.error("Error handling serverbound Voxy packet: " + e.getMessage());
                Logger.error(e);
            }
        }
    }

    /**
     * Handle messages received on the client from server.
     */
    private static void handleClientbound(VoxyPacketPayload payload) {
        if (clientMessageHandler != null) {
            try {
                clientMessageHandler.accept(payload);
            } catch (Exception e) {
                Logger.error("Error handling clientbound Voxy packet: " + e.getMessage());
                Logger.error(e);
            }
        }
    }

    // ==================== //
    // Sending Methods //
    // ==================== //

    /**
     * Send a payload to a specific player (server→client).
     */
    public static void sendToPlayer(ServerPlayer player, VoxyPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * Send a payload to the server (client→server).
     */
    public static void sendToServer(VoxyPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    // ==================== //
    // Player Capabilities //
    // ==================== //

    /**
     * Mark a player as having LOD streaming capability.
     */
    public static void setPlayerCapable(UUID playerId, boolean capable) {
        if (capable) {
            playerCapabilities.put(playerId, true);
        } else {
            playerCapabilities.remove(playerId);
        }
    }

    /**
     * Check if a player has LOD streaming capability.
     */
    public static boolean isPlayerCapable(UUID playerId) {
        return playerCapabilities.getOrDefault(playerId, false);
    }

    /**
     * Remove a player from capability tracking (on disconnect).
     */
    public static void removePlayer(UUID playerId) {
        playerCapabilities.remove(playerId);
    }

    // ==================== //
    // Single-player Check //
    // ==================== //

    /**
     * Check if we're running in a single-player/integrated server context.
     * In single-player, we don't need network streaming - data is local.
     */
    public static boolean isSinglePlayer() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null)
            return false;

        // Check if we have an integrated server (single-player or LAN host)
        var integratedServer = mc.getSingleplayerServer();
        return integratedServer != null;
    }

    /**
     * Check if LOD streaming should be enabled.
     * Disabled in single-player since data is already local.
     */
    public static boolean shouldEnableStreaming() {
        return !isSinglePlayer();
    }
}
