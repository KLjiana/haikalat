package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulation;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeVolumePass;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.glClearDepth;

/**
 * Real-GL pipeline acceptance for the #43 smoke simulation and volume consumer.
 *
 * <p>Guarded by {@code haikalat.glSmoke=true} and executed only by the
 * Orchestrator's serial GPU queue. The cases prove that the simulation advances
 * at the declared compute stage, that the volume pass contributes only inside
 * its declared AABB, that disabled settings leave the baseline byte-identical
 * and that the pass never writes the shared opaque depth. Field/pixel accuracy
 * against the independent references stays in {@code SmokeSimulationGlTest};
 * this class covers the wiring boundary. GPU performance and visual sign-off
 * are {@code NOT_EVALUATED}.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SmokePipelineGlTest {
    private static final int SIZE = 64;
    private static final String DIAGNOSTIC = "SMOKE-PIPELINE-DIAG";

    @Test
    void disabledSmokeCreatesNoPassesAndLeavesTheBaselineByteIdentical() {
        try (GlfwWindow window = VolumetricPipelineGlFixture.window(SIZE, "Smoke wiring off")) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline baseline = new RenderPipeline(window, new Scene(camera()), null,
                        VolumetricPipelineGlFixture.hdr());
                RenderPipeline explicitDisabled = new RenderPipeline(window, new Scene(camera()),
                        null, VolumetricPipelineGlFixture.hdr())
                        .smokeSimulation(SmokeSimulationSettings.disabled())
                        .smokeRender(SmokeRenderSettings.disabled());
                try {
                    baseline.build();
                    explicitDisabled.build();
                    baseline.execute(new GlRenderDevice(), 1.0f / 60.0f);
                    explicitDisabled.execute(new GlRenderDevice(), 1.0f / 60.0f);

                    assertFalse(baseline.graph().hasPass(SmokeSimulation.PASS_NAME));
                    assertFalse(baseline.graph().hasPass(SmokeVolumePass.PASS_NAME));
                    assertFalse(explicitDisabled.graph().hasPass(SmokeSimulation.PASS_NAME));
                    assertFalse(explicitDisabled.graph().hasPass(SmokeVolumePass.PASS_NAME));
                    assertTrue(VolumetricPipelineGlFixture.sameArray(
                                    VolumetricPipelineGlFixture.linearHdr(baseline),
                                    VolumetricPipelineGlFixture.linearHdr(explicitDisabled)),
                            "an explicitly disabled smoke medium must be an exact no-op");
                } finally {
                    explicitDisabled.close();
                    baseline.close();
                }
                VolumetricPipelineGlFixture.requireTrackedResourcesReleased();
            }
            GlDebug.assertNoError("smoke pipeline disabled no-op");
        }
    }

    @Test
    void smokeSimulationAdvancesThenContributesInsideItsBounds() {
        try (GlfwWindow window = VolumetricPipelineGlFixture.window(SIZE, "Smoke wiring on")) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = new RenderPipeline(window, new Scene(camera()), null,
                        VolumetricPipelineGlFixture.hdr())
                        .smokeSimulation(simulationSettings())
                        .smokeRender(renderSettings());
                try {
                    pipeline.build();
                    glClearDepth(1.0f);
                    for (int frame = 0; frame < 3; frame++) {
                        pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);
                    }

                    assertTrue(pipeline.graph().hasPass(SmokeSimulation.PASS_NAME),
                            "the active pipeline must declare the simulation stage");
                    assertTrue(pipeline.graph().hasPass(SmokeVolumePass.PASS_NAME),
                            "the active pipeline must declare the volume bridge");
                    List<String> order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(SmokeSimulation.PASS_NAME)
                                    < order.indexOf(SmokeVolumePass.PASS_NAME),
                            "the simulation must run before its consumer: " + order);
                    assertTrue(order.indexOf(PostProcessTargets.GEOMETRY_PASS)
                                    < order.indexOf(SmokeVolumePass.PASS_NAME));
                    assertTrue(order.indexOf(SmokeVolumePass.PASS_NAME)
                            < order.indexOf(pipeline.finalPassName()));

                    var generation = pipeline.activeGenerationForTest();
                    assertNotNull(generation.smokeSimulation());
                    assertNotNull(generation.smokeVolume());
                    assertTrue(generation.smokeSimulation().stepCount() > 0,
                            "the fixed-step simulation must advance in the real frame");

                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    float[] source = pipeline.graph().readColorAttachmentRgbaFloat(
                            PostProcessTargets.SCENE_COLOR);
                    float[] smoke = pipeline.graph().readColorAttachmentRgbaFloat(
                            SmokeVolumePass.COLOR_TEXTURE);
                    assertEquals(source.length, smoke.length);

                    int changed = VolumetricPipelineGlFixture.countDifferences(source, smoke);
                    int borderChanged = 0;
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            if (x >= 2 && x < width - 2 && y >= 2 && y < height - 2) continue;
                            int index = (y * width + x) * 4;
                            if (!VolumetricPipelineGlFixture.samePixel(source, smoke, index)) {
                                borderChanged++;
                            }
                        }
                    }
                    String diagnostic = DIAGNOSTIC + " extent=" + width + "x" + height
                            + " steps=" + generation.smokeSimulation().stepCount()
                            + " changed=" + changed + " borderChanged=" + borderChanged
                            + " order=" + order;
                    System.out.println(diagnostic);
                    assertTrue(changed > 0,
                            "the smoke medium must visibly modify the scene colour; "
                                    + diagnostic);
                    assertEquals(0, borderChanged,
                            "the projected smoke AABB copy must leave the outer border "
                                    + "byte-identical; " + diagnostic);
                } finally {
                    pipeline.close();
                }
                VolumetricPipelineGlFixture.requireTrackedResourcesReleased();
            }
            GlDebug.assertNoError("smoke pipeline contribution");
        }
    }

    @Test
    void smokeBridgeNeverWritesTheSharedOpaqueDepth() {
        try (GlfwWindow window = VolumetricPipelineGlFixture.window(SIZE, "Smoke wiring depth")) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = new RenderPipeline(window, new Scene(camera()), null,
                        VolumetricPipelineGlFixture.hdr())
                        .smokeSimulation(simulationSettings())
                        .smokeRender(renderSettings());
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
                                "the smoke composition wrote shared depth at index " + index);
                    }
                } finally {
                    pipeline.close();
                }
                VolumetricPipelineGlFixture.requireTrackedResourcesReleased();
            }
            GlDebug.assertNoError("smoke pipeline depth ownership");
        }
    }

    @Test
    void resizeRebuildAndCloseAreTransactionalForTheSmokeOwners() {
        try (GlfwWindow window = VolumetricPipelineGlFixture.window(SIZE, "Smoke wiring resize")) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = new RenderPipeline(window, new Scene(camera()), null,
                        VolumetricPipelineGlFixture.hdr())
                        .smokeSimulation(simulationSettings())
                        .smokeRender(renderSettings());
                try {
                    pipeline.build();
                    pipeline.execute(new GlRenderDevice(), 1.0f / 60.0f);
                    SmokeSimulation simulation =
                            pipeline.activeGenerationForTest().smokeSimulation();
                    assertNotNull(simulation);
                    assertTrue(simulation.stepCount() > 0);

                    pipeline.resize(48, 32);
                    var resized = pipeline.activeGenerationForTest();
                    assertEquals(48, resized.smokeVolume().width());
                    assertEquals(32, resized.smokeVolume().height());
                    assertSame(simulation, resized.smokeSimulation(),
                            "the grid-independent simulation owner must survive resize");

                    SmokeRenderSettings changed = new SmokeRenderSettings(true, 0.3f, 0.7f, 2.0f,
                            new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.0f, 2.5f, 2.0f),
                            0.2f, 32, 0.05f, SmokeRenderSettings.Blend.ADDITIVE, 1.0f, 1.0f);
                    pipeline.applySmokeRender(changed);
                    var rebuilt = pipeline.activeGenerationForTest();
                    assertSame(simulation, rebuilt.smokeSimulation(),
                            "an unrelated render-settings rebuild must borrow the owner");
                    assertEquals(SmokeRenderSettings.Blend.ADDITIVE,
                            rebuilt.smokeVolume().settings().blend());
                    assertEquals(changed, pipeline.smokeRender());
                } finally {
                    pipeline.close();
                }
                VolumetricPipelineGlFixture.requireTrackedResourcesReleased();
            }
            GlDebug.assertNoError("smoke pipeline resize and close");
        }
    }

    private static SmokeSimulationSettings simulationSettings() {
        return new SmokeSimulationSettings(true, 16, 16, 16, new Vector3f(),
                new Vector3f(1.5f), 0.02f, new Vector3f(), 1.0f, 4.0f, 1.0f, 1.0f, 4,
                List.of(new SmokeSimulationSettings.Source(new Vector3f(), 1.0f, 4.0f, 2.0f,
                        0.0f, 0.4f)));
    }

    private static SmokeRenderSettings renderSettings() {
        return new SmokeRenderSettings(true, 0.3f, 0.7f, 2.0f,
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.0f, 2.5f, 2.0f), 0.2f,
                32, 0.05f, SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f);
    }

    private static ExternalCamera camera() {
        return VolumetricPipelineGlFixture.camera(SIZE, SIZE, new Vector3f(0.0f, 0.0f, 8.0f),
                new Vector3f(), 50.0f, 0.125f, 64.0f);
    }
}
