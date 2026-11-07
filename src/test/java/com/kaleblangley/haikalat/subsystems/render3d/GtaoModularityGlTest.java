package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Production GTAO ownership, dependency and real-pixel acceptance. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GtaoModularityGlTest {
    @Test void stableTaaJitterDoesNotAdvanceGtaoPhaseAndCutsHaveExplicitRecovery() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.TAA, GtaoSettings.defaults().withEnabled(true));
                p.build(); var device = new GlRenderDevice(); var ao = p.activeGenerationForTest().gtao;
                for (int frame = 0; frame < 5; frame++) { p.execute(device); assertEquals(0, ao.samplePhase()); }
                fixture.camera.setPosition(new Vector3f(2.01f, 1.4f, 4)); p.execute(device);
                assertTrue(ao.samplePhase() > 0);
                assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_MOTION_PHASE"));
                p.execute(device); assertEquals(0, ao.samplePhase());
                fixture.camera.setPosition(new Vector3f(5, 3, 10)); p.execute(device);
                assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_CAMERA_CUT"));
                p.resetTemporalHistory(); assertFalse(ao.historyValid()); p.execute(device);
                assertEquals(0, ao.samplePhase());
                assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_EXPLICIT_RESET"));
                assertTrue(ao.historyValid());
            }
        }
    }

    @Test void radiusThicknessAndTemporalPolicyUpdateOnlyTheirDocumentedContent() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking(); var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                p.build(); var device = new GlRenderDevice(); p.execute(device); var owner = p.activeGenerationForTest();
                long created = GlDebug.resources().createdCount(); var history = owner.gtao.historyForTest();
                float[] initial = p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL);
                p.applyGtao(new GtaoSettings(true, p.gtao().quality(), 2, 1, .25f, true, .9f, .02f)); p.execute(device);
                float[] radius = p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL);
                assertFalse(java.util.Arrays.equals(initial, radius));
                p.applyGtao(new GtaoSettings(true, p.gtao().quality(), 2, 1, .01f, true, .9f, .02f)); p.execute(device);
                assertFalse(java.util.Arrays.equals(radius, p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL)));
                p.applyGtao(new GtaoSettings(true, p.gtao().quality(), 2, 1, .01f, true, .4f, .02f));
                assertTrue(history.valid(), "blend weight does not reinterpret the existing AO samples"); p.execute(device);
                p.applyGtao(new GtaoSettings(true, p.gtao().quality(), 2, 1, .01f, true, .4f, .04f));
                assertFalse(history.valid()); p.execute(device);
                assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_REJECTION_POLICY"));
                assertSame(owner, p.activeGenerationForTest()); assertEquals(created, GlDebug.resources().createdCount());
            }
        }
    }

    @Test void dedicatedMaskedVertexAlphaDoesNotCreateInvisibleSurfaceOccluders() throws Exception {
        System.setProperty("haikalat.test.gtaoDedicatedDepth", "true");
        try { maskedVertexAlphaDoesNotCreateInvisibleSurfaceOccluders(); }
        finally { System.clearProperty("haikalat.test.gtaoDedicatedDepth"); }
    }

    @Test void dedicatedDepthAndSharedSurfaceConsumeTheSamePreparedInstanceBatch() throws Exception {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture();
                 var mesh = Mesh.from(com.kaleblangley.haikalat.core.mesh.BuiltinMeshData.coloredQuad("GTAO instance"));
                 var shader = ShaderProgram.fromSources(Files.readString(Path.of("src/demo/resources/shaders/instancing/instanced-scene.vert")),
                         Files.readString(Path.of("src/demo/resources/shaders/basic/vertex-color-unlit.frag")))) {
                var batch = com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch.of(mesh, 1,
                        com.kaleblangley.haikalat.core.mesh.BuiltinMeshData.INSTANCE_ATTRIBUTE_BASE);
                var instances = new InstancedRenderer(batch, shader, true);
                instances.addInstance(frame -> new org.joml.Matrix4f().translation(.4f, -.4f, .5f).scale(.3f));
                System.setProperty("haikalat.test.gtaoDedicatedDepth", "true");
                try {
                    var p = new RenderPipeline(fixture.size, fixture.scene, instances,
                            RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                                    .toneMappingMode(ToneMappingMode.ACES).vsync(false).build(), fixture.environment)
                            .postProcessSettings(PostProcessSettings.builder().gtao(GtaoSettings.defaults().withEnabled(true)).build());
                    fixture.pipelines.add(p); p.build(); instances.beginFrame(0); p.execute(new GlRenderDevice());
                    assertTrue(p.lastRender3dDiagnostics().ambientOcclusion().depthPrepassDraws() >= 4);
                    assertEquals(1, instances.drawnCount()); assertEquals(64, instances.bufferStatistics().uploadedBytes());
                    assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL)); p.close();
                } finally { System.clearProperty("haikalat.test.gtaoDedicatedDepth"); instances.close(); }
            }
        }
    }

    @Test void optOutAndDirectLightKeepNoAoSemanticsAcrossRuntimeSwitch() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture()) {
                for (boolean optOut : new boolean[]{true, false}) {
                    var scene = new Scene(fixture.camera);
                    for (var original : fixture.scene.renderers()) {
                        var material = original.material().material().createInstance();
                        if (optOut) material.setInt("uGtaoMaterialOptOut", 1);
                        else material.setInt("uEnableDiffuseIbl", 0).setInt("uEnableSpecularIbl", 0);
                        scene.add(new MeshRenderer(original.mesh(), material, original.transform(), original.updater(),
                                original.castShadows(), original.drawBinding()));
                    }
                    for (var light : fixture.scene.lights()) scene.addLight(light);
                    var p = fixture.pipeline(scene, AntiAliasingMode.NONE, GtaoSettings.disabled());
                    p.build(); var device = new GlRenderDevice(); p.execute(device);
                    float[] original = p.captureLinearHdrRgbaFloat();
                    p.applyGtao(GtaoSettings.defaults().withEnabled(true)); p.execute(device);
                    assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL));
                    assertArrayEquals(original, p.captureLinearHdrRgbaFloat(), 0f,
                            optOut ? "material AO opt-out" : "AO must not attenuate direct light");
                    p.close();
                }
            }
        }
    }

    @Test void activeSkinMorphAndStaticDrawsKeepValidStorageAndCorrectSharedNormals() throws Exception {
        Files.writeString(evidence().resolve("active-storage-layout.csv"), "block,slot,minimumBytes,boundRangeBytes\n");
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            org.lwjgl.opengl.GL20.glVertexAttrib4f(5, 0, 0, 0, 0);
            org.lwjgl.opengl.GL20.glVertexAttrib4f(6, 1, 0, 0, 0);
            try (var joints = com.kaleblangley.haikalat.backend.buffer.GlBuffer.shaderStorageBuffer(org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW).allocate(64);
                 var deltas = com.kaleblangley.haikalat.backend.buffer.GlBuffer.shaderStorageBuffer(org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW).allocate(288);
                 var weights = com.kaleblangley.haikalat.backend.buffer.GlBuffer.shaderStorageBuffer(org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW).allocate(16);
                 var fixture = new Fixture()) {
                for (var aa : new AntiAliasingMode[]{AntiAliasingMode.NONE, AntiAliasingMode.TAA, AntiAliasingMode.MSAA}) {
                    for (int mode = 1; mode <= 3; mode++) {
                        boolean skin = mode != 2, morph = mode != 1;
                        var joint = new org.joml.Matrix4f().rotateY(skin ? .3f : 0);
                        var palette = org.lwjgl.BufferUtils.createByteBuffer(64); joint.get(palette.asFloatBuffer());
                        var delta = org.lwjgl.BufferUtils.createByteBuffer(288);
                        for (int vertex = 0; vertex < 6; vertex++) for (int component = 0; component < 12; component++)
                            delta.putFloat(component == 0 ? .15f : component == 4 ? .3f : 0);
                        delta.flip(); var weight = org.lwjgl.BufferUtils.createByteBuffer(4).putFloat(1).flip();
                        SceneDrawBinding binding = new SceneDrawBinding() {
                            @Override public boolean skinningEnabled() { return skin; }
                            @Override public int morphTargetCount() { return morph ? 1 : 0; }
                            @Override public void record(com.kaleblangley.haikalat.core.command.CommandBuffer commands,
                                                         ShaderProgram program, int frame, Pass pass) {
                                if (skin) commands.uploadBufferRegion(joints, 0, palette).bindStorageBuffer(7, joints, 0, 64);
                                if (morph) commands.uploadBufferRegion(deltas, 0, delta).bindStorageBuffer(8, deltas, 0, 288)
                                        .uploadBufferRegion(weights, 0, weight).bindStorageBuffer(9, weights, 0, 16);
                                commands.trySetUniformInt(program, "uMorphVertexCount", 6);
                            }
                        };
                        var transform = Transform.at(0, 0, -1).scale(3, 2, 1);
                        var content = new Scene(fixture.camera).add(new MeshRenderer(fixture.mesh, fixture.material.createInstance(),
                                transform, (model, frame) -> transform.matrix(model), true, binding));
                        for (var light : fixture.scene.lights()) content.addLight(light);
                        var p = fixture.pipeline(content, aa, GtaoSettings.defaults().withEnabled(true));
                        p.build(); var device = new GlRenderDevice();
                        var modelSkin = transform.matrix(new org.joml.Matrix4f()).mul(joint);
                        var expected = new org.joml.Matrix3f(modelSkin).invert().transpose()
                                .transform(new Vector3f(morph ? .3f : 0, 0, 1)).normalize();
                        float divisor = Math.abs(expected.x) + Math.abs(expected.y) + Math.abs(expected.z);
                        expected.div(divisor);
                        for (int frame = 0; frame < 2; frame++) {
                            p.execute(device);
                            assertActiveDeformationStorage(p);
                            float[] normals = p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_NORMAL);
                            int covered = 0;
                            for (int pixel = 0; pixel < normals.length; pixel += 4) {
                                assertTrue(Float.isFinite(normals[pixel]) && Float.isFinite(normals[pixel + 1]));
                                if (Math.abs(normals[pixel]) + Math.abs(normals[pixel + 1]) < .001f) continue;
                                assertEquals(expected.x, normals[pixel], .002f, aa + " mode=" + mode);
                                assertEquals(expected.y, normals[pixel + 1], .002f); covered++;
                            }
                            assertTrue(covered > 100);
                        }
                        p.close();
                    }
                }
                GlDebug.assertNoError("skin/morph shared normal and storage contracts");
            }
        }
    }

    private static void assertActiveDeformationStorage(RenderPipeline pipeline) throws Exception {
        var field = SceneSurfacePass.class.getDeclaredField("surfaceProgram"); field.setAccessible(true);
        var program = (ShaderProgram) field.get(pipeline.activeGenerationForTest().sceneSurfacePass);
        for (String block : java.util.List.of("JointPaletteBlock", "MorphDeltaBlock", "MorphWeightBlock",
                "PreviousJointPaletteBlock", "PreviousMorphWeightBlock")) {
            var properties = org.lwjgl.BufferUtils.createIntBuffer(2).put(new int[]{org.lwjgl.opengl.GL43.GL_BUFFER_BINDING,
                    org.lwjgl.opengl.GL43.GL_BUFFER_DATA_SIZE}).flip();
            var values = org.lwjgl.BufferUtils.createIntBuffer(2);
            org.lwjgl.opengl.GL43.glGetProgramResourceiv(program.id(), org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BLOCK,
                    program.storageBlockIndex(block), properties, null, values);
            int slot = values.get(0);
            long range = org.lwjgl.opengl.GL32.glGetInteger64i(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_SIZE, slot);
            Files.writeString(evidence().resolve("active-storage-layout.csv"), block + "," + slot + "," + values.get(1)
                    + "," + range + "\n", java.nio.file.StandardOpenOption.APPEND);
            assertTrue(org.lwjgl.opengl.GL30.glGetIntegeri(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_BINDING, slot) != 0, block);
            assertTrue(range >= values.get(1), block + " minimum=" + values.get(1));
        }
    }

    @Test void embeddedGtaoTransitionsPreserveBorrowedAttachmentsAndHostState() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture();
                 var first = com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptor(
                         com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(96, 64)
                                 .colorTexture(com.kaleblangley.haikalat.backend.RenderFormat.RGBA16F).depthTexture().build());
                 var second = com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptor(
                         com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(97, 65)
                                 .colorTexture(com.kaleblangley.haikalat.backend.RenderFormat.RGBA16F).depthTexture().build());
                 var hostSampler = com.kaleblangley.haikalat.backend.texture.Sampler.nearestRepeat();
                 var hostStorage = com.kaleblangley.haikalat.backend.buffer.GlBuffer.shaderStorageBuffer(org.lwjgl.opengl.GL15.GL_STATIC_DRAW).upload(new float[16])) {
                var target = borrowed(first, 1);
                var p = new RenderPipeline(target, fixture.scene, null, RenderSettings.builder()
                        .antiAliasingMode(AntiAliasingMode.NONE).toneMappingMode(ToneMappingMode.ACES).vsync(false).build(), fixture.environment);
                fixture.pipelines.add(p); p.build(); var device = new GlRenderDevice();
                for (int step = 0; step < 4; step++) {
                    var host = step < 2 ? first : second; target = borrowed(host, step < 2 ? 1 : 2);
                    org.lwjgl.opengl.GL45.glClearNamedFramebufferfv(host.id(), org.lwjgl.opengl.GL11.GL_COLOR, 0, new float[]{.05f, .05f, .05f, 1});
                    org.lwjgl.opengl.GL45.glClearNamedFramebufferfv(host.id(), org.lwjgl.opengl.GL11.GL_DEPTH, 0, new float[]{1});
                    org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, host.id());
                    org.lwjgl.opengl.GL11.glViewport(3, 4, 41, 27);
                    org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + 13);
                    for (int unit : new int[]{0, 1, 13}) org.lwjgl.opengl.GL33.glBindSampler(unit, hostSampler.id());
                    for (int slot = 7; slot <= 11; slot++) org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, slot, hostStorage.id());
                    p.applyGtao(GtaoSettings.defaults().withEnabled(step % 2 == 0));
                    assertHost(host.id(), hostSampler.id());
                    var camera = ExternalCamera.of(fixture.camera.getViewMatrix(new org.joml.Matrix4f()),
                            CameraProjection.stable(fixture.camera, host.width(), host.height(), new org.joml.Matrix4f()), fixture.camera.position(), 0);
                    p.execute(device, camera, target); assertHost(host.id(), hostSampler.id());
                    assertHostStorage(hostStorage.id());
                    assertTrue(org.lwjgl.opengl.GL30.glIsFramebuffer(host.id()));
                    assertTrue(org.lwjgl.opengl.GL11.glIsTexture(host.colorAttachment()));
                    assertTrue(org.lwjgl.opengl.GL11.glIsTexture(host.depthAttachment()));
                    if (step % 2 == 0) assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL));
                }
                p.close();
                assertHostStorage(hostStorage.id());
                assertTrue(org.lwjgl.opengl.GL30.glIsFramebuffer(first.id())); assertTrue(org.lwjgl.opengl.GL30.glIsFramebuffer(second.id()));
                assertTrue(org.lwjgl.opengl.GL11.glIsTexture(first.depthAttachment())); assertTrue(org.lwjgl.opengl.GL11.glIsTexture(second.depthAttachment()));
                GlDebug.assertNoError("embedded GTAO target lifecycle");
            }
        }
    }

    private static void assertHostStorage(int buffer) {
        assertTrue(org.lwjgl.opengl.GL15.glIsBuffer(buffer));
        for (int slot = 7; slot <= 11; slot++)
            assertEquals(buffer, org.lwjgl.opengl.GL30.glGetIntegeri(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_BINDING, slot));
    }

    private static com.kaleblangley.haikalat.core.presentation.PresentationTarget borrowed(
            com.kaleblangley.haikalat.backend.framebuffer.Framebuffer host, long generation) {
        return com.kaleblangley.haikalat.core.presentation.PresentationTarget.borrowed(host.id(),
                com.kaleblangley.haikalat.core.presentation.ExternalAttachment.borrowedColor(host.colorAttachment(),
                        com.kaleblangley.haikalat.backend.RenderFormat.RGBA16F, host.width(), host.height()),
                com.kaleblangley.haikalat.core.presentation.ExternalAttachment.borrowedDepth(host.depthAttachment(),
                        com.kaleblangley.haikalat.backend.RenderFormat.DEPTH_COMPONENT24, host.width(), host.height()),
                host.width(), host.height(), generation);
    }

    private static void assertHost(int framebuffer, int sampler) {
        assertEquals(framebuffer, org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        assertEquals(framebuffer, org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING));
        int[] viewport = new int[4]; org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);
        assertArrayEquals(new int[]{3, 4, 41, 27}, viewport);
        assertEquals(org.lwjgl.opengl.GL13.GL_TEXTURE0 + 13, org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE));
        for (int unit : new int[]{0, 1, 13}) assertEquals(sampler, org.lwjgl.opengl.GL30.glGetIntegeri(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING, unit));
    }

    @Test void gtaoSwitchesPreserveStaticShadowCacheAndAtlas() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking(); var fixture = new Fixture()) {
                fixture.scene.removeLight(0).addLight(SceneLight.shadowedDirectional(new Vector3f(-1, -1, -1), new Vector3f(1), .5f));
                var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.disabled());
                p.localShadows(LocalShadowPipelineSettings.balanced()); p.build();
                var device = new GlRenderDevice(); p.execute(device); p.execute(device);
                var cache = p.activeGenerationForTest().shadowCache;
                var shadowShader = p.activeGenerationForTest().shadowShader;
                int atlas = boundTexture2d(7);
                assertTrue(atlas > 0 && org.lwjgl.opengl.GL11.glIsTexture(atlas));
                assertTrue(cache.lastCacheHits() > 0); assertEquals(0, cache.lastTilesRendered());
                p.applyGtao(GtaoSettings.defaults().withEnabled(true)); p.execute(device);
                assertSame(cache, p.activeGenerationForTest().shadowCache);
                assertSame(shadowShader, p.activeGenerationForTest().shadowShader);
                assertEquals(atlas, boundTexture2d(7), "the PBR consumer must retain the native shadow atlas");
                assertTrue(cache.lastCacheHits() > 0); assertEquals(0, cache.lastTilesRendered());
                p.applyGtao(GtaoSettings.disabled()); p.execute(device);
                assertSame(cache, p.activeGenerationForTest().shadowCache);
                assertEquals(atlas, boundTexture2d(7));
                assertTrue(cache.lastCacheHits() > 0); assertEquals(0, cache.lastTilesRendered());
            }
        }
    }

    private static int boundTexture2d(int unit) {
        int active = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        try {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
            return org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        } finally { org.lwjgl.opengl.GL13.glActiveTexture(active); }
    }

    @Test void fiftyCyclesWithTemporalResizeFailuresAndFogReturnToDisabledBaseline() throws Exception {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities(); GlDebug.enableDebugCallback();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var fixture = new Fixture()) {
                    var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.disabled());
                    p.build(); var device = new GlRenderDevice(); p.execute(device);
                    var baselines = new java.util.HashMap<Integer, Long>(); long peak = 0;
                    Path trace = evidence().resolve("fifty-cycle.csv"); Files.writeString(trace, "cycle,width,disabledEstimatedBytes\n");
                    for (int cycle = 0; cycle < 50; cycle++) {
                        if (cycle % 10 == 0) {
                            fixture.size.width = fixture.size.width == 96 ? 97 : 96;
                            fixture.size.height = fixture.size.width == 96 ? 64 : 65;
                            p.resize(fixture.size.width, fixture.size.height);
                        }
                        if (cycle % 7 == 0) {
                            System.setProperty("haikalat.test.failGtaoGraph", "true");
                            try { assertThrows(RuntimeException.class, () -> p.applyGtao(GtaoSettings.defaults().withEnabled(true))); }
                            finally { System.clearProperty("haikalat.test.failGtaoGraph"); }
                        }
                        p.applyGtao(tuning(1, com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality.MEDIUM, cycle % 2 == 0));
                        if (cycle % 3 == 0) p.applyVolumetricFog(VolumetricGenerationGlTest.fog(.1f));
                        p.execute(device); assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL));
                        peak = Math.max(peak, GlDebug.resources().estimatedBytes());
                        if (cycle % 5 == 0) { p.applyGtao(tuning(2, com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality.HIGH, cycle % 2 == 0)); p.execute(device); }
                        p.applyGtao(GtaoSettings.disabled()); p.execute(device);
                        p.applyVolumetricFog(VolumetricFogSettings.disabled()); p.execute(device); p.drainRetirementsForTest();
                        assertNull(p.activeGenerationForTest().gtao); assertNull(p.activeGenerationForTest().volumetric);
                        long bytes = GlDebug.resources().estimatedBytes();
                        var previous = baselines.putIfAbsent(fixture.size.width, bytes);
                        if (previous != null) assertEquals(previous.longValue(), bytes, "disabled footprint grew at cycle " + cycle);
                        Files.writeString(trace, cycle + "," + fixture.size.width + "," + bytes + "\n", java.nio.file.StandardOpenOption.APPEND);
                    }
                    System.out.println("GTAO_50_SWITCHES disabled=" + baselines + " peakEstimatedBytes=" + peak);
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
                assertEquals(GlDebug.resources().createdCount(), GlDebug.resources().closedCount());
                GlDebug.assertNoError("GTAO 50 switch cycles");
            }
        }
    }

    @Test void initializedThreadContextExecutingAndClosedGuardsRejectBeforeMutation() throws Exception {
        try (var window = window()) {
            window.bindContext(); var capabilities = GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking(); var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                p.build(); var device = new GlRenderDevice(); p.execute(device);
                var owner = p.activeGenerationForTest(); long created = GlDebug.resources().createdCount();
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    assertThrows(IllegalStateException.class, () -> p.applyGtao(GtaoSettings.disabled()));
                    assertThrows(IllegalStateException.class, p::close);
                    assertThrows(IllegalStateException.class, () -> p.resize(97, 65));
                }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0); GL.setCapabilities(null);
                try { assertThrows(IllegalStateException.class, () -> p.applyGtao(GtaoSettings.disabled())); }
                finally { window.bindContext(); GL.setCapabilities(capabilities); }
                long other = org.lwjgl.glfw.GLFW.glfwCreateWindow(16, 16, "foreign GTAO context", 0, 0);
                assertNotEquals(0, other);
                try {
                    org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(other); GL.createCapabilities();
                    assertThrows(IllegalStateException.class, () -> p.applyGtao(GtaoSettings.disabled()));
                    assertThrows(IllegalStateException.class, p::close);
                } finally {
                    window.bindContext(); GL.setCapabilities(capabilities); org.lwjgl.glfw.GLFW.glfwDestroyWindow(other);
                }
                assertSame(owner, p.activeGenerationForTest()); assertEquals(created, GlDebug.resources().createdCount());
                var reentrant = new com.kaleblangley.haikalat.core.device.RenderDevice() {
                    public com.kaleblangley.haikalat.core.device.RenderBackendKind backendKind() { return device.backendKind(); }
                    public com.kaleblangley.haikalat.core.device.ExecutionModel executionModel() { return device.executionModel(); }
                    public com.kaleblangley.haikalat.core.command.CommandBuffer createCommandBuffer() { return device.createCommandBuffer(); }
                    public void execute(com.kaleblangley.haikalat.core.command.CommandBuffer commands) {
                        assertThrows(IllegalStateException.class, () -> p.applyGtao(GtaoSettings.disabled())); device.execute(commands);
                    }
                    public void invalidateState() { device.invalidateState(); }
                    public void transition(com.kaleblangley.haikalat.core.device.ResourceBarrier... barriers) { device.transition(barriers); }
                };
                p.execute(reentrant); assertSame(owner, p.activeGenerationForTest()); p.close();
                assertThrows(IllegalStateException.class, () -> p.applyGtao(GtaoSettings.disabled()));
                assertThrows(IllegalStateException.class, () -> p.replaceScene(fixture.scene));
            }
        }
    }

    @Test void worldReplacementAndTwoViewsDoNotShareOldAoHistory() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture()) {
                var a = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                var b = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                a.build(); b.build(); var device = new GlRenderDevice(); a.execute(device); b.execute(device);
                var historyA = a.activeGenerationForTest().gtao.historyForTest();
                var historyB = b.activeGenerationForTest().gtao.historyForTest();
                assertNotSame(historyA, historyB); var otherRead = historyB.readFramebuffer();
                var world = new Scene(new Camera(new Vector3f(2.4f, 1.4f, 4), new Vector3f(0, 1, 0), -116, -15));
                for (var renderer : fixture.scene.renderers()) world.add(renderer);
                for (var light : fixture.scene.lights()) world.addLight(light);
                a.replaceScene(world); assertSame(historyA, a.activeGenerationForTest().gtao.historyForTest());
                assertFalse(historyA.valid()); assertSame(otherRead, historyB.readFramebuffer()); assertTrue(historyB.valid());
                a.execute(device); assertTrue(a.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_WORLD"));
                var fresh = fixture.pipeline(world, AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                fresh.build(); fresh.execute(device);
                assertArrayEquals(fresh.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL),
                        a.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL), 0f);
            }
        }
    }

    @Test void inputContractsRejectMissingFormatSamplesExtentAndForeignGraph() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                p.build(); p.execute(new GlRenderDevice()); var owner = p.activeGenerationForTest(); var input = owner.gtaoInputs;
                input.validate();
                assertThrows(IllegalArgumentException.class, () -> new GtaoSurfaceInputs(input.generationId(), input.graph(), "absent",
                        input.depthTexture(), input.normalTexture(), input.aa(), input.temporal(), true).validate());
                assertThrows(IllegalArgumentException.class, () -> new GtaoSurfaceInputs(input.generationId(), input.graph(), input.producer(),
                        input.depthTexture(), "missingNormal", input.aa(), input.temporal(), true).validate());
                assertThrows(IllegalArgumentException.class, () -> new GtaoSurfaceInputs(input.generationId(), input.graph(), input.producer(),
                        input.depthTexture(), input.normalTexture(), AntiAliasingMode.MSAA, input.temporal(), true).validate());
                assertThrows(IllegalStateException.class, () -> input.validateFrame(1, 1));
                try (var foreign = new com.kaleblangley.haikalat.core.graph.RenderGraph(96, 64)) {
                    assertThrows(IllegalArgumentException.class, () -> owner.gtao.addPreGeometryPasses(foreign, input));
                    foreign.addPass("BadSurface").createColors(java.util.List.of("badDepth", "badNormal"),
                            java.util.List.of(com.kaleblangley.haikalat.backend.RenderFormat.RGBA16F,
                                    com.kaleblangley.haikalat.backend.RenderFormat.RG16F)).execute((res, cmd) -> {});
                    foreign.addPass(PostProcessTargets.GTAO_ESTIMATE_PASS).computeOnly().dependsOn("BadSurface").execute((res, cmd) -> {});
                    assertThrows(IllegalArgumentException.class, () -> new GtaoSurfaceInputs(1, foreign, "BadSurface", "badDepth", "badNormal",
                            AntiAliasingMode.NONE, false, true).validate());
                }
            }
        }
    }

    @Test void dedicatedDepthCompatibilityRunsThroughProductionPipeline() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture()) {
                System.setProperty("haikalat.test.gtaoDedicatedDepth", "true");
                try {
                    var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                    p.build(); p.execute(new GlRenderDevice());
                    assertFalse(p.activeGenerationForTest().gtaoInputs.shared());
                    assertTrue(p.graph().hasPass(PostProcessTargets.GTAO_DEPTH_PREPASS));
                    assertTrue(p.lastRender3dDiagnostics().ambientOcclusion().depthPrepassDraws() > 0);
                    assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL));
                } finally { System.clearProperty("haikalat.test.gtaoDedicatedDepth"); }
            }
        }
    }

    @Test void prepareAllocationShaderAndGraphFailuresKeepOldRealPixelsAndOwners() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking(); var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.disabled());
                p.build(); var device = new GlRenderDevice(); p.execute(device);
                var active = p.activeGenerationForTest(); float[] expected = p.captureLinearHdrRgbaFloat();
                String[][] faults = {{"haikalat.test.failGtaoPrepare", "true"},
                        {"haikalat.test.failGtaoAllocation", "0"}, {"haikalat.test.failGtaoAllocation", "1"},
                        {"haikalat.test.failGtaoAllocation", "2"}, {"haikalat.test.failGtaoCompilation", "0"},
                        {"haikalat.test.failGtaoCompilation", "1"}, {"haikalat.test.failGtaoCompilation", "2"},
                        {"haikalat.test.failGtaoCompilation", "3"}, {"haikalat.test.failGtaoGraph", "true"}};
                for (String[] fault : faults) {
                    long bytes = GlDebug.resources().estimatedBytes(), live = GlDebug.resources().liveResources().size();
                    long created = GlDebug.resources().createdCount();
                    System.setProperty(fault[0], fault[1]);
                    try {
                        var error = assertThrows(RuntimeException.class, () -> p.applyGtao(GtaoSettings.defaults().withEnabled(true)), fault[0]);
                        assertNotNull(error.getMessage());
                    } finally { System.clearProperty(fault[0]); }
                    assertSame(active, p.activeGenerationForTest()); assertFalse(p.gtao().enabled());
                    assertEquals(bytes, GlDebug.resources().estimatedBytes(), fault[0]);
                    assertEquals(live, GlDebug.resources().liveResources().size(), fault[0]);
                    if (fault[0].endsWith("Prepare")) assertEquals(created, GlDebug.resources().createdCount());
                    p.execute(device); assertArrayEquals(expected, p.captureLinearHdrRgbaFloat(), 0f, fault[0]);
                }
                p.applyGtao(GtaoSettings.defaults().withEnabled(true)); p.execute(device);
                assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL));
            }
        }
    }

    @Test void failedRetirementFenceKeepsOldAoReachableAndRetriesWithoutRollingBackPublication() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var fixture = new Fixture()) {
                    var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                    p.build(); var device = new GlRenderDevice(); p.execute(device);
                    var old = p.activeGenerationForTest();
                    var oldAo = old.gtao;
                    var oldRead = oldAo.historyForTest().readFramebuffer();
                    System.setProperty("haikalat.test.failRetirementFence", "true");
                    try {
                        var failure = assertThrows(IllegalStateException.class, () -> p.applyGtao(GtaoSettings.disabled()));
                        assertEquals("injected retirement fence creation failure", failure.getMessage());
                        assertFalse(p.gtao().enabled()); assertNotSame(old, p.activeGenerationForTest());
                        assertEquals("retirement-after-publication", p.lastRender3dDiagnostics().failureStage());
                        assertEquals(1, p.volumetricAllocationDiagnostics().retiringGenerations());
                        assertSame(oldAo, old.gtao); assertFalse(oldRead.isClosed());
                        assertThrows(IllegalStateException.class, p::drainRetirementsForTest);
                        assertEquals(1, p.volumetricAllocationDiagnostics().retiringGenerations());
                        assertSame(oldAo, old.gtao); assertFalse(oldRead.isClosed());
                    } finally { System.clearProperty("haikalat.test.failRetirementFence"); }
                    p.drainRetirementsForTest();
                    assertEquals(0, p.volumetricAllocationDiagnostics().retiringGenerations());
                    assertNull(old.gtao); assertTrue(oldRead.isClosed());
                    p.execute(device);
                    assertFalse(p.gtao().enabled());
                    assertTrue(p.graph().description().passes().stream().noneMatch(pass -> pass.name().startsWith("Gtao")));
                    var closing = p.activeGenerationForTest();
                    System.setProperty("haikalat.test.failRetirementFence", "true");
                    try {
                        assertThrows(IllegalStateException.class, p::close);
                        assertNotNull(closing.graph);
                        assertEquals(1, p.volumetricAllocationDiagnostics().retiringGenerations());
                        assertThrows(IllegalStateException.class, () -> p.execute(device));
                    } finally { System.clearProperty("haikalat.test.failRetirementFence"); }
                    p.close();
                    assertNull(closing.graph);
                }
                assertEquals(0, GlDebug.resources().liveResources().size());
                assertEquals(GlDebug.resources().createdCount(), GlDebug.resources().closedCount());
                GlDebug.assertNoError("retirement fence failure recovery");
            }
        }
    }

    @Test void publishedGtaoSurvivesRetirementFailureAndContinuesDrawing() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking(); var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.disabled());
                p.build(); var device = new GlRenderDevice(); p.execute(device);
                var previous = p.activeGenerationForTest();
                System.setProperty("haikalat.test.failGtaoRetirement", "true");
                try { assertThrows(IllegalStateException.class, () -> p.applyGtao(GtaoSettings.defaults().withEnabled(true))); }
                finally { System.clearProperty("haikalat.test.failGtaoRetirement"); }
                assertNotSame(previous, p.activeGenerationForTest()); assertTrue(p.gtao().enabled());
                assertEquals("retirement-after-publication", p.lastRender3dDiagnostics().failureStage());
                p.execute(device); assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL));
                p.drainRetirementsForTest();
            }
        }
    }

    @Test void unsubmittedAndPartiallyExecutedFailuresDoNotPublishHistoryAndRecover() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture()) {
                var failForward = new java.util.concurrent.atomic.AtomicBoolean();
                var content = new Scene(fixture.camera);
                for (var original : fixture.scene.renderers()) {
                    SceneDrawBinding failureBinding = (commands, shader, frameIndex, pass) -> {
                        if (pass == SceneDrawBinding.Pass.FORWARD && failForward.getAndSet(false))
                            commands.custom(() -> { throw new IllegalStateException("test failure after GTAO temporal output"); });
                    };
                    content.add(new MeshRenderer(original.mesh(), original.material(), original.transform(),
                            original.updater(), original.castShadows(), failureBinding));
                }
                for (var light : fixture.scene.lights()) content.addLight(light);
                var p = fixture.pipeline(content, AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                p.build(); var device = new GlRenderDevice(); p.execute(device);
                var ao = p.activeGenerationForTest().gtao; var history = ao.historyForTest();
                var read = history.readFramebuffer(); var write = history.writeFramebuffer();
                float[] readPixels = texture(read), writePixels = texture(write);
                var rejecting = new com.kaleblangley.haikalat.core.device.RenderDevice() {
                    public com.kaleblangley.haikalat.core.device.RenderBackendKind backendKind() { return device.backendKind(); }
                    public com.kaleblangley.haikalat.core.device.ExecutionModel executionModel() { return device.executionModel(); }
                    public com.kaleblangley.haikalat.core.command.CommandBuffer createCommandBuffer() { return device.createCommandBuffer(); }
                    public void execute(com.kaleblangley.haikalat.core.command.CommandBuffer commands) { throw new IllegalStateException("before GPU submission"); }
                    public void invalidateState() { device.invalidateState(); }
                    public void transition(com.kaleblangley.haikalat.core.device.ResourceBarrier... barriers) { device.transition(barriers); }
                };
                assertThrows(IllegalStateException.class, () -> p.execute(rejecting));
                assertSame(read, history.readFramebuffer()); assertArrayEquals(readPixels, texture(read), 0f);
                assertArrayEquals(writePixels, texture(write), 0f); assertFalse(history.valid());
                p.execute(device); assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_FRAME_FAILURE"));
                read = history.readFramebuffer(); write = history.writeFramebuffer(); readPixels = texture(read);
                org.lwjgl.opengl.GL45.glClearNamedFramebufferfv(write.id(), org.lwjgl.opengl.GL11.GL_COLOR, 0, new float[]{.1f, .2f, 0, 0});
                writePixels = texture(write);
                // The real graph submits GTAO before forward geometry. Keep the
                // executable fault entirely in this test's scene binding.
                failForward.set(true);
                var failure = assertThrows(IllegalStateException.class, () -> p.execute(device));
                assertEquals("test failure after GTAO temporal output", failure.getMessage());
                assertFalse(failForward.get(), "the forward failure command must have been recorded");
                assertSame(read, history.readFramebuffer()); assertArrayEquals(readPixels, texture(read), 0f);
                assertFalse(java.util.Arrays.equals(writePixels, texture(write)), "real GPU write must precede the injected failure");
                assertFalse(history.valid()); p.execute(device);
                assertTrue(history.valid()); assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_FRAME_FAILURE"));
            }
        }
    }

    @Test void failedResizeKeepsGraphHistoryAndPixelsThenOddExtentCommits() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking(); var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.TAA, GtaoSettings.defaults().withEnabled(true));
                p.build(); var device = new GlRenderDevice(); p.execute(device);
                var owner = p.activeGenerationForTest(); var read = owner.gtao.historyForTest().readFramebuffer();
                long bytes = GlDebug.resources().estimatedBytes(), live = GlDebug.resources().liveResources().size();
                System.setProperty("haikalat.test.failGtaoResizeAllocation", "true");
                try { assertThrows(IllegalStateException.class, () -> p.resize(97, 65)); }
                finally { System.clearProperty("haikalat.test.failGtaoResizeAllocation"); }
                assertEquals(96, p.graph().width()); assertSame(read, owner.gtao.historyForTest().readFramebuffer());
                assertEquals(bytes, GlDebug.resources().estimatedBytes()); assertEquals(live, GlDebug.resources().liveResources().size());
                p.execute(device); assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL));
                var stableRead = owner.gtao.historyForTest().readFramebuffer();
                long created = GlDebug.resources().createdCount();
                assertThrows(IllegalArgumentException.class, () -> p.resize(32768, 32768));
                assertEquals(created, GlDebug.resources().createdCount(), "AO budget must reject before any candidate GL allocation");
                int deviceLimit = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE);
                assertThrows(IllegalArgumentException.class, () -> p.resize(deviceLimit + 1, 1));
                assertEquals(created, GlDebug.resources().createdCount(), "a narrow over-limit extent must also reject before allocation");
                assertEquals(96, p.graph().width()); assertSame(stableRead, owner.gtao.historyForTest().readFramebuffer());
                p.resize(97, 65); fixture.size.width = 97; fixture.size.height = 65; p.execute(device);
                assertEquals(49, owner.gtao.historyForTest().readFramebuffer().width());
                assertEquals(33, owner.gtao.historyForTest().readFramebuffer().height());
                assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_RESIZE"));
            }
        }
    }

    static float[] texture(com.kaleblangley.haikalat.backend.framebuffer.Framebuffer framebuffer) {
        var values = org.lwjgl.BufferUtils.createFloatBuffer(framebuffer.width() * framebuffer.height() * 4);
        org.lwjgl.opengl.GL45.glGetTextureImage(framebuffer.colorAttachment(), 0, org.lwjgl.opengl.GL11.GL_RGBA,
                org.lwjgl.opengl.GL11.GL_FLOAT, values);
        float[] result = new float[values.remaining()]; values.get(result); return result;
    }

    @Test void runtimeEnableDisableRestoresNoAoPixelsAndKeepsUnrelatedOwners() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var fixture = new Fixture()) {
                    var p = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.disabled());
                    p.build(); var device = new GlRenderDevice(); p.execute(device);
                    float[] original = p.captureLinearHdrRgbaFloat();
                    var initial = p.activeGenerationForTest();
                    assertNull(initial.gtao); assertFalse(p.graph().hasPass(PostProcessTargets.GTAO_ESTIMATE_PASS));
                    p.applyGtao(GtaoSettings.defaults().withEnabled(true));
                    var ao = p.activeGenerationForTest().gtao;
                    assertSame(initial.cameraUniforms, p.activeGenerationForTest().cameraUniforms);
                    assertSame(initial.clusteredResources, p.activeGenerationForTest().clusteredResources);
                    assertSame(initial.shadowCache, p.activeGenerationForTest().shadowCache);
                    p.execute(device); assertAo(p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL));
                    p.applyGtao(GtaoSettings.disabled()); p.execute(device); p.drainRetirementsForTest();
                    assertNull(p.activeGenerationForTest().gtao);
                    assertFalse(p.graph().hasPass(PostProcessTargets.GTAO_UPSAMPLE_PASS));
                    assertArrayEquals(original, p.captureLinearHdrRgbaFloat(), 0f);
                    assertThrows(RuntimeException.class, () -> ao.applySettings(GtaoSettings.defaults().withEnabled(true)), "stopped owner must retire");
                    assertEquals(0, org.lwjgl.opengl.GL20.glGetUniformi(fixture.shader.id(),
                            org.lwjgl.opengl.GL20.glGetUniformLocation(fixture.shader.id(), "uGtaoEnabled")));
                    assertEquals(0, org.lwjgl.opengl.GL30.glGetIntegeri(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING, 13));
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test void scalarAndQualityUpdatesChangePixelsWithoutGraphOrNativeAllocation() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking(); var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.TAA, GtaoSettings.defaults().withEnabled(true));
                p.build(); var device = new GlRenderDevice(); p.execute(device);
                var owner = p.activeGenerationForTest(); var history = owner.gtao.historyForTest();
                var taa = owner.postProcess.taaHistoryForTest(); var graph = p.graph();
                long revision = graph.description().topologyRevision(), creations = GlDebug.resources().createdCount();
                float[] before = graph.readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL);
                for (int i = 0; i < 16; i++) {
                    p.applyGtao(tuning(i % 2 == 0 ? 0 : 2, i % 2 == 0
                            ? com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality.LOW
                            : com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality.HIGH, true));
                    assertSame(owner, p.activeGenerationForTest()); assertSame(graph, p.graph());
                    assertSame(history, owner.gtao.historyForTest()); assertSame(taa, owner.postProcess.taaHistoryForTest());
                    assertFalse(owner.gtao.historyValid()); p.execute(device);
                    assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("GTAO_SETTINGS"));
                }
                assertEquals(revision, graph.description().topologyRevision());
                assertEquals(creations, GlDebug.resources().createdCount());
                assertFalse(java.util.Arrays.equals(before, graph.readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL)));
            }
        }
    }

    @Test void temporalCandidatesAndFogSwitchesKeepIndependentOwnersAndHistories() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking(); var fixture = new Fixture()) {
                var p = fixture.pipeline(AntiAliasingMode.TAA, GtaoSettings.defaults().withEnabled(true));
                p.build(); var device = new GlRenderDevice(); p.execute(device);
                var initial = p.activeGenerationForTest(); var gtao = initial.gtao;
                var clustered = initial.clusteredResources;
                var taa = initial.postProcess.taaHistoryForTest();
                p.applyVolumetricFog(VolumetricGenerationGlTest.fog(.1f));
                assertSame(gtao, p.activeGenerationForTest().gtao); assertTrue(gtao.historyValid());
                p.execute(device); var fog = p.activeGenerationForTest().volumetric;
                var fogStorage = fog.resources();
                p.applyGtao(tuning(1, com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality.MEDIUM, false));
                assertSame(fog, p.activeGenerationForTest().volumetric); assertSame(fogStorage, p.activeGenerationForTest().volumetric.resources());
                assertNull(p.activeGenerationForTest().gtao.historyForTest());
                assertSame(taa, p.activeGenerationForTest().postProcess.taaHistoryForTest()); p.execute(device);
                p.applyGtao(GtaoSettings.disabled()); assertSame(fog, p.activeGenerationForTest().volumetric); p.execute(device);
                p.applyGtao(GtaoSettings.defaults().withEnabled(true)); p.execute(device);
                var currentAo = p.activeGenerationForTest().gtao;
                p.applyVolumetricFog(VolumetricFogSettings.disabled());
                assertSame(currentAo, p.activeGenerationForTest().gtao); assertTrue(currentAo.historyValid());
                p.execute(device); assertSame(clustered, p.activeGenerationForTest().clusteredResources);
                assertSame(initial.shadowCache, p.activeGenerationForTest().shadowCache);
            }
        }
    }

    static GtaoSettings tuning(float strength, com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality quality, boolean temporal) {
        return new GtaoSettings(true, quality, 1, strength, .25f, temporal, .9f, .02f);
    }

    @Test void materialSamplerStateCannotChangeFreshGtaoPixels() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture(); var poison = com.kaleblangley.haikalat.backend.texture.Sampler.nearestRepeat()) {
                var a = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                a.build(); a.execute(new GlRenderDevice());
                float[] expected = a.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL);
                var b = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                b.build();
                for (int unit = 0; unit < 6; unit++) org.lwjgl.opengl.GL33.glBindSampler(unit, poison.id());
                org.lwjgl.opengl.GL33.glBindSampler(13, poison.id());
                b.execute(new GlRenderDevice());
                assertArrayEquals(expected, b.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL), 0f);
            }
        }
    }

    @Test void repeatedColdSamplingModesKeepFiniteNormalInputs() throws Exception {
        var hashes = new java.util.TreeMap<String, String>();
        var rows = new StringBuilder("cycle,file,sha256\n");
        try {
            for (int cycle = 0; cycle < 64; cycle++) {
                try { correctedContactSceneReferenceAcrossSamplingModes(); }
                catch (AssertionError failure) { throw new AssertionError("cold pipeline cycle=" + cycle, failure); }
                try (var files = Files.list(evidence())) {
                    var references = files.filter(p -> p.getFileName().toString().startsWith("reference-")).sorted().toList();
                    assertEquals(18, references.size());
                    for (var file : references) {
                        String name = file.getFileName().toString();
                        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                        rows.append(cycle).append(',').append(name).append(',').append(hash).append('\n');
                        if (cycle == 0) hashes.put(name, hash);
                        else assertEquals(hashes.get(name), hash, "cold pixel drift: cycle=" + cycle + " file=" + name);
                    }
                }
            }
        } finally { Files.writeString(evidence().resolve("cold-cycle-pixels.csv"), rows); }
    }

    @Test void correctedContactSceneReferenceAcrossSamplingModes() throws Exception {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture()) {
                for (var aa : new AntiAliasingMode[]{AntiAliasingMode.NONE, AntiAliasingMode.TAA, AntiAliasingMode.MSAA}) {
                    fixture.size.width = 96; fixture.size.height = 64;
                    fixture.camera.setPosition(new Vector3f(2, 1.4f, 4));
                    var p = fixture.pipeline(aa, GtaoSettings.defaults().withEnabled(true));
                    p.build(); var device = new GlRenderDevice();
                    // The mesh has a normal array; the context's generic value must never replace it.
                    org.lwjgl.opengl.GL20.glVertexAttrib3f(2, 9, 8, 7);
                    for (int frame = 0; frame < 3; frame++) {
                        if (frame == 1) fixture.camera.setPosition(new Vector3f(2.1f, 1.4f, 4));
                        if (frame == 2) { fixture.size.width = 97; fixture.size.height = 65; p.resize(97, 65); }
                        p.execute(device);
                        float[] ao = p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL);
                        saveFloats("reference-" + aa + "-" + frame + "-ao", ao);
                        saveFloats("reference-" + aa + "-" + frame + "-hdr", p.captureLinearHdrRgbaFloat());
                        try {
                            assertAo(ao);
                            float[] sharedNormal = p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_NORMAL);
                            for (int pixel = 0; pixel < sharedNormal.length; pixel += 4)
                                assertTrue(Float.isFinite(sharedNormal[pixel]) && Float.isFinite(sharedNormal[pixel + 1]),
                                        "shared producer normals must be finite");
                        } catch (AssertionError failure) {
                            saveFloats("failed-" + aa + "-" + frame + "-normal", p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_NORMAL));
                            saveFloats("failed-" + aa + "-" + frame + "-shared-normal", p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_NORMAL));
                            if (aa == AntiAliasingMode.MSAA) saveFloats("failed-" + aa + "-" + frame + "-depth", p.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_DEPTH));
                            throw new AssertionError("contact reference " + aa + " frame=" + frame, failure);
                        }
                    }
                    p.close();
                }
            }
        }
    }

    @Test void sharedSignedWorldNormalsMatchGtaoViewNormalCache() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture()) {
                var pipeline = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.defaults().withEnabled(true));
                pipeline.build(); pipeline.execute(new GlRenderDevice());
                float[] source = pipeline.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_NORMAL);
                float[] cache = pipeline.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_NORMAL);
                var view = fixture.camera.getViewMatrix(new org.joml.Matrix4f());
                int checked = 0;
                for (int y = 1; y < 31; y++) for (int x = 1; x < 47; x++) {
                    int i = (y * 48 + x) * 4, full = ((y * 2) * 96 + x * 2) * 4;
                    if (Math.abs(cache[i] - .5f) < .0001f && Math.abs(cache[i + 1] - .5f) < .0001f) continue;
                    boolean flat = true;
                    for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
                        for (int c = 0; c < 2; c++) flat &= source[full + c] == source[full + (dy * 96 + dx) * 4 + c];
                    if (!flat) continue;
                    float nx = source[full], ny = source[full + 1], nz = 1 - Math.abs(nx) - Math.abs(ny);
                    if (nz < 0) { float oldX = nx; nx = (1 - Math.abs(ny)) * Math.signum(nx); ny = (1 - Math.abs(oldX)) * Math.signum(ny); }
                    var n = view.transformDirection(new Vector3f(nx, ny, nz).normalize()).normalize();
                    if (n.z < 0) n.negate();
                    n.div(Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z));
                    assertEquals(n.x * .5f + .5f, cache[i], .002f, "world/view normal x at " + x + "," + y);
                    assertEquals(n.y * .5f + .5f, cache[i + 1], .002f, "world/view normal y at " + x + "," + y);
                    checked++;
                }
                assertTrue(checked > 30, "must inspect actual flat surface pixels");
            }
        }
    }

    @Test void maskedVertexAlphaDoesNotCreateInvisibleSurfaceOccluders() throws Exception {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var fixture = new Fixture(); var mesh = Mesh.from(coloredQuad());
                 var masked = PbrMaterials.createWithBindings(fixture.shader,
                         new PbrMaterialProperties(new Vector4f(.8f, .2f, .2f, 1), 0, .85f, 1, 1,
                                 new Vector3f(), Map.of()), Map.of(), fixture.fallbacks,
                         com.kaleblangley.haikalat.core.CullMode.NONE, false, true, .5f,
                         com.kaleblangley.haikalat.core.BlendMode.OPAQUE)) {
                var hidden = new Scene(fixture.camera);
                for (var renderer : fixture.scene.renderers()) hidden.add(renderer);
                for (var light : fixture.scene.lights()) hidden.addLight(light);
                hidden.add(MeshRenderer.of(mesh, masked, Transform.at(0, 0, 1).scale(2)));
                var device = new GlRenderDevice();
                var baseline = fixture.pipeline(AntiAliasingMode.NONE, GtaoSettings.quality(
                        com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality.MEDIUM));
                baseline.build(); baseline.execute(device); float[] expected = baseline.captureLinearHdrRgbaFloat();
                float[] expectedNormal = baseline.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_NORMAL);
                float[] expectedAo = baseline.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL);
                var candidate = fixture.pipeline(hidden, AntiAliasingMode.NONE, GtaoSettings.quality(
                        com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality.MEDIUM));
                candidate.build(); candidate.execute(device);
                assertArrayEquals(expectedNormal, candidate.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_NORMAL), 0f, "masked surface normal");
                assertArrayEquals(expectedAo, candidate.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL), 0f, "masked AO input");
                assertArrayEquals(expected, candidate.captureLinearHdrRgbaFloat(), 0f,
                        "zero vertex alpha must be discarded by the same surface/depth producer as PBR");
            }
        }
    }

    @Test void msaaResolvedSurfacePrecedesEstimateOnFirstMovingAndResizedFrames() throws Exception {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities(); GlDebug.enableDebugCallback();
            try (var fixture = new Fixture()) {
                var pipeline = fixture.pipeline(AntiAliasingMode.MSAA, GtaoSettings.defaults().withEnabled(true));
                pipeline.build();
                var device = new GlRenderDevice();
                for (int frame = 0; frame < 3; frame++) {
                    if (frame == 1) fixture.camera.setPosition(new Vector3f(2.1f, 1.4f, 4));
                    if (frame == 2) { fixture.size.width = 97; fixture.size.height = 65; pipeline.resize(97, 65); }
                    pipeline.execute(device);
                    float[] ao = pipeline.graph().readColorAttachmentRgbaFloat(PostProcessTargets.GTAO_FINAL);
                    saveFloats("msaa-" + frame, ao);
                    var order = pipeline.graph().description().executionOrder();
                    Files.writeString(evidence().resolve("msaa-order-" + frame + ".txt"), order.toString());
                    assertTrue(order.indexOf(PostProcessTargets.SCENE_SURFACE_RESOLVE_PASS)
                                    < order.indexOf(PostProcessTargets.GTAO_ESTIMATE_PASS),
                            "GTAO must sample the resolved depth/normal producer, not depend on registration order: " + order);
                    assertAo(ao);
                }
                GlDebug.assertNoError("GTAO resolved input production frames");
            }
        }
    }

    static void assertAo(float[] values) {
        float minimum = 1;
        for (int i = 0; i < values.length; i += 4) {
            assertTrue(Float.isFinite(values[i]) && values[i] >= 0 && values[i] <= 1);
            minimum = Math.min(minimum, values[i]);
        }
        assertTrue(minimum < .99f, "contact scene must produce visible AO, not an empty white texture");
    }

    static Path evidence() throws Exception {
        Path output = Path.of(System.getProperty("haikalat.test.gtaoEvidence", "build/reports/gtao-v0271/pixels"));
        Files.createDirectories(output); return output;
    }

    static void saveFloats(String name, float[] values) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) bytes.putFloat(value);
        Files.write(evidence().resolve(name + ".rgba.f32"), bytes.array());
    }

    static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(96, 64).visible(false).title("GTAO module acceptance").build();
    }

    static final class Size implements RenderWindow {
        int width = 96, height = 64;
        public int width() { return width; }
        public int height() { return height; }
    }

    static final class Fixture implements AutoCloseable {
        final java.util.List<RenderPipeline> pipelines = new java.util.ArrayList<>();
        final Size size = new Size();
        final Camera camera = new Camera(new Vector3f(2, 1.4f, 4), new Vector3f(0, 1, 0), -116, -15);
        final Scene scene = new Scene(camera);
        final PbrEnvironment environment = PbrEnvironmentLoader.load(new GlRenderDevice(),
                GtaoModularityGlTest.class, "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
        final PbrFallbackTextures fallbacks = new PbrFallbackTextures();
        final ShaderProgram shader = ShaderProgram.fromResource(GtaoModularityGlTest.class,
                "/shaders/render3d/pbr/pbr-forward.vert", "/shaders/render3d/pbr/pbr-forward.frag");
        final Mesh mesh = Mesh.from(quad());
        final Material material = PbrMaterials.create(shader, new PbrMaterialProperties(new Vector4f(.6f, .6f, .6f, 1),
                0, .85f, 1, 1, new Vector3f(), Map.of()), Map.of(), fallbacks);

        Fixture() {
            scene.add(MeshRenderer.of(mesh, material, Transform.at(0, -1, 0)
                    .rotationRadians(-(float) Math.PI / 2, 0, 0).scale(3, 3, 1)));
            scene.add(MeshRenderer.of(mesh, material, Transform.at(0, 0, -1).scale(3, 2, 1)));
            scene.add(MeshRenderer.of(mesh, material, Transform.at(0, -.5f, 0).scale(.5f)));
            scene.addLight(SceneLight.directional(new Vector3f(-1, -1, -1), new Vector3f(1), .5f));
        }

        RenderPipeline pipeline(AntiAliasingMode aa, GtaoSettings gtao) {
            return pipeline(scene, aa, gtao);
        }

        RenderPipeline pipeline(Scene content, AntiAliasingMode aa, GtaoSettings gtao) {
            var pipeline = new RenderPipeline(size, content, null, RenderSettings.builder().antiAliasingMode(aa)
                    .msaaSamples(4).toneMappingMode(ToneMappingMode.ACES).vsync(false).build(), environment)
                    .postProcessSettings(PostProcessSettings.builder().gtao(gtao).build());
            pipelines.add(pipeline); return pipeline;
        }

        @Override public void close() {
            for (int i = pipelines.size() - 1; i >= 0; i--) pipelines.get(i).close();
            material.close(); mesh.close(); shader.close(); fallbacks.close(); environment.close();
        }

        private static MeshData quad() {
            VertexLayout layout = VertexLayout.interleaved(48,
                    VertexAttribute.builder().index(0).size(3).semantic(VertexSemantic.POSITION).offsetBytes(0).build(),
                    VertexAttribute.builder().index(1).size(2).semantic(VertexSemantic.TEXCOORD_0).offsetBytes(12).build(),
                    VertexAttribute.builder().index(2).size(3).semantic(VertexSemantic.NORMAL).offsetBytes(20).build(),
                    VertexAttribute.builder().index(3).size(4).semantic(VertexSemantic.TANGENT).offsetBytes(32).build());
            return MeshData.of("gtao-contact-quad", new float[]{
                    -1,-1,0, 0,0, 0,0,1, 1,0,0,1, 1,-1,0, 1,0, 0,0,1, 1,0,0,1,
                    1,1,0, 1,1, 0,0,1, 1,0,0,1, -1,-1,0, 0,0, 0,0,1, 1,0,0,1,
                    1,1,0, 1,1, 0,0,1, 1,0,0,1, -1,1,0, 0,1, 0,0,1, 1,0,0,1}, layout);
        }
    }

    private static MeshData coloredQuad() {
        float[] source = Fixture.quad().vertices(), colored = new float[6 * 16];
        for (int vertex = 0; vertex < 6; vertex++) {
            System.arraycopy(source, vertex * 12, colored, vertex * 16, 12);
            colored[vertex * 16 + 12] = 1; colored[vertex * 16 + 13] = 1; colored[vertex * 16 + 14] = 1;
        }
        VertexLayout layout = VertexLayout.interleaved(64,
                VertexAttribute.builder().index(0).size(3).semantic(VertexSemantic.POSITION).offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(2).semantic(VertexSemantic.TEXCOORD_0).offsetBytes(12).build(),
                VertexAttribute.builder().index(2).size(3).semantic(VertexSemantic.NORMAL).offsetBytes(20).build(),
                VertexAttribute.builder().index(3).size(4).semantic(VertexSemantic.TANGENT).offsetBytes(32).build(),
                VertexAttribute.builder().index(4).size(4).semantic(VertexSemantic.COLOR_0).offsetBytes(48).build());
        return MeshData.of("zero-alpha-mask", colored, layout);
    }
}
