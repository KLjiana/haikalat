package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.FrontFace;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.UniformKey;
import com.kaleblangley.haikalat.core.material.UniformValue;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;

/**
 * Producer of the shared scene surface buffers (depth, world normal, velocity,
 * previous surface depth, validity and reactive).  It renders the same
 * visibility set and deformation state as the forward geometry pass, using the
 * stable projection for motion and the jittered projection for rasterisation.
 */
final class SceneSurfacePass implements AutoCloseable {
    static final int PREVIOUS_INSTANCE_BINDING = 12;

    private final ShaderProgram surfaceProgram;
    private final ShaderProgram instancedProgram;
    private GlBuffer previousInstanceBuffer;
    private long previousInstanceBytes;
    private float[] instanceScratch = new float[0];
    private ByteBuffer instanceBytes = ByteBuffer.allocateDirect(0);
    private final Matrix4f scratchPreviousModel = new Matrix4f();
    private boolean closed;

    SceneSurfacePass() {
        ShaderProgram surface = null;
        ShaderProgram instanced = null;
        try {
            surface = ShaderProgram.fromResource(SceneSurfacePass.class,
                    "/shaders/render3d/surface/scene-surface.vert",
                    "/shaders/render3d/surface/scene-surface.frag");
            instanced = ShaderProgram.fromResource(SceneSurfacePass.class,
                    "/shaders/render3d/surface/scene-surface-instanced.vert",
                    "/shaders/render3d/surface/scene-surface.frag");
            this.surfaceProgram = surface;
            this.instancedProgram = instanced;
        } catch (RuntimeException failure) {
            if (instanced != null) instanced.close();
            if (surface != null) surface.close();
            throw failure;
        }
    }

    void record(CommandBuffer cmd, SceneFrame frame, TemporalSceneState sceneState,
                TemporalFrameState.FrameParameters current,
                TemporalFrameState.FrameParameters previous,
                InstancedRenderer instanced, int frameIndex, CameraUniforms cameraUniforms) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(frame, "frame");
        TemporalFrameState.FrameParameters previousFrame = previous == null ? current : previous;
        bindCameraUniforms(cmd, surfaceProgram, cameraUniforms, current, previousFrame);
        cmd.bindShader(surfaceProgram)
                .enableBlend(false)
                .enableDepthTest(true)
                .depthMask(true)
                .frontFace(FrontFace.CCW)
                .enableCullFace(true)
                .setUniformInt(surfaceProgram, "uDerivativeNormal", 0);

        ShaderProgram boundShader = surfaceProgram;
        Mesh boundMesh = null;
        boolean frontFaceBound = false;
        boolean cullBound = false;
        boolean boundMirrored = false;
        boolean boundCull = false;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        int boundMasked = -1;
        int boundPreviousValid = -1;
        for (int queueIndex = 0; queueIndex < frame.forwardCount; queueIndex++) {
            int entry = frame.forwardEntry(queueIndex);
            if (!frame.castsOpaqueShadow(entry)) continue;
            MeshRenderer renderer = frame.renderer(entry);
            boolean mirrored = frame.mirrored(entry);
            boolean cull = renderer.material().material().cullMode()
                    == com.kaleblangley.haikalat.core.CullMode.BACK;
            if (!frontFaceBound || mirrored != boundMirrored) {
                cmd.frontFace(mirrored ? FrontFace.CW : FrontFace.CCW);
                frontFaceBound = true;
                boundMirrored = mirrored;
            }
            if (!cullBound || cull != boundCull) {
                cmd.enableCullFace(cull);
                cullBound = true;
                boundCull = cull;
            }
            int masked = frame.masked(entry) ? 1 : 0;
            if (masked != boundMasked) {
                cmd.setUniformInt(surfaceProgram, "uMasked", masked);
                boundMasked = masked;
            }
            if (masked == 1) {
                bindMaskedMaterial(cmd, surfaceProgram, renderer.material());
            }
            Matrix4f model = frame.model(entry);
            Matrix4f previousModel = sceneState.previousModel(renderer);
            boolean previousValid = sceneState.previousStateValid(renderer);
            int previousValidValue = previousValid ? 1 : 0;
            if (previousValidValue != boundPreviousValid) {
                cmd.setUniformInt(surfaceProgram, "uPreviousValid", previousValidValue);
                boundPreviousValid = previousValidValue;
            }
            cmd.setUniformMat4(surfaceProgram, "uModel", model)
                    .setUniformMat4(surfaceProgram, "uPreviousModel",
                            previousModel == null
                                    ? scratchPreviousModel.set(model) : previousModel);
            SceneDrawBinding binding = renderer.drawBinding();
            int skinningEnabled = binding.skinningEnabled() ? 1 : 0;
            int morphTargetCount = binding.morphTargetCount();
            if (skinningEnabled != boundSkinningEnabled) {
                cmd.trySetUniformInt(surfaceProgram, "uSkinningEnabled", skinningEnabled);
                boundSkinningEnabled = skinningEnabled;
            }
            if (morphTargetCount != boundMorphTargetCount) {
                cmd.trySetUniformInt(surfaceProgram, "uMorphTargetCount", morphTargetCount);
                boundMorphTargetCount = morphTargetCount;
            }
            if (binding != SceneDrawBinding.NONE) {
                binding.record(cmd, surfaceProgram, frameIndex, SceneDrawBinding.Pass.SURFACE);
                sceneState.recordPreviousDeformation(cmd, surfaceProgram, renderer);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }

        if (instanced != null && instanced.instanceCount() > 0) {
            recordInstanced(cmd, instanced, current, previousFrame, cameraUniforms);
        }
    }

