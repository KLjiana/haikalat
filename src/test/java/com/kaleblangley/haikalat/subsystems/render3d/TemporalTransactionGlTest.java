package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;

/**
 * Locks the temporal transaction contract: a failed frame must not publish
 * TAA history, camera state, model snapshots or previous deformation uploads,
 * and deforming bindings without temporal capability must report validity 0.
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class TemporalTransactionGlTest {
    private static final String VERTEX_SOURCE = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            void main() {
                gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
            }
            """;
    private static final String FRAGMENT_SOURCE = """
            #version 330 core
            out vec4 FragColor;
            void main() { FragColor = vec4(0.8, 0.4, 0.2, 1.0); }
            """;

    @Test
    void failedFramePublishesNothingAndReuploadsPreviousDeformation() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();

            ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("temporal-transaction"));
            Material material = Material.builder(shader).build();
            TestTemporalBinding binding = new TestTemporalBinding();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity(),
                    false, binding));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.TAA)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();

                binding.palette(1.0f);
                pipeline.execute(device);
                TaaHistory history = pipeline.activeGenerationForTest()
                        .postProcess.taaHistoryForTest();
                int readColor = history.readColorTexture();
                int readDepth = history.readDepthTexture();
                long cameraToken = pipeline.temporalFrameStateForTest().previous().frameSequence();
                MeshRenderer renderer = scene.rendererAt(0);
                Matrix4f committedModel = new Matrix4f(
                        pipeline.temporalSceneStateForTest().previousModel(renderer));

                binding.palette(2.0f);
                binding.failNextPrepare = true;
                assertThrows(IllegalStateException.class, () -> pipeline.execute(device));

                assertEquals(readColor, history.readColorTexture(),
                        "failed frame must not advance the TAA history read color");
                assertEquals(readDepth, history.readDepthTexture(),
                        "failed frame must not advance the TAA history read depth");
                assertEquals(cameraToken,
                        pipeline.temporalFrameStateForTest().previous().frameSequence(),
                        "failed frame must not advance the camera token");
                assertEquals(committedModel,
                        pipeline.temporalSceneStateForTest().previousModel(renderer),
                        "failed frame must not advance the previous model");
                assertEquals(1, pipeline.temporalSceneStateForTest().snapshotCount());

                binding.palette(3.0f);
                pipeline.execute(device);
                float[] previousOnGpu = binding.readPreviousPalette();
                assertEquals(1.0f, previousOnGpu[0], 1.0e-6f,
                        "retry must upload the last committed previous palette, not stale data");
            } finally {
                pipeline.close();
                binding.close();
                material.close();
                mesh.close();
                shader.close();
            }
        }
    }

    @Test
    void deformingBindingWithoutTemporalCapabilityReportsInvalidSurface() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();

            ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("temporal-validity"));
            Material material = Material.builder(shader).build();
            SceneDrawBinding deforming = new SceneDrawBinding() {
                @Override
                public void record(CommandBuffer commands, ShaderProgram program,
                                   int frameIndex, Pass pass) {
                }

                @Override
                public boolean skinningEnabled() {
                    return true;
                }
            };
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity(),
                    false, deforming));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.TAA)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build());
            int[] validityTexture = {-1};
            pipeline.build();
            pipeline.graph().addPass("TemporalValidityReadback")
                    .createColor("temporalValidityReadback",
                            com.kaleblangley.haikalat.backend.RenderFormat.RGBA8)
                    .noClear()
                    .dependsOn(com.kaleblangley.haikalat.subsystems.postprocess
                            .PostProcessTargets.SCENE_SURFACE_PASS)
                    .execute((resources, commands) -> validityTexture[0] =
                            resources.colorAttachment(com.kaleblangley.haikalat.subsystems
                                    .postprocess.PostProcessTargets.SCENE_VALIDITY));
            try {
                GlRenderDevice device = new GlRenderDevice();
                pipeline.execute(device);
                pipeline.execute(device);
                assertTrue(validityTexture[0] > 0, "surface pass must produce a validity texture");
                int validity = readRedPixel(validityTexture[0], window.width(), window.height());
                assertEquals(0, validity,
                        "deforming binding without previous deformation must report validity 0");
            } finally {
                pipeline.close();
                material.close();
                mesh.close();
                shader.close();
            }
        }
    }

    @Test
    void removedObjectsArePrunedFromTemporalSnapshots() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();

            ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("temporal-churn"));
            Material material = Material.builder(shader).build();
            Camera camera = new Camera(new Vector3f(0, 0, 5));
            Scene first = new Scene(camera);
            for (int index = 0; index < 4; index++) {
                int offset = index;
                first.add(new SceneObject(mesh, material,
                        (model, frame) -> model.identity().translate(offset * 0.4f, 0, 0),
                        false));
            }
            Scene second = new Scene(camera);
            second.add(new SceneObject(mesh, material, (model, frame) -> model.identity(), false));

            RenderPipeline pipeline = new RenderPipeline(window, first, null,
                    RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.TAA)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                pipeline.execute(device);
                assertEquals(4, pipeline.temporalSceneStateForTest().snapshotCount());
                pipeline.replaceScene(second);
                pipeline.execute(device);
                assertEquals(1, pipeline.temporalSceneStateForTest().snapshotCount(),
                        "removed renderers must be pruned on successful commit");
            } finally {
                pipeline.close();
                material.close();
                mesh.close();
                shader.close();
            }
        }
    }

    @Test
    void retirementDoesNotInvalidateBorrowedSharedBinding() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
                 Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("retirement"));
                 TestTemporalBinding binding = new TestTemporalBinding()) {
                Material material = Material.builder(shader).build();
                try {
                    Camera camera = new Camera(new Vector3f(0, 0, 5));
                    Scene both = new Scene(camera);
                    both.add(new SceneObject(mesh, material, (model, frame) -> model.identity(), false, binding));
                    both.add(new SceneObject(mesh, material, (model, frame) -> model.identity(), false, binding));
                    Scene survivor = new Scene(camera).add(both.rendererAt(0));
                    TemporalSceneState state = new TemporalSceneState();
                    SceneFrameBuilder builder = new SceneFrameBuilder();
                    state.beginFrame(builder.build(both, 96, 96, new Matrix4f(), false, false, 0));
                    state.prepareFinalization(); state.commitSuccessfulFrame();
                    binding.failInvalidate = true;
                    state.beginFrame(builder.build(survivor, 96, 96, new Matrix4f(), false, false, 1));
                    state.prepareFinalization(); state.commitSuccessfulFrame();
                    assertEquals(1, state.snapshotCount());
                    assertTrue(state.previousStateValid(survivor.rendererAt(0)));
                    state.beginFrame(builder.build(new Scene(camera), 96, 96, new Matrix4f(), false, false, 2));
                    state.prepareFinalization(); state.commitSuccessfulFrame();
                    assertEquals(0, state.snapshotCount());
                    assertTrue(binding.previousDeformationAvailable(), "borrowed binding belongs to its owner");
                } finally { material.close(); }
            }
        }
    }

    private static int readRedPixel(int texture, int width, int height) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(width / 2, height / 2, 1, 1, GL_RED, GL_UNSIGNED_BYTE, pixel);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        org.lwjgl.opengl.GL30.glDeleteFramebuffers(fbo);
        return Byte.toUnsignedInt(pixel.get(0));
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder()
                .dimensions(96, 96)
                .title("Temporal Transaction GL Test")
                .visible(false)
                .build();
    }

    private static final class TestTemporalBinding implements SceneDrawBinding,
            TemporalDrawBinding, AutoCloseable {
        private static final int PALETTE_BINDING = 7;
        private static final int PREVIOUS_BINDING = PREVIOUS_JOINT_PALETTE_BINDING;

        private final float[] current = new float[16];
        private final float[] previous = new float[16];
        private final float[] pending = new float[16];
        private final GlBuffer currentBuffer;
        private final GlBuffer previousBuffer;
        private final ByteBuffer bytes = BufferUtils.createByteBuffer(16 * Float.BYTES);
        private boolean previousValid;
        private boolean failInvalidate;
        private boolean pendingValid;
        private boolean previousUploaded;
        private boolean failNextPrepare;
        private boolean closed;

        private TestTemporalBinding() {
            currentBuffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                    .allocate(16L * Float.BYTES);
            previousBuffer = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                    .allocate(16L * Float.BYTES);
        }

        private void palette(float value) {
            new Matrix4f().scale(value).get(current);
        }

        @Override
        public void record(CommandBuffer commands, ShaderProgram shader,
                           int frameIndex, Pass pass) {
            upload(commands, currentBuffer, current);
            commands.bindStorageBuffer(PALETTE_BINDING, currentBuffer, 0L, 16L * Float.BYTES);
        }

        @Override
        public TemporalDrawBinding temporalBinding() {
            return this;
        }

        @Override
        public boolean previousDeformationAvailable() {
            return previousValid;
        }

        @Override
        public void prepareTemporalFrame() {
            System.arraycopy(current, 0, pending, 0, 16);
            pendingValid = true;
        }

        @Override
        public void recordPrevious(CommandBuffer commands, ShaderProgram shader) {
            if (!previousValid) {
                throw new IllegalStateException("test binding has no previous palette");
            }
            if (!previousUploaded) {
                upload(commands, previousBuffer, previous);
                previousUploaded = true;
            }
            commands.bindStorageBuffer(PREVIOUS_BINDING, previousBuffer, 0L, 16L * Float.BYTES);
        }

        @Override
        public void prepareTemporalCommit() {
            if (failNextPrepare) {
                failNextPrepare = false;
                throw new IllegalStateException("injected deformation finalize failure");
            }
        }

        @Override
        public void commitTemporalFrame() {
            if (!pendingValid) return;
            System.arraycopy(pending, 0, previous, 0, 16);
            previousValid = true;
            previousUploaded = false;
            pendingValid = false;
        }

        @Override
        public void discardTemporalFrame() {
            pendingValid = false;
            previousUploaded = false;
        }

        @Override
        public void invalidatePrevious() {
            if (failInvalidate) throw new IllegalStateException("retirement must not invoke borrowed callbacks");
            previousValid = false;
            pendingValid = false;
            previousUploaded = false;
        }

        private void upload(CommandBuffer commands, GlBuffer buffer, float[] values) {
            bytes.clear();
            bytes.asFloatBuffer().put(values);
            bytes.limit(values.length * Float.BYTES);
            commands.uploadBufferRegion(buffer, 0L, bytes);
        }

        private float[] readPreviousPalette() {
            FloatBuffer result = BufferUtils.createFloatBuffer(16);
            org.lwjgl.opengl.GL45.glGetNamedBufferSubData(previousBuffer.id(), 0L, result);
            float[] values = new float[16];
            result.get(values);
            return values;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            try {
                previousBuffer.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                currentBuffer.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }
    }
}
