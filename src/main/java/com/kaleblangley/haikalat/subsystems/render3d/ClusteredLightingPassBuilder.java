package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.graph.RenderGraph;

/**
 * Registers the no-framebuffer clustered lighting passes and their dependency
 * edges.  Bounds depend on the light upload, assignment depends on bounds, and
 * the forward geometry pass depends on assignment.
 */
final class ClusteredLightingPassBuilder {
    static final String LIGHT_UPLOAD_PASS = "ClusteredLightUpload";
    static final String CLUSTER_BOUNDS_PASS = "ClusteredClusterBounds";
    static final String CLUSTER_ASSIGN_PASS = "ClusteredClusterAssign";
    static final String CLUSTER_STATS_PASS = "ClusteredClusterStats";

    private ClusteredLightingPassBuilder() {
    }

    static void addPasses(RenderGraph graph, PipelineTopology topology,
                          ClusteredLightingBinder binder) {
        RenderGraph.PassBuilder upload = graph.addPass(LIGHT_UPLOAD_PASS).computeOnly();
        if (topology.directionalShadow()) {
            upload.dependsOn(DirectionalShadowMap.PASS_NAME);
        }
        if (topology.pointShadow()) {
            upload.dependsOn(PointShadowAtlas.PASS_NAME);
        }
        if (topology.spotShadow()) {
            upload.dependsOn(SpotShadowAtlas.PASS_NAME);
        }
        upload.execute((resources, cmd) -> binder.recordUpload(cmd));

        graph.addPass(CLUSTER_BOUNDS_PASS)
                .computeOnly()
                .dependsOn(LIGHT_UPLOAD_PASS)
                .execute((resources, cmd) -> binder.recordBounds(cmd));

        graph.addPass(CLUSTER_ASSIGN_PASS)
                .computeOnly()
                .dependsOn(CLUSTER_BOUNDS_PASS)
                .execute((resources, cmd) -> binder.recordAssign(cmd));

        graph.addPass(CLUSTER_STATS_PASS)
                .computeOnly()
                .dependsOn(CLUSTER_ASSIGN_PASS)
                .execute((resources, cmd) -> binder.recordStats(cmd));
    }
}
