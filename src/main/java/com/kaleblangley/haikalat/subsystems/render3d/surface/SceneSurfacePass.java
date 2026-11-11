package com.kaleblangley.haikalat.subsystems.render3d.surface;

import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraUniforms;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.InstancedRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrame;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalSceneState;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.core.FrontFace;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
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
public final class SceneSurfacePass implements AutoCloseable {
    static final int PREVIOUS_INSTANCE_BINDING = 12;

    private final ShaderProgram surfaceProgram;
    private final ShaderProgram instancedProgram;
    private final boolean roughnessOutput;
    private final boolean subsurfaceOutput;
    private GlBuffer previousInstanceBuffer;
    private long previousInstanceBytes;
    private float[] instanceScratch = new float[0];
    private ByteBuffer instanceBytes = ByteBuffer.allocateDirect(0);
    private final Matrix4f modelScratch = new Matrix4f();
    private final Matrix4f scratchPreviousModel = new Matrix4f();
    private boolean closed;

    public SceneSurfacePass(SceneSurfaceLayout layout) {
        ShaderProgram surface = null;
        ShaderProgram instanced = null;
        try {
            String path = "/shaders/render3d/surface/scene-surface.frag";
            String fragment = fragmentSource(path, layout.rasterShaderDefines());
            surface = ShaderProgram.builder().resource(SceneSurfacePass.class, ShaderStage.VERTEX,
                    "/shaders/render3d/surface/scene-surface.vert").stage(ShaderStage.FRAGMENT, fragment, path).link();
            instanced = ShaderProgram.builder().resource(SceneSurfacePass.class, ShaderStage.VERTEX,
                    "/shaders/render3d/surface/scene-surface-instanced.vert").stage(ShaderStage.FRAGMENT, fragment, path).link();
            this.surfaceProgram = surface;
            this.instancedProgram = instanced;
            this.roughnessOutput = layout.raw(SceneBufferChannel.ROUGHNESS) != null;
            this.subsurfaceOutput = layout.raw(SceneBufferChannel.SUBSURFACE) != null;
        } catch (RuntimeException failure) {
            if (instanced != null) instanced.close();
            if (surface != null) surface.close();
            throw failure;
        }
    }

    static String fragmentSource(String path, String defines) {
        try (var input = SceneSurfacePass.class.getResourceAsStream(path)) {
            if (input == null) throw new GlException("missing surface shader " + path);
            String source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int newline = source.indexOf('\n');
            if (!source.startsWith("#version") || newline < 0) throw new GlException("missing GLSL version in " + path);
            return source.substring(0, newline + 1) + defines + source.substring(newline + 1);
        } catch (java.io.IOException failure) {
            throw new GlException("cannot read surface shader " + path, failure);
        }
    }

    public void record(CommandBuffer cmd, SceneFrame frame, TemporalSceneState sceneState,
                TemporalFrameState.FrameParameters current,
                TemporalFrameState.FrameParameters previous,
                InstancedRenderer instanced, int frameIndex, CameraUniforms cameraUniforms) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(frame, "frame");
        TemporalFrameState.FrameParameters previousFrame = previous == null ? current : previous;
        bindCameraUniforms(cmd, surfaceProgram, cameraUniforms, current, previousFrame);
        cmd.enableBlend(false)
                .enableDepthTest(true)
                .depthMask(true)
                .frontFace(FrontFace.CCW)
                .enableCullFace(true)
                .setUniformInt(surfaceProgram, "uDerivativeNormal", 0);