    private void recordInstanced(CommandBuffer cmd, InstancedRenderer instanced,
                                 TemporalFrameState.FrameParameters current,
                                 TemporalFrameState.FrameParameters previous,
                                 CameraUniforms cameraUniforms) {
        cmd.enableCullFace(false).frontFace(FrontFace.CCW);
        cmd.bindShader(instancedProgram)
                .setUniformInt(instancedProgram, "uDerivativeNormal", 1);
        bindCameraUniforms(cmd, instancedProgram, cameraUniforms, current, previous);
        int count = instanced.instanceCount();
        boolean previousValid = instanced.previousFrameValid();
        cmd.setUniformInt(instancedProgram, "uPreviousValid", previousValid ? 1 : 0);
        uploadPreviousInstances(cmd, instanced, count, previousValid);
        cmd.bindStorageBuffer(PREVIOUS_INSTANCE_BINDING, previousInstanceBuffer, 0L,
                (long) count * 16L * Float.BYTES);
        instanced.renderDepth(cmd, instancedProgram);
    }

    private void uploadPreviousInstances(CommandBuffer cmd, InstancedRenderer instanced,
                                         int count, boolean previousValid) {
        int requiredFloats = count * 16;
        if (requiredFloats == 0) return;
        if (instanceScratch.length < requiredFloats) {
            instanceScratch = new float[requiredFloats];
        }
        if (instanceBytes.capacity() < requiredFloats * Float.BYTES) {
            instanceBytes = ByteBuffer.allocateDirect(requiredFloats * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
        }
        if (previousInstanceBuffer == null
                || previousInstanceBytes < (long) requiredFloats * Float.BYTES) {
            if (previousInstanceBuffer != null) previousInstanceBuffer.close();
            previousInstanceBytes = (long) requiredFloats * Float.BYTES;
            previousInstanceBuffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                    .allocate(previousInstanceBytes);
        }
        java.util.List<Matrix4f> transforms = previousValid
                ? instanced.previousFrameTransforms() : instanced.currentFrameTransforms();
        int offset = 0;
        for (Matrix4f transform : transforms) {
            if (offset >= requiredFloats) break;
            transform.get(instanceScratch, offset);
            offset += 16;
        }
        instanceBytes.clear();
        instanceBytes.asFloatBuffer().put(instanceScratch, 0, requiredFloats);
        instanceBytes.limit(requiredFloats * Float.BYTES);
        cmd.uploadBufferRegion(previousInstanceBuffer, 0L, instanceBytes);
    }

    private static void bindCameraUniforms(CommandBuffer cmd, ShaderProgram shader,
                                           CameraUniforms cameraUniforms,
                                           TemporalFrameState.FrameParameters current,
                                           TemporalFrameState.FrameParameters previous) {
        cameraUniforms.bind(shader);
        cmd.setUniformMat4(shader, "uCurrentStableViewProjection", current.stableViewProjection())
                .setUniformMat4(shader, "uPreviousStableViewProjection", previous.stableViewProjection())
                .setUniformMat4(shader, "uPreviousView", previous.view())
                .setUniformVec3(shader, "uCameraPosition",
                        new org.joml.Vector3f(current.cameraPositionX(), current.cameraPositionY(),
                                current.cameraPositionZ()));
    }

    private static void bindMaskedMaterial(CommandBuffer cmd, ShaderProgram shader,
                                           MaterialInstance instance) {
        Material material = instance.material();
        Material.TextureBinding baseColor = instance.textureOverrides().get(0);
        if (baseColor == null) {
            for (Material.TextureBinding binding : material.defaultTextures()) {
                if (binding.unit() == 0) {
                    baseColor = binding;
                    break;
                }
            }
        }
        if (baseColor != null) cmd.bindTexture(0, baseColor.texture(), baseColor.sampler());
        UniformValue cutoff = instance.uniformOverrides().get(UniformKey.float1("uAlphaCutoff"));
        if (cutoff == null) {
            cutoff = material.defaultUniforms().get(UniformKey.float1("uAlphaCutoff"));
        }
        UniformValue factor = instance.uniformOverrides().get(UniformKey.vec4("uBaseColorFactor"));
        if (factor == null) {
            factor = material.defaultUniforms().get(UniformKey.vec4("uBaseColorFactor"));
        }
        float cutoffValue = cutoff instanceof UniformValue.FloatVal value ? value.value() : 0.5f;
        Vector4f factorValue = factor instanceof UniformValue.Vec4Val value
                ? value.value() : new Vector4f(1.0f);
        cmd.setUniformInt(shader, "uBaseColorMap", 0)
                .setUniformFloat(shader, "uAlphaCutoff", cutoffValue)
                .setUniformVec4(shader, "uBaseColorFactor", factorValue);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        if (previousInstanceBuffer != null) {
            try {
                previousInstanceBuffer.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            } finally {
                previousInstanceBuffer = null;
            }
        }
        try {
            instancedProgram.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            surfaceProgram.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("scene surface pass is closed");
    }
}
