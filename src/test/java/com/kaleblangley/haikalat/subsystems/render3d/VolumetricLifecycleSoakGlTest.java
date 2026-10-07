package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.ExecutionModel;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.device.RenderBackendKind;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.device.ResourceBarrier;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Long-lived resources must survive real resize transactions and discarded GPU writes. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricLifecycleSoakGlTest {
    private static final int FRAMES = 3600;
    private static final int RESIZE_INTERVAL = 36;
    private static final int GPU_FAILURE_INTERVAL = 180;
    private static final float DT = 1f / 60;

    @Test void longLivedVolumeRecoversFromResizeAndGpuFailuresWithoutLeaksOrCrossViewHistory() {
        assertTrue(Boolean.getBoolean("haikalat.internal.volume.diagnostics"), "soak requires real diagnostic counters");
        try (var window = new GlfwWindow.Builder().dimensions(65, 33).visible(false)
                .title("Volume lifecycle soak").build()) {
            window.bindContext();
            GL.createCapabilities();
            assertTrue(GlDebug.enableDebugCallback(), "soak requires the GL debug callback");
            try (var tracking = GlDebug.acquireResourceTracking()) {
                var size = new VolumetricGenerationGlTest.Size();
                var otherSize = new VolumetricGenerationGlTest.Size();
                var primary = pipeline(size, .2f, AntiAliasingMode.NONE);
                var secondary = pipeline(otherSize, .6f, AntiAliasingMode.TAA);
                var device = new GlRenderDevice();
                var failing = afterGpuExecution(device);
                Map<Integer, Footprint> steadyByWidth = new HashMap<>();
                int resizeEvents = 0, allocationFailures = 0, gpuFailures = 0, secondaryFrames = 0;
                try {
                    primary.build();
                    secondary.build();
                    primary.execute(device, DT);
                    secondary.execute(device, DT);
                    secondaryFrames++;
                    var generation = primary.activeGenerationForTest();
                    var volume = generation.volumetric;
                    var otherVolume = secondary.activeGenerationForTest().volumetric;
                    int otherSource = otherVolume.resources().source().id();
                    assertNotEquals(volume.resources().source().id(), otherSource);
                    assertHdr(primary, size.width, size.height, .2f);
                    assertHdr(secondary, otherSize.width, otherSize.height, .6f);
                    steadyByWidth.put(size.width, footprint());

                    for (int frame = 0; frame < FRAMES; frame++) {
                        var otherFrame = otherVolume.frameState().previous();
                        if (frame % RESIZE_INTERVAL == 0) {
                            int nextWidth = size.width == 33 ? 65 : 33;
                            int nextHeight = nextWidth == 33 ? 17 : 33;
                            var before = footprint();
                            var oldMedium = volume.resources().medium();
                            var oldRead = volume.resources().historyRead();
                            var oldWrite = volume.resources().historyWrite();
                            int allocation = resizeEvents % 14;
                            System.setProperty("haikalat.test.failVolumeAllocation", Integer.toString(allocation));
                            try {
                                var failure = assertThrows(IllegalStateException.class,
                                        () -> primary.resize(nextWidth, nextHeight));
                                assertTrue(failure.getMessage().contains("injected volume allocation failure"));
                            } finally {
                                System.clearProperty("haikalat.test.failVolumeAllocation");
                            }
                            allocationFailures++;
                            assertEquals(before, footprint(), "failed resize must release every candidate resource");
                            assertSame(generation, primary.activeGenerationForTest());
                            assertSame(oldMedium, volume.resources().medium());
                            assertSame(oldRead, volume.resources().historyRead());
                            assertSame(oldWrite, volume.resources().historyWrite());
                            assertEquals(size.width, generation.graph.width());
                            assertEquals(size.height, generation.graph.height());
                            assertTrue(volume.currentOutput().available());

                            primary.resize(nextWidth, nextHeight);
                            size.width = nextWidth;
                            size.height = nextHeight;
                            resizeEvents++;
                            assertSame(generation, primary.activeGenerationForTest());
                            assertFalse(volume.resources().historyValid());
                            assertFalse(volume.currentOutput().available());
                        }

                        var committed = volume.frameState().previous();
                        if (frame % GPU_FAILURE_INTERVAL == 0) {
                            var before = footprint();
                            var failure = assertThrows(IllegalStateException.class, () -> primary.execute(failing, .5f));
                            assertEquals("injected failure after complete real GPU execution", failure.getMessage());
                            gpuFailures++;
                            assertSame(committed, volume.frameState().previous(), "failed GPU frame must not publish its time or camera");
                            assertFalse(volume.currentOutput().available());
                            assertFalse(volume.resources().historyValid());
                            assertEquals(before, footprint(), "discarded GPU frame must not grow resources");
                            assertSame(otherFrame, otherVolume.frameState().previous());
                            assertTrue(otherVolume.currentOutput().available());
                        }

                        primary.execute(device, DT);
                        assertTrue(volume.currentOutput().available());
                        assertTrue(volume.resources().historyValid());
                        assertEquals(committed.timeSeconds() + DT, volume.frameState().previous().timeSeconds());
                        assertSame(otherFrame, otherVolume.frameState().previous());
                        assertEquals(otherSource, otherVolume.resources().source().id());
                        assertTrue(otherVolume.currentOutput().available());

                        var current = footprint();
                        var expected = steadyByWidth.putIfAbsent(size.width, current);
                        if (expected != null) assertEquals(expected, current, "same extent must keep the same live resource count and bytes");
                        if (frame % RESIZE_INTERVAL == 0) {
                            assertHdr(primary, size.width, size.height, .2f);
                            secondary.execute(device, DT);
                            secondaryFrames++;
                            assertHdr(secondary, otherSize.width, otherSize.height, .6f);
                            GlDebug.assertNoError("volume lifecycle cycle " + resizeEvents);
                            assertEquals(current, footprint(), "readback and secondary rendering must not grow resources");
                        }
                        if ((frame + 1) % RESIZE_INTERVAL == 0) {
                            assertCounters(primary);
                            assertCounters(secondary);
                            assertEquals(current, footprint(), "temporary diagnostic snapshots must release their staging storage");
                        }
                    }
                    assertEquals(100, resizeEvents);
                    assertEquals(100, allocationFailures);
                    assertEquals(20, gpuFailures);
                    assertEquals(101, secondaryFrames);
                    assertEquals(2, steadyByWidth.size());
                    System.out.printf("volume lifecycle frames=%d resize=%d allocationFailures=%d gpuFailures=%d secondaryFrames=%d footprints=%s%n",
                            FRAMES, resizeEvents, allocationFailures, gpuFailures, secondaryFrames, steadyByWidth);
                } finally {
                    primary.close();
                    secondary.close();
                    primary.close();
                    secondary.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(), "closing both pipelines must release all tracked resources");
                assertEquals(0, GlDebug.resources().estimatedBytes());
                assertEquals(GlDebug.resources().createdCount(), GlDebug.resources().closedCount());
            }
            GlDebug.assertNoError("volume lifecycle soak closed");
        }
    }

    private static RenderPipeline pipeline(VolumetricGenerationGlTest.Size size, float emission, AntiAliasingMode aa) {
        var fog = new VolumetricFogSettings(true, 32, VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.homogeneous(.04f, new Vector3f(.7f), new Vector3f(emission)),
                List.of(), 0, true, .9f, 1337, new Vector3f());
        return new RenderPipeline(size, new Scene(VolumetricMediumGlTest.camera(true, 33f / 17)), null,
                RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES).antiAliasingMode(aa).vsync(false).build())
                .volumetricFog(fog);
    }

    private record Footprint(int resources, long bytes) { }

    private static Footprint footprint() {
        var snapshot = GlDebug.resources();
        return new Footprint(snapshot.liveResources().size(), snapshot.estimatedBytes());
    }

    private static void assertHdr(RenderPipeline pipeline, int width, int height, float emission) {
        float[] pixels = pipeline.captureLinearHdrRgbaFloat();
        assertEquals(width * height * 4, pixels.length);
        for (float value : pixels) assertTrue(Float.isFinite(value), "soak output must be finite");
        // Uniform orthographic transport has the same physical distance for every grid ray and pixel.
        // Beer-Lambert is independent of the shader recurrence, including at odd extents and with TAA jitter.
        double expected = emission * -Math.expm1(-.04 * 32) / .04;
        int center = ((height / 2) * width + width / 2) * 4;
        for (int color = 0; color < 3; color++)
            assertEquals(expected, pixels[center + color], expected * .03, "each view must retain its own physical emission");
    }

    private static void assertCounters(RenderPipeline pipeline) {
        var counters = pipeline.captureVolumetricCounters();
        assertTrue(counters.voxels() > 0, "diagnostic writes must actually have executed");
        assertTrue(counters.historyAccepted() > 0, "soak must exercise history reuse between resize events");
        assertEquals(0, counters.nonfiniteMedium());
        assertEquals(0, counters.limitedMedium());
        assertEquals(0, counters.limitedSource());
        assertEquals(0, counters.limitedIntegral());
    }

    private static RenderDevice afterGpuExecution(GlRenderDevice delegate) {
        return new RenderDevice() {
            public RenderBackendKind backendKind() { return delegate.backendKind(); }
            public ExecutionModel executionModel() { return delegate.executionModel(); }
            public CommandBuffer createCommandBuffer() { return delegate.createCommandBuffer(); }
            public void execute(CommandBuffer commands) {
                delegate.execute(commands);
                throw new IllegalStateException("injected failure after complete real GPU execution");
            }
            public void invalidateState() { delegate.invalidateState(); }
            public void transition(ResourceBarrier... barriers) { delegate.transition(barriers); }
        };
    }
}
