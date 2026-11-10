package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.presentation.PresentationResult;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;

/** GL acceptance for the unified feature control transaction and pause semantics. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class RenderFeatureControlGlTest {
    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            void main() { gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0); }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            uniform int uEnableDirect;
            uniform int uEnableDiffuseIbl;
            out vec4 FragColor;
            void main() { FragColor = vec4(uEnableDirect, uEnableDiffuseIbl, 0.0, 1.0); }
            """;

    @Test
    void masterPauseSkipsOutputAndResumesWithoutLosingRequestedChildren() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-pause"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            try {
                pipeline.build();
                pipeline.previewController().panelVisible(true);
                pipeline.previewController().detailedDiagnostics(true);
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = externalCamera();
                PresentationTarget target = PresentationTarget.defaultFramebuffer(
                        window.width(), window.height());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                ByteBuffer before = readCenter();
                RenderFeatureSettings effective = pipeline.features().effective();
                assertTrue(effective.enabled());
                long revision = pipeline.features().submit(
                        effective.toBuilder().enabled(false).build());
                assertTrue(revision > pipeline.features().effectiveRevision());

                assertEquals(PresentationResult.SKIPPED_PAUSED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertFalse(pipeline.features().effective().enabled());
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                assertTrue(pipeline.features().effective().directionalShadows(),
                        "pausing must preserve the child configuration");
                assertArrayEquals(toBytes(before), toBytes(readCenter()),
                        "a paused frame must not touch the host target");
                var graph = pipeline.graph();
                assertEquals(PresentationResult.SKIPPED_PAUSED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertSame(graph, pipeline.graph(), "pause must not rebuild the graph");

                pipeline.features().submit(effective.toBuilder().enabled(true).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertTrue(pipeline.features().effective().enabled());
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                GlDebug.checkError("feature master pause");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void batchRequestSwitchesShadowFamilyAndKeepsOldGenerationOnFailure() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-batch"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-1, -1, -1),
                    new Vector3f(1), 0.5f));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            pipeline.localShadows(LocalShadowPipelineSettings.balanced());
            try {
                pipeline.build();
                pipeline.previewController().panelVisible(true);
                pipeline.previewController().detailedDiagnostics(true);
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = externalCamera();
                PresentationTarget target = PresentationTarget.defaultFramebuffer(
                        window.width(), window.height());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertTrue(pipeline.graph().hasPass(DirectionalShadowMap.PASS_NAME));
                RenderFeatureSettings effective = pipeline.features().effective();

                pipeline.features().submit(effective.toBuilder().directionalShadows(false).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertFalse(pipeline.features().effective().directionalShadows());
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                assertFalse(pipeline.graph().hasPass(DirectionalShadowMap.PASS_NAME),
                        "disabled directional shadows must remove their pass");

                var withoutShadows = pipeline.graph();
                RenderFeatureSettings invalid = pipeline.features().effective().toBuilder()
                        .volumetricFog(new VolumetricFogSettings(true, 80,
                                VolumetricFogSettings.Quality.BALANCED,
                                FogMediumSettings.vacuum(), java.util.List.of(), 0, true, 0.9f,
                                1, new Vector3f()))
                        .postProcess(PostProcessSettings.builder()
                                .fog(FogSettings.builder().enabled(true).build()).build())
                        .build();
                pipeline.features().submit(invalid);
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f),
                        "a rejected batch must leave the previous generation rendering");
                assertEquals(RenderFeatureStatus.FAILED, pipeline.features().status());
                assertTrue(pipeline.features().failureReason().contains("volumetric fog"));
                assertFalse(pipeline.features().effective().directionalShadows());
                assertSame(withoutShadows, pipeline.graph(),
                        "failed candidates must not replace the active graph");

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .directionalShadows(true).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertTrue(pipeline.features().effective().directionalShadows());
                assertTrue(pipeline.graph().hasPass(DirectionalShadowMap.PASS_NAME));
                GlDebug.checkError("feature batch and failure rollback");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void batchRequestSwitchesPostProcessEffectsAndKeepsSharedSurfaceConsumers() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-post"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-1, -1, -1),
                    new Vector3f(1), 0.5f));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = externalCamera();
                PresentationTarget target = PresentationTarget.defaultFramebuffer(
                        window.width(), window.height());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertFalse(pipeline.graph().hasPass(
                        com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.TAA_PASS));

                RenderFeatureSettings on = pipeline.features().effective().toBuilder()
                        .render(RenderSettings.builder()
                                .antiAliasingMode(AntiAliasingMode.TAA)
                                .toneMappingMode(ToneMappingMode.ACES)
                                .bloomSettings(com.kaleblangley.haikalat.runtime.BloomSettings
                                        .builder().enabled(true).build())
                                .vsync(false).build())
                        .postProcess(PostProcessSettings.builder()
                                .fog(FogSettings.builder().enabled(true).build()).build())
                        .build();
                pipeline.features().submit(on);
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                assertTrue(pipeline.graph().hasPass(
                        com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.TAA_PASS));
                assertTrue(pipeline.graph().hasPass(
                        com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.BLOOM_EXTRACT_PASS));
                assertTrue(pipeline.graph().hasPass(
                        com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.FOG_PASS));
                assertTrue(pipeline.graph().hasPass(DirectionalShadowMap.PASS_NAME),
                        "an unrelated shadow owner must survive a post-process switch");
                assertTrue(pipeline.features().activities().stream().anyMatch(a ->
                        a.feature() == RenderFeature.ANTI_ALIASING
                                && a.reason() == RenderFeatureReason.ACTIVE));
                assertTrue(pipeline.features().activities().stream().anyMatch(a ->
                        a.feature() == RenderFeature.ANALYTIC_FOG
                                && a.reason() == RenderFeatureReason.ACTIVE));

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .render(RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                                .toneMappingMode(ToneMappingMode.ACES).vsync(false).build())
                        .postProcess(PostProcessSettings.builder()
                                .fog(FogSettings.builder().enabled(false).build()).build())
                        .build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertFalse(pipeline.graph().hasPass(
                        com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.TAA_PASS));
                assertFalse(pipeline.graph().hasPass(
                        com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.BLOOM_EXTRACT_PASS));
                assertFalse(pipeline.graph().hasPass(
                        com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.FOG_PASS));
                assertTrue(pipeline.graph().hasPass(DirectionalShadowMap.PASS_NAME));
                GlDebug.checkError("feature post-process switches");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void hdrVfxGateSkipsTheCallerRecorderWithoutOwningIt() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-vfx"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            java.util.concurrent.atomic.AtomicInteger calls =
                    new java.util.concurrent.atomic.AtomicInteger();
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            pipeline.hdrVfx((resources, commands) -> calls.incrementAndGet());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = externalCamera();
                PresentationTarget target = PresentationTarget.defaultFramebuffer(
                        window.width(), window.height());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                int afterBuild = calls.get();
                assertTrue(afterBuild > 0, "the configured recorder must run by default");

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .hdrVfx(false).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertEquals(afterBuild, calls.get(),
                        "disabled HDR VFX must not invoke the caller recorder");
                assertTrue(pipeline.features().activities().stream().anyMatch(a ->
                        a.feature() == RenderFeature.HDR_VFX && !a.active()));

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .hdrVfx(true).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertTrue(calls.get() > afterBuild);
                GlDebug.checkError("feature HDR VFX gate");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void directLightingGateReachesTheShaderWithoutOwningSceneLights() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-direct"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            scene.addLight(SceneLight.directional(new Vector3f(-1, -1, -1),
                    new Vector3f(1), 0.5f));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = externalCamera();
                PresentationTarget target = PresentationTarget.defaultFramebuffer(
                        window.width(), window.height());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertTrue(Byte.toUnsignedInt(readCenter().get(0)) > 40,
                        "direct lighting is enabled by default");

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .directLighting(false).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertEquals(0, Byte.toUnsignedInt(readCenter().get(0)),
                        "the frame gate must clear uEnableDirect in the bound shader");
                assertFalse(pipeline.features().effective().directLighting());

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .directLighting(true).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertTrue(Byte.toUnsignedInt(readCenter().get(0)) > 40);
                GlDebug.checkError("feature direct lighting gate");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void embeddedPauseLeavesTheHostTargetAndGlBindingUntouched() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (OwnedPresentationTarget owner =
                         OwnedPresentationTarget.create(64, 64, RenderFormat.RGBA8, true, 1)) {
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-embedded"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            PresentationTarget target = owner.target();
            RenderPipeline pipeline = new RenderPipeline(target, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = ExternalCamera.of(
                        new Matrix4f().lookAt(0, 0, 3, 0, 0, 0, 0, 1, 0),
                        new Matrix4f().perspective((float) Math.toRadians(45), 1.0f, 0.1f, 100.0f),
                        new Vector3f(0, 0, 3), 0.5f);
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, target.readFramebufferId());
                ByteBuffer before = readCenter();
                RenderGraph graph = pipeline.graph();
                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .enabled(false).build());

                assertEquals(PresentationResult.SKIPPED_PAUSED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertSame(graph, pipeline.graph(), "pause must keep the active generation");
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,
                        target.readFramebufferId());
                assertArrayEquals(toBytes(before), toBytes(readCenter()));
                assertTrue(target.color().isPresent()
                                && glIsTexture(target.color().orElseThrow().textureId()),
                        "host-owned colour target must stay open");

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .enabled(true).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                GlDebug.checkError("feature embedded pause");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
            }
        }
    }

    @Test
    void retirementFailureAfterPublicationKeepsGraphAndEffectiveConfigurationAligned() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-retirement"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = externalCamera();
                PresentationTarget target = PresentationTarget.defaultFramebuffer(
                        window.width(), window.height());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                RenderFeatureSettings volume = pipeline.features().effective().toBuilder()
                        .volumetricFog(new VolumetricFogSettings(true, 80,
                                VolumetricFogSettings.Quality.BALANCED,
                                FogMediumSettings.vacuum(), java.util.List.of(), 0, true, 0.9f,
                                1, new Vector3f()))
                        .build();
                System.setProperty("haikalat.test.failVolumeRetirement", "true");
                try {
                    pipeline.features().submit(volume);
                    assertEquals(PresentationResult.RENDERED,
                            pipeline.render(device, camera, target, 1.0f / 60.0f),
                            "retirement failure after publication must not abort the frame");
                } finally {
                    System.clearProperty("haikalat.test.failVolumeRetirement");
                }

                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                        "the published generation is the requested one; state must not roll back");
                assertTrue(pipeline.features().effective().volumetricFog().enabled());
                assertTrue(pipeline.graph().hasPass(VolumetricPassBuilder.MEDIUM_PASS),
                        "the active graph and effective configuration must describe the same generation");
                assertTrue(pipeline.features().failureReason().isBlank());

                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertTrue(pipeline.graph().hasPass(VolumetricPassBuilder.MEDIUM_PASS));
                GlDebug.checkError("feature retirement failure consistency");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void previewGateStopsRendererWithoutTouchingProductionAttachments() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-preview"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = externalCamera();
                PresentationTarget target = PresentationTarget.defaultFramebuffer(
                        window.width(), window.height());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertDoesNotThrow(pipeline::previewOverlayRecorder,
                        "preview is available by default");

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .preview(false).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertFalse(pipeline.features().effective().preview());
                assertThrows(IllegalStateException.class, pipeline::previewOverlayRecorder,
                        "preview off must not expose an overlay recorder");
                assertTrue(pipeline.graph().hasPass(
                                com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.PRESENT_PASS),
                        "production presentation stays intact when preview is off");

                pipeline.features().submit(pipeline.features().effective().toBuilder()
                        .preview(true).build());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertDoesNotThrow(pipeline::previewOverlayRecorder);
                GlDebug.checkError("feature preview gate");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    @Test
    void revisionsExposePendingLatestWinsAndClearFailuresOnANewRequest() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("feature-revisions"));
            ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            Material material = Material.builder(shader).build();
            Scene scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.add(new SceneObject(mesh, material, (model, frame) -> model.identity()));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES).vsync(false).build());
            try {
                pipeline.build();
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = externalCamera();
                PresentationTarget target = PresentationTarget.defaultFramebuffer(
                        window.width(), window.height());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                long applied = pipeline.features().effectiveRevision();
                RenderFeatureSettings first = pipeline.features().effective().toBuilder()
                        .directLighting(false).build();
                pipeline.features().submit(first);
                assertEquals(RenderFeatureStatus.PENDING, pipeline.features().status());
                assertTrue(pipeline.features().requestedRevision() > applied);
                assertTrue(pipeline.features().effective().directLighting(),
                        "pending request must not advance effective state");

                RenderFeatureSettings latest = pipeline.features().effective().toBuilder()
                        .directionalShadows(false).build();
                long latestRevision = pipeline.features().submit(latest);
                assertEquals(RenderFeatureStatus.PENDING, pipeline.features().status());
                assertEquals(latestRevision, pipeline.features().requestedRevision());
                assertEquals(latest, pipeline.features().requested());
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                assertEquals(latestRevision, pipeline.features().effectiveRevision());
                assertTrue(pipeline.features().effective().directLighting(),
                        "latest request wins; the superseded direct-lighting patch is not merged");
                assertFalse(pipeline.features().effective().directionalShadows());

                RenderFeatureSettings invalid = latest.toBuilder()
                        .volumetricFog(new VolumetricFogSettings(true, 80,
                                VolumetricFogSettings.Quality.BALANCED,
                                FogMediumSettings.vacuum(), java.util.List.of(), 0, true, 0.9f,
                                1, new Vector3f()))
                        .postProcess(PostProcessSettings.builder()
                                .fog(FogSettings.builder().enabled(true).build()).build())
                        .build();
                pipeline.features().submit(invalid);
                assertEquals(PresentationResult.RENDERED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertEquals(RenderFeatureStatus.FAILED, pipeline.features().status());
                assertFalse(pipeline.features().failureReason().isBlank());
                assertEquals(latestRevision, pipeline.features().effectiveRevision(),
                        "failure must not advance effective revision");
                assertEquals(latest, pipeline.features().effective());

                long recoveredRevision = pipeline.features().submit(latest.toBuilder()
                        .enabled(false).build());
                assertEquals(PresentationResult.SKIPPED_PAUSED,
                        pipeline.render(device, camera, target, 1.0f / 60.0f));
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                assertTrue(pipeline.features().failureReason().isBlank(),
                        "a successful new request clears the previous failure");
                assertEquals(recoveredRevision, pipeline.features().effectiveRevision());
                GlDebug.checkError("feature revisions");
            } finally {
                pipeline.close();
                material.close();
                shader.close();
                mesh.close();
            }
        }
    }

    private static ExternalCamera externalCamera() {
        return ExternalCamera.of(
                new Matrix4f().lookAt(0, 0, 3, 0, 0, 0, 0, 1, 0),
                new Matrix4f().perspective((float) Math.toRadians(45), 1.0f, 0.1f, 100.0f),
                new Vector3f(0, 0, 3), 0.5f);
    }

    private static ByteBuffer readCenter() {
        ByteBuffer pixel = org.lwjgl.BufferUtils.createByteBuffer(4);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        glReadPixels(viewport[0] + viewport[2] / 2, viewport[1] + viewport[3] / 2,
                1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return pixel;
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        byte[] result = new byte[4];
        buffer.get(0, result);
        return result;
    }
}
