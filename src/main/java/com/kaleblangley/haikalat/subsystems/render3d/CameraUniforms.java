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

    void update(CommandBuffer cmd, Camera camera, int width, int height,
                AntiAliasingMode mode, int frameIndex) {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(45.0),
                width / (float) Math.max(1, height), 0.1f, 100.0f);
        applyTemporalJitter(projection, width, height, mode, frameIndex);

        block.setMat4(0, projection)
                .setMat4(16 * Float.BYTES, camera.getViewMatrix());
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
        float[] jitter = jitter(frameIndex);
        projection.m20(projection.m20() + (jitter[0] * 2.0f / width));
        projection.m21(projection.m21() + (jitter[1] * 2.0f / height));
    }

    static float[] jitter(int frameIndex) {
        return switch (frameIndex & 3) {
            case 0 -> new float[]{-0.25f, -0.25f};
            case 1 -> new float[]{0.25f, -0.25f};
            case 2 -> new float[]{-0.25f, 0.25f};
            default -> new float[]{0.25f, 0.25f};
        };
    }
}
