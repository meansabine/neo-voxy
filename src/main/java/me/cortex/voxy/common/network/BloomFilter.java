package me.cortex.voxy.common.network;

import java.util.Collection;

/**
 * Simple bloom filter for efficient probabilistic membership testing.
 * <p>
 * Used by the pull LOD streaming system to allow the client to communicate
 * which sections it already has without sending the full list of keys.
 * False positives are possible (may say "has" when doesn't), but false
 * negatives are not (will never say "doesn't have" when it does).
 */
public class BloomFilter {

    private static final int DEFAULT_BITS_PER_ELEMENT = 10;
    private static final int NUM_HASH_FUNCTIONS = 7;

    private final long[] bits;
    private final int numBits;
    private final int numHashFunctions;

    /**
     * Create a bloom filter with the specified number of bits.
     */
    public BloomFilter(int numBits) {
        this.numBits = numBits;
        this.numHashFunctions = NUM_HASH_FUNCTIONS;
        this.bits = new long[(numBits + 63) / 64];
    }

    /**
     * Create a bloom filter sized for the expected number of elements.
     * 
     * @param expectedElements Expected number of elements to add
     * @return Appropriately sized bloom filter
     */
    public static BloomFilter forExpectedElements(int expectedElements) {
        int numBits = Math.max(64, expectedElements * DEFAULT_BITS_PER_ELEMENT);
        return new BloomFilter(numBits);
    }

    /**
     * Create a bloom filter from serialized bytes.
     */
    public static BloomFilter fromBytes(byte[] data) {
        if (data == null || data.length < 4) {
            return new BloomFilter(64);
        }

        int numBits = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) |
                ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

        BloomFilter filter = new BloomFilter(numBits);
        int longCount = filter.bits.length;
        int byteIndex = 4;

        for (int i = 0; i < longCount && byteIndex + 7 < data.length; i++) {
            long value = 0;
            for (int j = 0; j < 8; j++) {
                value |= ((long) (data[byteIndex++] & 0xFF)) << (j * 8);
            }
            filter.bits[i] = value;
        }

        return filter;
    }

    /**
     * Add a section key to the filter.
     */
    public void add(long key) {
        for (int i = 0; i < numHashFunctions; i++) {
            int hash = hash(key, i);
            int bitIndex = Math.abs(hash % numBits);
            bits[bitIndex / 64] |= (1L << (bitIndex % 64));
        }
    }

    /**
     * Add all keys from a collection.
     */
    public void addAll(Collection<Long> keys) {
        for (Long key : keys) {
            add(key);
        }
    }

    /**
     * Add all keys from an array.
     */
    public void addAll(long[] keys) {
        for (long key : keys) {
            add(key);
        }
    }

    /**
     * Check if a key might be in the filter.
     * 
     * @return true if the key might be present, false if definitely not present
     */
    public boolean mightContain(long key) {
        for (int i = 0; i < numHashFunctions; i++) {
            int hash = hash(key, i);
            int bitIndex = Math.abs(hash % numBits);
            if ((bits[bitIndex / 64] & (1L << (bitIndex % 64))) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Serialize the bloom filter to bytes for network transfer.
     */
    public byte[] toBytes() {
        byte[] result = new byte[4 + bits.length * 8];

        // Write numBits
        result[0] = (byte) (numBits >> 24);
        result[1] = (byte) (numBits >> 16);
        result[2] = (byte) (numBits >> 8);
        result[3] = (byte) numBits;

        // Write bit array
        int byteIndex = 4;
        for (long value : bits) {
            for (int j = 0; j < 8; j++) {
                result[byteIndex++] = (byte) (value >> (j * 8));
            }
        }

        return result;
    }

    /**
     * Get the size of this filter in bytes when serialized.
     */
    public int getSerializedSize() {
        return 4 + bits.length * 8;
    }

    /**
     * Merge another bloom filter into this one.
     * The result will contain all elements from both filters.
     * Filters should ideally have the same size for best results.
     * 
     * @param other The filter to merge into this one
     */
    public void merge(BloomFilter other) {
        if (other == null)
            return;

        // Merge by OR-ing the bits together
        int minLength = Math.min(this.bits.length, other.bits.length);
        for (int i = 0; i < minLength; i++) {
            this.bits[i] |= other.bits[i];
        }
    }

    /**
     * Hash function for bloom filter.
     * Uses a combination of the key and seed to generate different hashes.
     */
    private int hash(long key, int seed) {
        // MurmurHash3-inspired mixing
        long h = key ^ (seed * 0xc6a4a7935bd1e995L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) h;
    }
}
