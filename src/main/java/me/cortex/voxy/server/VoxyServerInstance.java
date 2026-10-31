package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.LZ4Compressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side Voxy instance for managing LOD data on dedicated servers.
 * This allows servers to generate, store, and stream LOD data to clients.
 */
public class VoxyServerInstance extends VoxyInstance {

    private final SectionStorageConfig storageConfig;
    private final Path basePath;

    public VoxyServerInstance() {
        super();
        this.basePath = getServerBasePath();
        this.storageConfig = getCreateStorageConfig(this.basePath);
        this.updateDedicatedThreads();
        Logger.info("VoxyServerInstance initialized at: " + this.basePath);
    }

    @Override
    public void updateDedicatedThreads() {
        // Server uses a fixed thread count - can be made configurable later
        this.setNumThreads(3);
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.storageConfig.build(ctx);
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        // Server-side always has ingest enabled
        return true;
    }

    public static SectionStorageConfig getCreateStorageConfig(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Voxy storage directory: " + path, e);
        }
        var json = path.resolve("config.json");
        Config config = null;
        if (Files.exists(json)) {
            try {
                config = Serialization.GSON.fromJson(Files.readString(json), Config.class);
                if (config == null) {
                    Logger.error("Config deserialization null, reverting to default");
                } else {
                    if (config.sectionStorageConfig == null) {
                        Logger.error("Config section storage null, reverting to default");
                        config = null;
                    }
                }
            } catch (Exception e) {
                Logger.error(
                        "Failed to load the storage configuration file, resetting it to default",
                        e);
            }
        }

        if (config == null) {
            config = DEFAULT_STORAGE_CONFIG;
        }
        try {
            Files.writeString(json, Serialization.GSON.toJson(config));
        } catch (Exception e) {
            throw new RuntimeException("Failed write the config, aborting!", e);
        }
        if (config == null) {
            throw new IllegalStateException("Config is still null\n");
        }
        return config.sectionStorageConfig;
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    private static class Config {
        public int version = 1;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();

        // Load the default config - same as client
        var baseDB = new RocksDBStorageBackend.Config();

        var compressor = new LZ4Compressor.Config();

        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = baseDB;
        compression.compressor = compressor;

        var serializer = new SectionSerializationStorage.Config();
        serializer.storage = compression;
        config.sectionStorageConfig = serializer;

        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getServerBasePath() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.getWorldPath(LevelResource.ROOT).resolve("neovoxy").toAbsolutePath();
        }
        // Fallback if server not yet available
        return Path.of(".", "neovoxy").toAbsolutePath();
    }
}
