package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.core.SectionPos;

public interface IBoundStore {
    GlBuffer getBuffer();
    int getCount();

    default void preRender(Viewport<?> viewport) {};
    default void postRender(Viewport<?> viewport) {};

    void free();

    static long transformBeforeStore(long pos) {
        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int x = SectionPos.x(pos);
            int y = SectionPos.y(pos);
            int sector = (x+512)>>10;
            x-=sector<<10;
            y+=16+(256-32-sector*30);

            return SectionPos.asLong(x, y, SectionPos.z(pos));
        } else {
            return pos;
        }
    }
}
