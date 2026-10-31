package me.cortex.voxy.common.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Utility class that provides memory operations using sun.misc.Unsafe.
 * This replaces LWJGL MemoryUtil to work on both client and dedicated server.
 */
public class UnsafeUtil {
    private static final Unsafe UNSAFE;
    private static final long BUFFER_ADDRESS_OFFSET;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);

            // Get the address field offset from DirectByteBuffer
            Field addressField = Buffer.class.getDeclaredField("address");
            BUFFER_ADDRESS_OFFSET = UNSAFE.objectFieldOffset(addressField);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    private static final long SHORT_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(short[].class);
    private static final long INT_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(int[].class);
    private static final long LONG_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(long[].class);

    // Memory allocation operations (replaces LWJGL MemoryUtil)
    public static long allocateMemory(long size) {
        return UNSAFE.allocateMemory(size);
    }

    public static void freeMemory(long address) {
        UNSAFE.freeMemory(address);
    }

    public static void memset(long address, int value, long size) {
        UNSAFE.setMemory(address, size, (byte) value);
    }

    // Memory read/write primitives (replaces LWJGL MemoryUtil.memPut*/memGet*)
    public static boolean memGetBoolean(long ptr) { return UNSAFE.getByte(null, ptr) != 0; }
    public static byte memGetByte(long ptr)       { return UNSAFE.getByte(null, ptr); }
    public static short memGetShort(long ptr)     { return UNSAFE.getShort(null, ptr); }
    public static int memGetInt(long ptr)         { return UNSAFE.getInt(null, ptr); }
    public static long memGetLong(long ptr)       { return UNSAFE.getLong(null, ptr); }
    public static float memGetFloat(long ptr)     { return UNSAFE.getFloat(null, ptr); }
    public static double memGetDouble(long ptr)   { return UNSAFE.getDouble(null, ptr); }

    public static void memPutByte(long ptr, byte value)     { UNSAFE.putByte(null, ptr, value); }
    public static void memPutShort(long ptr, short value)   { UNSAFE.putShort(null, ptr, value); }
    public static void memPutInt(long ptr, int value)       { UNSAFE.putInt(null, ptr, value); }
    public static void memPutLong(long ptr, long value)     { UNSAFE.putLong(null, ptr, value); }
    public static void memPutFloat(long ptr, float value)   { UNSAFE.putFloat(null, ptr, value); }
    public static void memPutDouble(long ptr, double value) { UNSAFE.putDouble(null, ptr, value); }

    /**
     * Gets the native memory address of a direct ByteBuffer.
     * Replaces LWJGL MemoryUtil.memAddress()
     */
    public static long memAddress(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be direct");
        }
        return UNSAFE.getLong(buffer, BUFFER_ADDRESS_OFFSET);
    }

    public static void memcpy(long src, long dst, long length) {
        UNSAFE.copyMemory(src, dst, length);
    }

    // Copy the entire length of src to the dst memory where dst is a byte array (source length from dst)
    public static void memcpy(long src, byte[] dst) {
        UNSAFE.copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET, dst.length);
    }

    public static void memcpy(byte[] src, long dst, int length) {
        UNSAFE.copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, length);
    }

    public static void memcpy(long src, int length, byte[] dst, int offset) {
        UNSAFE.copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET + offset, length);
    }

    // Copy the entire length of src to the dst memory where src is a byte array (source length from src)
    public static void memcpy(byte[] src, long dst) {
        UNSAFE.copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, src.length);
    }

    public static void memcpy(byte[] src, int len, long dst) {
        UNSAFE.copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, len);
    }

    public static void memcpy(short[] src, long dst) {
        UNSAFE.copyMemory(src, SHORT_ARRAY_BASE_OFFSET, null, dst, (long) src.length << 1);
    }

    public static void memcpy(long[] src, long dst) {
        UNSAFE.copyMemory(src, LONG_ARRAY_BASE_OFFSET, null, dst, (long) src.length << 3);
    }

    public static void memcpy(int[] src, long dst) {
        UNSAFE.copyMemory(src, INT_ARRAY_BASE_OFFSET, null, dst, (long) src.length << 2);
    }

    public static void memcpy(int[] src, int count, long dst) {
        UNSAFE.copyMemory(src, INT_ARRAY_BASE_OFFSET, null, dst, (long) count << 2);
    }

    /**
     * Creates a ByteBuffer view of native memory at the given address.
     * This properly wraps the native memory so that writes to the ByteBuffer
     * are reflected in the native memory.
     */
    public static ByteBuffer createByteBuffer(long address, int size) {
        try {
            Class<?> directByteBufferClass = Class.forName("java.nio.DirectByteBuffer");
            var constructor = directByteBufferClass.getDeclaredConstructor(long.class, int.class);
            constructor.setAccessible(true);
            return (ByteBuffer) constructor.newInstance(address, size);
        } catch (Exception e) {
            try {
                ByteBuffer buffer = ByteBuffer.allocateDirect(size);
                UNSAFE.putLong(buffer, BUFFER_ADDRESS_OFFSET, address);
                return buffer;
            } catch (Exception e2) {
                throw new RuntimeException("Failed to create ByteBuffer wrapping native memory", e2);
            }
        }
    }

    /**
     * Allocates a direct ByteBuffer. Replaces LWJGL MemoryUtil.memAlloc()
     */
    public static ByteBuffer memAlloc(int size) {
        return ByteBuffer.allocateDirect(size);
    }

    /**
     * Frees a direct ByteBuffer. Replaces LWJGL MemoryUtil.memFree()
     */
    public static void memFree(Buffer buffer) {
        // No-op for standard Java direct buffers, relying on GC.
    }

    /**
     * Copies memory from one ByteBuffer to another. Replaces LWJGL MemoryUtil.memCopy()
     */
    public static void memCopy(ByteBuffer src, ByteBuffer dst) {
        ByteBuffer srcSlice = src.duplicate();
        ByteBuffer dstSlice = dst.duplicate();
        dstSlice.put(srcSlice);
    }
}
