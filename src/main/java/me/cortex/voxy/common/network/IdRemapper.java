package me.cortex.voxy.common.network;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Remaps block and biome IDs from server to client mappings.
 * <p>
 * When receiving LOD data from a server, the block/biome IDs in the data
 * correspond to the server's Mapper. This class builds a translation table
 * from server IDs to client IDs so the data can be correctly interpreted.
 */
public class IdRemapper {

    private final Int2IntOpenHashMap serverToClientBlock = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap serverToClientBiome = new Int2IntOpenHashMap();

    private boolean isReady = false;

    public IdRemapper() {
        // Default: map 0→0 for air
        serverToClientBlock.defaultReturnValue(0);
        serverToClientBiome.defaultReturnValue(0);
    }

    /**
     * Build the remapping tables from serialized server mapper data.
     * 
     * @param serverMapperData Serialized mapper data from server
     * @param clientMapper     The client's local mapper
     */
    public void buildFromServerData(byte[] serverMapperData, Mapper clientMapper) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(serverMapperData))) {
            // Read version
            int version = in.readInt();
            if (version != 1) {
                Logger.warn("Unknown mapper sync version: " + version);
                return;
            }

            // Read block states
            int blockStateCount = in.readInt();
            for (int i = 0; i < blockStateCount; i++) {
                int serverId = in.readInt();
                String blockStateString = in.readUTF();
                // Register block state on-the-fly if needed
                int clientId = clientMapper.getOrRegisterBlockStateFromString(blockStateString);
                serverToClientBlock.put(serverId, clientId);
            }

            // Read biomes
            int biomeCount = in.readInt();
            for (int i = 0; i < biomeCount; i++) {
                int serverId = in.readInt();
                String biomeString = in.readUTF();
                // Register biome on-the-fly if needed
                int clientId = clientMapper.getOrRegisterBiomeFromString(biomeString);
                serverToClientBiome.put(serverId, clientId);
            }

            isReady = true;
            Logger.info("Built ID remapper: " + blockStateCount + " blocks, " + biomeCount + " biomes");

        } catch (IOException e) {
            Logger.error("Failed to parse server mapper data: " + e.getMessage());
            Logger.error(e);
        }
    }

    /**
     * Remap a voxel ID from server IDs to client IDs.
     * 
     * @param serverVoxelId The voxel ID using server mappings
     * @return The voxel ID using client mappings
     */
    public long remapVoxelId(long serverVoxelId) {
        int serverBlockId = Mapper.getBlockId(serverVoxelId);
        int serverBiomeId = Mapper.getBiomeId(serverVoxelId);
        int light = Mapper.getLightId(serverVoxelId);

        int clientBlockId = serverToClientBlock.get(serverBlockId);
        int clientBiomeId = serverToClientBiome.get(serverBiomeId);

        return Mapper.composeMappingId((byte) light, clientBlockId, clientBiomeId);
    }

    /**
     * Check if the remapper has been initialized with server data.
     */
    public boolean isReady() {
        return isReady;
    }

    /**
     * Reset the remapper (e.g., on disconnect).
     */
    public void reset() {
        serverToClientBlock.clear();
        serverToClientBiome.clear();
        serverToClientBlock.defaultReturnValue(0);
        serverToClientBiome.defaultReturnValue(0);
        isReady = false;
    }

    // ==================== //
    // Server-side helpers //
    // ==================== //

    /**
     * Serialize a Mapper's state entries for network transfer.
     * Called on the server to send to clients.
     */
    public static byte[] serializeMapper(Mapper mapper) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(baos)) {

            // Version
            out.writeInt(1);

            // Block states
            var stateEntries = mapper.getStateEntries();
            out.writeInt(stateEntries.length);
            for (var entry : stateEntries) {
                out.writeInt(entry.id);
                out.writeUTF(entry.state.toString()); // Serializes as "Block{namespace:name}[property=value,...]"
            }

            // Biomes
            var biomeEntries = mapper.getBiomeEntries();
            out.writeInt(biomeEntries.length);
            for (var entry : biomeEntries) {
                out.writeInt(entry.id);
                out.writeUTF(entry.biomeKey); // Resource location string
            }

            return baos.toByteArray();

        } catch (IOException e) {
            Logger.error("Failed to serialize mapper: " + e.getMessage());
            Logger.error(e);
            return new byte[0];
        }
    }
}
