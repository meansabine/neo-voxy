package me.cortex.voxy.compat;

import net.neoforged.fml.common.Mod;

/**
 * Compatibility entry point declaring modId "voxy" on NeoForge.
 * Ensures add-ons like Voxy SeedGen that depend on or query "voxy" resolve their dependencies
 * and lifecycle ordering correctly.
 */
@Mod("voxy")
public class VoxyModCompat {
    public VoxyModCompat() {
        // Compatibility alias; main lifecycle is handled by NeoVoxy (me.cortex.voxy.Voxy)
    }
}
