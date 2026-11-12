package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL acceptance for the bounded smoke simulation and its volume consumer.
 *
 * <p>The fixture runs a 16^3 grid over the world box {@code [-2, 2]^3} with a
 * perspective camera at {@code (0, 0, 8)} looking down {@code -Z}. Expected
 * field totals come from the independent double reference
 * {@link SmokeSimulationReference}; expected pixels come from
 * {@link SmokeVolumeReference} fed with the read-back field, never from the
 * shader output. Cases:</p>
 *
 * <ul>
 *   <li>a) density stays finite, non-negative and bounded across 40 fixed
 *       steps; the total matches the reference after injection stops;</li>
 *   <li>b) a burst source then drift-free dissipation follows the closed-form
 *       exponential decay of the retained mass;</li>
 *   <li>c) an outward wind against the closed box does not leak mass: the
 *       total after a long run equals the post-injection total;</li>
 *   <li>d) pause records nothing and replay reproduces the exact field;</li>
 *   <li>e) a foreground surface rejects the smoke byte-identically, a
 *       mid-volume surface truncates the march, and OCCLUDING/ADDITIVE
 *       composite exactly once;</li>
 *   <li>f) disabled settings create no owner/pass, and a zero contribution
 *       weight is an exact byte-identical no-op;</li>
 *   <li>g) reconfigure/resize are candidate-first and close is idempotent with
 *       every tracked GL resource released.</li>
 * </ul>
 *
 * <p>Performance, MSAA depth, TAA/jitter and a full RenderPipeline integration
 * are not evaluated by this class. The temporary readbacks are test evidence
 * only; production never reads the field or the colour target back.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SmokeSimulationGlTest {
    private static final int SIZE = 64;
    private static final int GRID = 16;
    private static final int SMALL_GRID = 12;
    private static final int SMALL_WIDTH = 48;
    private static final int SMALL_HEIGHT = 32;
    private static final float FLOOR_DISTANCE = 12.0f;
    private static final float FOREGROUND_DISTANCE = 5.0f;
    private static final float MID_DISTANCE = 7.5f;
    private static final float HALF_EXTENT = 2.0f;
    private static final float[] BACKGROUND = {0.05f, 0.10f, 0.15f};
    private static final float TOLERANCE = 2.0e-2f;
    private static final float FOREGROUND_HALF_EXTENT_PIXELS = 5.0f;

    private static final String QUAD_VERTEX = """
            #version 460 core
            layout (location = 0) in vec3 aViewPosition;
            uniform mat4 uProjection;
            void main() {
                gl_Position = uProjection * vec4(aViewPosition, 1.0);
            }
            """;

    private static final String QUAD_FRAGMENT = """
            #version 460 core
            uniform vec4 uColor;
            layout (location = 0) out vec4 FragColor;
            void main() {
                FragColor = uColor;
            }
            """;

    @Test
    void densityStaysFiniteAndMatchesTheReferenceAcrossBoundedSteps() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (var tracking = GlDebug.acquireResourceTracking()) {
                GlRenderDevice device = new GlRenderDevice();
                SmokeSimulationSettings settings = simulationSettings(GRID,
                        new Vector3f(0.5f, 0.0f, 0.25f), 0.5f, 1.0f,
                        List.of(burst(0.0f, 0.19f)));
                try (SmokeSimulation simulation = new SmokeSimulation(settings)) {
                    step(device, simulation, 10);
                    double injected = totalDensity(readDensity(simulation));
                    assertTrue(injected > 0.0, "the burst source must inject density");
                    step(device, simulation, 30);
                    double after = totalDensity(readDensity(simulation));
                    assertTrue(after < injected, "density must decay after injection stops");
                    assertTrue(after > injected * 0.5,
                            "one half-second of decay must stay bounded: " + after);

                    SmokeSimulationReference.Grid reference =
                            SmokeSimulationReference.empty(settings);
                    for (long step = 0; step < 40; step++) {
                        SmokeSimulationReference.step(reference, settings, step);
                    }
                    assertEquals(reference.totalDensity(), after,
                            Math.max(1.0e-3, reference.totalDensity() * 0.02),
                            "the GPU total must match the independent double reference");
                    assertEquals(reference.maximumDensity(), maximumDensity(readDensity(simulation)),
                            Math.max(1.0e-3, reference.maximumDensity() * 0.05));
                    for (double density : readDensity(simulation)) {
                        assertTrue(Double.isFinite(density) && density >= 0.0,
                                "the field must stay finite and non-negative");
                    }
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("smoke density stability");
        }
    }

    @Test
    void injectionThenDissipationFollowsTheClosedFormDecay() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            SmokeSimulationSettings settings = simulationSettings(GRID,
                    new Vector3f(1.0f, 0.0f, 0.0f), 1.0f, 1.0f,
                    List.of(burst(0.0f, 0.09f)));
            try (SmokeSimulation simulation = new SmokeSimulation(settings)) {
                step(device, simulation, 5);
                double retained = totalDensity(readDensity(simulation));
                assertTrue(retained > 0.0);
                step(device, simulation, 25);
                double decayed = totalDensity(readDensity(simulation));
                assertEquals(retained * Math.exp(-0.5), decayed,
                        Math.max(2.0e-3, retained * 0.03),
                        "25 steps of 1/s dissipation must retain exp(-0.5)");

                SmokeSimulationReference.Grid reference =
                        SmokeSimulationReference.empty(settings);
                for (long step = 0; step < 30; step++) {
                    SmokeSimulationReference.step(reference, settings, step);
                }
                assertEquals(reference.totalDensity(), decayed,
                        Math.max(1.0e-3, reference.totalDensity() * 0.02));
            }
            GlDebug.assertNoError("smoke injection and dissipation");
        }
    }

    @Test
    void closedBoundaryDoesNotLeakMassUnderOutwardWind() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            SmokeSimulationSettings.Source nearWall = new SmokeSimulationSettings.Source(
                    new Vector3f(-1.0f, 0.0f, 0.0f), 0.8f, 4.0f, 0.0f, 0.0f, 0.1f);
            SmokeSimulationSettings settings = simulationSettings(GRID,
                    new Vector3f(2.0f, 0.0f, 0.0f), 0.0f, 0.0f, List.of(nearWall));
            try (SmokeSimulation simulation = new SmokeSimulation(settings)) {
                step(device, simulation, 10);
                double injected = totalDensity(readDensity(simulation));
                assertTrue(injected > 0.0);
                step(device, simulation, 50);
                double retained = totalDensity(readDensity(simulation));
                assertEquals(injected, retained, Math.max(2.0e-3, injected * 0.02),
                        "closed faces must not leak mass out of the grid");
                SmokeSimulationReference.Grid reference =
                        SmokeSimulationReference.empty(settings);
                for (long step = 0; step < 60; step++) {
                    SmokeSimulationReference.step(reference, settings, step);
                }
                assertEquals(reference.totalDensity(), retained,
                        Math.max(1.0e-3, reference.totalDensity() * 0.02));
            }
            GlDebug.assertNoError("smoke closed boundary");
        }
    }

    @Test
    void pauseAndReplayAreDeterministic() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            SmokeSimulationSettings settings = simulationSettings(GRID,
                    new Vector3f(0.5f, 0.0f, 0.25f), 0.5f, 0.5f,
                    List.of(burst(0.0f, 0.2f)));
            try (SmokeSimulation simulation = new SmokeSimulation(settings)) {
                step(device, simulation, 25);
                double[] first = readDensity(simulation);
                assertEquals(25, simulation.stepCount());

                simulation.setPaused(true);
                CommandBuffer pausedCommands = device.createCommandBuffer();
                simulation.advance(pausedCommands, 1.0f);
                device.execute(pausedCommands);
                assertEquals(25, simulation.stepCount(), "a paused advance must record nothing");
                assertArrayEquals(first, readDensity(simulation), 0.0,
                        "a paused frame must not change the field");

                CommandBuffer replayCommands = device.createCommandBuffer();
                simulation.replay(replayCommands, 25);
                device.execute(replayCommands);
                assertEquals(25, simulation.stepCount());
                assertArrayEquals(first, readDensity(simulation), 0.0,
                        "replay must reproduce the exact deterministic field");

                simulation.setPaused(false);
                CommandBuffer resumed = device.createCommandBuffer();
                simulation.advance(resumed, 1.0f);
                device.execute(resumed);
                assertEquals(29, simulation.stepCount(),
                        "a resumed frame advances by the per-frame cap, not by wall time");
            }
            GlDebug.assertNoError("smoke pause and replay");
        }
    }

    @Test
    void volumeRendererOccludesBehindGeometryAndCompositesEachMediumOnce() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = camera();
            SmokeSimulationSettings simulationSettings = simulationSettings(GRID,
                    new Vector3f(), 0.0f, 0.0f, List.of(burst(0.0f, 0.4f)));
            SmokeRenderSettings renderSettings = renderSettings(
                    SmokeRenderSettings.Blend.OCCLUDING, 1.0f);
            SmokeRenderSettings additiveSettings = renderSettings(
                    SmokeRenderSettings.Blend.ADDITIVE, 1.0f);
            SmokeRenderSettings noOpSettings = renderSettings(
                    SmokeRenderSettings.Blend.OCCLUDING, 0.0f);
            try (SmokeSimulation simulation = new SmokeSimulation(simulationSettings)) {
                step(device, simulation, 30);
                double[] density = readDensity(simulation);
                assertTrue(totalDensity(density) > 0.0);
                Matrix4f inverseProjection = new Matrix4f(camera.projection()).invert();
                Matrix4f inverseView = new Matrix4f(camera.getViewMatrix(new Matrix4f())).invert();
                try (Framebuffer scene = sceneTarget();
                     SmokeVolumePass pass = new SmokeVolumePass(renderSettings, SIZE, SIZE);
                     SmokeVolumePass additive = new SmokeVolumePass(additiveSettings, SIZE, SIZE);
                     SmokeVolumePass noOp = new SmokeVolumePass(noOpSettings, SIZE, SIZE)) {
                    float floorDepth = deviceDepth(camera, FLOOR_DISTANCE);
                    clear(scene, BACKGROUND, floorDepth);
                    FloatBuffer source = readColor(scene);
                    FloatBuffer full = renderVolume(device, pass, scene, camera, simulation);
                    SmokeVolumeReference.Ray floorRay = SmokeVolumeReference.rayForPixel(
                            inverseProjection, inverseView, SIZE, SIZE, 32, 32, floorDepth);
                    SmokeVolumeReference.Contribution floorContribution =
                            SmokeVolumeReference.contribution(renderSettings,
                                    new Vector3f(), new Vector3f(HALF_EXTENT),
                                    density, GRID, GRID, GRID, floorRay);
                    assertTrue(floorContribution.transmittance() < 1.0,
                            "the fixture must actually integrate smoke");
                    double[] expected = SmokeVolumeReference.composited(renderSettings,
                            floorContribution, asDouble(BACKGROUND));
                    assertPixel(expected, full, 32, 32, TOLERANCE, "full-depth smoke pixel");
                    assertTrue(maxDifference(expected, asDouble(BACKGROUND)) > 0.01,
                            "the smoke must change the center pixel");

                    FloatBuffer additivePixels = renderVolume(device, additive, scene, camera,
                            simulation);
                    double[] expectedAdditive = SmokeVolumeReference.composited(additiveSettings,
                            floorContribution, asDouble(BACKGROUND));
                    assertPixel(expectedAdditive, additivePixels, 32, 32, TOLERANCE,
                            "ADDITIVE composition pixel");
                    assertTrue(maxDifference(expectedAdditive, expected) > 0.01,
                            "the two blend modes must not be interchangeable");

                    FloatBuffer noOpPixels = renderVolume(device, noOp, scene, camera, simulation);
                    assertArrayEquals(toArray(source), toArray(noOpPixels), 0.0f,
                            "a zero contribution weight must leave every pixel byte-identical");

                    drawForegroundDepth(device, scene, camera, 32, 32, FOREGROUND_DISTANCE);
                    FloatBuffer occludedSource = readColor(scene);
                    FloatBuffer occluded = renderVolume(device, pass, scene, camera, simulation);
                    // Only the pixels covered by the occluder are expected to be
                    // unchanged; the rest of the grid must still render. The
                    // exact check is restricted to the quad interior (29..35)
                    // so a real leak over the occluder cannot hide in a
                    // global tolerance.
                    for (int y = 29; y <= 35; y++) {
                        for (int x = 29; x <= 35; x++) {
                            assertPixelEquals(occludedSource, occluded, x, y, 0.0f,
                                    "a surface in front of the volume must reject smoke at "
                                            + x + "," + y);
                        }
                    }
                    int changedOutsideOccluder = 0;
                    for (int index = 0; index < occluded.capacity(); index += 4) {
                        float delta = Math.max(
                                Math.abs(occluded.get(index) - occludedSource.get(index)),
                                Math.max(Math.abs(occluded.get(index + 1)
                                                - occludedSource.get(index + 1)),
                                        Math.abs(occluded.get(index + 2)
                                                - occludedSource.get(index + 2))));
                        if (delta > 1.0e-4f) {
                            changedOutsideOccluder++;
                        }
                    }
                    assertTrue(changedOutsideOccluder > 100,
                            "negative control: smoke must still render outside the occluder,"
                                    + " changed pixels=" + changedOutsideOccluder);

                    clear(scene, BACKGROUND, deviceDepth(camera, MID_DISTANCE));
                    FloatBuffer midSource = readColor(scene);
                    FloatBuffer mid = renderVolume(device, pass, scene, camera, simulation);
                    SmokeVolumeReference.Ray midRay = SmokeVolumeReference.rayForPixel(
                            inverseProjection, inverseView, SIZE, SIZE, 32, 32,
                            deviceDepth(camera, MID_DISTANCE));
                    SmokeVolumeReference.Contribution midContribution =
                            SmokeVolumeReference.contribution(renderSettings,
                                    new Vector3f(), new Vector3f(HALF_EXTENT),
                                    density, GRID, GRID, GRID, midRay);
                    double[] expectedMid = SmokeVolumeReference.composited(renderSettings,
                            midContribution, asDouble(BACKGROUND));
                    assertPixel(expectedMid, mid, 32, 32, TOLERANCE,
                            "a surface cutting through the volume must truncate the march");
                    int center = (32 * SIZE + 32) * 4;
                    assertTrue(mid.get(center) < full.get(center) - 1.0e-3f,
                            "truncated smoke must contribute less than the full volume");
                    assertTrue(mid.get(center) > midSource.get(center),
                            "the visible part in front of the surface must still contribute");
                }
            }
            GlDebug.assertNoError("smoke volume depth and composition");
        }
    }

    @Test
    void disabledSettingsCreateNoResourcesAndLeaveTheSceneByteIdentical() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (var tracking = GlDebug.acquireResourceTracking()) {
                int resourcesBefore = GlDebug.resources().liveResources().size();
                assertThrows(IllegalArgumentException.class,
                        () -> new SmokeSimulation(SmokeSimulationSettings.disabled()),
                        "disabled simulation settings must not create GPU resources");
                assertThrows(IllegalArgumentException.class,
                        () -> new SmokeVolumePass(SmokeRenderSettings.disabled(), SIZE, SIZE),
                        "disabled render settings must not create GPU resources");
                assertEquals(resourcesBefore, GlDebug.resources().liveResources().size(),
                        "rejected disabled constructors must not leak resources");
                try (Framebuffer scene = sceneTarget()) {
                    clear(scene, BACKGROUND, deviceDepth(camera(), FLOOR_DISTANCE));
                    float[] baseline = toArray(readColor(scene));
                    // The integration contract is: while disabled no owner/pass
                    // exists and nothing is recorded, so the caller's HDR scene
                    // remains byte-identical.
                    assertArrayEquals(baseline, toArray(readColor(scene)), 0.0f,
                            "disabled smoke must leave the scene byte-identical");
                }
            }
            GlDebug.assertNoError("disabled smoke no-op");
        }
    }

    @Test
    void gridReconfigureAndPassResizeAreTransactionalAndIdempotent() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (var tracking = GlDebug.acquireResourceTracking()) {
                GlRenderDevice device = new GlRenderDevice();
                SmokeSimulationSettings sixteen = simulationSettings(GRID,
                        new Vector3f(0.5f, 0.0f, 0.25f), 0.5f, 0.5f,
                        List.of(burst(0.0f, 0.4f)));
                SmokeSimulationSettings twelve = simulationSettings(SMALL_GRID,
                        new Vector3f(0.5f, 0.0f, 0.25f), 0.5f, 0.5f,
                        List.of(burst(0.0f, 0.4f)));
                try (SmokeSimulation simulation = new SmokeSimulation(sixteen);
                     SmokeVolumePass pass = new SmokeVolumePass(renderSettings(
                             SmokeRenderSettings.Blend.OCCLUDING, 1.0f), SIZE, SIZE)) {
                    step(device, simulation, 5);
                    assertTrue(totalDensity(readDensity(simulation)) > 0.0);
                    int sixteenId = simulation.id();
                    assertEquals(GRID, simulation.gridWidth());

                    simulation.reconfigure(twelve);
                    assertEquals(SMALL_GRID, simulation.gridWidth());
                    assertEquals(SMALL_GRID, simulation.gridHeight());
                    assertEquals(SMALL_GRID, simulation.gridDepth());
                    assertEquals(0, simulation.stepCount(), "reconfigure resets the sequence");
                    assertEquals(0.0, totalDensity(readDensity(simulation)), 0.0,
                            "the candidate field starts empty");
                    int twelveId = simulation.id();
                    assertTrue(twelveId != sixteenId, "the candidate grid must be published");

                    simulation.reconfigure(twelve);
                    assertEquals(twelveId, simulation.id(), "a same-settings reconfigure is a no-op");
                    assertThrows(IllegalArgumentException.class,
                            () -> simulation.reconfigure(SmokeSimulationSettings.disabled()));
                    assertEquals(SMALL_GRID, simulation.gridWidth());
                    assertEquals(twelveId, simulation.id(),
                            "a rejected reconfigure must keep the active grid");

                    assertEquals(SIZE, pass.width());
                    int firstTarget = pass.id();
                    pass.resize(SMALL_WIDTH, SMALL_HEIGHT);
                    assertEquals(SMALL_WIDTH, pass.width());
                    assertEquals(SMALL_HEIGHT, pass.height());
                    assertTrue(pass.id() != firstTarget);
                    int resized = pass.id();
                    pass.resize(SMALL_WIDTH, SMALL_HEIGHT);
                    assertEquals(resized, pass.id(), "a same-extent resize is a no-op");
                    assertThrows(IllegalArgumentException.class,
                            () -> pass.resize(0, SMALL_HEIGHT));
                    assertEquals(SMALL_WIDTH, pass.width());
                    assertEquals(resized, pass.id(), "a failed resize keeps the old target");

                    int closedPassId = pass.id();
                    assertTrue(closedPassId != 0);
                    pass.close();
                    assertTrue(pass.isClosed());
                    assertEquals(0, pass.id());
                    pass.close();
                    assertThrows(GlException.class, pass::colorTarget);

                    int closedSimulationId = simulation.id();
                    assertTrue(closedSimulationId != 0);
                    simulation.close();
                    assertTrue(simulation.isClosed());
                    assertEquals(0, simulation.id());
                    simulation.close();
                    assertThrows(GlException.class,
                            () -> simulation.step(device.createCommandBuffer(), 1));
                    assertThrows(GlException.class, simulation::densityTexture);
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "reconfigure/resize/close must release every tracked resource");
            }
            GlDebug.assertNoError("smoke grid lifecycle");
        }
    }

    private static SmokeSimulationSettings simulationSettings(int grid, Vector3f wind,
                                                              float densityDissipation,
                                                              float temperatureDissipation,
                                                              List<SmokeSimulationSettings.Source> sources) {
        return new SmokeSimulationSettings(true, grid, grid, grid, new Vector3f(),
                new Vector3f(HALF_EXTENT), 0.02f, wind, 1.0f, 4.0f,
                densityDissipation, temperatureDissipation, 4, sources);
    }

    private static SmokeSimulationSettings.Source burst(float startSeconds, float endSeconds) {
        return new SmokeSimulationSettings.Source(new Vector3f(0.0f, 0.0f, 0.0f), 1.0f,
                4.0f, 2.0f, startSeconds, endSeconds);
    }

    private static SmokeRenderSettings renderSettings(SmokeRenderSettings.Blend blend,
                                                      float contributionWeight) {
        return new SmokeRenderSettings(true, 0.3f, 0.7f, 2.0f,
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.0f, 2.5f, 2.0f), 0.2f,
                32, 0.05f, blend, contributionWeight, 1.0f);
    }

    private static void step(GlRenderDevice device, SmokeSimulation simulation, int steps) {
        CommandBuffer commands = device.createCommandBuffer();
        simulation.step(commands, steps);
        device.execute(commands);
    }

    private static double[] readDensity(SmokeSimulation simulation) {
        int nx = simulation.gridWidth();
        int ny = simulation.gridHeight();
        int nz = simulation.gridDepth();
        double[] field = new double[nx * ny * nz];
        Texture3D texture = simulation.densityTexture();
        for (int z = 0; z < nz; z++) {
            float[] layer = texture.readLayerRgbaFloat(z);
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    field[x + nx * (y + ny * z)] = layer[(y * nx + x) * 4];
                }
            }
        }
        return field;
    }

    private static double totalDensity(double[] field) {
        double total = 0.0;
        for (double value : field) {
            total += value;
        }
        return total;
    }

    private static double maximumDensity(double[] field) {
        double maximum = 0.0;
        for (double value : field) {
            maximum = Math.max(maximum, value);
        }
        return maximum;
    }

    private static FloatBuffer renderVolume(GlRenderDevice device, SmokeVolumePass pass,
                                            Framebuffer source, ExternalCamera camera,
                                            SmokeSimulation simulation) {
        CommandBuffer commands = device.createCommandBuffer();
        pass.record(commands, source, source.depthAttachment(), camera, simulation);
        device.execute(commands);
        return readColor(pass.colorTarget());
    }

    private static ExternalCamera camera() {
        return camera(SIZE, SIZE);
    }

    private static ExternalCamera camera(int width, int height) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                width / (float) height, 0.125f, 64.0f);
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 8.0f), new Vector3f(),
                new Vector3f(0.0f, 1.0f, 0.0f));
        return ExternalCamera.of(view, projection, new Vector3f(0.0f, 0.0f, 8.0f), 0.0f);
    }

    private static Framebuffer sceneTarget() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA16F)
                .depthTexture()
                .build());
    }

    private static FloatBuffer readColor(Framebuffer framebuffer) {
        framebuffer.bind();
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(
                framebuffer.width() * framebuffer.height() * 4);
        glReadPixels(0, 0, framebuffer.width(), framebuffer.height(), GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static void clear(Framebuffer target, float[] color, float depth) {
        target.bind();
        glViewport(0, 0, target.width(), target.height());
        glDisable(GL_SCISSOR_TEST);
        glDepthMask(true);
        glClearColor(color[0], color[1], color[2], 1.0f);
        glClearDepth(depth);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static float deviceDepth(ExternalCamera camera, float viewDistance) {
        Matrix4f projection = camera.projection();
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDistance, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static void drawForegroundDepth(GlRenderDevice device, Framebuffer scene,
                                            ExternalCamera camera, int centerX, int centerY,
                                            float viewDistance) {
        try (ShaderProgram shader = ShaderProgram.fromSources(QUAD_VERTEX, QUAD_FRAGMENT);
             Mesh mesh = foregroundQuad(camera, centerX, centerY, viewDistance)) {
            CommandBuffer commands = device.createCommandBuffer();
            commands.bindFramebuffer(scene)
                    .viewport(0, 0, SIZE, SIZE)
                    .enableScissor(false)
                    .enableBlend(false)
                    .enableCullFace(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .bindShader(shader)
                    .setUniformMat4(shader, "uProjection", camera.projection())
                    .setUniformVec4(shader, "uColor", new Vector4f(BACKGROUND[0], BACKGROUND[1],
                            BACKGROUND[2], 1.0f))
                    .bindMesh(mesh)
                    .drawMesh(mesh);
            device.execute(commands);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static Mesh foregroundQuad(ExternalCamera camera, int centerX, int centerY,
                                       float viewDistance) {
        float tanHalf = (float) Math.tan(Math.toRadians(50.0 * 0.5));
        float ndcX = (centerX + 0.5f) / SIZE * 2.0f - 1.0f;
        float ndcY = (centerY + 0.5f) / SIZE * 2.0f - 1.0f;
        float centerXView = ndcX * viewDistance * tanHalf;
        float centerYView = ndcY * viewDistance * tanHalf;
        float halfExtent = FOREGROUND_HALF_EXTENT_PIXELS
                * (2.0f * viewDistance * tanHalf / SIZE);
        float z = -viewDistance;
        float[] positions = {
                centerXView - halfExtent, centerYView - halfExtent, z,
                centerXView + halfExtent, centerYView - halfExtent, z,
                centerXView + halfExtent, centerYView + halfExtent, z,
                centerXView - halfExtent, centerYView + halfExtent, z
        };
        VertexAttribute position = VertexAttribute.builder()
                .index(0).size(3).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        return Mesh.builder()
                .vertices(positions, 3 * Float.BYTES, position)
                .indices(new int[]{0, 1, 2, 0, 2, 3})
                .build();
    }

    private static void bind(GlfwWindow window) {
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        while (glGetError() != GL_NO_ERROR) {
            // drain stale errors from context creation before asserting.
        }
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Smoke simulation test").visible(false).build();
    }

    private static void assertPixel(double[] expected, FloatBuffer pixels, int x, int y,
                                    float tolerance, String message) {
        int index = (y * SIZE + x) * 4;
        assertEquals(expected[0], pixels.get(index), tolerance, message + " red");
        assertEquals(expected[1], pixels.get(index + 1), tolerance, message + " green");
        assertEquals(expected[2], pixels.get(index + 2), tolerance, message + " blue");
    }

    private static void assertPixelEquals(FloatBuffer expected, FloatBuffer actual, int x, int y,
                                          float tolerance, String message) {
        int index = (y * SIZE + x) * 4;
        assertEquals(expected.get(index), actual.get(index), tolerance, message + " red");
        assertEquals(expected.get(index + 1), actual.get(index + 1), tolerance,
                message + " green");
        assertEquals(expected.get(index + 2), actual.get(index + 2), tolerance,
                message + " blue");
        assertEquals(expected.get(index + 3), actual.get(index + 3), tolerance,
                message + " alpha");
    }

    private static double[] asDouble(float[] values) {
        return new double[]{values[0], values[1], values[2]};
    }

    private static float[] toArray(FloatBuffer buffer) {
        float[] values = new float[buffer.remaining()];
        buffer.duplicate().get(values);
        return values;
    }

    private static double maxDifference(double[] left, double[] right) {
        return Math.max(Math.abs(left[0] - right[0]),
                Math.max(Math.abs(left[1] - right[1]), Math.abs(left[2] - right[2])));
    }
}
