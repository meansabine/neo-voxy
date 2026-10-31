package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

import java.util.ArrayList;
import java.util.List;

public class VoxyDebugScreenEntry {
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!VoxyCommon.isAvailable()) {
            event.getLeft().add(ChatFormatting.RED + "voxy-" + VoxyCommon.MOD_VERSION);
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            event.getLeft().add(ChatFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);
            return;
        }
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();

        event.getLeft().add((vrs == null ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN) + "voxy-" + VoxyCommon.MOD_VERSION);

        List<String> instanceLines = new ArrayList<>();
        instance.addDebug(instanceLines);
        for (String line : instanceLines) {
            event.getLeft().add("[Voxy] " + line);
        }

        if (vrs != null) {
            List<String> renderLines = new ArrayList<>();
            vrs.addDebugInfo(renderLines);
            for (String line : renderLines) {
                event.getLeft().add("[Voxy Render] " + line);
            }
        }
    }
}
