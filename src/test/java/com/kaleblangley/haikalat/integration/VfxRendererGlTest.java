package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.vfx.Decal;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.FlipbookConfig;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.RibbonEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.MeshVfx;
import com.kaleblangley.haikalat.subsystems.vfx.VfxDissolve;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaskMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.subsystems.vfx.VfxRimLight;
import com.kaleblangley.haikalat.subsystems.vfx.VfxUvFlow;
import com.kaleblangley.haikalat.subsystems.vfx.VfxVisualSet;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.glIsFramebuffer;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VfxRendererGlTest {
    @Test
    void particlesRibbonAndDecalRenderInSortedOrderAndCloseCleanly() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer();
                 EffectAsset asset = asset();
                 EffectInstance instance = asset.instantiate(123L)) {
                for (int frame = 0; frame < 10; frame++) {
                    instance.update(0.05f, new Vector3f(frame * 0.08f - 0.35f,
                            (float) Math.sin(frame * 0.3f) * 0.2f, 0.0f));
                }
                instance.spawnDecal(new Vector3f(0.0f, -0.55f, -0.05f),
                        new Vector3f(0.0f, 0.0f, 1.0f), new Vector2f(0.8f, 0.35f), 0.15f);
                instance.spawnMesh(new Matrix4f().translation(0.35f, 0.2f, -0.2f)
                        .rotateY(0.4f));
                Vector3f camera = new Vector3f(0.0f, 0.0f, 4.0f);
                EffectSnapshot snapshot = instance.snapshot(camera);
                Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                        1.0f, 0.1f, 20.0f);
                Matrix4f view = new Matrix4f().lookAt(camera, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));

                var commands = device.createCommandBuffer();
                commands.bindDefaultFramebuffer().viewport(0, 0, 32, 32)
                        .enableFramebufferSrgb(false).enableDepthTest(true).depthMask(true)
                        .clearColor(0.0f, 0.0f, 0.0f, 1.0f).clear(true, true);
                VfxRenderer.Statistics stats = renderer.record(commands, snapshot, projection, view);
                device.execute(commands);

                assertTrue(stats.particles() > 0);
                assertTrue(stats.ribbonSegments() > 0);
                assertEquals(1, stats.decals());
                assertEquals(1, stats.meshes());
                assertEquals(snapshot.primitiveCount(), stats.drawCalls());
                assertEquals((long) stats.drawCalls() * 80L, stats.uniformPayloadBytes());
                assertTrue(nonBlackPixels(readFrame()) > 4);
                assertEquals(GL_NO_ERROR, glGetError());

                renderer.close();
                assertTrue(renderer.isClosed());
                assertThrows(IllegalStateException.class,
                        () -> renderer.record(device.createCommandBuffer(), snapshot, projection, view));
            }
        }
    }

    @Test
    void rimLightAddsVisibleHdrEdgeGlowOnANormalMesh() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer()) {
                VfxRenderer.Statistics baselineStats = render(renderer, device,
                        meshSnapshot(meshMaterial(false), 0.0f, 0.0f));
                ByteBuffer baseline = readFrame();
                VfxRenderer.Statistics rimStats = render(renderer, device,
                        meshSnapshot(meshMaterial(true), 0.0f, 0.0f));
                ByteBuffer rim = readFrame();

                assertEquals(0, baselineStats.rimDraws());
                assertEquals(1, rimStats.rimDraws());
                assertTrue(colorSum(rim) > colorSum(baseline) + 500,
                        "rim must add visible HDR edge energy");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void uvFlowChangesSamplingAtFixedExplicitTime() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer()) {
                VfxMaterial material = VfxMaterial.builder("flow-mesh")
                        .texture(AssetRef.of("/vfx/particles/kenney/particle_pack/fire_01.png"))
                        .maskMode(VfxMaskMode.LUMINANCE)
                        .blendMode(BlendMode.ADDITIVE)
                        .emissiveIntensity(4.0f)
                        .uvFlow(VfxUvFlow.of(0.6f, 0.1f, 4.0f, 0.0f, 5L))
                        .build();
                EffectSnapshot atZero = texturedMeshSnapshot(material, 0.0f);
                VfxRenderer.Statistics stats = render(renderer, device, atZero);
                ByteBuffer first = readFrame();
                EffectSnapshot atTime = atZero.withFrameValues(0.4f, 0.0f, List.of());
                render(renderer, device, atTime);
                ByteBuffer second = readFrame();
                assertEquals(1, stats.uvFlowDraws());
                assertTrue(differingBytes(first, second) > 8,
                        "UV flow must move the sampled texture at a fixed explicit time");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void dissolveRestoresBaselineEndpointsAndBoundedEdgeGlow() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer()) {
                VfxMaterial material = dissolveMeshMaterial(4.0f);
                EffectSnapshot complete = dissolveSnapshot(material);
                VfxRenderer.Statistics completeStats = render(renderer, device, complete);
                int completePixels = nonBlackPixels(readFrame());
                assertEquals(1, completeStats.dissolveDraws());
                assertTrue(completePixels > 100);

                EffectSnapshot gone = complete.withFrameValues(0.0f, 1.0f, List.of());
                render(renderer, device, gone);
                assertEquals(0, nonBlackPixels(readFrame()),
                        "progress 1 must remove every covered fragment");

                EffectSnapshot edge = complete.withFrameValues(0.0f, 0.5f, List.of());
                render(renderer, device, edge);
                int edgeRed = channelSum(readFrame(), 0);
                EffectSnapshot noEdge = dissolveSnapshot(dissolveMeshMaterial(0.0f))
                        .withFrameValues(0.0f, 0.5f, List.of());
                render(renderer, device, noEdge);
                int noEdgeRed = channelSum(readFrame(), 0);
                assertTrue(edgeRed > noEdgeRed,
                        "a non-zero edge width must add visible red edge energy: edge="
                                + edgeRed + " noEdge=" + noEdgeRed
                                + " completePixels=" + completePixels);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void cancelledEffectLeavesNoPrimitivePixelsOrLights() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer();
                 EffectAsset asset = EffectAsset.builder("cancel-scene")
                         .meshes(new MeshVfx(1, 10.0f,
                                 BuiltinMeshData.texturedQuad("cancel-mesh"), 2.5f, 2.5f,
                                 new Vector4f(1.0f, 0.0f, 0.0f, 1.0f),
                                 new Vector4f(1.0f, 0.0f, 0.0f, 1.0f)))
                         .meshMaterial(VfxMaterial.builder("cancel-material")
                                 .maskMode(VfxMaskMode.WHITE)
                                 .blendMode(BlendMode.ADDITIVE)
                                 .emissiveIntensity(2.0f)
                                 .build())
                         .light(com.kaleblangley.haikalat.subsystems.vfx.EffectLight.steady(
                                 0.0f, 2.0f, new Vector3f(0.0f, 0.5f, 0.0f),
                                 new Vector3f(1.0f, 0.4f, 0.1f), 4.0f, 5.0f, 1))
                         .build();
                 EffectInstance instance = asset.instantiate(1L)) {
                instance.spawnMesh(new Matrix4f());
                instance.update(0.5f, new Vector3f());
                EffectSnapshot active = instance.snapshot(new Vector3f(0.0f, 0.0f, 4.0f));
                assertEquals(1, active.meshes().size());
                assertEquals(1, active.lights().size());
                VfxRenderer.Statistics activeStats = render(renderer, device, active);
                assertEquals(1, activeStats.meshes());
                assertTrue(nonBlackPixels(readFrame()) > 20);

                instance.cancel();
                EffectSnapshot cancelled = instance.snapshot(new Vector3f(0.0f, 0.0f, 4.0f));
                assertTrue(cancelled.meshes().isEmpty());
                assertTrue(cancelled.lights().isEmpty());
                VfxRenderer.Statistics cancelledStats = render(renderer, device, cancelled);
                assertEquals(0, cancelledStats.drawCalls());
                assertEquals(0, nonBlackPixels(readFrame()));
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void rimLightingTracksRotatedNonUniformAndMirroredMeshes() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer()) {
                Matrix4f rotated = new Matrix4f().rotateY(0.7f).rotateX(0.2f);
                int rotatedBase = renderSum(renderer, device,
                        meshSnapshot(meshMaterial(false), rotated, 0.0f, 0.0f));
                int rotatedRim = renderSum(renderer, device,
                        meshSnapshot(meshMaterial(true), rotated, 0.0f, 0.0f));
                assertTrue(rotatedRim > rotatedBase + 400,
                        "rotated mesh rim must add visible edge energy");

                Matrix4f nonUniform = new Matrix4f().scale(1.8f, 0.55f, 1.0f);
                int nonUniformBase = renderSum(renderer, device,
                        meshSnapshot(meshMaterial(false), nonUniform, 0.0f, 0.0f));
                int nonUniformRim = renderSum(renderer, device,
                        meshSnapshot(meshMaterial(true), nonUniform, 0.0f, 0.0f));
                assertTrue(nonUniformRim > nonUniformBase + 400,
                        "non-uniform scale must use the inverse-transpose normal matrix");

                Matrix4f mirrored = new Matrix4f().scale(-1.0f, 1.0f, 1.0f).rotateY(0.4f);
                int mirroredBase = renderSum(renderer, device,
                        meshSnapshot(meshMaterial(false), mirrored, 0.0f, 0.0f));
                int mirroredRim = renderSum(renderer, device,
                        meshSnapshot(meshMaterial(true), mirrored, 0.0f, 0.0f));
                assertTrue(mirroredRim > mirroredBase + 400,
                        "mirrored mesh rim must remain finite and visible");

                int sharp = renderSum(renderer, device,
                        meshSnapshot(rimMaterial(1.0f), rotated, 0.0f, 0.0f));
                int soft = renderSum(renderer, device,
                        meshSnapshot(rimMaterial(4.0f), rotated, 0.0f, 0.0f));
                assertTrue(sharp > soft + 100,
                        "higher rim exponent must narrow the edge contribution");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void flipbookSamplesOnlyItsAtlasCellAtFixedTime() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer()) {
                VfxMaterial redCell = atlasMaterial(false);
                render(renderer, device, atlasSnapshot(redCell, 0.0f));
                ByteBuffer red = readFrame();
                assertEquals(0, maximumChannel(red, 1),
                        "frame 0 must not sample the adjacent green cell");
                assertTrue(centerChannel(red, 0) > 80);
                assertTrue(centerChannel(red, 1) < 20);

                render(renderer, device, atlasSnapshot(redCell, 1.0f));
                ByteBuffer green = readFrame();
                assertEquals(0, maximumChannel(green, 0),
                        "frame 1 must not sample the red cell");
                assertTrue(centerChannel(green, 1) > 80);
                assertTrue(centerChannel(green, 0) < 20);

                render(renderer, device, atlasSnapshot(atlasMaterial(true), 0.5f));
                ByteBuffer blended = readFrame();
                assertTrue(centerChannel(blended, 0) > 24);
                assertTrue(centerChannel(blended, 1) > 24,
                        "flipbook interpolation must blend current and next cell");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    private static int renderSum(VfxRenderer renderer, GlRenderDevice device,
                                 EffectSnapshot snapshot) {
        render(renderer, device, snapshot);
        return colorSum(readFrame());
    }

    private static VfxMaterial rimMaterial(float exponent) {
        return VfxMaterial.builder("rim-exponent-" + exponent)
                .maskMode(VfxMaskMode.WHITE)
                .blendMode(BlendMode.ALPHA)
                .emissiveIntensity(0.15f)
                .rimLight(new VfxRimLight(new Vector3f(1.0f, 0.0f, 0.0f), 5.0f, exponent))
                .build();
    }

    private static VfxMaterial atlasMaterial(boolean interpolate) {
        return VfxMaterial.builder("atlas-" + interpolate)
                .texture(AssetRef.of("/vfx/test/two-cell-atlas.png"))
                .maskMode(VfxMaskMode.RGBA_COLOR)
                .blendMode(BlendMode.ALPHA)
                .flipbook(new FlipbookConfig(2, 1, 2, 1.0f, false, interpolate))
                .build();
    }

    private static EffectSnapshot atlasSnapshot(VfxMaterial material, float ageSeconds) {
        EffectSnapshot.ParticleSprite particle = new EffectSnapshot.ParticleSprite(
                1L, new Vector3f(0.0f, 0.0f, -3.0f), 32.0f, 0.0f,
                new Vector4f(1.0f), 49.0f, new Vector3f(), 0.0f, ageSeconds, 0);
        return new EffectSnapshot(List.of(particle), List.of(), List.of(), List.of(particle),
                new VfxVisualSet(material, material, material, material));
    }

    private static int centerChannel(ByteBuffer pixels, int channel) {
        return Byte.toUnsignedInt(pixels.get((16 * 32 + 16) * 4 + channel));
    }

    private static int maximumChannel(ByteBuffer pixels, int channel) {
        int maximum = 0;
        for (int offset = channel; offset < pixels.capacity(); offset += 4) {
            maximum = Math.max(maximum, Byte.toUnsignedInt(pixels.get(offset)));
        }
        return maximum;
    }

    private static VfxRenderer.Statistics render(VfxRenderer renderer, GlRenderDevice device,
                                                  EffectSnapshot snapshot) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                1.0f, 0.1f, 20.0f);
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 4.0f), new Vector3f(),
                new Vector3f(0.0f, 1.0f, 0.0f));
        var commands = device.createCommandBuffer();
        commands.bindDefaultFramebuffer().viewport(0, 0, 32, 32)
                .enableFramebufferSrgb(false).enableDepthTest(true).depthMask(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f).clear(true, true);
        VfxRenderer.Statistics stats = renderer.record(commands, snapshot, projection, view);
        device.execute(commands);
        return stats;
    }

    private static EffectSnapshot meshSnapshot(VfxMaterial material, float time, float dissolve) {
        return meshSnapshot(material, new Matrix4f(), time, dissolve);
    }

    private static EffectSnapshot meshSnapshot(VfxMaterial material, Matrix4f model,
                                               float time, float dissolve) {
        MeshVfx mesh = new MeshVfx(1, 10.0f, BuiltinMeshData.coloredCube("rim-cube"),
                1.4f, 1.4f, new Vector4f(1.0f), new Vector4f(1.0f));
        try (EffectAsset asset = EffectAsset.builder("rim-scene")
                .meshes(mesh).meshMaterial(material).build();
             EffectInstance instance = asset.instantiate(1L)) {
            instance.spawnMesh(model);
            instance.update(0.1f, new Vector3f());
            instance.dissolve(dissolve);
            return instance.snapshot(new Vector3f(0.0f, 0.0f, 4.0f))
                    .withFrameValues(time, dissolve, List.of());
        }
    }

    private static EffectSnapshot texturedMeshSnapshot(VfxMaterial material, float time) {
        MeshVfx mesh = new MeshVfx(1, 10.0f,
                BuiltinMeshData.texturedQuad("effect-mesh"), 3.0f, 3.0f,
                new Vector4f(0.0f, 0.0f, 1.0f, 1.0f),
                new Vector4f(0.0f, 0.0f, 1.0f, 1.0f));
        try (EffectAsset asset = EffectAsset.builder("mesh-scene")
                .meshes(mesh).meshMaterial(material).build();
             EffectInstance instance = asset.instantiate(1L)) {
            instance.spawnMesh(new Matrix4f());
            instance.update(0.1f, new Vector3f());
            return instance.snapshot(new Vector3f(0.0f, 0.0f, 4.0f))
                    .withFrameValues(time, 0.0f, List.of());
        }
    }

    @Test
    void threeMaterialTermsComposeOnOneMeshWithoutOverwritingEachOther() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer()) {
                EffectSnapshot base = combinedSnapshot(false, false, false);
                render(renderer, device, base);
                ByteBuffer basePixels = readFrame();
                EffectSnapshot rim = combinedSnapshot(true, false, false);
                VfxRenderer.Statistics rimStats = render(renderer, device, rim);
                ByteBuffer rimPixels = readFrame();
                EffectSnapshot flow = combinedSnapshot(false, true, false);
                VfxRenderer.Statistics flowStats = render(renderer, device, flow);
                ByteBuffer flowPixels = readFrame();
                EffectSnapshot dissolve = combinedSnapshot(false, false, true);
                VfxRenderer.Statistics dissolveStats = render(renderer, device, dissolve);
                ByteBuffer dissolvePixels = readFrame();
                EffectSnapshot combined = combinedSnapshot(true, true, true);
                VfxRenderer.Statistics combinedStats = render(renderer, device, combined);
                ByteBuffer combinedPixels = readFrame();

                assertEquals(1, rimStats.rimDraws());
                assertEquals(1, flowStats.uvFlowDraws());
                assertEquals(1, dissolveStats.dissolveDraws());
                assertEquals(1, combinedStats.rimDraws());
                assertEquals(1, combinedStats.uvFlowDraws());
                assertEquals(1, combinedStats.dissolveDraws());
                assertTrue(differingBytes(basePixels, combinedPixels) > 16,
                        "combined terms must change the base image");
                assertTrue(differingBytes(rimPixels, combinedPixels) > 16,
                        "UV flow and dissolve must survive the rim term");
                assertTrue(differingBytes(flowPixels, combinedPixels) > 16,
                        "rim and dissolve must survive the UV flow term");
                assertTrue(differingBytes(dissolvePixels, combinedPixels) > 16,
                        "rim and UV flow must survive the dissolve term");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void vfxRendererResizesAcrossBorrowedColorDepthTargetsWithoutOwningThem() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer();
                 Framebuffer small = Framebuffer.singleSampled(48, 32);
                 Framebuffer resized = Framebuffer.singleSampled(80, 48)) {
                EffectSnapshot snapshot = combinedSnapshot(true, true, true);
                ByteBuffer resizedPixels = renderTo(renderer, device, resized, snapshot);
                assertTrue(nonBlackPixels(resizedPixels) > 20,
                        "VFX must follow a resized borrowed target, pixels="
                                + nonBlackPixels(resizedPixels));
                ByteBuffer smallPixels = renderTo(renderer, device, small, snapshot);
                assertTrue(nonBlackPixels(smallPixels) > 20,
                        "VFX must render into a borrowed color/depth target");
                assertTrue(glIsFramebuffer(small.id()));
                assertTrue(glIsFramebuffer(resized.id()));
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    private static ByteBuffer renderTo(VfxRenderer renderer, GlRenderDevice device,
                                       Framebuffer target, EffectSnapshot snapshot) {
        target.bind();
        org.lwjgl.opengl.GL11.glViewport(0, 0, target.width(), target.height());
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glDepthMask(true);
        org.lwjgl.opengl.GL11.glClearDepth(1.0);
        org.lwjgl.opengl.GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                target.width() / (float) target.height(), 0.1f, 20.0f);
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 4.0f), new Vector3f(),
                new Vector3f(0.0f, 1.0f, 0.0f));
        var commands = device.createCommandBuffer();
        renderer.record(commands, snapshot, projection, view);
        device.execute(commands);
        ByteBuffer pixels = BufferUtils.createByteBuffer(
                target.width() * target.height() * 4);
        glReadPixels(0, 0, target.width(), target.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static EffectSnapshot combinedSnapshot(boolean rim, boolean flow, boolean dissolve) {
        VfxMaterial.Builder builder = VfxMaterial.builder("combined-" + rim + flow + dissolve)
                .texture(AssetRef.of("/vfx/particles/kenney/particle_pack/fire_01.png"))
                .maskMode(VfxMaskMode.LUMINANCE)
                .blendMode(BlendMode.ADDITIVE)
                .emissiveIntensity(2.5f);
        if (rim) {
            builder.rimLight(new VfxRimLight(new Vector3f(1.0f, 0.2f, 0.1f), 3.0f, 1.5f));
        }
        if (flow) {
            builder.uvFlow(VfxUvFlow.of(0.6f, 0.0f, 4.0f, 0.0f, 3L));
        }
        if (dissolve) {
            builder.dissolve(VfxDissolve.explicit(0.15f,
                    new Vector3f(0.1f, 0.8f, 1.0f), 3.0f, 6.0f, 7L));
        }
        VfxMaterial material = builder.build();
        MeshVfx mesh = new MeshVfx(1, 10.0f,
                BuiltinMeshData.texturedQuad("combined-mesh"), 2.5f, 2.5f,
                new Vector4f(1.0f), new Vector4f(1.0f));
        try (EffectAsset asset = EffectAsset.builder("combined-scene")
                .meshes(mesh).meshMaterial(material).build();
             EffectInstance instance = asset.instantiate(1L)) {
            instance.spawnMesh(new Matrix4f().rotateY(0.7f));
            instance.update(0.1f, new Vector3f());
            return instance.snapshot(new Vector3f(0.0f, 0.0f, 4.0f))
                    .withFrameValues(flow ? 0.4f : 0.0f, dissolve ? 0.4f : 0.0f, List.of());
        }
    }

    private static EffectSnapshot dissolveSnapshot(VfxMaterial material) {
        MeshVfx mesh = new MeshVfx(1, 10.0f, BuiltinMeshData.coloredCube("dissolve-cube"),
                1.5f, 1.5f, new Vector4f(0.0f, 0.0f, 1.0f, 1.0f),
                new Vector4f(0.0f, 0.0f, 1.0f, 1.0f));
        try (EffectAsset asset = EffectAsset.builder("dissolve-scene")
                .meshes(mesh).meshMaterial(material).build();
             EffectInstance instance = asset.instantiate(1L)) {
            instance.spawnMesh(new Matrix4f());
            instance.update(0.1f, new Vector3f());
            return instance.snapshot(new Vector3f(0.0f, 0.0f, 4.0f));
        }
    }

    private static VfxMaterial meshMaterial(boolean rim) {
        VfxMaterial.Builder builder = VfxMaterial.builder(rim ? "rim-on" : "rim-off")
                .maskMode(VfxMaskMode.WHITE)
                .blendMode(BlendMode.ALPHA)
                .emissiveIntensity(0.15f);
        if (rim) {
            builder.rimLight(new VfxRimLight(new Vector3f(1.0f, 0.0f, 0.0f), 5.0f, 2.0f));
        }
        return builder.build();
    }

    @Test
    void zeroStrengthRimDoesNotMoveTheDissolveCoverage() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer()) {
                render(renderer, device, dissolveSnapshot(dissolveMeshMaterial(4.0f))
                        .withFrameValues(0.0f, 0.5f, List.of()));
                ByteBuffer withoutRim = readFrame();
                render(renderer, device, dissolveSnapshot(dissolveWithRimMaterial(0.0f))
                        .withFrameValues(0.0f, 0.5f, List.of()));
                ByteBuffer zeroRim = readFrame();
                assertEquals(0, differingBytes(withoutRim, zeroRim),
                        "enabling a zero-strength rim term must not alter dissolve coverage");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    private static VfxMaterial dissolveWithRimMaterial(float rimIntensity) {
        return VfxMaterial.builder("dissolve-rim-" + rimIntensity)
                .maskMode(VfxMaskMode.WHITE)
                .blendMode(BlendMode.ALPHA)
                .emissiveIntensity(1.0f)
                .dissolve(VfxDissolve.explicit(0.2f, new Vector3f(1.0f, 0.0f, 0.0f),
                        4.0f, 4.0f, 11L))
                .rimLight(new VfxRimLight(new Vector3f(1.0f, 0.0f, 0.0f),
                        rimIntensity, 2.0f))
                .build();
    }

    private static VfxMaterial dissolveMeshMaterial(float edgeIntensity) {
        return VfxMaterial.builder("dissolve-" + edgeIntensity)
                .maskMode(VfxMaskMode.WHITE)
                .blendMode(BlendMode.ALPHA)
                .emissiveIntensity(1.0f)
                .dissolve(VfxDissolve.explicit(0.2f, new Vector3f(1.0f, 0.0f, 0.0f),
                        edgeIntensity, 4.0f, 11L))
                .build();
    }

    private static int colorSum(ByteBuffer pixels) {
        int sum = 0;
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            sum += Byte.toUnsignedInt(pixels.get(offset))
                    + Byte.toUnsignedInt(pixels.get(offset + 1))
                    + Byte.toUnsignedInt(pixels.get(offset + 2));
        }
        return sum;
    }

    private static int channelSum(ByteBuffer pixels, int channel) {
        int sum = 0;
        for (int offset = channel; offset < pixels.capacity(); offset += 4) {
            sum += Byte.toUnsignedInt(pixels.get(offset));
        }
        return sum;
    }

    private static int differingBytes(ByteBuffer first, ByteBuffer second) {
        int differences = 0;
        for (int index = 0; index < Math.min(first.capacity(), second.capacity()); index++) {
            if (first.get(index) != second.get(index)) differences++;
        }
        return differences;
    }

    private static ByteBuffer readFrame() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(32 * 32 * 4);
        glReadPixels(0, 0, 32, 32, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static int nonBlackPixels(ByteBuffer pixels) {
        int count = 0;
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            if (Byte.toUnsignedInt(pixels.get(offset))
                    + Byte.toUnsignedInt(pixels.get(offset + 1))
                    + Byte.toUnsignedInt(pixels.get(offset + 2)) > 12) count++;
        }
        return count;
    }

    private static EffectAsset asset() {
        return EffectAsset.builder("gl-vfx")
                .particles(new ParticleEmitter(32, 30.0f, 1.5f,
                        new Vector3f(0.0f, 1.0f, 0.0f), 0.3f, 0.2f, 0.8f,
                        new Vector3f(0.0f, -0.2f, 0.0f), 0.1f, 0.16f, 0.04f,
                        new Vector4f(1.0f, 0.55f, 0.1f, 0.85f),
                        new Vector4f(1.0f, 0.1f, 0.05f, 0.0f)))
                .ribbon(new RibbonEmitter(16, 1.2f, 0.02f, 0.12f, 0.02f,
                        new Vector4f(0.1f, 0.7f, 1.0f, 0.8f),
                        new Vector4f(0.1f, 0.2f, 1.0f, 0.0f)))
                .decals(new Decal(4, 2.0f,
                        new Vector4f(0.65f, 0.2f, 1.0f, 0.7f),
                        new Vector4f(0.2f, 0.05f, 0.4f, 0.0f)))
                .meshes(new MeshVfx(2, 2.0f,
                        BuiltinMeshData.texturedQuad("gl-mesh-vfx"),
                        0.7f, 1.2f,
                        new Vector4f(0.2f, 1.0f, 0.5f, 0.8f),
                        new Vector4f(0.1f, 0.3f, 1.0f, 0.0f)))
                .build();
    }
}
