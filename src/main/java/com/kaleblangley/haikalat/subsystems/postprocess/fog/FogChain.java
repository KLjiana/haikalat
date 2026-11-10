package com.kaleblangley.haikalat.subsystems.postprocess.fog;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalFramePose;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owner of the analytic distance/height fog program.
 *
 * <p>Fog consumes the borrowed shared scene depth plus the frame's inverse
 * view-projection.  Those are adapter inputs; the chain owns only its shader
 * and the pass declaration.</p>
 */
public final class FogChain implements AutoCloseable {
    private final FogPass pass = new FogPass();

    public void addPass(RenderGraph graph, String sourceTexture, String sourcePass,
                        SurfaceInputs surface, SurfaceInputs.Input depth,
                        Supplier<TemporalFramePose> pose, FogSettings settings) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            throw new IllegalArgumentException("fog chain requires enabled settings");
        }
        graph.addPass(PostProcessTargets.FOG_PASS)
                .createColor(PostProcessTargets.FOG_COLOR, RenderFormat.RGBA16F)
                .noClear()
                .dependsOn(sourcePass)
                .dependsOn(depth.producerPass())
                .execute((res, cmd) -> {
                    TemporalFramePose frame = pose.get();
                    if (frame == null || !frame.fogAvailable()) {
                        throw new IllegalStateException(
                                "fog requires the frame's inverse view-projection and camera position");
                    }
                    pass.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(sourceTexture),
                            surface.texture(res, depth),
                            frame.fogInverseViewProjection(),
                            frame.fogCameraPosition(),
                            settings);
                });
    }

    @Override
    public void close() {
        pass.close();
    }
}
