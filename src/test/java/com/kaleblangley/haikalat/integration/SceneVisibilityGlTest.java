package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;

/** SceneFrame、相机/阴影视锥和失败恢复的真实 GL 合同。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SceneVisibilityGlTest {
    private static final String VERTEX = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock { mat4 uProjection; mat4 uView; };
            uniform mat4 uModel;
            void main() { gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0); }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            out vec4 FragColor;
            void main() { FragColor = vec4(0.8, 0.3, 0.1, 1.0); }
            """;

    @Test
    void sceneObjectsUsingOneMaterialReceiveIndependentMutableInstances() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("material-isolation"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            try {
                Scene scene = new Scene(new Camera());
                scene.add(SceneObject.fixed(mesh, material, new org.joml.Matrix4f(), false));
                scene.add(SceneObject.fixed(mesh, material,
                        new org.joml.Matrix4f().translation(1.0f, 0.0f, 0.0f), false));

                var first = scene.renderers().get(0).material();
                var second = scene.renderers().get(1).material();
                assertNotSame(first, second);
                assertSame(material, first.material());
                assertSame(material, second.material());

                first.setFloat("uObjectValue", 0.25f);

                assertEquals(1, first.uniformOverrides().size());
                assertTrue(second.uniformOverrides().isEmpty(),
                        "一个 SceneObject 的 override 不得串到共享 Material 的另一个对象");
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void cameraAndShadowQueuesAreIndependentAndDisabledUsesSameSingleUpdatePath() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("visibility"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            AtomicInteger visibleUpdates = new AtomicInteger();
            AtomicInteger shadowOnlyUpdates = new AtomicInteger();
            AtomicInteger hiddenUpdates = new AtomicInteger();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)))
                    .addLight(SceneLight.shadowedDirectional(new Vector3f(-1, -2, -1),
                            new Vector3f(1), 1.0f));
            scene.add(new SceneObject(mesh, material, (model, frame) -> {
                visibleUpdates.incrementAndGet();
                model.identity();
            }));
            scene.add(new SceneObject(mesh, material, (model, frame) -> {
                shadowOnlyUpdates.incrementAndGet();
                // 位于相机背后，但仍处在以相机为中心的方向光阴影视锥内。
                model.identity().translate(0, 0, 10);
            }));
            scene.add(new SceneObject(mesh, material, (model, frame) -> {
                hiddenUpdates.incrementAndGet();
                model.identity().translate(100, 0, 0);
            }));
            RenderPipeline enabled = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false).sceneVisibility(true).build());
            RenderPipeline disabled = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false).sceneVisibility(false).build());
            try {
                enabled.build();
                enabled.execute(new GlRenderDevice());
                RenderPipeline.VisibilityStatistics visible = enabled.lastVisibilityStatistics();
                assertAll(
                        () -> assertTrue(visible.available()),
                        () -> assertEquals(3, visible.candidateRenderers()),
                        () -> assertEquals(1, visible.forwardVisible(), visible.toString()),
                        () -> assertEquals(2, visible.forwardCulled(), visible.toString()),
                        () -> assertEquals(3, visible.shadowCandidates()),
                        () -> assertEquals(2, visible.shadowVisible()),
                        () -> assertEquals(1, visible.shadowCulled()),
                        () -> assertEquals(1, visibleUpdates.get()),
                        () -> assertEquals(1, shadowOnlyUpdates.get()),
                        () -> assertEquals(1, hiddenUpdates.get()));
                ByteBuffer enabledPixel = centerPixel();

                disabled.build();
                disabled.execute(new GlRenderDevice());
                RenderPipeline.VisibilityStatistics all = disabled.lastVisibilityStatistics();
                assertAll(
                        () -> assertFalse(all.cullingEnabled()),
                        () -> assertEquals(3, all.forwardVisible()),
                        () -> assertEquals(0, all.forwardCulled()),
                        () -> assertEquals(3, all.shadowVisible()),
                        () -> assertEquals(2, visibleUpdates.get()),
                        () -> assertEquals(2, shadowOnlyUpdates.get()),
                        () -> assertEquals(2, hiddenUpdates.get()));
                ByteBuffer disabledPixel = centerPixel();
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(Byte.toUnsignedInt(enabledPixel.get(channel)),
                            Byte.toUnsignedInt(disabledPixel.get(channel)), 1);
                }

                long revision = scene.membershipRevision();
                scene.add(new SceneObject(mesh, material,
                        (model, frame) -> model.identity().translate(-100, 0, 0), false));
                assertTrue(scene.membershipRevision() > revision);
                enabled.execute(new GlRenderDevice());
                assertEquals(4, enabled.lastVisibilityStatistics().candidateRenderers());
                assertEquals(1, enabled.lastVisibilityStatistics().forwardVisible());
                GlDebug.assertNoError("SceneVisibilityGlTest.queues");
            } finally {
                disabled.close();
                enabled.close();
                material.close();
                shader.close();
                Bounds3f retained = mesh.localBounds();
                mesh.close();
                mesh.close();
                assertEquals(BuiltinMeshData.coloredTriangle("expected").localBounds(), retained);
                assertEquals(retained, mesh.localBounds());
            }
        }
    }

    @Test
    void sceneFrameBuildFailurePublishesCurrentGraphFailureAndRetriesCleanly() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("visibility-retry"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            AtomicBoolean fail = new AtomicBoolean(true);
            AtomicInteger updates = new AtomicInteger();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> {
                updates.incrementAndGet();
                if (fail.getAndSet(false)) throw new IllegalStateException("injected updater failure");
                model.identity();
            }));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false).build());
            try {
                pipeline.build();
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> pipeline.execute(new GlRenderDevice()));
                assertTrue(failure.getMessage().contains("renderer[0]"));
                assertEquals(0, pipeline.graph().lastFrameProfile().frameSequence());
                assertFalse(pipeline.lastVisibilityStatistics().available());

                pipeline.execute(new GlRenderDevice());
                assertEquals(1, pipeline.graph().lastFrameProfile().frameSequence());
                assertTrue(pipeline.lastVisibilityStatistics().available());
                assertEquals(1, pipeline.lastVisibilityStatistics().forwardVisible());
                assertEquals(2, updates.get());
                GlDebug.assertNoError("SceneVisibilityGlTest.retry");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void staticModelBoundsAndQueuesReuseThenInvalidateOnTransformMutation() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("visibility-cache"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Transform transform = Transform.identity();
            AtomicInteger dynamicUpdates = new AtomicInteger();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(MeshRenderer.of(mesh, material, transform).withoutShadows());
            scene.add(MeshRenderer.animated(mesh, material, (model, frame) -> {
                dynamicUpdates.incrementAndGet();
                model.identity().translate(100.0f, 0.0f, 0.0f);
            }).withoutShadows());
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false).build());
            GlRenderDevice device = new GlRenderDevice();
            try {
                pipeline.build();
                pipeline.execute(device);
                RenderPipeline.VisibilityStatistics first = pipeline.lastVisibilityStatistics();
                assertAll(
                        () -> assertEquals(1, first.staticRenderers()),
                        () -> assertEquals(1, first.dynamicRenderers()),
                        () -> assertEquals(0, first.modelCacheHits()),
                        () -> assertEquals(2, first.modelCacheMisses()),
                        () -> assertEquals(0, first.boundsCacheHits()),
                        () -> assertEquals(2, first.boundsCacheMisses()),
                        () -> assertTrue(first.forwardQueueRebuilt()),
                        () -> assertEquals(1, dynamicUpdates.get()));

                pipeline.execute(device);
                RenderPipeline.VisibilityStatistics stable = pipeline.lastVisibilityStatistics();
                assertAll(
                        () -> assertEquals(1, stable.modelCacheHits()),
                        () -> assertEquals(1, stable.modelCacheMisses()),
                        () -> assertEquals(1, stable.boundsCacheHits()),
                        () -> assertEquals(1, stable.boundsCacheMisses()),
                        // 动态 renderer 每帧都会更新 bounds，因此整个 queue 仍须重建。
                        () -> assertTrue(stable.forwardQueueRebuilt()),
                        () -> assertEquals(2, dynamicUpdates.get()));

                transform.position(100.0f, 0.0f, 0.0f);
                pipeline.execute(device);
                RenderPipeline.VisibilityStatistics mutated = pipeline.lastVisibilityStatistics();
                assertAll(
                        () -> assertEquals(0, mutated.modelCacheHits()),
                        () -> assertEquals(2, mutated.modelCacheMisses()),
                        () -> assertEquals(0, mutated.boundsCacheHits()),
                        () -> assertEquals(2, mutated.boundsCacheMisses()),
                        () -> assertEquals(0, mutated.forwardVisible()),
                        () -> assertEquals(3, dynamicUpdates.get()));
                GlDebug.assertNoError("SceneVisibilityGlTest.static-cache");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void unchangedFixedSceneReusesBothQueues() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("visibility-fixed"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(SceneObject.fixed(mesh, material, new org.joml.Matrix4f(), false));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false).build());
            GlRenderDevice device = new GlRenderDevice();
            try {
                pipeline.build();
                pipeline.execute(device);
                assertTrue(pipeline.lastVisibilityStatistics().forwardQueueRebuilt());
                assertTrue(pipeline.lastVisibilityStatistics().shadowQueueRebuilt());

                pipeline.execute(device);
                RenderPipeline.VisibilityStatistics stable = pipeline.lastVisibilityStatistics();
                assertAll(
                        () -> assertEquals(1, stable.modelCacheHits()),
                        () -> assertEquals(1, stable.boundsCacheHits()),
                        () -> assertTrue(stable.forwardQueueReused()),
                        () -> assertTrue(stable.shadowQueueReused()),
                        () -> assertEquals(1, stable.forwardVisible()));
                GlDebug.assertNoError("SceneVisibilityGlTest.queue-reuse");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void primitiveAndLegacyMatrixSnapshotsProduceTheSameFinalPixel() {
        String previous = System.getProperty("haikalat.command.matrixArena");
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("matrix-arena-parity"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(SceneObject.fixed(mesh, material, new org.joml.Matrix4f(), false));
            try {
                System.setProperty("haikalat.command.matrixArena", "false");
                ByteBuffer legacy = executeAndRead(window, scene);
                System.setProperty("haikalat.command.matrixArena", "true");
                ByteBuffer primitive = executeAndRead(window, scene);

                assertTrue(Byte.toUnsignedInt(primitive.get(0))
                        + Byte.toUnsignedInt(primitive.get(1))
                        + Byte.toUnsignedInt(primitive.get(2)) > 0);
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(Byte.toUnsignedInt(legacy.get(channel)),
                            Byte.toUnsignedInt(primitive.get(channel)), 1);
                }
                GlDebug.assertNoError("SceneVisibilityGlTest.matrix-arena-parity");
            } finally {
                material.close();
                shader.close();
                mesh.close();
            }
        } finally {
            if (previous == null) System.clearProperty("haikalat.command.matrixArena");
            else System.setProperty("haikalat.command.matrixArena", previous);
        }
    }

    @Test
    void lightAndCameraChangesInvalidateTheRequiredQueues() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("queue-key-mutation"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Camera camera = new Camera(new Vector3f(0, 0, 5));
            Scene scene = new Scene(camera).addLight(SceneLight.shadowedDirectional(
                    new Vector3f(-1, -2, -1), new Vector3f(1), 1.0f));
            scene.add(SceneObject.fixed(mesh, material, new org.joml.Matrix4f(), true));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false).build());
            GlRenderDevice device = new GlRenderDevice();
            try {
                pipeline.build();
                pipeline.execute(device);
                pipeline.execute(device);
                assertTrue(pipeline.lastVisibilityStatistics().forwardQueueReused());
                assertTrue(pipeline.lastVisibilityStatistics().shadowQueueReused());

                scene.setLight(0, SceneLight.shadowedDirectional(
                        new Vector3f(1, -2, -1), new Vector3f(1), 1.0f));
                pipeline.execute(device);
                assertTrue(pipeline.lastVisibilityStatistics().forwardQueueReused());
                assertTrue(pipeline.lastVisibilityStatistics().shadowQueueRebuilt());

                camera.setYaw(-82.0f);
                pipeline.execute(device);
                assertTrue(pipeline.lastVisibilityStatistics().forwardQueueRebuilt());
                GlDebug.assertNoError("SceneVisibilityGlTest.queue-key-mutation");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void taaJitterDoesNotInvalidateStableVisibilityForThirtyTwoFrames() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("taa-stable-visibility"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(SceneObject.fixed(mesh, material,
                    new org.joml.Matrix4f().translate(1.95f, 0.0f, 0.0f), false));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false)
                            .antiAliasingMode(AntiAliasingMode.TAA).build());
            GlRenderDevice device = new GlRenderDevice();
            try {
                pipeline.build();
                for (int frame = 0; frame < 32; frame++) {
                    pipeline.execute(device);
                    RenderPipeline.VisibilityStatistics statistics =
                            pipeline.lastVisibilityStatistics();
                    assertEquals(1, statistics.forwardVisible(), "frame=" + frame);
                    if (frame > 0) assertTrue(statistics.forwardQueueReused(), "frame=" + frame);
                }
                GlDebug.assertNoError("SceneVisibilityGlTest.taa-stable-visibility");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void resizeRebuildsCameraClassificationWithoutChangingFixedShadowExtent() {
        try (GlfwWindow context = hiddenWindow()) {
            context.bindContext();
            GL.createCapabilities();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("visibility-resize"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            MutableWindow viewport = new MutableWindow(640, 640);
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)))
                    .addLight(SceneLight.shadowedDirectional(new Vector3f(-1, -2, -1),
                            new Vector3f(1), 1.0f));
            scene.add(SceneObject.fixed(mesh, material,
                    new org.joml.Matrix4f().translate(3, 0, 0)));
            RenderPipeline pipeline = new RenderPipeline(viewport, scene, null,
                    RenderSettings.builder().vsync(false).build());
            try {
                pipeline.build();
                pipeline.execute(new GlRenderDevice());
                assertEquals(0, pipeline.lastVisibilityStatistics().forwardVisible());
                assertTrue(pipeline.lastVisibilityStatistics().forwardQueueRebuilt());
                assertShadowExtent(pipeline, 2048, 2048);

                viewport.resize(960, 540);
                pipeline.resize(960, 540);
                pipeline.execute(new GlRenderDevice());
                assertEquals(1, pipeline.lastVisibilityStatistics().forwardVisible());
                assertTrue(pipeline.lastVisibilityStatistics().forwardQueueRebuilt());
                assertShadowExtent(pipeline, 2048, 2048);
                assertEquals(960, pipeline.graph().description().width());
                assertEquals(540, pipeline.graph().description().height());
                GlDebug.assertNoError("SceneVisibilityGlTest.resize");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    private static void assertShadowExtent(RenderPipeline pipeline, int width, int height) {
        var shadow = pipeline.graph().description().passes().stream()
                .filter(pass -> pass.name().equals("DirectionalShadowPass"))
                .findFirst().orElseThrow();
        assertEquals(width, shadow.width());
        assertEquals(height, shadow.height());
    }

    private static final class MutableWindow implements RenderWindow {
        private int width;
        private int height;

        private MutableWindow(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        private void resize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static ByteBuffer centerPixel() {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return pixel;
    }

    private static ByteBuffer executeAndRead(RenderWindow window, Scene scene) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                RenderSettings.builder().vsync(false).build());
        try {
            pipeline.build();
            pipeline.execute(new GlRenderDevice());
            return centerPixel();
        } finally {
            pipeline.close();
        }
    }
}
