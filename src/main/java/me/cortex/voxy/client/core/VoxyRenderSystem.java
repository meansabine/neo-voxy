package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.bounding.BoundRenderer;
import me.cortex.voxy.client.core.rendering.bounding.ColumnStreamedBoundStore;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.FogParameters;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.GlobalCleaner;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.lang.ref.Cleaner;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureLevelParameteri;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30.glGetIntegeri;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    private final WorldEngine worldIn;

    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    private final Cleaner.Cleanable geoRef;

    private final RenderDistanceTracker renderDistanceTracker;
    private final BoundRenderer boundOutlineRenderer;
    public final StreamedBoundStore visbleSectionStream;
    private @Nullable ColumnStreamedBoundStore columnStreamedBoundStore;

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;
    private final RenderProperties properties;

    // LOD streaming reception service
    private LodReceptionService lodReceptionService;
    private final java.util.concurrent.atomic.AtomicBoolean isShutdown = new java.util.concurrent.atomic.AtomicBoolean(false);

    private static AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRenderBackendFactory() {
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        System.gc();

        if (Minecraft.getInstance().options.renderDistance().get() < 3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().gui.getChat().addMessage(Component.literal(msg));
        }

        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            glFinish();
            glFinish();

            this.worldIn = world;
            this.properties = RenderProperties.getRenderProperties();
            this.visbleSectionStream = new StreamedBoundStore();
            var backendFactory = getRenderBackendFactory();

            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm,
                        IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

                this.geometryData = new BasicSectionGeometryData(1 << 20, RenderResourceReuse.getOrCreateGeometryBuffer());

                if (((BasicSectionGeometryData) this.geometryData).isExternalGeometryBuffer) {
                    var buffer = ((BasicSectionGeometryData) this.geometryData).getGeometryBuffer();
                    this.geoRef = GlobalCleaner.CLEANER.register(this.geometryData, () -> RenderResourceReuse.giveBackGeometryBuffer(buffer));
                } else {
                    this.geoRef = null;
                }

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                // Pre-bake common block models to reduce likelihood of black faces on initial join
                prebakeCommonModels(world, this.modelService);

                this.nodeManager.start();
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal,
                    this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);

            // Late stage traversal compile for shaders with taa
            this.traversal.lateStageCompile(this.pipeline);

            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSection() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSection() - 1) >> 5;

                if (VoxyCommon.IS_MINE_IN_ABYSS) {
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(40,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.boundOutlineRenderer = new BoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '"
                    + this.pipeline.getClass().getSimpleName() + "' with renderer '"
                    + sectionRenderer.getClass().getSimpleName() + "'");

            // Initialize LOD reception service for network streaming
            this.lodReceptionService = new LodReceptionService(world, world.getMapper(), this.modelService);
        } catch (RuntimeException e) {
            world.releaseRef();
            throw e;
        }

        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }

        for (int i = 0; i < 12; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0 + i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
    }

    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, FogParameters fogParameters, int width, int height, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int) Math.floor(cameraX) >> 4) + 512) >> 10;
            cameraX -= sector << 14;
            cameraY += (16 + (256 - 32 - sector * 30)) * 16;
        }

        float far = 3000 * 16;
        if (this.pipeline instanceof IrisVoxyRenderPipeline ivrp) {
            if (ivrp._getData().useDynamicFarPlane) {
                far = (VoxyConfig.CONFIG.sectionRenderDistance * 32 + 2) * ((float) Math.sqrt(3)) * 16;
            }
        }

        float near = getVanillaRenderDistance() <= 32.0f ? 8f : 16f;
        near = VoxyClient.disableSodiumChunkRender() ? 0.1f : near;
        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection, near, far);

        {
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width * factor[0]);
                height = (int) (height * factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection, near, far)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .setFogParameters(fogParameters)
                .update();

        if (VoxyClient.getOcclusionDebugState() == 0) {
            viewport.frameId++;
        }

        return viewport;
    }

    public Viewport<?> setupViewport(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ) {
        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);
        return setupViewport(matrices.projection(), matrices.modelView(), FogParameters.capture(), dims[2], dims[3], cameraX, cameraY, cameraZ);
    }

    public void renderOpaque(Viewport<?> viewport, int sourceDepthTexture, int sourceColourTexture) {
        if (viewport == null) {
            return;
        }

        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            return;
        }

        if (sourceDepthTexture == 0) {
            throw new IllegalStateException("Source depth texture cannot be 0");
        }

        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();
        TimingStatistics.main.start();

        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(this.properties.closerEqualDepthCompare());
        GlStateManager._depthMask(true);
        GlStateManager._disablePolygonOffset();

        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        glViewport(0, 0, viewport.width, viewport.height);

        int scrWidth = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_WIDTH);
        int scrHeight = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_HEIGHT);

        this.pipeline.preSetup(viewport);

        TimingStatistics.E.start();
        if (this.visbleSectionStream != null && (!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
            if (VoxyClient.isFrexActive() != (this.columnStreamedBoundStore != null)) {
                if (this.columnStreamedBoundStore == null) {
                    this.columnStreamedBoundStore = new ColumnStreamedBoundStore();
                } else {
                    this.columnStreamedBoundStore.free();
                    this.columnStreamedBoundStore = null;
                }
            }
            this.boundOutlineRenderer.render(viewport, this.columnStreamedBoundStore == null ? this.visbleSectionStream : this.columnStreamedBoundStore);
        } else {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
        }
        TimingStatistics.E.stop();

        GPUTiming.INSTANCE.marker();
        this.pipeline.runPipeline(viewport, sourceDepthTexture, sourceColourTexture, scrWidth, scrHeight);
        GPUTiming.INSTANCE.marker();

        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        {
            UploadStream.INSTANCE.tick();

            if (this.lodReceptionService != null) {
                this.lodReceptionService.tick();
            }

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ)
                    && VoxyClient.isFrexActive())
                ;
            TimingStatistics.H.start();
            do {
                this.modelService.tick(900_000);
            } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }

        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        {
            GlStateManager._glUseProgram(0);
            glUseProgram(0);
            GlStateManager._enableDepthTest();
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);

            GlStateManager._glBindVertexArray(0);

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }

            IrisUtil.clearIrisSamplers();

            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }
            glBlendEquation(GL_FUNC_ADD);
            GlStateManager._blendFuncSeparate(0, 0, 0, 0);
            glBlendFunc(0, 0);
            GlStateManager._disableBlend();
            glDisable(GL_BLEND);
            GlStateManager._depthFunc(GL_LESS);
            glDepthFunc(GL_LESS);
        }

        TimingStatistics.all.stop();
    }

    public void renderOpaque(Viewport<?> viewport) {
        var mc = Minecraft.getInstance();
        var mainTarget = mc.getMainRenderTarget();
        renderOpaque(viewport, mainTarget.getDepthTextureId(), mainTarget.getColorTextureId());
    }

    private void autoBalanceSubDivSize() {
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize
                    + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize
                    - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28);
        }
    }

    public static float getVanillaRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }

    private static Matrix4f makeProjectionMatrix(float near, float far) {
        var projection = new Matrix4f();
        var client = Minecraft.getInstance();
        var gameRenderer = client.gameRenderer;

        float fov = (float) gameRenderer.getFov(gameRenderer.getMainCamera(),
                client.getTimer().getGameTimeDeltaPartialTick(true), true);

        projection.setPerspective(fov * 0.01745329238474369f,
                (float) client.getWindow().getWidth() / (float) client.getWindow().getHeight(),
                near, far);
        return projection;
    }

    private static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base, float nearPlane, float farPlane) {
        var rawMCProj = makeProjectionMatrix(0.05f, Minecraft.getInstance().gameRenderer.getDepthFar());
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);

        float far = farPlane;
        float near = nearPlane;

        if (properties.isReverseZ()) {
            float tmp = nearPlane;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                        .m22((properties.isZero2One() ? far : (far + near)) / (near - far))
                        .m32((properties.isZero2One() ? far : (far + far)) * near / (near - far))
        );
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        UploadStream.INSTANCE.tick();
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount() != 0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance + 1));
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize() / 1_000_000) + "],["
                + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize() / 1_000_000) + "]");
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", "
                    + TimingStatistics.main.pVal() + ", " + TimingStatistics.postDynamic.pVal() + ", "
                    + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", "
                    + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", "
                    + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        if (!this.isShutdown.compareAndSet(false, true)) {
            return;
        }
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {
            if (this.worldIn != null) {
                this.worldIn.setDirtyCallback(null);
                if (this.worldIn.getMapper() != null) {
                    this.worldIn.getMapper().setBiomeCallback(null);
                    this.worldIn.getMapper().setStateCallback(null);
                }
            }

            if (this.nodeManager != null) {
                this.nodeManager.stop();
            }

            if (this.modelService != null) this.modelService.shutdown();
            if (this.renderGen != null) this.renderGen.shutdown();
            if (this.traversal != null) this.traversal.free();
            if (this.nodeCleaner != null) this.nodeCleaner.free();
            if (this.geometryData != null) this.geometryData.free();
            if (this.geoRef != null) {
                this.geoRef.clean();
            }

            if (this.boundOutlineRenderer != null) this.boundOutlineRenderer.free();
            if (this.visbleSectionStream != null) {
                this.visbleSectionStream.free();
            }
            if (this.columnStreamedBoundStore != null) {
                this.columnStreamedBoundStore.free();
                this.columnStreamedBoundStore = null;
            }

            if (this.viewportSelector != null) this.viewportSelector.free();

            // Shutdown LOD streaming reception
            if (this.lodReceptionService != null) {
                this.lodReceptionService.close();
                this.lodReceptionService = null;
            }
        } catch (Exception e) {
            Logger.error("Error shutting down renderer components", e);
        }
        Logger.info("Shutting down render pipeline");
        try {
            if (this.pipeline != null) {
                this.pipeline.free();
            }
        } catch (Exception e) {
            Logger.error("Error releasing render pipeline", e);
        }

        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        try {
            if (this.worldIn != null && this.worldIn.isLive()) {
                this.worldIn.releaseRef();
            }
        } catch (Exception e) {
            Logger.warn("Error releasing world reference during renderer shutdown: " + e.getMessage());
        }
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }

    /**
     * Pre-bakes models for common blocks to avoid black faces on initial join.
     * This gives the model baking system a head start before LOD data arrives.
     */
    private static void prebakeCommonModels(WorldEngine world, ModelBakerySubsystem modelService) {
        var mapper = world.getMapper();

        String[] commonBlockNames = {
                "minecraft:stone",
                "minecraft:dirt",
                "minecraft:grass_block",
                "minecraft:water",
                "minecraft:sand",
                "minecraft:gravel",
                "minecraft:oak_log",
                "minecraft:oak_leaves",
                "minecraft:spruce_log",
                "minecraft:spruce_leaves",
                "minecraft:birch_log",
                "minecraft:birch_leaves",
                "minecraft:snow",
                "minecraft:ice",
                "minecraft:cobblestone",
                "minecraft:deepslate"
        };

        for (String blockName : commonBlockNames) {
            try {
                int blockId = mapper.getOrRegisterBlockStateFromString(blockName + "[]");
                if (blockId > 0) {
                    modelService.requestBlockBake(blockId);
                }
            } catch (Exception e) {
                // Ignore if block doesn't exist in this version
            }
        }

        Logger.info("Requested pre-baking of " + commonBlockNames.length + " common block models");
    }

    public LodReceptionService getLodReceptionService() {
        return this.lodReceptionService;
    }
}
