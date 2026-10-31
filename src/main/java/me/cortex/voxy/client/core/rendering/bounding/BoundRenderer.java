package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public class BoundRenderer {
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Shader rasterShader;
    private final RenderProperties properties;

    private final AbstractRenderPipeline pipeline;
    public BoundRenderer(AbstractRenderPipeline pipeline) {
        this.properties = pipeline.properties;

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            this.pipeline = pipeline;
            vert = vert+"\n\n\n"+taa;
        } else {
            this.pipeline = null;
        }

        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .apply(this.properties::apply)
                .compile()
                .ubo(0, this.uniformBuffer);
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport, IBoundStore store) {
        store.preRender(viewport);
        int count = store.getCount();
        if (count == 0) {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            return;
        }
        ((AutoBindingShader)this.rasterShader).ssbo(1, store.getBuffer());
        final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance()*16;//In blocks
        this.renderInner(viewport, renderDistance, count);
        store.postRender(viewport);
    }

    private void renderInner(Viewport<?> viewport, float renderDistanceBlocks, int chunkCount) {
        viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
        if (chunkCount == 0) {
            return;
        }

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4*4*4;

        {//This is recomputed to be in chunk section space not worldsection

            //Camera block pos
            int bx = (int)Math.floor(viewport.cameraX);
            int by = (int)Math.floor(viewport.cameraY);
            int bz = (int)Math.floor(viewport.cameraZ);
            new Vector3i(bx, by, bz).getToAddress(ptr); ptr += 4*4;

            var negInnerBlock = new Vector3f(
                    (float) (viewport.cameraX - bx),
                    (float) (viewport.cameraY - by),
                    (float) (viewport.cameraZ - bz));


            negInnerBlock.getToAddress(ptr); ptr += 4*3;
            viewport.MVP.translate(negInnerBlock.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistanceBlocks); ptr += 4;
        }
        UploadStream.INSTANCE.commit();


        {
            //need to reverse the winding order since we want the back faces of the AABB, not the front

            glFrontFace(GL_CW);//Reverse winding order

            //"reverse depth buffer" it goes from 0->1 where 1 is far away
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(this.properties.furtherDepthCompare());
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        if (this.pipeline != null) this.pipeline.bindUniforms();//shader TAA

        //Batch the draws into groups of size 32
        int count = chunkCount;
        if (count >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count/32);
        }
        if (count%32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (count%32), GL_UNSIGNED_BYTE, 0, 1, (count/32)*32);
        }

        {
            glFrontFace(GL_CCW);//Restore winding order

            glDepthFunc(this.properties.closerEqualDepthCompare());

            //TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
    }
}
