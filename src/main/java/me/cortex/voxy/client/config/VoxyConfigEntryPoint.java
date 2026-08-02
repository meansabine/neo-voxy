package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Sodium 0.8 configuration entry point for Neo-Voxy.
 * <p>
 * Registers the Voxy option page into Sodium's settings screen using the
 * official third-party config API ({@code sodium.api.config}).
 * Discovered automatically by Sodium via the {@link ConfigEntryPointForge}
 * annotation on NeoForge.
 */
@ConfigEntryPointForge("neovoxy")
public class VoxyConfigEntryPoint implements ConfigEntryPoint {

    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX / SUBDIV_MIN) / Math.log(2);

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder mod = builder.registerModOptions("neovoxy");

        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.translatable("voxy.config.title"));

        // ==================== General ====================
        OptionGroupBuilder general = builder.createOptionGroup()
                .setName(Component.translatable("voxy.config.general"));

        BooleanOptionBuilder enabled = builder.createBooleanOption(id("enabled"))
                .setName(Component.translatable("voxy.config.general.enabled"))
                .setTooltip(Component.translatable("voxy.config.general.enabled.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setDefaultValue(true)
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(this::onEnabledChange, () -> VoxyConfig.CONFIG.enabled);
        general.addOption(enabled);

        BooleanOptionBuilder rendering = builder.createBooleanOption(id("rendering"))
                .setName(Component.translatable("voxy.config.general.rendering"))
                .setTooltip(Component.translatable("voxy.config.general.rendering.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setDefaultValue(true)
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(this::onRenderingChange, () -> VoxyConfig.CONFIG.enableRendering);
        general.addOption(rendering);

        BooleanOptionBuilder ingest = builder.createBooleanOption(id("ingest"))
                .setName(Component.translatable("voxy.config.general.ingest"))
                .setTooltip(Component.translatable("voxy.config.general.ingest.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setDefaultValue(true)
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(v -> VoxyConfig.CONFIG.ingestEnabled = v, () -> VoxyConfig.CONFIG.ingestEnabled);
        general.addOption(ingest);

        IntegerOptionBuilder subDivision = builder.createIntegerOption(id("subdivision"))
                .setName(Component.translatable("voxy.config.general.subDivisionSize"))
                .setTooltip(Component.translatable("voxy.config.general.subDivisionSize.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setRange(0, SUBDIV_IN_MAX, 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(Math.round(ln2subDiv(v)))))
                .setDefaultValue(subDiv2ln(64))
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(v -> VoxyConfig.CONFIG.subDivisionSize = ln2subDiv(v),
                        () -> subDiv2ln(VoxyConfig.CONFIG.subDivisionSize));
        general.addOption(subDivision);

        IntegerOptionBuilder renderDistance = builder.createIntegerOption(id("renderDistance"))
                .setName(Component.translatable("voxy.config.general.renderDistance"))
                .setTooltip(Component.translatable("voxy.config.general.renderDistance.tooltip"))
                .setImpact(OptionImpact.LOW)
                .setRange(2, 64, 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(v * 32))) // Every unit is equal to 32
                                                                                      // vanilla chunks
                .setDefaultValue(16)
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(this::onRenderDistanceChange, () -> VoxyConfig.CONFIG.sectionRenderDistance);
        general.addOption(renderDistance);

        BooleanOptionBuilder vanillaFog = builder.createBooleanOption(id("vanillaFog"))
                .setName(Component.translatable("voxy.config.general.vanilla_fog"))
                .setTooltip(Component.translatable("voxy.config.general.vanilla_fog.tooltip"))
                .setDefaultValue(false)
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(v -> VoxyConfig.CONFIG.renderVanillaFog = v, () -> VoxyConfig.CONFIG.renderVanillaFog);
        general.addOption(vanillaFog);

        BooleanOptionBuilder statistics = builder.createBooleanOption(id("renderStatistics"))
                .setName(Component.translatable("voxy.config.general.render_statistics"))
                .setTooltip(Component.translatable("voxy.config.general.render_statistics.tooltip"))
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setDefaultValue(false)
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(v -> RenderStatistics.enabled = v, () -> RenderStatistics.enabled);
        general.addOption(statistics);

        page.addOptionGroup(general);

        // ==================== Threads ====================
        OptionGroupBuilder threads = builder.createOptionGroup()
                .setName(Component.translatable("voxy.config.threads"));

        IntegerOptionBuilder serviceThreads = builder.createIntegerOption(id("serviceThreads"))
                .setName(Component.translatable("voxy.config.general.serviceThreads"))
                .setTooltip(Component.translatable("voxy.config.general.serviceThreads.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setRange(1, Math.max(CpuLayout.getCoreCount(), 1), 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(v)))
                .setDefaultValue(Math.max((int) (CpuLayout.getCoreCount() / 1.5), 1))
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(this::onServiceThreadsChange, () -> VoxyConfig.CONFIG.serviceThreads);
        threads.addOption(serviceThreads);

        BooleanOptionBuilder useSodiumBuilder = builder.createBooleanOption(id("useSodiumBuilder"))
                .setName(Component.translatable("voxy.config.general.useSodiumBuilder"))
                .setTooltip(Component.translatable("voxy.config.general.useSodiumBuilder.tooltip"))
                .setImpact(OptionImpact.VARIES)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setDefaultValue(true)
                .setStorageHandler(VoxyConfig.CONFIG::save)
                .setBinding(this::onUseSodiumBuilderChange, () -> !VoxyConfig.CONFIG.dontUseSodiumBuilderThreads);
        threads.addOption(useSodiumBuilder);

        page.addOptionGroup(threads);

        mod.addPage(page);
    }

    private void onEnabledChange(boolean v) {
        VoxyConfig.CONFIG.enabled = v;
        if (v) {
            if (VoxyClientInstance.isInGame) {
                VoxyCommon.createInstance();
                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                if (vrsh != null && VoxyConfig.CONFIG.enableRendering) {
                    vrsh.createRenderer();
                }
            }
        } else {
            var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
            if (vrsh != null) {
                vrsh.shutdownRenderer();
            }
            VoxyCommon.shutdownInstance();
        }
    }

    private void onRenderingChange(boolean v) {
        VoxyConfig.CONFIG.enableRendering = v;
        var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
        if (vrsh != null) {
            if (v) {
                vrsh.createRenderer();
            } else {
                vrsh.shutdownRenderer();
            }
        }
    }

    private void onRenderDistanceChange(int v) {
        VoxyConfig.CONFIG.sectionRenderDistance = v;
        var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
        if (vrsh != null) {
            var vrs = vrsh.getVoxyRenderSystem();
            if (vrs != null) {
                vrs.setRenderDistance(v);
            }
        }
    }

    private void onServiceThreadsChange(int v) {
        VoxyConfig.CONFIG.serviceThreads = v;
        var instance = VoxyCommon.getInstance();
        if (instance != null) {
            instance.updateDedicatedThreads();
        }
    }

    private void onUseSodiumBuilderChange(boolean v) {
        VoxyConfig.CONFIG.dontUseSodiumBuilderThreads = !v;
        var instance = VoxyCommon.getInstance();
        if (instance != null) {
            instance.updateDedicatedThreads();
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("neovoxy", path);
    }

    // In range is 0->100
    // Out range is 28->256
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN * Math.pow(2, SUBDIV_CONST * ((double) in / SUBDIV_IN_MAX)));
    }

    // In range is ... any?
    // Out range is 0->100
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double) in) / SUBDIV_MIN) / Math.log(2)) / SUBDIV_CONST) * SUBDIV_IN_MAX);
    }
}
