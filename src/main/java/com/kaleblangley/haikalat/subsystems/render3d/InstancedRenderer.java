package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
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
    private volatile List<Matrix4f> frameTransforms = List.of();
    private final AtomicInteger frameIndex = new AtomicInteger(0);
    private final AtomicInteger drawnCount = new AtomicInteger(0);
    private final List<InstanceDef> definitions = new ArrayList<>();

    public InstancedRenderer(InstancedMeshBatch batch, ShaderProgram shader) {
        this.batch = batch;
        this.shader = shader;
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
        List<Matrix4f> nextFrame = new ArrayList<>(definitions.size());
        for (InstanceDef def : definitions) {
            nextFrame.add(new Matrix4f(def.compute(frame)));
        }
        frameTransforms = List.copyOf(nextFrame);
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
        cmd.drawInstancedBatch(batch, frameTransforms, drawnCount::set);
    }

    public void close() {
        batch.close();
    }

    @FunctionalInterface
    public interface InstanceDef {
        Matrix4f compute(int frame);
    }
}
