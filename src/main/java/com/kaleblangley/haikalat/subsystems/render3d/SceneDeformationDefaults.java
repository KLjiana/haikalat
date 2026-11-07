package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;

/** Valid storage for inactive deformation blocks; real draw bindings replace active inputs. */
final class SceneDeformationDefaults implements AutoCloseable {
    private final GlBuffer identityJoint;
    private final GlBuffer zeroMorph;
    private boolean closed;

    SceneDeformationDefaults() {
        GlBuffer joint = null, zero = null;
        try {
            joint = GlBuffer.shaderStorageBuffer(GL_STATIC_DRAW).upload(new float[]{
                    1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1});
            zero = GlBuffer.shaderStorageBuffer(GL_STATIC_DRAW).upload(new float[4]);
            identityJoint = joint;
            zeroMorph = zero;
        } catch (RuntimeException | Error failure) {
            if (zero != null) try { zero.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            if (joint != null) try { joint.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void record(CommandBuffer commands) {
        if (closed) throw new IllegalStateException("scene deformation defaults are closed");
        commands.bindStorageBuffer(7, identityJoint, 0, 64)
                .bindStorageBuffer(8, zeroMorph, 0, 16)
                .bindStorageBuffer(9, zeroMorph, 0, 16)
                .bindStorageBuffer(10, identityJoint, 0, 64)
                .bindStorageBuffer(11, zeroMorph, 0, 16);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { zeroMorph.close(); } finally { identityJoint.close(); }
    }
}
