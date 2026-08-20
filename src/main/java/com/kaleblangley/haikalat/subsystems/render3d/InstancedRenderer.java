package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.InstanceBatchStats;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.buffer.InstanceBufferStatistics;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class InstancedRenderer {
    private final InstancedMeshBatch batch;
    private final ShaderProgram shader;
    private final boolean castShadows;
    private volatile List<Matrix4f> frameTransforms = List.of();
    private final AtomicInteger frameIndex = new AtomicInteger(0);
    private final AtomicInteger drawnCount = new AtomicInteger(0);
    private final AtomicInteger shadowDrawnCount = new AtomicInteger(0);
    private final List<InstanceDef> definitions = new ArrayList<>();
    private boolean sharedBatchPrepared;

    public InstancedRenderer(InstancedMeshBatch batch, ShaderProgram shader) {
        this(batch, shader, false);
    }

    /**
     * 创建实例渲染器。
     *
     * @param batch       geometry 与 shadow pass 复用的实例批次
     * @param shader      geometry pass 使用的着色器
     * @param castShadows 是否把当前实例作为阴影投射物提交
     */
    public InstancedRenderer(InstancedMeshBatch batch, ShaderProgram shader, boolean castShadows) {
        this.batch = batch;
        this.shader = shader;
        this.castShadows = castShadows;
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

    /** @return 是否在 shadow pass 提交当前帧实例 */
    public boolean castShadows() {
        return castShadows;
    }

    /** @return 最近一次 shadow pass 实际绘制的实例数量 */
    public int shadowDrawnCount() {
        return shadowDrawnCount.get();
    }

    public boolean supportsPersistent() {
        return batch.isPersistent();
    }

    public InstanceBatchStats statistics() {
        return batch.statistics();
    }

    public InstanceBufferStatistics bufferStatistics() {
        return batch.bufferStatistics();
    }

    public void resetBufferStatistics() {
        batch.resetBufferStatistics();
    }

    public ShaderProgram shader() {
        return shader;
    }

    public void beginFrame(int frame) {
        // A previous graph failure may have stopped before the final geometry pass;
        // clear that ring slot before accepting the next immutable snapshot.
        batch.abortPrepared();
        frameIndex.set(frame);
        sharedBatchPrepared = false;
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

    /**
     * 使用 shadow pass 已经绑定的 depth-only shader 提交同一帧实例快照。
     * 未显式开启实例阴影时不记录绘制命令。
     */
    public void renderShadow(CommandBuffer cmd) {
        if (!castShadows) {
            shadowDrawnCount.set(0);
            return;
        }
        prepareIfNeeded(cmd);
        cmd.drawPreparedInstancedBatch(batch, shadowDrawnCount::set);
        sharedBatchPrepared = true;
    }

    /** Submit the current instance snapshot to a depth-only prepass. */
    public void renderDepth(CommandBuffer cmd, ShaderProgram depthShader) {
        prepareIfNeeded(cmd);
        cmd.drawPreparedInstancedBatch(batch, ignored -> { });
        sharedBatchPrepared = true;
    }

    /** Abort an in-flight cross-pass batch after a RenderGraph command failure. */
    public void abortFrame() {
        batch.abortPrepared();
        sharedBatchPrepared = false;
    }

    private void prepareIfNeeded(CommandBuffer cmd) {
        if (sharedBatchPrepared) return;
        cmd.prepareInstancedBatchPersistent(batch, frameTransforms);
        sharedBatchPrepared = true;
    }

    private void submitBatch(CommandBuffer cmd) {
        if (sharedBatchPrepared) {
            cmd.drawPreparedInstancedBatch(batch, drawnCount::set)
                    .finishPreparedInstancedBatch(batch);
            sharedBatchPrepared = false;
        } else {
            cmd.drawInstancedBatch(batch, frameTransforms, drawnCount::set);
        }
    }

    public void close() {
        batch.close();
    }

    @FunctionalInterface
    public interface InstanceDef {
        Matrix4f compute(int frame);
    }
}
