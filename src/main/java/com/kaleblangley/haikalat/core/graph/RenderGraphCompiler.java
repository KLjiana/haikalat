package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.GlException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** 只负责 pass dependency 校验与稳定拓扑排序。 */
final class RenderGraphCompiler {
    private RenderGraphCompiler() {
    }

    static CompiledRenderGraph compile(List<PassSpec> passes) {
        Map<String, PassSpec> byName = new LinkedHashMap<>();
        for (PassSpec pass : passes) {
            if (byName.putIfAbsent(pass.name(), pass) != null) {
                throw new GlException("Duplicate RenderGraph pass: " + pass.name());
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (PassSpec pass : passes) {
            inDegree.putIfAbsent(pass.name(), 0);
            Set<String> uniqueDependencies = new HashSet<>();
            for (String dependency : pass.dependencies()) {
                if (!byName.containsKey(dependency)) {
                    throw new GlException("RenderGraph pass " + pass.name()
                            + " depends on missing pass " + dependency);
                }
                if (!uniqueDependencies.add(dependency)) {
                    throw new GlException("RenderGraph pass " + pass.name()
                            + " declares duplicate dependency " + dependency);
                }
                inDegree.merge(pass.name(), 1, Integer::sum);
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>())
                        .add(pass.name());
            }
        }

        Map<String, Integer> declarationOrder = new HashMap<>();
        for (int index = 0; index < passes.size(); index++) {
            declarationOrder.put(passes.get(index).name(), index);
        }
        PriorityQueue<String> ready = new PriorityQueue<>(
                (left, right) -> Integer.compare(declarationOrder.get(left), declarationOrder.get(right)));
        for (PassSpec pass : passes) {
            if (inDegree.get(pass.name()) == 0) ready.add(pass.name());
        }
        List<String> sorted = new ArrayList<>(passes.size());
        while (!ready.isEmpty()) {
            String pass = ready.remove();
            sorted.add(pass);
            for (String dependent : dependents.getOrDefault(pass, List.of())) {
                int degree = inDegree.merge(dependent, -1, Integer::sum);
                if (degree == 0) ready.add(dependent);
            }
        }
        if (sorted.size() != passes.size()) {
            throw new GlException("RenderGraph has circular dependency");
        }
        return new CompiledRenderGraph(sorted);
    }

    record PassSpec(String name, List<String> dependencies) {
        PassSpec {
            dependencies = List.copyOf(dependencies);
        }
    }
}
