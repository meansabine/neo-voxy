package com.dripps.voxy.locator;

import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
// VoxyLog handles routing to SLF4J or standard out

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * NeoForge runtime IDependencyLocator service provider.
 * Executes during early game boot to reconstruct and inject the complete
 * NeoForge Voxy mod into FML's discovery pipeline.
 */
public class VoxyDependencyLocator implements IDependencyLocator {

    @Override
    public int getPriority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        VoxyLog.info("[NeoVoxy] Running early NeoForge dependency locator scan...");

        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path modsDir = FMLPaths.MODSDIR.get();
            Path cacheDir = gameDir.resolve("cache");

            VoxyPatcher patcher = new VoxyPatcher(gameDir, modsDir, cacheDir);
            Path patchedJar = patcher.preparePatchedJar();
            if (patchedJar == null) {
                VoxyLog.info("[NeoVoxy] Running in non-distributable direct mode (no patches manifest). Skipping runtime locator.");
                return;
            }

            VoxyLog.info("[NeoVoxy] Handing reconstructed Voxy JAR to NeoForge pipeline: {}", patchedJar);

            ModFileDiscoveryAttributes attributes = ModFileDiscoveryAttributes.DEFAULT.withDependencyLocator(this);
            Optional<IModFile> modFile = pipeline.addPath(patchedJar, attributes, IncompatibleFileReporting.ERROR);

            if (modFile.isPresent()) {
                VoxyLog.info("[NeoVoxy] Successfully loaded reconstructed NeoForge Voxy mod: {}",
                        modFile.get().getFileName());
            } else {
                VoxyLog.warn("[NeoVoxy] NeoForge pipeline addPath returned empty for: {}", patchedJar);
            }
        } catch (Exception e) {
            VoxyLog.error("[NeoVoxy] FATAL: Failed to reconstruct or locate NeoForge Voxy JAR!", e);
            ModLoadingIssue issue = ModLoadingIssue.error("fml.modloadingissue.brokenfile",
                    "NeoVoxy runtime reconstructor failed: " + e.getMessage()).withCause(e);
            pipeline.addIssue(issue);
            throw new ModLoadingException(issue);
        }
    }
}