        boolean programBound = false;
        Mesh boundMesh = null;
        boolean frontFaceBound = false;
        boolean cullBound = false;
        boolean boundMirrored = false;
        boolean boundCull = false;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        int boundMasked = -1;
        int boundPreviousValid = -1;
        float boundRoughness = Float.NaN;
        for (int queueIndex = 0; queueIndex < frame.forwardCount(); queueIndex++) {
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
            if (roughnessOutput) {
                float roughness = materialRoughness(renderer.material());
                if (roughness != boundRoughness) {
                    cmd.setUniformFloat(surfaceProgram, "uRoughness", roughness);
                    boundRoughness = roughness;
                }
            }
            int masked = frame.masked(entry) ? 1 : 0;
            if (masked != boundMasked) {
                cmd.setUniformInt(surfaceProgram, "uMasked", masked);
                boundMasked = masked;
            }
            if (masked == 1) {
                bindMaskedMaterial(cmd, surfaceProgram, renderer.material());
            }
            if (subsurfaceOutput) {
                bindSubsurfaceMaterial(cmd, surfaceProgram, renderer.material());
            }
            Matrix4f model = frame.copyModel(entry, modelScratch);
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
            binding.recordVertexDisplacement(cmd, surfaceProgram, SceneDrawBinding.Pass.SURFACE);
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
            if (!programBound) {
                // First use must see the mesh's attribute arrays, not the preceding pass's VAO.
                // Cold MSAA specialization can otherwise read the generic normal attribute.
                cmd.bindShader(surfaceProgram);
                programBound = true;
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
        if (roughnessOutput) {
            // Instanced draws carry no per-instance material in the first
            // version; report fully rough so they never act as reflectors.
            cmd.setUniformFloat(instancedProgram, "uRoughness", 1.0f);
        }
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

    /**
     * Material roughness written to the shared surface channel.  The first
     * version carries the scalar PBR roughness factor; a per-texel
     * metallic-roughness map is not sampled here and non-PBR materials report
     * fully rough.
     */
    private static float materialRoughness(MaterialInstance instance) {
        if (instance.material().model() != MaterialModel.METALLIC_ROUGHNESS) {
            return 1.0f;
        }
        UniformValue value = instance.uniformOverrides()
                .get(UniformKey.float1("uRoughnessFactor"));
        if (value == null) {
            value = instance.material().defaultUniforms()
                    .get(UniformKey.float1("uRoughnessFactor"));
        }
        return value instanceof UniformValue.FloatVal roughness
                ? Math.min(Math.max(roughness.value(), 0.0f), 1.0f) : 1.0f;
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
        UniformValue vertexColor = instance.uniformOverrides().get(UniformKey.int1("uHasVertexColor"));
        if (vertexColor == null) vertexColor = material.defaultUniforms().get(UniformKey.int1("uHasVertexColor"));
        cmd.setUniformInt(shader, "uBaseColorMap", 0)
                .setUniformInt(shader, "uHasVertexColor", vertexColor instanceof UniformValue.IntVal value ? value.value() : 0)
                .setUniformFloat(shader, "uAlphaCutoff", cutoffValue)
                .setUniformVec4(shader, "uBaseColorFactor", factorValue);
    }

    /**
     * Uploads the per-material subsurface mask inputs. Effective instance
     * overrides are resolved first so the surface mask and the forward shading
     * cannot silently disagree. The thickness source falls back to CONSTANT
     * when the map it would sample has no binding, which keeps a raw
     * {@code Material.builder(...)} caller on the bounded contract instead of
     * sampling an unrelated unit-0 texture.
     */
    private static void bindSubsurfaceMaterial(CommandBuffer cmd, ShaderProgram shader,
                                               MaterialInstance instance) {
        SubsurfaceScatteringSettings settings = instance.material().subsurfaceScattering();
        int enabled = effectiveInt(instance, "uSubsurfaceEnabled",
                settings.active() ? 1 : 0);
        cmd.setUniformInt(shader, "uSubsurfaceEnabled", enabled);
        if (enabled == 0) return;

        int thicknessSource = effectiveInt(instance, "uSubsurfaceThicknessSource",
                settings.thicknessSource().shaderIndex());
        if (thicknessSource == SubsurfaceScatteringSettings.ThicknessSource.OCCLUSION.shaderIndex()
                && textureBinding(instance, 3) == null) {
            thicknessSource = SubsurfaceScatteringSettings.ThicknessSource.CONSTANT.shaderIndex();
        }
        if (thicknessSource == SubsurfaceScatteringSettings.ThicknessSource.BASE_ALPHA.shaderIndex()
                && textureBinding(instance, 0) == null) {
            thicknessSource = SubsurfaceScatteringSettings.ThicknessSource.CONSTANT.shaderIndex();
        }
        cmd.setUniformFloat(shader, "uSubsurfaceIntensity",
                        effectiveFloat(instance, "uSubsurfaceIntensity", settings.intensity()))
                .setUniformInt(shader, "uSubsurfaceProfile",
                        effectiveInt(instance, "uSubsurfaceProfile",
                                settings.profile().shaderIndex()))
                .setUniformFloat(shader, "uSubsurfaceProfileScale",
                        effectiveFloat(instance, "uSubsurfaceProfileScale",
                                settings.profileScaleMetres()))
                .setUniformInt(shader, "uSubsurfaceThicknessSource", thicknessSource)
                .setUniformFloat(shader, "uSubsurfaceThickness",
                        effectiveFloat(instance, "uSubsurfaceThickness",
                                settings.thicknessMetres()))
                .setUniformFloat(shader, "uSubsurfaceDiffusionRadius",
                        effectiveFloat(instance, "uSubsurfaceDiffusionRadius",
                                settings.diffusionRadiusMetres()))
                .setUniformInt(shader, "uSubsurfaceScreenSpace",
                        effectiveInt(instance, "uSubsurfaceScreenSpace",
                                settings.screenSpaceEnabled() ? 1 : 0))
                .setUniformFloat(shader, "uSubsurfaceMaxDistance",
                        effectiveFloat(instance, "uSubsurfaceMaxDistance",
                                settings.maxDistanceMetres()));

        if (thicknessSource == SubsurfaceScatteringSettings.ThicknessSource.OCCLUSION.shaderIndex()) {
            Material.TextureBinding occlusion = textureBinding(instance, 3);
            cmd.bindTexture(3, occlusion.texture(), occlusion.sampler())
                    .setUniformInt(shader, "uOcclusionMap", 3);
        } else if (thicknessSource
                == SubsurfaceScatteringSettings.ThicknessSource.BASE_ALPHA.shaderIndex()) {
            Material.TextureBinding baseColor = textureBinding(instance, 0);
            cmd.bindTexture(0, baseColor.texture(), baseColor.sampler())
                    .setUniformInt(shader, "uBaseColorMap", 0)
                    .setUniformInt(shader, "uHasVertexColor",
                            effectiveInt(instance, "uHasVertexColor", 0))
                    .setUniformVec4(shader, "uBaseColorFactor",
                            effectiveVec4(instance, "uBaseColorFactor",
                                    new Vector4f(1.0f)));
        }
    }

    private static int effectiveInt(MaterialInstance instance, String name, int fallback) {
        UniformValue value = instance.uniformOverrides().get(UniformKey.int1(name));
        if (value == null) value = instance.material().defaultUniforms().get(UniformKey.int1(name));
        return value instanceof UniformValue.IntVal intValue ? intValue.value() : fallback;
    }

    private static float effectiveFloat(MaterialInstance instance, String name, float fallback) {
        UniformValue value = instance.uniformOverrides().get(UniformKey.float1(name));
        if (value == null) value = instance.material().defaultUniforms().get(UniformKey.float1(name));
        return value instanceof UniformValue.FloatVal floatValue ? floatValue.value() : fallback;
    }

    private static Vector4f effectiveVec4(MaterialInstance instance, String name,
                                          Vector4f fallback) {
        UniformValue value = instance.uniformOverrides().get(UniformKey.vec4(name));
        if (value == null) value = instance.material().defaultUniforms().get(UniformKey.vec4(name));
        return value instanceof UniformValue.Vec4Val vec4Value
                ? new Vector4f(vec4Value.value()) : fallback;
    }

    private static Material.TextureBinding textureBinding(MaterialInstance instance, int unit) {
        Material.TextureBinding override = instance.textureOverrides().get(unit);
        if (override != null) return override;
        for (Material.TextureBinding binding : instance.material().defaultTextures()) {
            if (binding.unit() == unit) return binding;
        }
        return null;
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
