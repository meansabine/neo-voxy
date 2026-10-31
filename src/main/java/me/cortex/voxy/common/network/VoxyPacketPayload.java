package me.cortex.voxy.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Custom packet payload for all Voxy LOD streaming network messages.
 * <p>
 * Message types:
 * <ul>
 * <li>{@link #MSG_MAPPER_SYNC} - Server sends block/biome mapping table on
 * connect</li>
 * <li>{@link #MSG_LOD_SECTION} - Complete section data (small sections)</li>
 * <li>{@link #MSG_LOD_CHUNK} - Chunk of large section (chunked transfer)</li>
 * <li>{@link #MSG_CACHE_QUERY} - Server asks what sections client has</li>
 * <li>{@link #MSG_CACHE_RESPONSE} - Client responds with bloom filter</li>
 * <li>{@link #MSG_RATE_UPDATE} - Client sends desired rate to server</li>
 * <li>{@link #MSG_SYNC_REQUEST} - Client requests LOD sync</li>
 * <li>{@link #MSG_REQUEST_SECTIONS} - Client requests specific sections (pull
 * model)</li>
 * </ul>
 */
public record VoxyPacketPayload(byte messageType, byte[] data) implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("neovoxy", "lod_sync");
    public static final Type<VoxyPacketPayload> TYPE = new Type<>(ID);

    // Message type constants
    public static final byte MSG_MAPPER_SYNC = 0; // Server→Client: block/biome ID mapping table
    public static final byte MSG_LOD_SECTION = 1; // Server→Client: complete section data
    public static final byte MSG_LOD_CHUNK = 2; // Server→Client: chunk of large section
    public static final byte MSG_CACHE_QUERY = 3; // Server→Client: asks what sections client has
    public static final byte MSG_CACHE_RESPONSE = 4; // Client→Server: bloom filter response
    public static final byte MSG_RATE_UPDATE = 5; // Client→Server: desired rate from congestion control
    public static final byte MSG_SYNC_REQUEST = 6; // Client→Server: request LOD sync
    public static final byte MSG_SYNC_COMPLETE = 7; // Server→Client: signals streaming complete
    public static final byte MSG_REQUEST_SECTIONS = 8; // Client→Server: request specific sections (pull model)

    @NotNull
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * StreamCodec for encoding/decoding VoxyPacketPayload to/from FriendlyByteBuf.
     */
    public static class Codec implements StreamCodec<FriendlyByteBuf, VoxyPacketPayload> {

        public static final Codec INSTANCE = new Codec();

        @NotNull
        @Override
        public VoxyPacketPayload decode(@NotNull FriendlyByteBuf buf) {
            byte messageType = buf.readByte();
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new VoxyPacketPayload(messageType, data);
        }

        @Override
        public void encode(@NotNull FriendlyByteBuf buf, VoxyPacketPayload payload) {
            buf.writeByte(payload.messageType);
            buf.writeVarInt(payload.data.length);
            buf.writeBytes(payload.data);
        }
    }

    /**
     * Helper to create a section payload.
     */
    public static VoxyPacketPayload section(byte[] sectionData) {
        return new VoxyPacketPayload(MSG_LOD_SECTION, sectionData);
    }

    /**
     * Helper to create a chunk payload for chunked transfer.
     */
    public static VoxyPacketPayload chunk(byte[] chunkData) {
        return new VoxyPacketPayload(MSG_LOD_CHUNK, chunkData);
    }

    /**
     * Helper to create a mapper sync payload.
     */
    public static VoxyPacketPayload mapperSync(byte[] mapperData) {
        return new VoxyPacketPayload(MSG_MAPPER_SYNC, mapperData);
    }

    /**
     * Helper to create a sync request payload.
     */
    public static VoxyPacketPayload syncRequest() {
        return new VoxyPacketPayload(MSG_SYNC_REQUEST, new byte[0]);
    }

    /**
     * Helper to create a rate update payload.
     */
    public static VoxyPacketPayload rateUpdate(int desiredRateKBps) {
        byte[] data = new byte[4];
        data[0] = (byte) (desiredRateKBps >> 24);
        data[1] = (byte) (desiredRateKBps >> 16);
        data[2] = (byte) (desiredRateKBps >> 8);
        data[3] = (byte) desiredRateKBps;
        return new VoxyPacketPayload(MSG_RATE_UPDATE, data);
    }

    /**
     * Parse rate from a rate update payload.
     */
    public int parseRate() {
        if (messageType != MSG_RATE_UPDATE || data.length < 4) {
            return 0;
        }
        return ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) |
                ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
    }

    /**
     * Helper to create a section request payload (client→server).
     * Uses delta-encoding for efficient compression of adjacent section keys.
     * 
     * @param sectionKeys Array of section keys to request
     * @return Payload with compressed section keys
     */
    public static VoxyPacketPayload requestSections(long[] sectionKeys) {
        if (sectionKeys == null || sectionKeys.length == 0) {
            return new VoxyPacketPayload(MSG_REQUEST_SECTIONS, new byte[0]);
        }

        // Sort keys for better delta compression
        long[] sorted = sectionKeys.clone();
        java.util.Arrays.sort(sorted);

        // Calculate buffer size: 4 bytes count + 8 bytes first key + variable deltas
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(baos);

        try {
            out.writeInt(sorted.length);
            out.writeLong(sorted[0]); // First key in full

            // Delta-encode remaining keys
            for (int i = 1; i < sorted.length; i++) {
                long delta = sorted[i] - sorted[i - 1];
                writeVarLong(out, delta);
            }

            return new VoxyPacketPayload(MSG_REQUEST_SECTIONS, baos.toByteArray());
        } catch (java.io.IOException e) {
            return new VoxyPacketPayload(MSG_REQUEST_SECTIONS, new byte[0]);
        }
    }

    /**
     * Parse requested section keys from a request payload.
     * 
     * @return Array of section keys, or empty array on error
     */
    public long[] parseRequestedKeys() {
        if (messageType != MSG_REQUEST_SECTIONS || data.length < 4) {
            return new long[0];
        }

        try {
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(data));

            int count = in.readInt();
            if (count <= 0 || count > 2000) { // Limit batch size
                return new long[0];
            }

            long[] keys = new long[count];
            keys[0] = in.readLong(); // First key in full

            // Delta-decode remaining keys
            for (int i = 1; i < count; i++) {
                long delta = readVarLong(in);
                keys[i] = keys[i - 1] + delta;
            }

            return keys;
        } catch (java.io.IOException e) {
            return new long[0];
        }
    }

    /**
     * Write a variable-length long (similar to VarInt but for longs).
     */
    private static void writeVarLong(java.io.DataOutputStream out, long value) throws java.io.IOException {
        while ((value & ~0x7FL) != 0) {
            out.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    /**
     * Read a variable-length long.
     */
    private static long readVarLong(java.io.DataInputStream in) throws java.io.IOException {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = in.readByte();
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    /**
     * Helper to create a cache query payload (server→client).
     * Queries specific section keys to check if client has them.
     * 
     * @param sectionKeys Keys to query
     * @return Cache query payload
     */
    public static VoxyPacketPayload cacheQuery(long[] sectionKeys) {
        return new VoxyPacketPayload(MSG_CACHE_QUERY, encodeLongArray(sectionKeys));
    }

    /**
     * Parse section keys from a cache query payload.
     * 
     * @return Array of queried section keys
     */
    public long[] parseCacheQueryKeys() {
        if (messageType != MSG_CACHE_QUERY) {
            return new long[0];
        }
        return decodeLongArray(data);
    }

    /**
     * Helper to create a cache response payload (client→server).
     * Contains a bloom filter representing sections the client has.
     * 
     * @param bloomFilter Bloom filter with cached section keys
     * @return Cache response payload
     */
    public static VoxyPacketPayload cacheResponse(BloomFilter bloomFilter) {
        return new VoxyPacketPayload(MSG_CACHE_RESPONSE, bloomFilter.toBytes());
    }

    /**
     * Parse bloom filter from a cache response payload.
     * 
     * @return BloomFilter representing client's cached sections
     */
    public BloomFilter parseCacheResponseBloomFilter() {
        if (messageType != MSG_CACHE_RESPONSE) {
            return BloomFilter.forExpectedElements(0);
        }
        return BloomFilter.fromBytes(data);
    }

    /**
     * Encode a long array to bytes using delta compression.
     */
    private static byte[] encodeLongArray(long[] keys) {
        if (keys == null || keys.length == 0) {
            return new byte[0];
        }

        long[] sorted = keys.clone();
        java.util.Arrays.sort(sorted);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(baos);

        try {
            out.writeInt(sorted.length);
            out.writeLong(sorted[0]);
            for (int i = 1; i < sorted.length; i++) {
                writeVarLong(out, sorted[i] - sorted[i - 1]);
            }
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            return new byte[0];
        }
    }

    /**
     * Decode a long array from delta-compressed bytes.
     */
    private static long[] decodeLongArray(byte[] data) {
        if (data == null || data.length < 4) {
            return new long[0];
        }

        try {
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(data));

            int count = in.readInt();
            if (count <= 0 || count > 10000) {
                return new long[0];
            }

            long[] keys = new long[count];
            keys[0] = in.readLong();
            for (int i = 1; i < count; i++) {
                keys[i] = keys[i - 1] + readVarLong(in);
            }
            return keys;
        } catch (java.io.IOException e) {
            return new long[0];
        }
    }
}
