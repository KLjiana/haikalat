package com.kaleblangley.haikalat.core.graph;

import java.util.Objects;

/**
 * Package-private RenderGraph lookups exposed to out-of-package GL tests.
 *
 * <p>The graph deliberately keeps native attachment ids out of its public
 * contract.  A real-GL acceptance test still needs to hand one generation's
 * scene colour/depth attachments to an independent reference pass, so this
 * test-only bridge reads the already-validated logical-name lookup; it adds no
 * production API and allocates nothing.</p>
 */
public final class RenderGraphTestAccess {
    private RenderGraphTestAccess() {
    }

    /**
     * @return the native texture id registered for a logical attachment name,
     *         or {@code 0} when the graph did not produce it
     */
    public static int textureId(RenderGraph graph, String logicalName) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(logicalName, "logicalName");
        return graph.getTextureAttachmentId(logicalName);
    }
}
