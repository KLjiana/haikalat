package com.kaleblangley.haikalat.core.graph;

import java.util.List;

/** 不持有窗口或 framebuffer 的不可变 RenderGraph 拓扑执行计划。 */
final class CompiledRenderGraph {
    private final List<String> passNames;

    CompiledRenderGraph(List<String> passNames) {
        this.passNames = List.copyOf(passNames);
    }

    List<String> passNames() {
        return passNames;
    }
}
