package me.cortex.voxy.common.util.cpu;

import java.util.Arrays;
import java.util.Random;

//Represents the layout of the current cpu running on
public class CpuLayout {
    private CpuLayout() {
    }

    public static void setThreadAffinity(Core... cores) {
        // No-op without LWJGL
    }

    public static void setThreadAffinity(Affinity... affinities) {
        // No-op without LWJGL
    }

    public record Affinity(long msk, short group) {
    }

    public record Core(boolean isEfficiency, Affinity affinity) {

    }

    public static final Core[] CORES = null;

    public static void main(String[] args) throws InterruptedException {
        // No-op
    }

    public static int getCoreCount() {
        return Runtime.getRuntime().availableProcessors();
    }
}
