package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;

import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;

/** Asset-owned, delta-only GPU storage for one primitive's morph targets. */
final class GltfMorphTargetBuffer implements AutoCloseable {
    static final int DELTA_STORAGE_BINDING = 8;
    static final int WEIGHT_STORAGE_BINDING = 9;
    private static final int FLOATS_PER_VERTEX_TARGET = 12;

    private final GlBuffer deltas;
    private final int targetCount;
    private final int vertexCount;
    private final long byteSize;
    private boolean closed;

    GltfMorphTargetBuffer(LoadedGltfScene.MorphTargetSetDef definition) {
        Objects.requireNonNull(definition, "definition");
        targetCount = definition.targets().size();
        vertexCount = definition.vertexCount();
        float[] packed = new float[Math.multiplyExact(
                Math.multiplyExact(targetCount, vertexCount), FLOATS_PER_VERTEX_TARGET)];
        List<LoadedGltfScene.MorphTargetDef> targets = definition.targets();
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            LoadedGltfScene.MorphTargetDef target = targets.get(targetIndex);
            pack(target.positionDeltas(), packed, targetIndex, 0);
            pack(target.normalDeltas(), packed, targetIndex, 4);
            pack(target.tangentDeltas(), packed, targetIndex, 8);
        }
        byteSize = Math.multiplyExact((long) packed.length, Float.BYTES);
        deltas = GlBuffer.shaderStorageBuffer(GL_STATIC_DRAW).upload(packed);
    }

    GlBuffer buffer() {
        ensureOpen();
        return deltas;
    }

    int targetCount() {
        return targetCount;
    }

    int vertexCount() {
        return vertexCount;
    }

    long byteSize() {
        return byteSize;
    }

    @Override
    public void close() {
        if (closed) return;
        deltas.close();
        closed = true;
    }

    private void pack(float[] source, float[] destination, int targetIndex,
                      int tupleOffset) {
        if (source.length == 0) return;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int output = (targetIndex * vertexCount + vertex)
                    * FLOATS_PER_VERTEX_TARGET + tupleOffset;
            System.arraycopy(source, vertex * 3, destination, output, 3);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("morph target buffer is closed");
    }
}
