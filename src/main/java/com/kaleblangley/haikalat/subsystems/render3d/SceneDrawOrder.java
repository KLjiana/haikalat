package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.BlendMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 在 CommandBuffer 上层执行安全 draw 排序，以保持 pass 和透明物体语义。 */
final class SceneDrawOrder {
    private SceneDrawOrder() {
    }

    static List<MeshRenderer> forward(List<MeshRenderer> source) {
        List<IndexedRenderer> ordered = indexed(source);
        ordered.sort((left, right) -> {
            BlendMode leftBlend = blendMode(left.renderer);
            BlendMode rightBlend = blendMode(right.renderer);
            int category = Integer.compare(rank(leftBlend), rank(rightBlend));
            if (category != 0) return category;
            if (leftBlend == BlendMode.ALPHA) {
                return Integer.compare(left.index, right.index); // stable user-defined transparency order
            }
            int shader = Integer.compare(shaderId(left.renderer), shaderId(right.renderer));
            if (shader != 0) return shader;
            int material = Integer.compare(
                    System.identityHashCode(left.renderer.material().material()),
                    System.identityHashCode(right.renderer.material().material()));
            if (material != 0) return material;
            return Integer.compare(left.renderer.mesh().vertexArray().id(),
                    right.renderer.mesh().vertexArray().id());
        });
        return ordered.stream().map(IndexedRenderer::renderer).toList();
    }

    static List<MeshRenderer> shadow(List<MeshRenderer> source) {
        return source.stream()
                .filter(MeshRenderer::castShadows)
                .sorted(Comparator.comparingInt(renderer -> renderer.mesh().vertexArray().id()))
                .toList();
    }

    private static List<IndexedRenderer> indexed(List<MeshRenderer> source) {
        List<IndexedRenderer> result = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) result.add(new IndexedRenderer(source.get(i), i));
        return result;
    }

    private static BlendMode blendMode(MeshRenderer renderer) {
        return renderer.material().material().blendMode();
    }

    private static int shaderId(MeshRenderer renderer) {
        return renderer.material().material().shader().id();
    }

    private static int rank(BlendMode mode) {
        return switch (mode) {
            case OPAQUE -> 0;
            case ADDITIVE -> 1;
            case ALPHA -> 2;
        };
    }

    private record IndexedRenderer(MeshRenderer renderer, int index) {
    }
}
