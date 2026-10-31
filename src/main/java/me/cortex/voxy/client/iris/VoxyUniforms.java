package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.RenderProperties;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.function.Supplier;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class VoxyUniforms {

    public static Matrix4f getViewProjection() {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) {
            return new Matrix4f();
        }
        var view = vrs.getViewport();
        if (view == null) {
            return new Matrix4f();
        }
        return conditionallyReverseRevZ(view.properties, new Matrix4f(view.MVP));
    }

    public static Matrix4f getModelView() {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) {
            return new Matrix4f();
        }
        var view = vrs.getViewport();
        if (view == null) {
            return new Matrix4f();
        }
        return new Matrix4f(view.modelView);
    }

    public static Matrix4f getProjection() {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) {
            return new Matrix4f();
        }
        var view = vrs.getViewport();
        if (view == null) {
            return new Matrix4f();
        }
        var mat = view.projection;
        if (mat == null) {
            return new Matrix4f();
        }
        return conditionallyReverseRevZ(view.properties, new Matrix4f(mat));
    }

    // In is safe to mutate
    private static Matrix4f conditionallyReverseRevZ(RenderProperties props, Matrix4f in) {
        if (!props.isReverseZ()) return in;

        var row = in.getRow(2, new Vector4f());
        if (props.isZero2One()) {
            row = row.mul(-2.0f).add(in.getRow(3, new Vector4f()));
        } else {
            row.mul(-1.0f);
        }
        in.setRow(2, row);
        return in;
    }

    public static void addUniforms(UniformHolder uniforms) {
        uniforms
                .uniform1i(PER_FRAME, "vxRenderDistance", () -> Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32)) // In chunks
                .uniform1f(PER_FRAME, "vxFarPlane", () -> {
                    var vrs = IVoxyRenderSystemHolder.getNullable();
                    if (vrs == null) {
                        return 16.0f;
                    }
                    var view = vrs.getViewport();
                    if (view == null) {
                        return 16.0f;
                    }
                    return view.farPlane;
                })
                .uniform1f(PER_FRAME, "vxNearPlane", () -> {
                    var vrs = IVoxyRenderSystemHolder.getNullable();
                    if (vrs == null) {
                        return 3000.0f;
                    }
                    var view = vrs.getViewport();
                    if (view == null) {
                        return 3000.0f;
                    }
                    return view.nearPlane;
                })
                .uniformMatrix(PER_FRAME, "vxViewProj", VoxyUniforms::getViewProjection)
                .uniformMatrix(PER_FRAME, "vxViewProjInv", new Inverted(VoxyUniforms::getViewProjection))
                .uniformMatrix(PER_FRAME, "vxViewProjPrev", new PreviousMat(VoxyUniforms::getViewProjection))
                .uniformMatrix(PER_FRAME, "vxModelView", VoxyUniforms::getModelView)
                .uniformMatrix(PER_FRAME, "vxModelViewInv", new Inverted(VoxyUniforms::getModelView))
                .uniformMatrix(PER_FRAME, "vxModelViewPrev", new PreviousMat(VoxyUniforms::getModelView))
                .uniformMatrix(PER_FRAME, "vxProj", VoxyUniforms::getProjection)
                .uniformMatrix(PER_FRAME, "vxProjInv", new Inverted(VoxyUniforms::getProjection))
                .uniformMatrix(PER_FRAME, "vxProjPrev", new PreviousMat(VoxyUniforms::getProjection));
    }

    private record Inverted(Supplier<Matrix4fc> parent) implements Supplier<Matrix4fc> {
        @Override
        public Matrix4fc get() {
            return this.parent.get().invert(new Matrix4f());
        }
    }

    private static class PreviousMat implements Supplier<Matrix4fc> {
        private final Supplier<Matrix4fc> current;
        private Matrix4fc prev;

        private PreviousMat(Supplier<Matrix4fc> current) {
            this.current = current;
        }

        @Override
        public Matrix4fc get() {
            var ret = this.prev;
            this.prev = this.current.get();
            if (ret == null) {
                ret = this.prev;
            }
            return ret;
        }
    }
}
