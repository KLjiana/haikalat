package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctBox;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctPass;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctProbeBatch;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSceneInput;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctVoxelMath;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_RG16F;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glReadBuffer;
import static org.lwjgl.opengl.GL44.glClearTexImage;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glTexStorage2D;
import static org.lwjgl.opengl.GL45.glTexSubImage2D;

/**
 * Real-GL acceptance for the bounded VCT owner.
 *
 * <p>All cases use a hidden 64×64 window and one 32³ production volume unless a
 * case explicitly reconfigures it. Geometry enters through
 * {@link VctSceneInput} (world-space triangles), not through the full scene
 * pipeline, because pipeline wiring is intentionally outside this worker's file
 * ownership. Cases cover voxel scale/bounds, thin-wall gap and attenuation,
 * dirty-region updates and budget rejection, the compute mip chain against the
 * CPU oracle, analytic uniform-enclosure convergence, roughness response,
 * HDR composite plus exact disabled restore, and transactional reconfigure.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VctGlTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final VctSettings SETTINGS = VctSettings.enabled(32, 8.0f);

    private GlfwWindow window;
    private GlRenderDevice device;

    @BeforeAll
    void setUp() {
        window = hiddenWindow();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        device = new GlRenderDevice();
    }

    @AfterAll
    void tearDown() {
        if (window != null) window.close();
    }

    @Test
    void voxelScaleBoundsAndShellCoverageMatchFixedExpectations() {
        try (VctPass pass = new VctPass(SETTINGS.withThinWallDilation(0))) {
            VctSceneInput scene = VctSceneInput.builder(1L)
                    .addBox(new Vector3f(-2.0f, -2.0f, -2.0f), new Vector3f(0.0f, 0.0f, 0.0f),
                            1.0f, 0.0f, 0.0f)
                    .build();
            CommandBuffer command = new CommandBuffer();
            VctPass.UpdateStats stats = pass.recordVoxelize(command, scene, List.of());
            execute(command, "voxelize box");
            assertTrue(stats.full());
            assertEquals(1, stats.regions());
            assertEquals(12, stats.triangles());
            assertEquals(32L * 32L * 32L, stats.baseVoxels());
            assertEquals(4681L, stats.mipVoxels());
            assertEquals(37_449L, stats.totalVoxels());
            assertTrue(pass.volumeValid());

            Texture3D volume = pass.volumeTexture();
            assertEquals(32, volume.width());
            assertEquals(32, volume.height());
            assertEquals(32, volume.depth());
            assertEquals(6, volume.mipLevels());
            assertEquals(0.5f, SETTINGS.voxelSize(), 0.0f);
            assertEquals(299_592L + 655_360L, pass.volumeBytes());

            float[] base = readLevel(volume, 0);
            int occupied = 0;
            for (int z = 0; z < 32; z++) {
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 32; x++) {
                        if (alpha(base, x, y, z) > 0.0f) occupied++;
                    }
                }
            }
            assertTrue(occupied > 40 && occupied < 240,
                    "a one-voxel shell around a 4³ face volume must stay bounded, got " + occupied);

            // Box spans voxel indices 12..15. Face-adjacent centers (0.25 from a
            // face) are covered; strictly interior centers (0.75) stay empty.
            for (int z = 12; z <= 15; z++) {
                for (int y = 12; y <= 15; y++) {
                    for (int x = 12; x <= 15; x++) {
                        float distance = distanceToBoxSurface(x, y, z);
                        float actual = alpha(base, x, y, z);
                        if (distance <= 0.26f) {
                            assertTrue(actual > 0.5f, "shell voxel (" + x + "," + y + "," + z
                                    + ") must be occupied, alpha=" + actual);
                        } else if (distance >= 0.74f) {
                            assertEquals(0.0f, actual, 0.0f, "interior voxel (" + x + "," + y
                                    + "," + z + ") must stay empty");
                        }
                    }
                }
            }
            assertEquals(0.0f, alpha(base, 0, 0, 0), 0.0f,
                    "far outside geometry must stay empty");
            GlDebug.assertNoError("voxel scale and bounds");
        }
    }

    @Test
    void thinWallGapStaysEmptyAndAdditionalWallAttenuatesCone() {
        try (VctPass pass = new VctPass(SETTINGS.withThinWallDilation(0)
                .withTrace(64, 0.0f, 0.75f))) {
            VctSceneInput twoWalls = planes(1L, 0.3f, -2.0f, 2.0f);
            CommandBuffer command = new CommandBuffer();
            pass.recordVoxelize(command, twoWalls, List.of());
            execute(command, "voxelize thin walls");
            float[] base = readLevel(pass.volumeTexture(), 0);
            int occupiedWithoutDilation = countOccupied(base);
            assertTrue(occupiedWithoutDilation > 80);
            assertEquals(0.0f, alphaAtWorld(base, 0.0f, 0.0f, 0.0f), 0.0f,
                    "the gap between two thin walls must stay empty");
            pass.reconfigure(SETTINGS.withThinWallDilation(1).withTrace(64, 0.0f, 0.75f));
            CommandBuffer dilatedCommand = new CommandBuffer();
            pass.recordVoxelize(dilatedCommand, twoWalls, List.of());
            execute(dilatedCommand, "dilate thin walls");
            float[] dilated = readLevel(pass.volumeTexture(), 0);
            assertTrue(countOccupied(dilated) > occupiedWithoutDilation,
                    "explicit dilation must grow the wall shell");
            assertEquals(0.0f, alphaAtWorld(dilated, 0.0f, 0.0f, 0.0f), 0.0f,
                    "one dilation voxel must not fill the gap");

            // A second thin wall must attenuate a specular cone more than one.
            try (VctPass wallPass = new VctPass(SETTINGS.withThinWallDilation(0)
                    .withTrace(64, 0.0f, 0.75f))) {
                VctSceneInput oneWall = planes(1L, 0.3f, 1.0f);
                CommandBuffer single = new CommandBuffer();
                wallPass.recordVoxelize(single, oneWall, List.of());
                execute(single, "voxelize one wall");
                VctProbeBatch probe = probe(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.1f);
                float singleTransmittance = specularTransmittance(wallPass, probe);

                VctSceneInput doubleWall = planes(2L, 0.3f, 1.0f, 2.0f);
                CommandBuffer doubled = new CommandBuffer();
                wallPass.recordVoxelize(doubled, doubleWall, List.of());
                execute(doubled, "voxelize two walls");
                float doubleTransmittance = specularTransmittance(wallPass, probe);
                assertTrue(singleTransmittance > 0.2f && singleTransmittance < 0.99f,
                        "one partial-coverage wall must leave measurable transmittance, got "
                                + singleTransmittance);
                assertTrue(doubleTransmittance < singleTransmittance - 0.05f,
                        "two walls must attenuate more: single=" + singleTransmittance
                                + " double=" + doubleTransmittance);
            }
            GlDebug.assertNoError("thin-wall leak control");
        }
    }

    @Test
    void dynamicDirtyUpdateClearsOldRegionAndRejectsOverBudgetWork() {
        try (VctPass pass = new VctPass(SETTINGS)) {
            VctSceneInput first = VctSceneInput.builder(1L)
                    .addBox(new Vector3f(5.0f, 0.0f, 0.0f), new Vector3f(6.0f, 1.0f, 1.0f),
                            0.0f, 1.0f, 0.0f)
                    .addBox(new Vector3f(-5.0f, 0.0f, 0.0f), new Vector3f(-4.0f, 1.0f, 1.0f),
                            1.0f, 0.0f, 0.0f)
                    .build();
            CommandBuffer full = new CommandBuffer();
            pass.recordVoxelize(full, first, List.of());
            execute(full, "voxelize moving box");
            float[] before = readLevel(pass.volumeTexture(), 0);
            assertTrue(alphaAtWorld(before, -4.25f, 0.25f, 0.25f) > 0.0f);
            assertTrue(alphaAtWorld(before, 5.75f, 0.25f, 0.25f) > 0.0f);

            VctSceneInput second = VctSceneInput.builder(2L)
                    .addBox(new Vector3f(5.0f, 0.0f, 0.0f), new Vector3f(6.0f, 1.0f, 1.0f),
                            0.0f, 1.0f, 0.0f)
                    .addBox(new Vector3f(3.0f, 0.0f, 0.0f), new Vector3f(4.0f, 1.0f, 1.0f),
                            1.0f, 0.0f, 0.0f)
                    .build();
            List<VctBox> dirty = List.of(
                    VctBox.of(-5.0f, 0.0f, 0.0f, -4.0f, 1.0f, 1.0f),
                    VctBox.of(3.0f, 0.0f, 0.0f, 4.0f, 1.0f, 1.0f));
            CommandBuffer partial = new CommandBuffer();
            VctPass.UpdateStats stats = pass.recordVoxelize(partial, second, dirty);
            assertFalse(stats.full());
            assertEquals(24, stats.triangles());
            assertTrue(stats.regions() >= 1);
            assertTrue(stats.totalVoxels() > 0L);
            execute(partial, "dirty voxel update");
            float[] after = readLevel(pass.volumeTexture(), 0);
            assertEquals(0.0f, alphaAtWorld(after, -4.25f, 0.25f, 0.25f), 0.0f,
                    "the old dirty region must be cleared");
            assertTrue(alphaAtWorld(after, 3.25f, 0.25f, 0.25f) > 0.0f,
                    "the moved box must appear");
            assertTrue(alphaAtWorld(after, 5.75f, 0.25f, 0.25f) > 0.0f,
                    "geometry outside the dirty region must be preserved");

            CommandBuffer repeat = new CommandBuffer();
            VctPass.UpdateStats noOp = pass.recordVoxelize(repeat, second, List.of());
            assertTrue(noOp.noOp());
            assertEquals(0, repeat.commandCount());

            pass.reconfigure(SETTINGS.withUpdateBudget(8, 100L));
            CommandBuffer overBudget = new CommandBuffer();
            assertThrows(IllegalArgumentException.class, () -> pass.recordVoxelize(
                    overBudget, second, List.of(VctBox.of(0.0f, 0.0f, 0.0f, 4.0f, 4.0f, 4.0f))));
            assertEquals(0, overBudget.commandCount(),
                    "budget rejection must happen before any command is recorded");
            GlDebug.assertNoError("dynamic dirty update");
        }
    }

    @Test
    void mipChainMatchesTheCpuDownsampleContract() {
        try (VctPass pass = new VctPass(SETTINGS)) {
            VctSceneInput scene = VctSceneInput.builder(1L)
                    .addBox(new Vector3f(-2.0f, -2.0f, -2.0f), new Vector3f(0.0f, 0.0f, 0.0f),
                            1.0f, 0.0f, 0.0f)
                    .build();
            CommandBuffer command = new CommandBuffer();
            pass.recordVoxelize(command, scene, List.of());
            execute(command, "voxelize mip source");
            float[] level0 = readLevel(pass.volumeTexture(), 0);
            float[] level1 = readLevel(pass.volumeTexture(), 1);
            float[] expected = VctVoxelMath.downsampleMip(level0, 32);
            assertEquals(16 * 16 * 16 * 4, level1.length);
            float maximumDifference = 0.0f;
            for (int index = 0; index < expected.length; index++) {
                maximumDifference = Math.max(maximumDifference,
                        Math.abs(expected[index] - level1[index]));
            }
            assertTrue(maximumDifference < 0.01f,
                    "GPU mip 1 must match the CPU coverage/radiance contract, max diff "
                            + maximumDifference);
            assertEquals(1.0f, alpha(level1, 6, 6, 6), 0.01f,
                    "downsampled shell coverage must stay opaque");
            GlDebug.assertNoError("mip-chain downsample");
        }
    }

    @Test
    void coneTraceConvergesToUniformEnclosureRadiance() {
        try (VctPass pass = new VctPass(SETTINGS.withConeCounts(4, 4))) {
            VctSceneInput enclosure = VctSceneInput.builder(1L)
                    .addBox(new Vector3f(-4.0f, -4.0f, -4.0f), new Vector3f(4.0f, 4.0f, 4.0f),
                            1.0f, 1.0f, 1.0f)
                    .build();
            CommandBuffer command = new CommandBuffer();
            pass.recordVoxelize(command, enclosure, List.of());
            execute(command, "voxelize enclosure");

            VctProbeBatch probe = probe(0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, -1.0f, 0.5f);
            float diffuse4 = diffuseLuminance(trace(pass, probe));
            pass.reconfigure(SETTINGS.withConeCounts(16, 4));
            float diffuse16 = diffuseLuminance(trace(pass, probe));
            pass.reconfigure(SETTINGS.withConeCounts(32, 4));
            float diffuse32 = diffuseLuminance(trace(pass, probe));

            for (float value : new float[]{diffuse4, diffuse16, diffuse32}) {
                assertTrue(Float.isFinite(value) && value > 0.5f && value < 1.25f,
                        "uniform enclosure must converge near 1.0, got " + value);
            }
            assertTrue(Math.abs(diffuse32 - 1.0f) <= Math.abs(diffuse4 - 1.0f) + 0.05f,
                    "more diffuse cones must approach the analytic enclosure radiance: 4="
                            + diffuse4 + " 32=" + diffuse32);
            GlDebug.assertNoError("cone convergence");
        }
    }

    @Test
    void specularConeRespondsToRoughness() {
        try (VctPass pass = new VctPass(SETTINGS.withThinWallDilation(0))) {
            VctSceneInput brightCube = VctSceneInput.builder(1L)
                    .addBox(new Vector3f(1.7f, -0.3f, -0.3f), new Vector3f(2.3f, 0.3f, 0.3f),
                            4.0f, 4.0f, 4.0f)
                    .build();
            CommandBuffer command = new CommandBuffer();
            pass.recordVoxelize(command, brightCube, List.of());
            execute(command, "voxelize specular target");
            VctProbeBatch probes = VctProbeBatch.builder()
                    .addProbe(new Vector3f(-2.0f, 0.0f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f),
                            new Vector3f(1.0f, 0.0f, 0.0f), 0.1f)
                    .addProbe(new Vector3f(-2.0f, 0.0f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f),
                            new Vector3f(1.0f, 0.0f, 0.0f), 0.9f)
                    .build();
            float[] result = trace(pass, probes);
            float sharp = luminance(result, 4);   // specular row of probe 0
            float rough = luminance(result, 12);  // specular row of probe 1
            assertTrue(sharp > 0.05f, "the sharp specular cone must hit the bright cube, got "
                    + sharp);
            assertTrue(Math.abs(sharp - rough) > 0.01f,
                    "roughness must change the specular cone result: sharp=" + sharp
                            + " rough=" + rough);
            GlDebug.assertNoError("specular roughness response");
        }
    }

    @Test
    void compositeAddsIndirectAndDisabledRestoresExactColor() {
        int depthTexture = 0;
        int normalTexture = 0;
        try (VctPass pass = new VctPass(SETTINGS)) {
            VctSceneInput scene = VctSceneInput.builder(1L)
                    .addBox(new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(2.0f, 2.0f, 2.0f),
                            3.0f, 3.0f, 3.0f)
                    .build();
            CommandBuffer command = new CommandBuffer();
            pass.recordVoxelize(command, scene, List.of());
            execute(command, "voxelize composite source");
            assertTrue(countOccupied(readLevel(pass.volumeTexture(), 0)) > 0,
                    "the composite fixture must have voxel radiance");
            float receiverIrradiance = diffuseLuminance(trace(pass,
                    probe(0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 1.0f,
                            0.0f, 0.0f, 1.0f, 0.5f)));
            assertTrue(receiverIrradiance > 0.0f,
                    "a probe on the receiver plane must see the voxel box, got "
                            + receiverIrradiance);

            Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 4.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
            Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT, new Matrix4f());
            Matrix4f view = camera.getViewMatrix(new Matrix4f());
            float depth = planeDepth(projection, 4.0f);
            ExternalCamera external = ExternalCamera.of(view, projection,
                    new Vector3f(0.0f, 0.0f, 4.0f), 0.0f);
            depthTexture = createDepthTexture(WIDTH, HEIGHT, depth);
            normalTexture = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(normalTexture, 1, GL_RG16F, WIDTH, HEIGHT);
            clearNormal(normalTexture, 0f, 0f); // +Z in signed octahedral coordinates.
            assertEquals(depth, readDepthTexture(depthTexture, WIDTH, HEIGHT), 1.0e-4f,
                    "the composite fixture depth upload must be readable");

            Framebuffer target = Framebuffer.fromDescriptor(FramebufferDescriptor
                    .builder(WIDTH, HEIGHT).colorTexture(GL_RGBA16F).build());
            try {
                float[] baseline = clearAndRead(target);
                CommandBuffer composite = new CommandBuffer();
                pass.recordComposite(composite, target, depthTexture, normalTexture, external, 0.5f,
                        new Vector3f(1.0f, 1.0f, 1.0f));
                execute(composite, "indirect composite");
                float[] lit = readColor(target);
                float baselineMaximum = maximumChannel(baseline);
                float litMaximum = maximumChannel(lit);
                assertTrue(litMaximum > baselineMaximum + 0.01f,
                        "voxel indirect light must be added to the HDR target: baseline="
                                + baselineMaximum + " lit=" + litMaximum);

                // The original surface stays at z=0 to the left of x=33. Only
                // the right-hand background moves, inside the old derivative quad.
                int boundary = 33;
                writeDepthRegion(depthTexture, boundary, WIDTH - boundary, planeDepth(projection, 6f));
                clearAndRead(target);
                CommandBuffer edgeComposite = new CommandBuffer();
                pass.recordComposite(edgeComposite, target, depthTexture, normalTexture, external, 0.5f,
                        new Vector3f(1f));
                execute(edgeComposite, "composite beside the depth discontinuity");
                float[] atDiscontinuity = readColor(target);
                for (int x = boundary - 3; x < boundary; x++) {
                    int red = (HEIGHT / 2 * WIDTH + x) * 4;
                    assertTrue(lit[red] > baseline[red] + 0.1f, "receiver must get indirect light at x=" + x);
                    assertEquals(lit[red], atDiscontinuity[red], 0.001f,
                            "unchanged receiver radiance must not depend on its neighbor's depth at x=" + x);
                }

                clearNormal(normalTexture, -0.75f, 0.25f);
                clearAndRead(target);
                CommandBuffer tiltedComposite = new CommandBuffer();
                pass.recordComposite(tiltedComposite, target, depthTexture, normalTexture, external, 0.5f,
                        new Vector3f(1f));
                execute(tiltedComposite, "change only the signed world normal");
                int edgeRed = (HEIGHT / 2 * WIDTH + boundary - 1) * 4;
                assertTrue(Math.abs(readColor(target)[edgeRed] - lit[edgeRed]) > 0.1f,
                        "cone radiance must respond to the supplied normal, not just the depth");
                clearNormal(normalTexture, 0f, 0f);
                writeDepthRegion(depthTexture, boundary, WIDTH - boundary, depth);

                // Keep the source inside but place the z=0 receiver 0.1 m outside.
                // Its biased cone origin can still reach the first occupied voxels.
                pass.reconfigure(SETTINGS.withBounds(new Vector3f(0f, 0f, 8.1f), 8f));
                CommandBuffer outsideVolume = new CommandBuffer();
                pass.recordVoxelize(outsideVolume, scene, List.of());
                execute(outsideVolume, "voxelize next to the outside receiver");
                clearAndRead(target);
                CommandBuffer outsideComposite = new CommandBuffer();
                pass.recordComposite(outsideComposite, target, depthTexture, normalTexture, external, 0.5f,
                        new Vector3f(1f));
                execute(outsideComposite, "composite outside the volume");
                assertArrayEquals(baseline, readColor(target), 0.0f,
                        "a receiver outside the voxel cube must keep its original HDR colour");

                pass.reconfigure(VctSettings.disabled());
                assertFalse(pass.active());
                assertEquals(null, pass.volumeTexture());
                CommandBuffer disabled = new CommandBuffer();
                pass.recordComposite(disabled, target, depthTexture, normalTexture, external, 0.5f,
                        new Vector3f(1.0f, 1.0f, 1.0f));
                assertEquals(0, disabled.commandCount(),
                        "a disabled pass must record no composite command");
                execute(new CommandBuffer()
                        .bindFramebuffer(target)
                        .viewport(0, 0, target.width(), target.height())
                        .enableScissor(false)
                        .clearColor(0.05f, 0.05f, 0.05f, 1.0f)
                        .clear(true, false), "clear before disabled check");
                execute(disabled, "disabled composite");
                assertArrayEquals(baseline, readColor(target), 0.0f,
                        "a disabled pass must not modify the cleared baseline");
            } finally {
                target.close();
            }
            GlDebug.assertNoError("indirect composite");
        } finally {
            if (normalTexture != 0) glDeleteTextures(normalTexture);
            if (depthTexture != 0) glDeleteTextures(depthTexture);
        }
    }

    @Test
    void transactionalReconfigureKeepsPublishedGenerationOnFailure() {
        VctPass pass = new VctPass(SETTINGS);
        try {
            int id32 = pass.volumeTexture().id();
            long revision32 = pass.revision();
            pass.reconfigure(SETTINGS.withResolution(64));
            assertTrue(pass.active());
            assertEquals(64, pass.volumeTexture().width());
            assertNotEquals(id32, pass.volumeTexture().id());
            assertEquals(revision32 + 1L, pass.revision());
            int id64 = pass.volumeTexture().id();
            long revision64 = pass.revision();
            pass.reconfigure(pass.settings());
            assertEquals(id64, pass.volumeTexture().id());
            assertEquals(revision64, pass.revision());

            System.setProperty("haikalat.test.failVctVolumeAllocation", "true");
            assertThrows(IllegalStateException.class,
                    () -> pass.reconfigure(pass.settings().withResolution(128)));
            assertNull(System.getProperty("haikalat.test.failVctVolumeAllocation"),
                    "the injected allocation failure must be consumed once");
            assertEquals(64, pass.settings().resolution());
            assertEquals(id64, pass.volumeTexture().id(),
                    "a failed candidate must leave the published volume active");

            pass.reconfigure(VctSettings.disabled());
            assertFalse(pass.active());
            assertEquals(null, pass.volumeTexture());
            assertEquals(0L, pass.volumeBytes());
            pass.reconfigure(VctSettings.enabled(32, 8.0f));
            assertTrue(pass.active());
            assertEquals(32, pass.volumeTexture().width());
            pass.close();
            pass.close();
            assertFalse(pass.active());
        } finally {
            System.clearProperty("haikalat.test.failVctVolumeAllocation");
            pass.close();
        }
    }

    // ---------------------------------------------------------------------

    private static VctSceneInput planes(long revision, float occupancy, float... zPlanes) {
        VctSceneInput.Builder builder = VctSceneInput.builder(revision);
        for (float z : zPlanes) {
            builder.addQuad(new Vector3f(-4.0f, -4.0f, z), new Vector3f(4.0f, -4.0f, z),
                    new Vector3f(4.0f, 4.0f, z), new Vector3f(-4.0f, 4.0f, z),
                    new Vector3f(0.0f, 0.0f, 1.0f), 1.0f, 1.0f, 1.0f, occupancy);
        }
        return builder.build();
    }

    private static VctProbeBatch probe(float px, float py, float pz,
                                       float nx, float ny, float nz,
                                       float vx, float vy, float vz, float roughness) {
        return VctProbeBatch.builder()
                .addProbe(new Vector3f(px, py, pz), new Vector3f(nx, ny, nz),
                        new Vector3f(vx, vy, vz), roughness)
                .build();
    }

    private void execute(CommandBuffer command, String label) {
        device.execute(command);
        GlDebug.assertNoError(label);
    }

    private float[] trace(VctPass pass, VctProbeBatch batch) {
        CommandBuffer command = new CommandBuffer();
        assertEquals(1, pass.recordProbeTrace(command, batch));
        execute(command, "probe cone trace");
        int floats = batch.count() * 8;
        ByteBuffer buffer = ByteBuffer.allocateDirect(floats * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        pass.probeResults().read(0L, buffer);
        buffer.rewind();
        float[] result = new float[floats];
        buffer.asFloatBuffer().get(result);
        return result;
    }

    private float specularTransmittance(VctPass pass, VctProbeBatch probe) {
        return trace(pass, probe)[7];
    }

    private static float diffuseLuminance(float[] result) {
        return luminance(result, 0);
    }

    private static float luminance(float[] result, int offset) {
        return 0.2126f * result[offset] + 0.7152f * result[offset + 1]
                + 0.0722f * result[offset + 2];
    }

    private float[] readLevel(Texture3D texture, int level) {
        int dimension = texture.levelWidth(level);
        float[] result = new float[dimension * dimension * dimension * 4];
        for (int z = 0; z < dimension; z++) {
            float[] layer = texture.readLayerRgbaFloat(level, z);
            System.arraycopy(layer, 0, result, z * dimension * dimension * 4, layer.length);
        }
        return result;
    }

    private static float alpha(float[] volume, int x, int y, int z) {
        int dimension = 32;
        while ((long) dimension * dimension * dimension * 4 != volume.length) dimension /= 2;
        return volume[((z * dimension + y) * dimension + x) * 4 + 3];
    }

    private static int countOccupied(float[] volume) {
        int count = 0;
        for (int index = 3; index < volume.length; index += 4) {
            if (volume[index] > 0.0f) count++;
        }
        return count;
    }

    private static float alphaAtWorld(float[] volume, float x, float y, float z) {
        int dimension = 32;
        while ((long) dimension * dimension * dimension * 4 != volume.length) dimension /= 2;
        float size = 16.0f / dimension;
        int ix = clamp((int) Math.floor((x + 8.0f) / size), dimension);
        int iy = clamp((int) Math.floor((y + 8.0f) / size), dimension);
        int iz = clamp((int) Math.floor((z + 8.0f) / size), dimension);
        return volume[((iz * dimension + iy) * dimension + ix) * 4 + 3];
    }

    private static int clamp(int value, int dimension) {
        return Math.max(0, Math.min(dimension - 1, value));
    }

    private static float distanceToBoxSurface(int x, int y, int z) {
        float[] centers = {-1.75f, -1.25f, -0.75f, -0.25f};
        float cx = centers[x - 12];
        float cy = centers[y - 12];
        float cz = centers[z - 12];
        float dx = Math.min(Math.abs(cx - (-2.0f)), Math.abs(0.0f - cx));
        float dy = Math.min(Math.abs(cy - (-2.0f)), Math.abs(0.0f - cy));
        float dz = Math.min(Math.abs(cz - (-2.0f)), Math.abs(0.0f - cz));
        return Math.min(dx, Math.min(dy, dz));
    }

    private static float planeDepth(Matrix4f projection, float viewDistance) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDistance, 1.0f);
        projection.transform(clip);
        float ndc = clip.z / clip.w;
        return ndc * 0.5f + 0.5f;
    }

    private static void clearNormal(int texture, float octX, float octY) {
        glClearTexImage(texture, 0, GL_RG, GL_FLOAT, new float[] {octX, octY});
        GlDebug.assertNoError("upload the constant VCT normal");
    }

    private static void writeDepthRegion(int texture, int x, int width, float depth) {
        FloatBuffer data = BufferUtils.createFloatBuffer(width * HEIGHT);
        for (int index = 0; index < width * HEIGHT; index++) data.put(depth);
        data.flip();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, 0, width, HEIGHT,
                org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, GL_FLOAT, data);
        glBindTexture(GL_TEXTURE_2D, 0);
        GlDebug.assertNoError("update only depths neighboring the VCT receiver");
    }

    private static int createDepthTexture(int width, int height, float depth) {        int texture = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_DEPTH_COMPONENT24, width, height);
            FloatBuffer data = BufferUtils.createFloatBuffer(width * height);
            for (int index = 0; index < width * height; index++) data.put(depth);
            data.flip();
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, GL_FLOAT, data);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, 0);
            GlDebug.assertNoError("vct depth texture");
            return texture;
        } catch (RuntimeException failure) {
            glDeleteTextures(texture);
            throw failure;
        }
    }

    private static float readDepthTexture(int texture, int width, int height) {
        glBindTexture(GL_TEXTURE_2D, texture);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height);
        org.lwjgl.opengl.GL11.glGetTexImage(GL_TEXTURE_2D, 0,
                org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, GL_FLOAT, pixels);
        glBindTexture(GL_TEXTURE_2D, 0);
        GlDebug.assertNoError("vct depth texture readback");
        return pixels.get(0);
    }

    private float[] clearAndRead(Framebuffer target) {
        CommandBuffer clear = new CommandBuffer()
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableScissor(false)
                .clearColor(0.05f, 0.05f, 0.05f, 1.0f)
                .clear(true, false);
        execute(clear, "clear colour target");
        return readColor(target);
    }

    /** Reads the currently bound target without clearing it (for post-composite pixels). */
    private float[] readColor(Framebuffer target) {
        execute(new CommandBuffer()
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height()), "bind colour target");
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(target.width() * target.height() * 4);
        glReadPixels(0, 0, target.width(), target.height(), GL_RGBA, GL_FLOAT, pixels);
        GlDebug.assertNoError("vct colour target read");
        float[] result = new float[pixels.capacity()];
        pixels.rewind();
        pixels.get(result);
        return result;
    }

    private static float maximumChannel(float[] image) {
        float maximum = 0.0f;
        for (int pixel = 0; pixel < image.length / 4; pixel++) {
            int offset = pixel * 4;
            maximum = Math.max(maximum, Math.max(image[offset],
                    Math.max(image[offset + 1], image[offset + 2])));
        }
        return maximum;
    }
}
