package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.graph.RenderGraph;

/**
 * Render3D-owned late surface production (reactive history rejection) that the
 * colour chain must schedule after the last opaque/transparent colour producer.
 *
 * <p>The producer keeps ownership of its pass resources and graph callback;
 * the colour chain only knows where in its order the pass belongs.</p>
 */
public interface LateSurfaceProducer {
    /** @return whether the active topology produces a late surface output at all */
    boolean enabled();

    /**
     * Declares the late pass after {@code colorProducerPass}.  Called exactly
     * once per graph build, before {@link PostProcessPipeline#addFinalPass}.
     */
    void declare(RenderGraph graph, String colorProducerPass);
}
