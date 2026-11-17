package com.kaleblangley.haikalat.subsystems.render3d.geometry;

import com.kaleblangley.haikalat.subsystems.render3d.Camera;

import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

import java.util.Set;

public final class CameraUniforms implements AutoCloseable {
    private static final String BLOCK_NAME = "CameraBlock";
    private static final int BLOCK_BINDING = 0;
    private static final int BLOCK_SIZE_BYTES = 2 * 16 * Float.BYTES;

    private final UniformBlock block = new UniformBlock(BLOCK_SIZE_BYTES);
    private final Set<ShaderProgram> boundPrograms = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();

    public void update(CommandBuffer cmd, Camera camera, int width, int height,
                AntiAliasingMode mode, int frameIndex) {
        CameraProjection.stable(camera, Math.max(1, width), Math.max(1, height), projection);
        applyTemporalJitter(projection, width, height, mode, frameIndex);

        block.setMat4(0, projection)
                .setMat4(16 * Float.BYTES, camera.getViewMatrix(view));
        cmd.bindUniformBlock(BLOCK_BINDING, block);
    }

    /**
     * Uploads a projection that the frame already owns.
     *
     * <p>The reduced-resolution path captures its jittered reduced projection
     * once in the temporal frame pose ({@code TemporalFrameState.FrameParameters})
     * so the camera block used for rasterisation is the same matrix the
     * internal temporal upscaler reconstructs with; recomputing the jitter here
     * would create a second source of truth for the phase.</p>
     *
     * @param cmd        command buffer receiving the block
     * @param camera     camera supplying the view matrix
     * @param projection projection for this frame's raster
     */
    public void update(CommandBuffer cmd, Camera camera, Matrix4f projection) {
        block.setMat4(0, projection)
                .setMat4(16 * Float.BYTES, camera.getViewMatrix(view));
        cmd.bindUniformBlock(BLOCK_BINDING, block);
    }

    public void bind(ShaderProgram shader) {
        if (boundPrograms.add(shader)) {
            shader.bindUniformBlock(BLOCK_NAME, BLOCK_BINDING);
        }
    }

    @Override
    public void close() {
        block.close();
        boundPrograms.clear();
    }

    public static void applyTemporalJitter(Matrix4f projection, int width, int height,
                                    AntiAliasingMode mode, int frameIndex) {
        TemporalJitter.applyProjection(projection, width, height, mode, frameIndex);
    }
}
