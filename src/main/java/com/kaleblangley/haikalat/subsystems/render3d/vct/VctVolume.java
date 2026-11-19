package com.kaleblangley.haikalat.subsystems.render3d.vct;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.texture.Texture3D;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;

/**
 * Exclusive owner of one voxel volume generation and its upload/readback
 * buffers. All storage is allocated together; a candidate is never published
 * until every allocation succeeded.
 */
final class VctVolume implements AutoCloseable {
    static final int TRIANGLE_FLOATS = VctSceneInput.MAX_TRIANGLES * VctSceneInput.FLOATS_PER_TRIANGLE;
    static final int PROBE_INPUT_FLOATS = VctProbeBatch.MAX_PROBES * VctProbeBatch.FLOATS_PER_PROBE;
    static final int PROBE_OUTPUT_FLOATS =
            VctProbeBatch.MAX_PROBES * VctProbeBatch.RESULT_FLOATS_PER_PROBE;
    static final long TRIANGLE_BYTES = (long) TRIANGLE_FLOATS * Float.BYTES;
    static final long PROBE_INPUT_BYTES = (long) PROBE_INPUT_FLOATS * Float.BYTES;
    static final long PROBE_OUTPUT_BYTES = (long) PROBE_OUTPUT_FLOATS * Float.BYTES;
    static final long FIXED_BUFFER_BYTES = TRIANGLE_BYTES + PROBE_INPUT_BYTES + PROBE_OUTPUT_BYTES;

    final Texture3D texture;
    final GlBuffer triangles;
    final GlBuffer probeInput;
    final GlBuffer probeOutput;
    final long bytes;
    private boolean closed;

    VctVolume(VctSettings settings) {
        long textureBytes = VctVoxelMath.estimatedVolumeBytes(settings.resolution());
        long totalBytes = Math.addExact(textureBytes, FIXED_BUFFER_BYTES);
        if (totalBytes > settings.memoryBudgetBytes()) {
            throw new IllegalArgumentException("voxel volume requires " + totalBytes
                    + " bytes, exceeding the " + settings.memoryBudgetBytes() + " byte budget");
        }
        injectAllocationFailure();
        Texture3D createdTexture = null;
        GlBuffer createdTriangles = null;
        GlBuffer createdProbeInput = null;
        GlBuffer createdProbeOutput = null;
        try {
            createdTexture = Texture3D.create(settings.resolution(), settings.resolution(),
                    settings.resolution(), RenderFormat.RGBA16F, settings.mipLevels());
            createdTriangles = storageBuffer(TRIANGLE_BYTES, GL_DYNAMIC_STORAGE_BIT);
            createdProbeInput = storageBuffer(PROBE_INPUT_BYTES, GL_DYNAMIC_STORAGE_BIT);
            createdProbeOutput = storageBuffer(PROBE_OUTPUT_BYTES, 0);
            texture = createdTexture;
            triangles = createdTriangles;
            probeInput = createdProbeInput;
            probeOutput = createdProbeOutput;
            bytes = totalBytes;
        } catch (RuntimeException | Error failure) {
            closeCollecting(createdProbeOutput, failure);
            closeCollecting(createdProbeInput, failure);
            closeCollecting(createdTriangles, failure);
            closeCollecting(createdTexture, failure);
            throw failure;
        }
    }

    private static GlBuffer storageBuffer(long bytes, int storageFlags) {
        GlBuffer buffer = new GlBuffer(GL_SHADER_STORAGE_BUFFER, GL_DYNAMIC_DRAW);
        try {
            buffer.allocateStorage(bytes, storageFlags);
            GlDebug.assertNoError("voxel storage buffer allocation");
            return buffer;
        } catch (RuntimeException failure) {
            buffer.close();
            throw failure;
        }
    }

    private static void injectAllocationFailure() {
        if (Boolean.getBoolean("haikalat.test.failVctVolumeAllocation")) {
            System.clearProperty("haikalat.test.failVctVolumeAllocation");
            throw new IllegalStateException("injected voxel volume allocation failure");
        }
    }

    void ensureOpen() {
        if (closed) throw new IllegalStateException("voxel volume is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        failure = closeCollecting(probeOutput, failure);
        failure = closeCollecting(probeInput, failure);
        failure = closeCollecting(triangles, failure);
        failure = closeCollecting(texture, failure);
        rethrow(failure);
    }

    private static Throwable closeCollecting(AutoCloseable resource, Throwable failure) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (Exception | Error cleanup) {
            if (failure == null) return cleanup;
            failure.addSuppressed(cleanup);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new IllegalStateException("voxel volume cleanup failed", failure);
    }
}
