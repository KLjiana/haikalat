package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.EnvironmentBackgroundRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewRenderer;

import java.util.concurrent.atomic.AtomicLong;

/** Exclusive owner of one complete RenderGraph/pass-resource generation. */
final class PipelineGeneration implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    final long id = NEXT_ID.getAndIncrement();
    PipelineTopology topology;
    RenderGraph graph;
    CameraUniforms cameraUniforms;
    final LightingBinder lightingBinder = new LightingBinder();
    PostProcessPassBuilder postProcess;
    ShaderProgram shadowShader;
    ShaderProgram maskedShadowShader;
    ShaderProgram instancedShadowShader;
    String finalPassName;
    PbrMaterialBinder pbrMaterialBinder;
    EnvironmentBackgroundRenderer environmentBackground;
    final SceneFrameBuilder sceneFrameBuilder = new SceneFrameBuilder();
    GraphPreviewRenderer previewRenderer;
    private boolean closed;

    PipelineGeneration(PipelineTopology topology) {
        this.topology = topology;
    }

    boolean isClosed() {
        return closed;
    }

    void resize(int width, int height) {
        if (closed) throw new IllegalStateException("PipelineGeneration is closed");
        if (width <= 0 || height <= 0
                || graph.width() == width && graph.height() == height) return;
        // RenderGraph and TAA history both allocate candidate targets before swapping. Programs,
        // fixed-size shadow targets and immutable pass policy remain owned by this generation.
        graph.resize(width, height);
        postProcess.resize(width, height);
        topology = topology.withExtent(width, height);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        GraphPreviewRenderer localPreview = previewRenderer;
        ShaderProgram localInstancedShadow = instancedShadowShader;
        ShaderProgram localShadow = shadowShader;
        ShaderProgram localMaskedShadow = maskedShadowShader;
        PostProcessPassBuilder localPostProcess = postProcess;
        CameraUniforms localCameraUniforms = cameraUniforms;
        PbrMaterialBinder localPbrBinder = pbrMaterialBinder;
        EnvironmentBackgroundRenderer localBackground = environmentBackground;
        RenderGraph localGraph = graph;
        previewRenderer = null;
        instancedShadowShader = null;
        shadowShader = null;
        maskedShadowShader = null;
        postProcess = null;
        cameraUniforms = null;
        pbrMaterialBinder = null;
        environmentBackground = null;
        graph = null;
        finalPassName = null;

        RuntimeException failure = null;
        failure = closeCollecting(localPreview, failure);
        failure = closeCollecting(localInstancedShadow, failure);
        failure = closeCollecting(localShadow, failure);
        failure = closeCollecting(localMaskedShadow, failure);
        failure = closeCollecting(localPostProcess, failure);
        failure = closeCollecting(localCameraUniforms, failure);
        failure = closeCollecting(localPbrBinder, failure);
        failure = closeCollecting(localBackground, failure);
        failure = closeCollecting(localGraph, failure);
        if (failure != null) throw failure;
    }

    private static RuntimeException closeCollecting(AutoCloseable resource,
                                                     RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (Exception cleanupFailure) {
            RuntimeException runtime = cleanupFailure instanceof RuntimeException existing
                    ? existing
                    : new IllegalStateException("Failed to close pipeline generation resource",
                    cleanupFailure);
            if (failure == null) return runtime;
            failure.addSuppressed(runtime);
        }
        return failure;
    }
}
