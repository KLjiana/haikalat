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
    ShadowSamplingBlock shadowSamplingBlock;
    final ShadowLightScheduler shadowLightScheduler = new ShadowLightScheduler();
    final ShadowCacheState shadowCache = new ShadowCacheState();
    final LightingBinder lightingBinder = new LightingBinder();
    PostProcessPassBuilder postProcess;
    ShaderProgram shadowShader;
    ShaderProgram maskedShadowShader;
    ShaderProgram instancedShadowShader;
    ShaderProgram gtaoDepthShader;
    ShaderProgram gtaoMaskedDepthShader;
    ShaderProgram gtaoInstancedDepthShader;
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
        ResizeCandidate candidate = null;
        Throwable primaryFailure = null;
        try {
            candidate = prepareResize(width, height);
            commitResize(candidate);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (RuntimeException failure) {
                    cleanupFailure = failure;
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    /** Allocates all resize resources without changing the active generation. */
    private ResizeCandidate prepareResize(int width, int height) {
        RenderGraph.ResizeCandidate graphCandidate = null;
        PostProcessPassBuilder.ResizeCandidate postProcessCandidate = null;
        try {
            graphCandidate = graph.prepareResize(width, height);
            postProcessCandidate = postProcess.prepareResize(width, height);
            return new ResizeCandidate(this, width, height, topology.withExtent(width, height),
                    graphCandidate, postProcessCandidate);
        } catch (RuntimeException | Error failure) {
            if (postProcessCandidate != null) {
                try {
                    postProcessCandidate.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (graphCandidate != null) {
                try {
                    graphCandidate.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private void commitResize(ResizeCandidate candidate) {
        if (candidate == null) throw new NullPointerException("candidate");
        candidate.commitInto(this);
    }

    /** One generation-wide resize transaction for graph, TAA, GTAO and topology. */
    private static final class ResizeCandidate implements AutoCloseable {
        private final PipelineGeneration owner;
        private final int width;
        private final int height;
        private final PipelineTopology candidateTopology;
        private final RenderGraph.ResizeCandidate graphCandidate;
        private final PostProcessPassBuilder.ResizeCandidate postProcessCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(PipelineGeneration owner, int width, int height,
                                PipelineTopology candidateTopology,
                                RenderGraph.ResizeCandidate graphCandidate,
                                PostProcessPassBuilder.ResizeCandidate postProcessCandidate) {
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.candidateTopology = candidateTopology;
            this.graphCandidate = graphCandidate;
            this.postProcessCandidate = postProcessCandidate;
        }

        private void validateFor(PipelineGeneration expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("resize candidate belongs to another pipeline generation");
            }
            if (closed) throw new IllegalStateException("pipeline resize candidate is closed");
            if (committed) throw new IllegalStateException("pipeline resize candidate already committed");
            // The child candidates are private to this generation and were
            // prepared from these exact owners.  Their public commit methods
            // perform the final ownership/state checks; after preparation they
            // only swap already-allocated handles and do not allocate.
        }

        private void commitInto(PipelineGeneration expectedOwner) {
            validateFor(expectedOwner);
            owner.graph.commitResize(graphCandidate);
            owner.postProcess.commitResize(postProcessCandidate);
            owner.topology = candidateTopology;
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            try {
                postProcessCandidate.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                graphCandidate.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        GraphPreviewRenderer localPreview = previewRenderer;
        ShaderProgram localInstancedShadow = instancedShadowShader;
        ShaderProgram localShadow = shadowShader;
        ShaderProgram localMaskedShadow = maskedShadowShader;
        ShaderProgram localGtaoDepth = gtaoDepthShader;
        ShaderProgram localGtaoMaskedDepth = gtaoMaskedDepthShader;
        ShaderProgram localGtaoInstancedDepth = gtaoInstancedDepthShader;
        PostProcessPassBuilder localPostProcess = postProcess;
        CameraUniforms localCameraUniforms = cameraUniforms;
        ShadowSamplingBlock localShadowSamplingBlock = shadowSamplingBlock;
        PbrMaterialBinder localPbrBinder = pbrMaterialBinder;
        EnvironmentBackgroundRenderer localBackground = environmentBackground;
        RenderGraph localGraph = graph;
        previewRenderer = null;
        instancedShadowShader = null;
        shadowShader = null;
        maskedShadowShader = null;
        gtaoDepthShader = null;
        gtaoMaskedDepthShader = null;
        gtaoInstancedDepthShader = null;
        postProcess = null;
        cameraUniforms = null;
        shadowSamplingBlock = null;
        pbrMaterialBinder = null;
        environmentBackground = null;
        graph = null;
        finalPassName = null;

        RuntimeException failure = null;
        failure = closeCollecting(localPreview, failure);
        failure = closeCollecting(localInstancedShadow, failure);
        failure = closeCollecting(localShadow, failure);
        failure = closeCollecting(localMaskedShadow, failure);
        failure = closeCollecting(localGtaoInstancedDepth, failure);
        failure = closeCollecting(localGtaoMaskedDepth, failure);
        failure = closeCollecting(localGtaoDepth, failure);
        failure = closeCollecting(localPostProcess, failure);
        failure = closeCollecting(localCameraUniforms, failure);
        failure = closeCollecting(localShadowSamplingBlock, failure);
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
