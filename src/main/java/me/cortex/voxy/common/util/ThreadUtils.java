package me.cortex.voxy.common.util;

//Platform specific code to assist in thread utilities
public class ThreadUtils {
    public static final int WIN32_THREAD_PRIORITY_TIME_CRITICAL = 15;
    public static final int WIN32_THREAD_PRIORITY_LOWEST = -2;
    public static final int WIN32_THREAD_MODE_BACKGROUND_BEGIN = 0x00010000;
    public static final int WIN32_THREAD_MODE_BACKGROUND_END = 0x00020000;
    public static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    public static final boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");

    public static boolean SetThreadSelectedCpuSetMasksWin32(long mask) {
        return SetThreadSelectedCpuSetMasksWin32(new long[] { mask }, new short[] { 0 });
    }

    public static boolean SetThreadSelectedCpuSetMasksWin32(long[] masks, short[] groups) {
        // No-op implementation to remove LWJGL dependency
        return false;
    }

    public static boolean SetSelfThreadPriorityWin32(int priority) {
        // No-op implementation to remove LWJGL dependency
        return false;
    }

    public static boolean schedSetaffinityLinux(long masks[]) {
        // No-op implementation to remove LWJGL dependency
        return false;
    }
}
