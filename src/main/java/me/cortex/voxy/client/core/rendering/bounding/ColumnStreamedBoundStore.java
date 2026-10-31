package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.system.MemoryUtil;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public class ColumnStreamedBoundStore implements IBoundStore {
    private static final int INIT_MAX_CHUNK_COUNT = 1<<12;
    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT*8);//Stored as ivec2
    private int count;
    public ColumnStreamedBoundStore() {
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    @Override
    public void preRender(Viewport<?> viewport) {
        final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance()*16;//In blocks
        {
            long addr = UploadStream.INSTANCE.uploadTo(this.chunkPosBuffer);
            this.count = findEmitBoundingChunks(viewport, renderDistance, (int) (this.chunkPosBuffer.size() / 8), addr);
            UploadStream.INSTANCE.commit();
        }
        if (this.count<0) {
            this.chunkPosBuffer.free();//Destroy old
            var chunkPoss = new MemoryBuffer((-this.count) * 8L);
            this.count = findEmitBoundingChunks(viewport, renderDistance, -this.count, chunkPoss.address);
            if (this.count<0) {
                chunkPoss.free();
                throw new IllegalStateException("Not sure how this is possible, should have exact capacity. badness: " + this.count);
            }
            this.chunkPosBuffer = new GlBuffer(chunkPoss);//Setup new buffer with the new data
            chunkPoss.free();
        }
    }

    private static void putPos(long ptr, long pos) {
        pos = IBoundStore.transformBeforeStore(pos);
        MemoryUtil.memPutInt(ptr, (int)(pos&0xFFFFFFFFL)); ptr += 4;
        MemoryUtil.memPutInt(ptr, (int)((pos>>>32)&0xFFFFFFFFL));
    }

    public void free() {
        this.chunkPosBuffer.free();
    }


    private static int findEmitBoundingChunks(Viewport<?> viewport, float searchDistance, int capacity, long writePtr) {
        if (capacity == -1) {
            writePtr = 0;
            capacity = Integer.MAX_VALUE;
        }
        //WTAF IS THIS HORRIFIC CODE, _screams_ this is so so bad, and jank and slow and orrible but
        // headache hard think
        int by = (int)Math.floor(viewport.cameraY);
        float fy = (float) (viewport.cameraY - (int)viewport.cameraY);


        //Inclusive
        int mincy = by>>4;
        int maxcy = by>>4;
        {
            while (testYPos(by, fy, mincy, searchDistance)) {
                mincy--;
            }
            mincy++;
            while (testYPos(by, fy, maxcy, searchDistance)) {
                maxcy++;
            }
            maxcy--;
        }

        int count = 0;
        var tracker = ChunkTrackerHolder.get(Minecraft.getInstance().level);
        if (tracker != null) {
            var iter = tracker.getReadyChunks().longIterator();
            while (iter.hasNext()) {
                long column = iter.nextLong();
                //Emit column
                for (int cy = mincy + 1; cy < maxcy; cy++) {
                    if (count++ < capacity && writePtr != 0) {
                        putPos(writePtr, SectionPos.asLong(ChunkPos.getX(column), cy, ChunkPos.getZ(column)));
                        writePtr += 8;
                    }
                }
            }
        }

        if (count>capacity) {
            return -count;
        }
        return count;
    }

    private static boolean testYPos(int by, float fy, int cy, float d) {
        int ry =  cy*16-by;
        float dy = (float)nearestToZero(ry - 1, ry + 17) - fy;
        return Math.abs(dy) < d;
    }

    private static int nearestToZero(int min, int max) {
        int clamped = 0;
        if (min > 0) {
            clamped = min;
        }

        if (max < 0) {
            clamped = max;
        }

        return clamped;
    }

    @Override
    public GlBuffer getBuffer() {
        return this.chunkPosBuffer;
    }

    @Override
    public int getCount() {
        return this.count;
    }
}
