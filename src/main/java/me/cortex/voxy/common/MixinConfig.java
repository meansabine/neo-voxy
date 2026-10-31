package me.cortex.voxy.common;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MixinConfig implements IMixinConfigPlugin {
    private String mixinPackage;
    private static final Set<String> FALLTHROUGH = Set.of("minecraft");

    public MixinConfig() {
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage + ".";
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    private static boolean isModLoaded(String modId) {
        if (FALLTHROUGH.contains(modId)) {
            return true;
        }
        try {
            var loadingList = LoadingModList.get();
            if (loadingList != null && loadingList.getModFileById(modId) != null) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            var loadingList = FMLLoader.getLoadingModList();
            if (loadingList != null && loadingList.getModFileById(modId) != null) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            var modList = ModList.get();
            if (modList != null && modList.isLoaded(modId)) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixin) {
        if (mixin.startsWith(this.mixinPackage)) {
            var pth = mixin.substring(this.mixinPackage.length());
            if (!pth.contains(".")) return true;
            var targetMod = pth.substring(0, pth.indexOf('.'));
            boolean apply = isModLoaded(targetMod);
            System.out.println("[NeoVoxy MixinConfig] shouldApplyMixin: " + mixin + " -> " + apply);
            return apply;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
