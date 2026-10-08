package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.*;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Independent CPU coordinates/noise verify mixed stationary and wind-varying coefficients. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricWindGlTest {
    private static final int WIDTH = 129, HEIGHT = 65, NX = 9, NY = 5, NZ = 64;
    private static final float DT = 1f / 60;
    private static final Path RAW_OUTPUT = Path.of(System.getProperty("haikalat.volumeWindEvidenceDir",
            "build/reports/volumetric-wind-native/" + UUID.randomUUID()));
    private enum Mode {
        VOLUME_OFF_TAA_ON(false, AntiAliasingMode.TAA),
        VOLUME_ON_TAA_OFF(true, AntiAliasingMode.NONE), BOTH_ON(true, AntiAliasingMode.TAA);
        final boolean history;
        final AntiAliasingMode aa;
        Mode(boolean history, AntiAliasingMode aa) { this.history = history; this.aa = aa; }
    }

    @Test void forestProfileWindUsesSuccessfulTimeAndKeepsStationaryMediumInThreeHistoryCombinations() throws IOException {
        checkWind(new Vector3f(.12f, 0, .02f));
    }

    @Test void strongThreeAxisWindUsesSuccessfulTimeAndKeepsStationaryMediumInThreeHistoryCombinations() throws IOException {
        checkWind(new Vector3f(1.2f, .35f, -.2f));
    }

    private static void checkWind(Vector3f wind) throws IOException {
        assertTrue(Boolean.getBoolean("haikalat.internal.volume.diagnostics"));
        for (Mode mode : Mode.values()) {
            try (var window = new GlfwWindow.Builder().dimensions(WIDTH, HEIGHT).visible(false).title("Physical wind " + mode).build()) {
                window.bindContext();
                GL.createCapabilities();
                assertTrue(GlDebug.enableDebugCallback());
                try (var tracking = GlDebug.acquireResourceTracking()) {
                    var size = new VolumetricGenerationGlTest.Size();
                    size.width = WIDTH;
                    size.height = HEIGHT;
                    var global = new FogMediumSettings(.01f, new Vector3f(.3f, .4f, .5f),
                            new Vector3f(.01f, .008f, .006f), 0, .05f);
                    var stationary = new LocalFogVolume(LocalFogVolume.Shape.SPHERE, new Vector3f(1.2f, .3f, -9),
                            new Vector3f(2), .06f, new Vector3f(.8f, .2f, .4f), new Vector3f(.04f, .01f, .03f), .3f, 0, 0);
                    var noisy = new LocalFogVolume(LocalFogVolume.Shape.BOX, new Vector3f(0, 0, -14),
                            new Vector3f(3.2f, 2.4f, 9), .055f, new Vector3f(.2f, .7f, .9f), new Vector3f(.06f, .04f, .02f),
                            .25f, .42f, .65f);
                    var fog = new VolumetricFogSettings(true, 32, VolumetricFogSettings.Quality.BALANCED,
                            global, List.of(stationary, noisy), 0, mode.history, .9f, 1337, wind);
                    var pipeline = new RenderPipeline(size, new Scene(VolumetricMediumGlTest.camera(true, (float) WIDTH / HEIGHT)), null,
                            RenderSettings.builder().antiAliasingMode(mode.aa).toneMappingMode(ToneMappingMode.ACES).vsync(false).build())
                            .volumetricFog(fog);
                    float time = 0;
                    int failures = 0, captures = 0;
                    double maximumWindChange = 0, maximumError = 0;
                    long reusedHistory = 0;
                    Path rawDirectory = RAW_OUTPUT.resolve(wind.y == 0 ? "forest-speed" : "three-axis").resolve(mode.name());
                    Files.createDirectories(rawDirectory);
                    try {
                        pipeline.build();
                        var device = new GlRenderDevice();
                        var failing = failingAfterExecution(device);
                        var volume = pipeline.activeGenerationForTest().volumetric;
                        for (int frame = 0; frame < 180; frame++) {
                            if (frame == 60 || frame == 120) {
                                var previous = volume.frameState().previous();
                                var error = assertThrows(IllegalStateException.class, () -> pipeline.execute(failing, .5f));
                                assertEquals("physical wind GPU frame rejected", error.getMessage());
                                failures++;
                                assertSame(previous, volume.frameState().previous());
                                assertFalse(volume.currentOutput().available());
                            }
                            pipeline.execute(device, DT);
                            time += DT;
                            assertEquals(time, volume.frameState().previous().timeSeconds());
                            if (frame > 0 && frame != 60 && frame != 120)
                                assertEquals(VolumetricHistoryPlan.Reason.NONE, volume.historyPlan().reason(), "wind time alone must not reset the whole volume");
                            if (frame % 15 == 0 || frame == 179) {
                                captures++;
                                var check = checkCoefficients(volume, frame, time, mode.history, wind);
                                maximumError = Math.max(maximumError, check.error());
                                maximumWindChange = Math.max(maximumWindChange, check.windDelta());
                                var counters = pipeline.captureVolumetricCounters();
                                assertTrue(counters.voxels() > 0);
                                if (mode.history && frame > 0 && frame != 60 && frame != 120) {
                                    assertTrue(counters.historyAccepted() > 0, "windy frames must still reuse some actual source history");
                                    reusedHistory += counters.historyAccepted();
                                }
                                assertEquals(0, counters.nonfiniteMedium());
                                assertEquals(0, counters.limitedMedium());
                                assertEquals(0, counters.limitedSource());
                                assertEquals(0, counters.limitedIntegral());
                                float[] hdr = pipeline.captureLinearHdrRgbaFloat();
                                double energy = 0;
                                for (int p = 0; p < hdr.length; p++) {
                                    assertTrue(Float.isFinite(hdr[p]));
                                    if (p % 4 != 3) energy += hdr[p];
                                }
                                assertTrue(energy > 1, "actual native HDR must contain fog emission");
                                saveRaw(rawDirectory.resolve(String.format("%04d.vwnd.gz", frame)), time, wind, check, hdr);
                            }
                        }
                        assertEquals(2, failures);
                        assertEquals(13, captures);
                        assertTrue(maximumWindChange > .001, "physical wind must cause measurable coefficient changes at the same world point");
                        if (mode.history) assertTrue(reusedHistory > 0);
                        GlDebug.assertNoError("physical wind " + wind + " " + mode);
                        System.out.printf("physicalWind mode=%s wind=%s frames=180 gpuFailures=%d captures=%d maximumCoefficientError=%s maximumWindDelta=%s acceptedHistory=%d%n",
                                mode, wind, failures, captures, maximumError, maximumWindChange, reusedHistory);
                        System.out.println("physicalWindRaw " + rawDirectory.toAbsolutePath());
                    } finally { pipeline.close(); }
                    assertTrue(GlDebug.resources().liveResources().isEmpty());
                    assertEquals(0, GlDebug.resources().estimatedBytes());
                }
            }
        }
    }

    private record FieldCapture(float[] medium, float[] emission, float[] source, float[] filtered, double error, double windDelta) { }

    private static FieldCapture checkCoefficients(VolumetricPassBuilder volume, int frame, float time, boolean history, Vector3f wind) {
        float[] medium = VolumetricMediumGlTest.texture(volume.resources().medium());
        float[] emission = VolumetricMediumGlTest.texture(volume.resources().emission());
        float[] source = VolumetricMediumGlTest.texture(volume.resources().source());
        float[] filtered = VolumetricMediumGlTest.texture(volume.resources().historyRead());
        double phaseX = history ? (float) (.15f + .7f * radicalInverse(frame + 1, 3)) : .5;
        double phaseY = history ? (float) (.15f + .7f * radicalInverse(frame + 1, 5)) : .5;
        double phaseZ = history ? (float) (.15f + .7f * radicalInverse(frame + 1, 2)) : .5;
        double maximumError = 0, maximumWindChange = 0;
        for (int z = 0; z < NZ; z++) for (int y = 0; y < NY; y++) for (int x = 0; x < NX; x++) {
            // Independently derived orthographic coordinates; no production grid, camera inversion or sampling helper.
            double px = x * 16 + phaseX * Math.min(16, WIDTH - x * 16);
            double py = y * 16 + phaseY * Math.min(16, HEIGHT - y * 16);
            double wx = (2 * px / WIDTH - 1) * 4 * ((float) WIDTH / HEIGHT);
            double wy = (2 * py / HEIGHT - 1) * 4;
            double wz = -(z + phaseZ) * 32 / NZ;
            double[] expected = coefficients(wx, wy, wz, time, wind);
            double[] withoutWind = coefficients(wx, wy, wz, time, new Vector3f());
            int offset = ((z * NY + y) * NX + x) * 4;
            for (int c = 0; c < 4; c++) {
                maximumError = Math.max(maximumError, Math.abs(expected[c] - medium[offset + c]));
                assertEquals(expected[c], medium[offset + c], .00005 + .001 * Math.abs(expected[c]), "CPU medium x/y/z=" + x + "/" + y + "/" + z);
            }
            for (int c = 0; c < 3; c++) {
                assertEquals(expected[c + 4], emission[offset + c], .00005 + .001 * Math.abs(expected[c + 4]));
                // There are no lights. Physical current q is emission, before the independent history filter.
                assertEquals(expected[c + 4], source[offset + c], .00005 + .001 * Math.abs(expected[c + 4]));
                maximumWindChange = Math.max(maximumWindChange, Math.abs(expected[c + 4] - withoutWind[c + 4]));
            }
            assertEquals(medium[offset + 3], source[offset + 3]);
            assertEquals(medium[offset + 3], filtered[offset + 3], "history must always retain current extinction, including mixed stationary/wind fields");
            for (int c = 0; c < 4; c++) assertTrue(Float.isFinite(filtered[offset + c]));
        }
        return new FieldCapture(medium, emission, source, filtered, maximumError, maximumWindChange);
    }

    private static void saveRaw(Path path, float time, Vector3f wind, FieldCapture fields, float[] hdr) throws IOException {
        // Big-endian versioned native FP32 readback, preserving half-storage quantization as observed.
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(0x56574e44); // VWND
            out.writeInt(1);
            for (int extent : new int[]{WIDTH, HEIGHT, NX, NY, NZ}) out.writeInt(extent);
            out.writeFloat(time);
            out.writeFloat(wind.x);
            out.writeFloat(wind.y);
            out.writeFloat(wind.z);
            for (float[] values : new float[][]{fields.medium(), fields.emission(), fields.source(), fields.filtered(), hdr}) {
                out.writeInt(values.length);
                for (float value : values) out.writeFloat(value);
            }
        }
    }

    private static double[] coefficients(double x, double y, double z, double time, Vector3f wind) {
        double height = Math.exp(-y * (float) .05);
        double sigma = (float) .01 * height;
        double[] scattering = {sigma * (float) .3, sigma * (float) .4, sigma * (float) .5};
        double[] emission = {(float) .01 * height, (float) .008 * height, (float) .006 * height};
        double sphere = Math.sqrt(square(x - (float) 1.2) + square(y - (float) .3) + square(z + 9)) / 2;
        double a = edge(sphere, (float) .3);
        double box = Math.max(Math.max(Math.abs(x) / (float) 3.2, Math.abs(y) / (float) 2.4), Math.abs(z + 14) / 9);
        double b = edge(box, (float) .25) * (1 - (float) .65 + (float) .65 * noise(
                (x - wind.x * time) * (float) .42, (y - wind.y * time) * (float) .42, (z - wind.z * time) * (float) .42));
        double sa = (float) .06 * a, sb = (float) .055 * b;
        sigma += sa + sb;
        double[] albedoA = {(float) .8, (float) .2, (float) .4}, albedoB = {(float) .2, (float) .7, (float) .9};
        double[] emissionA = {(float) .04, (float) .01, (float) .03}, emissionB = {(float) .06, (float) .04, (float) .02};
        for (int c = 0; c < 3; c++) { scattering[c] += sa * albedoA[c] + sb * albedoB[c]; emission[c] += a * emissionA[c] + b * emissionB[c]; }
        return new double[]{scattering[0], scattering[1], scattering[2], sigma, emission[0], emission[1], emission[2]};
    }

    private static double noise(double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz);
        double sum = 0;
        for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            int h = (ix + dx) * 1597334677 ^ (iy + dy) * (int) 3812015801L ^ (iz + dz) * (int) 2798796415L ^ 1337;
            h = (h ^ (h >>> 16)) * (int) 2246822519L;
            h = (h ^ (h >>> 13)) * (int) 3266489917L;
            h ^= h >>> 16;
            double value = (h & 0x00ffffff) / 16777215.0;
            sum += value * (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
        }
        return sum;
    }

    private static float radicalInverse(int index, int base) {
        double result = 0, place = 1.0 / base;
        while (index > 0) { result += (index % base) * place; index /= base; place /= base; }
        return (float) result;
    }
    private static double square(double x) { return x * x; }
    private static double smooth(double x) { return x * x * (3 - 2 * x); }
    private static double edge(double radius, double falloff) {
        if (radius >= 1) return 0;
        if (radius <= 1 - falloff) return 1;
        return 1 - smooth((radius - 1 + falloff) / falloff);
    }
    private static RenderDevice failingAfterExecution(GlRenderDevice delegate) {
        return new RenderDevice() {
            public RenderBackendKind backendKind() { return delegate.backendKind(); }
            public ExecutionModel executionModel() { return delegate.executionModel(); }
            public CommandBuffer createCommandBuffer() { return delegate.createCommandBuffer(); }
            public void execute(CommandBuffer commands) { delegate.execute(commands); throw new IllegalStateException("physical wind GPU frame rejected"); }
            public void invalidateState() { delegate.invalidateState(); }
            public void transition(ResourceBarrier... barriers) { delegate.transition(barriers); }
        };
    }
}
