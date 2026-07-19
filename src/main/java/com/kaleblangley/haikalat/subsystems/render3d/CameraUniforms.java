package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Set;

final class CameraUniforms implements AutoCloseable {
    private static final String BLOCK_NAME = "CameraBlock";
    private static final int BLOCK_BINDING = 0;
    private static final int BLOCK_SIZE_BYTES = 2 * 16 * Float.BYTES;

    private final UniformBlock block = new UniformBlock(BLOCK_SIZE_BYTES);
    private final Set<Integer> boundPrograms = new HashSet<>();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();

    void update(CommandBuffer cmd, Camera camera, int width, int height,
                AntiAliasingMode mode, int frameIndex) {
        CameraProjection.stable(camera, Math.max(1, width), Math.max(1, height), projection);
        applyTemporalJitter(projection, width, height, mode, frameIndex);

        block.setMat4(0, projection)
                .setMat4(16 * Float.BYTES, camera.getViewMatrix(view));
        cmd.bindUniformBlock(BLOCK_BINDING, block);
    }

    void bind(ShaderProgram shader) {
        if (boundPrograms.add(shader.id())) {
            shader.bindUniformBlock(BLOCK_NAME, BLOCK_BINDING);
        }
    }

    @Override
    public void close() {
        block.close();
        boundPrograms.clear();
    }

    static void applyTemporalJitter(Matrix4f projection, int width, int height,
                                    AntiAliasingMode mode, int frameIndex) {
        if (mode != AntiAliasingMode.TAA || width <= 0 || height <= 0) {
            return;
        }
        int phase = frameIndex & 3;
        float jitterX = (phase & 1) == 0 ? -0.25f : 0.25f;
        float jitterY = (phase & 2) == 0 ? -0.25f : 0.25f;
        projection.m20(projection.m20() + (jitterX * 2.0f / width));
        projection.m21(projection.m21() + (jitterY * 2.0f / height));
    }
}
