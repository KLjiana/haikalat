package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.VertexDisplacementDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.VertexDisplacementSettings;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrame;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrameBuilder;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDepthFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL11.glFinish;

/**
 * Real GL oracle for the production vertex stages: displaced forward and depth
 * silhouettes must change together, the shadow depth stage must consume the
 * same displacement, previous-frame surface velocity must include the
 * deformation, and disabling the model must restore the exact baseline.
 *
 * <p>Deliberately not executed by this worker.  It is enabled only by the
 * repository's GL smoke property and must be run on a desktop GL 4.6 context.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VertexDisplacementGlTest {
    private static final int SIZE = 128;
    private static final int VERTEX_STRIDE_FLOATS = 16;
    private static final int VERTEX_STRIDE_BYTES = VERTEX_STRIDE_FLOATS * Float.BYTES;

    private static final String FORWARD_VERTEX = "/shaders/render3d/pbr/pbr-forward.vert";
    private static final String GTAO_DEPTH_VERTEX = "/shaders/render3d/gtao/gtao-depth.vert";
    private static final String SHADOW_VERTEX = "/shaders/shadows/directional-depth.vert";
    private static final String SURFACE_VERTEX = "/shaders/render3d/surface/scene-surface.vert";

    @Test
    void displacedForwardDepthShadowAndVelocityRestoreExactly() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(SIZE, SIZE)
                .title("Vertex Displacement GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();

            Mesh mesh = gridMesh();
            ShaderProgram forward = program(FORWARD_VERTEX, """
                    #version 460 core
                    out vec4 FragColor;
                    void main() { FragColor = vec4(1.0, 0.2, 0.05, 1.0); }
                    """);
            ShaderProgram gtaoDepth = program(GTAO_DEPTH_VERTEX, emptyFragment());
            ShaderProgram shadowDepth = program(SHADOW_VERTEX, emptyFragment());
            ShaderProgram surface = program(SURFACE_VERTEX, """
                    #version 460 core
                    in vec4 vCurrentClip;
                    in vec4 vPreviousClip;
                    in float vPreviousViewDepth;
                    in float vPreviousValid;
                    layout(location = 0) out vec4 outSurface;
                    void main() {
                        vec2 currentUv = (vCurrentClip.xy / vCurrentClip.w) * 0.5 + 0.5;
                        vec2 previousUv = (vPreviousClip.xy / vPreviousClip.w) * 0.5 + 0.5;
                        outSurface = vec4(previousUv - currentUv, vPreviousViewDepth,
                                vPreviousValid > 0.5 ? 1.0 : 0.0);
                    }
                    """);
            UniformBlock cameraBlock = new UniformBlock(2 * 16 * Float.BYTES);
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60.0f),
                    1.0f, 0.1f, 100.0f);
            Matrix4f view = new Matrix4f().lookAt(0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f);
            cameraBlock.setMat4(0, projection).setMat4(16 * Float.BYTES, view);
            cameraBlock.bind(0);
            forward.bindUniformBlock("CameraBlock", 0);
            gtaoDepth.bindUniformBlock("CameraBlock", 0);
            surface.bindUniformBlock("CameraBlock", 0);

            ColorDepthTarget forwardTarget = colorTarget(GL_RGBA8, true);
            ColorDepthTarget depthTarget = colorTarget(GL_RGBA8, true);
            ColorDepthTarget surfaceTarget = colorTarget(GL_RGBA32F, true);
            DepthTarget shadowTarget = depthTarget();
            Matrix4f model = new Matrix4f();
            Matrix4f lightSpace = new Matrix4f().ortho(-2.0f, 2.0f, -2.0f, 2.0f, 0.1f, 10.0f)
                    .mul(new Matrix4f().lookAt(0.0f, 0.0f, 0.0f,
                            0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f));
            Matrix4f viewProjection = new Matrix4f(projection).mul(view);
            VertexDisplacementSettings displacement = new VertexDisplacementSettings(true,
                    0.25f, 2.0f, 1.0f, 0.0f,
                    new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f));
            try {
                float[] baselineColor = renderForward(forward, mesh, forwardTarget, model,
                        displacement, false, 0.0f, 0.0f);
                float[] baselineDepth = renderGtao(gtaoDepth, mesh, depthTarget, model,
                        projection, view, displacement, false, 0.0f);
                float[] annotatedShadow = renderShadow(shadowDepth, mesh, shadowTarget, model,
                        lightSpace, displacement, false, 0.0f);
                float[] baselineVelocity = renderSurface(surface, mesh, surfaceTarget, model,
                        projection, view, viewProjection, displacement, false, 0.4f, 0.1f);

                float[] displacedColor = renderForward(forward, mesh, forwardTarget, model,
                        displacement, true, 0.4f, 0.1f);
                float[] displacedDepth = renderGtao(gtaoDepth, mesh, depthTarget, model,
                        projection, view, displacement, true, 0.4f);
                float[] displacedShadow = renderShadow(shadowDepth, mesh, shadowTarget, model,
                        lightSpace, displacement, true, 0.4f);
                float[] displacedVelocity = renderSurface(surface, mesh, surfaceTarget, model,
                        projection, view, viewProjection, displacement, true, 0.4f, 0.1f);

                float[] restoredColor = renderForward(forward, mesh, forwardTarget, model,
                        displacement, false, 0.0f, 0.0f);
                float[] restoredDepth = renderGtao(gtaoDepth, mesh, depthTarget, model,
                        projection, view, displacement, false, 0.0f);
                float[] restoredShadow = renderShadow(shadowDepth, mesh, shadowTarget, model,
                        lightSpace, displacement, false, 0.0f);
                float[] restoredVelocity = renderSurface(surface, mesh, surfaceTarget, model,
                        projection, view, viewProjection, displacement, false, 0.4f, 0.1f);

                boolean[] baselineCoverage = coverage(baselineColor);
                boolean[] displacedCoverage = coverage(displacedColor);
                boolean[] depthCoverage = depthCoverage(displacedDepth);
                assertTrue(count(baselineCoverage) > 100, "grid must be visible");
                assertFalse(Arrays.equals(baselineCoverage, displacedCoverage),
                        "enabled displacement must change the forward silhouette");
                assertArrayEquals(displacedCoverage, depthCoverage,
                        "forward color and shared depth prepass must share the displaced silhouette");

                boolean[] shadowBaseline = depthCoverage(annotatedShadow);
                boolean[] shadowDisplaced = depthCoverage(displacedShadow);
                assertTrue(count(shadowBaseline) > 0, "baseline shadow must cover the grid");
                assertFalse(Arrays.equals(shadowBaseline, shadowDisplaced),
                        "shadow depth must consume the same displacement");
                assertTrue(hasNonZeroVelocity(displacedVelocity),
                        "previous time must produce non-zero surface velocity");
                assertTrue(hasZeroVelocity(baselineVelocity),
                        "disabled deformation must keep surface velocity zero");
                assertFalse(hasZeroVelocity(displacedVelocity),
                        "velocity oracle must distinguish the displaced frame");

                assertArrayEquals(baselineColor, restoredColor, 0.0f,
                        "disabled restore must reproduce the baseline color");
                assertArrayEquals(baselineDepth, restoredDepth, 0.0f,
                        "disabled restore must reproduce the baseline depth");
                assertArrayEquals(annotatedShadow, restoredShadow, 0.0f,
                        "disabled restore must reproduce the baseline shadow depth");
                assertArrayEquals(baselineVelocity, restoredVelocity, 0.0f,
                        "disabled restore must reproduce the baseline velocity");
            } finally {
                shadowTarget.close();
                surfaceTarget.close();
                depthTarget.close();
                forwardTarget.close();
                cameraBlock.close();
                surface.close();
                shadowDepth.close();
                gtaoDepth.close();
                forward.close();
                mesh.close();
            }
        }
    }

    /**
     * Regression for the immutable-scene shortcut: a fixed object whose
     * displacement binding starts disabled must not keep its rest-pose bounds
     * after the binding becomes active.  The cached immutable path is only
     * valid while every binding is still non-deforming and revision-stable.
     */
    @Test
    void immutableFastPathRebuildsBoundsAfterFixedObjectDisplacementTurnsOn() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(SIZE, SIZE)
                .title("Scene Frame Immutable Bounds Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            ShaderProgram shader = ShaderProgram.builder()
                    .stage(ShaderStage.VERTEX, """
                            #version 460 core
                            void main() { gl_Position = vec4(0.0); }
                            """, "scene-frame-bounds.vert")
                    .stage(ShaderStage.FRAGMENT, """
                            #version 460 core
                            void main() {}
                            """, "scene-frame-bounds.frag")
                    .link();
            Mesh mesh = gridMesh();
            try {
                Material material = Material.builder(shader).build();
                VertexDisplacementDrawBinding binding = new VertexDisplacementDrawBinding(
                        VertexDisplacementSettings.disabled());
                Scene scene = new Scene(new Camera());
                scene.add(SceneObject.fixed(mesh, material, new Matrix4f(), true, binding));
                SceneFrameBuilder builder = new SceneFrameBuilder();
                Matrix4f shadowMatrix = new Matrix4f();

                SceneFrame rest = builder.build(scene, SIZE, SIZE, shadowMatrix,
                        false, false, 0);
                assertEquals(1, rest.rendererCount());
                assertFalse(rest.bounds(0).unbounded());
                float restMaxX = rest.bounds(0).maxX();

                binding.update(VertexDisplacementSettings.localSinusoidal(
                        0.25f, 1.0f, 1.0f, 0.0f), 0.0f);
                SceneFrame deformed = builder.build(scene, SIZE, SIZE, shadowMatrix,
                        false, false, 1);
                assertEquals(1, deformed.changedCount(),
                        "an activated fixed binding must invalidate its cached bounds");
                assertTrue(deformed.bounds(0).maxX() > restMaxX + 0.2f,
                        "displacement padding must be included in the rebuilt bounds");
                assertTrue(deformed.bounds(0).minX() < -1.2f,
                        "padding must expand both bounds sides");
            } finally {
                mesh.close();
                shader.close();
            }
        }
    }

    private static float[] renderForward(ShaderProgram program, Mesh mesh, ColorDepthTarget target,
                                         Matrix4f model, VertexDisplacementSettings settings,
                                         boolean enabled, float time, float previousTime) {
        begin(target.framebuffer, true);
        program.use();
        program.setMat4("uModel", model);
        setDisplacement(program, settings, enabled, time, previousTime);
        mesh.draw();
        glFinish();
        return readColor(target.framebuffer);
    }

    private static float[] renderGtao(ShaderProgram program, Mesh mesh, ColorDepthTarget target,
                                      Matrix4f model, Matrix4f projection, Matrix4f view,
                                      VertexDisplacementSettings settings, boolean enabled,
                                      float time) {
        begin(target.framebuffer, true);
        program.use();
        program.setMat4("uModel", model);
        setDisplacement(program, settings, enabled, time, time);
        mesh.draw();
        glFinish();
        return readDepth(target.framebuffer);
    }

    private static float[] renderShadow(ShaderProgram program, Mesh mesh, DepthTarget target,
                                        Matrix4f model, Matrix4f lightSpace,
                                        VertexDisplacementSettings settings, boolean enabled,
                                        float time) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glViewport(0, 0, SIZE, SIZE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        glClear(GL_DEPTH_BUFFER_BIT);
        program.use();
        program.setMat4("uLightSpace", lightSpace);
        program.setMat4("uModel", model);
        setDisplacement(program, settings, enabled, time, time);
        mesh.draw();
        glFinish();
        return readDepth(target.framebuffer);
    }

    private static float[] renderSurface(ShaderProgram program, Mesh mesh,
                                         ColorDepthTarget target, Matrix4f model,
                                         Matrix4f projection, Matrix4f view,
                                         Matrix4f viewProjection,
                                         VertexDisplacementSettings settings, boolean enabled,
                                         float time, float previousTime) {
        begin(target.framebuffer, true);
        program.use();
        program.setMat4("uModel", model)
                .setMat4("uPreviousModel", model)
                .setMat4("uCurrentStableViewProjection", viewProjection)
                .setMat4("uPreviousStableViewProjection", viewProjection)
                .setMat4("uPreviousView", view)
                .setInt("uPreviousValid", 1)
                .setInt("uSkinningEnabled", 0)
                .setInt("uMorphTargetCount", 0)
                .setInt("uMorphVertexCount", 0);
        setDisplacement(program, settings, enabled, time, previousTime);
        mesh.draw();
        glFinish();
        return readColorFloats(target.framebuffer);
    }

    private static void setDisplacement(ShaderProgram program, VertexDisplacementSettings settings,
                                        boolean enabled, float time, float previousTime) {
        boolean active = enabled && settings.active();
        setIntIfPresent(program, "uDisplacementEnabled", active ? 1 : 0);
        setFloatIfPresent(program, "uDisplacementTime", time);
        setFloatIfPresent(program, "uDisplacementPreviousTime", previousTime);
        if (!active) return;
        setFloatIfPresent(program, "uDisplacementAmplitude", settings.amplitude());
        setFloatIfPresent(program, "uDisplacementFrequency", settings.frequency());
        setFloatIfPresent(program, "uDisplacementSpeed", settings.speed());
        setFloatIfPresent(program, "uDisplacementPhase", settings.phase());
        if (program.uniformLocationOrMinusOne("uDisplacementAxis") >= 0) {
            program.setVec3("uDisplacementAxis", settings.axis());
        }
        if (program.uniformLocationOrMinusOne("uDisplacementWaveDirection") >= 0) {
            program.setVec3("uDisplacementWaveDirection", settings.waveDirection());
        }
    }

    private static void setIntIfPresent(ShaderProgram program, String name, int value) {
        if (program.uniformLocationOrMinusOne(name) >= 0) program.setInt(name, value);
    }

    private static void setFloatIfPresent(ShaderProgram program, String name, float value) {
        if (program.uniformLocationOrMinusOne(name) >= 0) program.setFloat(name, value);
    }

    private static void begin(int framebuffer, boolean clearColor) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, SIZE, SIZE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear((clearColor ? GL_COLOR_BUFFER_BIT : 0) | GL_DEPTH_BUFFER_BIT);
    }

    private static boolean[] coverage(float[] rgba) {
        boolean[] covered = new boolean[SIZE * SIZE];
        for (int pixel = 0; pixel < covered.length; pixel++) {
            covered[pixel] = rgba[pixel * 4] > 0.1f || rgba[pixel * 4 + 1] > 0.1f
                    || rgba[pixel * 4 + 2] > 0.1f;
        }
        return covered;
    }

    private static boolean[] depthCoverage(float[] depth) {
        boolean[] covered = new boolean[depth.length];
        for (int pixel = 0; pixel < covered.length; pixel++) {
            covered[pixel] = depth[pixel] < 0.999999f;
        }
        return covered;
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        return count;
    }

    private static boolean hasNonZeroVelocity(float[] surface) {
        for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
            if (surface[pixel * 4 + 3] <= 0.5f) continue;
            if (Math.abs(surface[pixel * 4]) > 1.0e-4f
                    || Math.abs(surface[pixel * 4 + 1]) > 1.0e-4f) return true;
        }
        return false;
    }

    private static boolean hasZeroVelocity(float[] surface) {
        for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
            if (surface[pixel * 4 + 3] <= 0.5f) continue;
            if (Math.abs(surface[pixel * 4]) > 1.0e-6f
                    || Math.abs(surface[pixel * 4 + 1]) > 1.0e-6f) return false;
        }
        return true;
    }

    private static Mesh gridMesh() {
        int columns = 6;
        int rows = 6;
        float[] vertices = new float[columns * rows * VERTEX_STRIDE_FLOATS];
        int offset = 0;
        for (int row = 0; row < rows; row++) {
            float y = -1.0f + 2.0f * row / (rows - 1.0f);
            for (int column = 0; column < columns; column++) {
                float x = -1.0f + 2.0f * column / (columns - 1.0f);
                vertices[offset++] = x;
                vertices[offset++] = y;
                vertices[offset++] = -3.0f;
                vertices[offset++] = column / (columns - 1.0f);
                vertices[offset++] = row / (rows - 1.0f);
                vertices[offset++] = 0.0f;
                vertices[offset++] = 0.0f;
                vertices[offset++] = 1.0f;
                vertices[offset++] = 1.0f;
                vertices[offset++] = 0.0f;
                vertices[offset++] = 0.0f;
                vertices[offset++] = 1.0f;
                vertices[offset++] = 1.0f;
                vertices[offset++] = 1.0f;
                vertices[offset++] = 1.0f;
                vertices[offset++] = 1.0f;
            }
        }
        int[] indices = new int[(columns - 1) * (rows - 1) * 6];
        int index = 0;
        for (int row = 0; row < rows - 1; row++) {
            for (int column = 0; column < columns - 1; column++) {
                int base = row * columns + column;
                indices[index++] = base;
                indices[index++] = base + 1;
                indices[index++] = base + columns;
                indices[index++] = base + 1;
                indices[index++] = base + columns + 1;
                indices[index++] = base + columns;
            }
        }
        return Mesh.builder()
                .vertices(vertices, VERTEX_STRIDE_BYTES,
                        attribute(0, 3, 0, VertexSemantic.POSITION),
                        attribute(1, 2, 12, VertexSemantic.TEXCOORD_0),
                        attribute(2, 3, 20, VertexSemantic.NORMAL),
                        attribute(3, 4, 32, VertexSemantic.TANGENT),
                        attribute(4, 4, 48, VertexSemantic.COLOR_0))
                .indices(indices)
                .build();
    }

    private static VertexAttribute attribute(int location, int size, long offsetBytes,
                                             VertexSemantic semantic) {
        return VertexAttribute.builder().index(location).size(size).type(GL_FLOAT)
                .offsetBytes(offsetBytes).semantic(semantic).build();
    }

    private static ShaderProgram program(String vertexPath, String fragmentSource) {
        return ShaderProgram.builder()
                .resource(VertexDisplacementGlTest.class, ShaderStage.VERTEX, vertexPath)
                .stage(ShaderStage.FRAGMENT, fragmentSource, "displacement-oracle.frag")
                .link();
    }

    private static String emptyFragment() {
        return """
                #version 460 core
                void main() {}
                """;
    }

    private static ColorDepthTarget colorTarget(int colorFormat, boolean withDepth) {
        int color = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, color);
        glTexImage2D(GL_TEXTURE_2D, 0, colorFormat, SIZE, SIZE, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        int depth = 0;
        if (withDepth) {
            depth = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, depth);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SIZE, SIZE, 0,
                    GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        if (depth != 0) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        }
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER),
                "color/depth framebuffer must be complete");
        return new ColorDepthTarget(framebuffer, color, depth);
    }

    private static DepthTarget depthTarget() {
        int depth = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depth);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SIZE, SIZE, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        glDrawBuffer(org.lwjgl.opengl.GL11.GL_NONE);
        glReadBuffer(org.lwjgl.opengl.GL11.GL_NONE);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER),
                "shadow depth framebuffer must be complete");
        return new DepthTarget(framebuffer, depth);
    }

    private static float[] readColor(int framebuffer) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        float[] result = new float[SIZE * SIZE * 4];
        for (int index = 0; index < result.length; index++) {
            result[index] = Byte.toUnsignedInt(pixels.get(index)) / 255.0f;
        }
        return result;
    }

    private static float[] readColorFloats(int framebuffer) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        float[] result = new float[SIZE * SIZE * 4];
        pixels.get(result);
        return result;
    }

    private static float[] readDepth(int framebuffer) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE);
        glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, pixels);
        float[] result = new float[SIZE * SIZE];
        pixels.get(result);
        return result;
    }

    private record ColorDepthTarget(int framebuffer, int color, int depth) implements AutoCloseable {
        @Override
        public void close() {
            glDeleteFramebuffers(framebuffer);
            if (depth != 0) glDeleteTextures(depth);
            glDeleteTextures(color);
        }
    }

    private record DepthTarget(int framebuffer, int depth) implements AutoCloseable {
        @Override
        public void close() {
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(depth);
        }
    }
}
