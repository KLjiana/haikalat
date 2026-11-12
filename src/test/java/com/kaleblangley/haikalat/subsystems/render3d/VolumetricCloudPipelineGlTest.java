package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.glClearDepth;

/**
 * Real-GL pipeline acceptance for the #40 visible cloud layer.
 *
 * <p>Every case is guarded by {@code haikalat.glSmoke=true}; the Orchestrator
 * runs it on the single GPU queue. The cases prove the production graph order,
 * that the layer contributes only inside its declared height-band region, that
 * disabling it leaves the baseline byte-identical and that the pass never
 * writes the shared opaque depth. Expected pixels come from the independent
 * pass-level reference in {@code VolumetricCloudGlTest}; this class checks the
 * wiring/composition boundary instead. GPU performance and visual sign-off are
 * {@code NOT_EVALUATED}.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricCloudPipelineGlTest {
    private static final int SIZE = 64;
    private static final String DIAGNOSTIC = "CLOUD-PIPELINE-DIAG";

    @Test
    void disabledCloudsCreateNoGraphPassAndLeaveTheBaselineByteIdentical() {
        try (GlfwWindow window = VolumetricPipelineGlFixture.window(SIZE, "Cloud wiring off")) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline baseline = new RenderPipeline(window, new Scene(camera()), null,
                        VolumetricPipelineGlFixture.hdr());
                RenderPipeline explicitDisabled = new RenderPipeline(window, new Scene(camera()),
                        null, VolumetricPipelineGlFixture.hdr())
                        .volumetricClouds(VolumetricCloudSettings.disabled())
                        .smokeSimulation(SmokeSimulationSettings.disabled())
                        .smokeRender(SmokeRenderSettings.disabled());
                try {
                    baseline.build();
                    explicitDisabled.build();
                    baseline.execute(new GlRenderDevice(), 1.0f / 60.0f);
                    explicitDisabled.execute(new GlRenderDevice(), 1.0f / 60.0f);

                    assertFalse(baseline.graph().hasPass(VolumetricCloudPass.PASS_NAME));
                    assertFalse(explicitDisabled.graph().hasPass(VolumetricCloudPass.PASS_NAME));
                    float[] baselineHdr = VolumetricPipelineGlFixture.linearHdr(baseline);
                    float[] disabledHdr = VolumetricPipelineGlFixture.linearHdr(explicitDisabled);
                    assertTrue(VolumetricPipelineGlFixture.sameArray(baselineHdr, disabledHdr),
                            "an explicitly disabled cloud layer must be an exact no-op");
                } finally {
                    explicitDisabled.close();
                    baseline.close();
                }
                VolumetricPipelineGlFixture.requireTrackedResourcesReleased();
            }
            GlDebug.assertNoError("cloud pipeline disabled no-op");
        }
    }

    @Test
    void cloudLayerContributesAboveTheLayerButNotOnDownwardRays() {
        try (GlfwWindow window = VolumetricPipelineGlFixture.window(SIZE, "Cloud wiring on")) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = new RenderPipeline(window, new Scene(camera()), null,
                        VolumetricPipelineGlFixture.hdr())
                        .volumetricClouds(clouds());
                try {
                    pipeline.build();
                    assertNotNull(pipeline.activeGenerationForTest().volumetricClouds());
                    glClearDepth(1.0f);
                    pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);

                    assertTrue(pipeline.graph().hasPass(VolumetricCloudPass.PASS_NAME),
                            "the active pipeline must declare the cloud bridge");
                    List<String> order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(PostProcessTargets.GEOMETRY_PASS)
                            < order.indexOf(VolumetricCloudPass.PASS_NAME));
                    assertTrue(order.indexOf(VolumetricCloudPass.PASS_NAME)
                            < order.indexOf(pipeline.finalPassName()));

                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    float[] source = pipeline.graph().readColorAttachmentRgbaFloat(
                            PostProcessTargets.SCENE_COLOR);
                    float[] cloud = pipeline.graph().readColorAttachmentRgbaFloat(
                            VolumetricCloudPass.COLOR_TEXTURE);
                    assertEquals(source.length, cloud.length);

                    int changed = VolumetricPipelineGlFixture.countDifferences(source, cloud);
                    int upperChanged = 0;
                    int lowerChanged = 0;
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int index = (y * width + x) * 4;
                            if (VolumetricPipelineGlFixture.samePixel(source, cloud, index)) {
                                continue;
                            }
                            if (y >= height / 2) upperChanged++;
                            else lowerChanged++;
                        }
                    }
                    String diagnostic = DIAGNOSTIC + " extent=" + width + "x" + height
                            + " changed=" + changed + " upper=" + upperChanged
                            + " lower=" + lowerChanged + " order=" + order;
                    System.out.println(diagnostic);
                    assertTrue(upperChanged > 0,
                            "the cloud layer must visibly contribute to upward rays; "
                                    + diagnostic);
                    assertEquals(0, lowerChanged,
                            "downward rays never enter the layer and must copy the source "
                                    + "byte-identically; " + diagnostic);
                } finally {
                    pipeline.close();
                }
                VolumetricPipelineGlFixture.requireTrackedResourcesReleased();
            }
            GlDebug.assertNoError("cloud pipeline contribution");
        }
    }

    @Test
    void cloudBridgeNeverWritesTheSharedOpaqueDepth() {
        try (GlfwWindow window = VolumetricPipelineGlFixture.window(SIZE, "Cloud wiring depth")) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = new RenderPipeline(window, new Scene(camera()), null,
                        VolumetricPipelineGlFixture.hdr())
                        .volumetricClouds(clouds());
                try (var host = VolumetricPipelineGlFixture.host(SIZE)) {
                    pipeline.build();
                    int[] depthTexture = new int[1];
                    VolumetricPipelineGlFixture.addDepthProbe(pipeline, depthTexture);
                    VolumetricPipelineGlFixture.seedHostDepth(host, 0.5f);
                    pipeline.execute(new GlRenderDevice(), camera(),
                            VolumetricPipelineGlFixture.borrowed(host), 1.0f / 60.0f);

                    assertTrue(depthTexture[0] > 0, "the shared depth texture must be readable");
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    float[] depth = VolumetricPipelineGlFixture.readDepth(depthTexture[0],
                            width, height);
                    for (int index = 0; index < depth.length; index++) {
                        assertEquals(0.5f, depth[index], 1.0e-5f,
                                "the cloud composition wrote shared depth at index " + index);
                    }
                } finally {
                    pipeline.close();
                }
                VolumetricPipelineGlFixture.requireTrackedResourcesReleased();
            }
            GlDebug.assertNoError("cloud pipeline depth ownership");
        }
    }

    @Test
    void resizeReallocatesTheCloudTargetsAndCloseReleasesThem() {
        try (GlfwWindow window = VolumetricPipelineGlFixture.window(SIZE, "Cloud wiring resize")) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = new RenderPipeline(window, new Scene(camera()), null,
                        VolumetricPipelineGlFixture.hdr())
                        .volumetricClouds(clouds());
                try {
                    pipeline.build();
                    var before = pipeline.activeGenerationForTest().volumetricClouds();
                    assertNotNull(before);
                    int oldId = before.id();

                    pipeline.resize(48, 32);
                    var generation = pipeline.activeGenerationForTest();
                    assertNotNull(generation.volumetricClouds());
                    assertEquals(48, generation.volumetricClouds().width());
                    assertEquals(32, generation.volumetricClouds().height());
                    assertEquals(24, generation.volumetricClouds().lowResolutionWidth());
                    assertEquals(16, generation.volumetricClouds().lowResolutionHeight());
                    assertTrue(generation.volumetricClouds().id() != oldId,
                            "resize must publish a fresh, fully allocated cloud owner");

                    pipeline.resize(48, 32);
                    assertEquals(generation.volumetricClouds().id(),
                            pipeline.activeGenerationForTest().volumetricClouds().id(),
                            "same-extent resize must stay a no-op");
                } finally {
                    pipeline.close();
                }
                VolumetricPipelineGlFixture.requireTrackedResourcesReleased();
            }
            GlDebug.assertNoError("cloud pipeline resize and close");
        }
    }

    private static VolumetricCloudSettings clouds() {
        return VolumetricCloudSettings.disabled().withEnabled(true);
    }
    private static ExternalCamera camera() {
        // Level camera below the default 900..1600 m layer: the top half looks
        // into the slab and the bottom half can never intersect it.
        return VolumetricPipelineGlFixture.camera(SIZE, SIZE,
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, -1.0f),
                60.0f, 0.125f, 10_000.0f);
    }
}
