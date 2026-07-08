package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.buffer.TripleBuffer;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.InstanceBatchStats;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class InstancedRenderer {
    private final InstancedMeshBatch batch;
    private final ShaderProgram shader;
    private final TripleBuffer<List<Matrix4f>> tripleBuffer;
    private final AtomicInteger frameIndex = new AtomicInteger(0);
    private final AtomicInteger drawnCount = new AtomicInteger(0);
    private final List<InstanceDef> definitions = new ArrayList<>();

    public InstancedRenderer(InstancedMeshBatch batch, ShaderProgram shader) {
        this.batch = batch;
        this.shader = shader;
        this.tripleBuffer = new TripleBuffer<>(ArrayList::new);
    }

    public void addInstance(InstanceDef def) {
        definitions.add(def);
    }

    public int frameIndex() {
        return frameIndex.get();
    }

    public int drawnCount() {
        return drawnCount.get();
    }

    public boolean supportsPersistent() {
        return batch.isPersistent();
    }

    public InstanceBatchStats statistics() {
        return batch.statistics();
    }

    public ShaderProgram shader() {
        return shader;
    }

    public void beginFrame(int frame) {
        frameIndex.set(frame);
        List<Matrix4f> writes = tripleBuffer.write();
        writes.clear();
        for (InstanceDef def : definitions) {
            writes.add(def.compute(frame));
        }
        tripleBuffer.flip();
    }

    public void render(CommandBuffer cmd, Matrix4f projection, Matrix4f view) {
        cmd.bindShader(shader);
        cmd.setUniformMat4(shader, "uProjection", projection);
        cmd.setUniformMat4(shader, "uView", view);
        submitBatch(cmd);
    }

    public void render(CommandBuffer cmd) {
        cmd.bindShader(shader);
        submitBatch(cmd);
    }

    private void submitBatch(CommandBuffer cmd) {
        // Temporary custom command until instanced batch upload/draw has a formal command API.
        cmd.custom(() -> {
            batch.beginFrame();
            batch.submitAll(tripleBuffer.read());
            drawnCount.set(batch.flush());
        });
    }

    public void close() {
        batch.close();
    }

    @FunctionalInterface
    public interface InstanceDef {
        Matrix4f compute(int frame);
    }
}
