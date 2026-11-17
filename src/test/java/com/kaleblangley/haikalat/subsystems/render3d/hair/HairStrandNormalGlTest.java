package com.kaleblangley.haikalat.subsystems.render3d.hair;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL acceptance for the hair normal channel (#46 v1).
 *
 * <p>The fixture owns a single-sample RG16F normal attachment and a shared
 * depth attachment prefilled with an exact sentinel. Each case calls the
 * production {@link HairStrandPass#recordNormals} once, reads the attachment
 * back with {@code GL_RGBA/GL_FLOAT} and checks the written octahedral values
 * against an independent double-precision reference:</p>
 *
 * <ul>
 *   <li>a straight lock in front of the camera must write the analytic ribbon
 *       normal (world +Z) on covered pixels and must not touch uncovered
 *       pixels (the sentinel survives);</li>
 *   <li>rotating the lock 90 degrees about Y must rotate the written normals to
 *       world +X while still matching the analytic tangent/view reference;</li>
 *   <li>a CUTOUT alpha cutoff must write only the fragments the forward pass
 *       would keep: the root region below the cutoff keeps the sentinel;</li>
 *   <li>a disabled settings value must be an exact no-command no-op.</li>
 * </ul>
 *
 * <p>Every case ends with {@code glGetError == GL_NO_ERROR} and asserts that
 * the pass and its target release every tracked GL resource. The guard matches
 * {@code HairStrandGlTest}: plain {@code test} skips the class; {@code glSmoke}
 * runs it. No production helper computes the expected values: the reference
 * below evaluates the model transform, the ribbon plane and the shared
 * octahedral encoding in {@code double}.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class HairStrandNormalGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final float FOV_Y = (float) Math.toRadians(55.0);
    /** Exact half-float values, so an untouched pixel reads back bit-identically. */
    private static final float SENTINEL_U = 5.0f;
    private static final float SENTINEL_V = 7.0f;
    /** Half-float storage and interpolation error bound for one encoded component. */
    private static final float ENCODED_TOLERANCE = 2.0e-3f;
    /** Decoded world-normal component tolerance (dominated by the RG16F encode). */
    private static final float NORMAL_TOLERANCE = 5.0e-3f;
    private static final double EYE_X = 0.5;
    private static final double EYE_Y = 0.0;
    private static final double EYE_Z = 2.5;
    private static final Matrix4f STATIC_MODEL = new Matrix4f().translate(-0.5f, 0.0f, 0.0f);
    private static final Matrix4f ROTATED_MODEL = new Matrix4f()
            .translate(-0.6f, 0.0f, 0.0f)
            .rotateY((float) (Math.PI * 0.5));

    @Test
    void staticLockWritesTheAnalyticRibbonNormalAndKeepsUncoveredPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            GlDebug.resourceTracking(true);
            try {
                HairStrandSettings settings = straightLock(1.0f, 0.35f);
                ExternalCamera camera = camera();
                try (Fixture fixture = fixture(settings)) {
                    prefill(device, fixture.target());
                    float[] pixels = record(device, fixture, camera, STATIC_MODEL, settings);

                    double[] expected = analyticRibbonNormal(STATIC_MODEL);
                    int covered = 0;
                    int untouched = 0;
                    int firstCovered = -1;
                    for (int index = 0; index < WIDTH * HEIGHT; index++) {
                        float u = pixels[index * 4];
                        float v = pixels[index * 4 + 1];
                        if (isSentinel(u, v)) {
                            untouched++;
                            continue;
                        }
                        if (firstCovered < 0) {
                            firstCovered = index;
                        }
                        covered++;
                        assertEncodedNormal(pixels, index, expected,
                                "static lock pixel " + index);
                    }
                    System.out.println("[hair-normal] static covered=" + covered
                            + " untouched=" + untouched
                            + " firstEncoded=(" + pixels[firstCovered * 4] + ", "
                            + pixels[firstCovered * 4 + 1] + ")"
                            + " expected=" + java.util.Arrays.toString(expected));
                    assertTrue(covered > 200,
                            "the straight lock must rasterize a real ribbon, covered=" + covered);
                    assertTrue(untouched > 10_000,
                            "pixels outside the ribbon must keep the sentinel, untouched="
                                    + untouched);
                    assertEquals(0.0f, expected[0], 1.0e-9,
                            "the static fixture's analytic normal must be world +Z");
                    assertEquals(0.0f, expected[1], 1.0e-9);
                    assertEquals(1.0f, expected[2], 1.0e-9);
                }
                assertCleanResources();
            } finally {
                GlDebug.resourceTracking(false);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void rotatingTheLockRotatesTheWrittenNormals() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            GlDebug.resourceTracking(true);
            try {
                HairStrandSettings settings = straightLock(1.0f, 0.35f);
                ExternalCamera camera = camera();
                try (Fixture fixture = fixture(settings)) {
                    prefill(device, fixture.target());
                    float[] pixels = record(device, fixture, camera, ROTATED_MODEL, settings);

                    double[] expected = analyticRibbonNormal(ROTATED_MODEL);
                    assertTrue(Math.abs(expected[0] - 1.0) < 1.0e-6
                                    && Math.abs(expected[1]) < 1.0e-6
                                    && Math.abs(expected[2]) < 1.0e-6,
                            "a 90 degree Y rotation must map the ribbon normal to world +X, was "
                                    + java.util.Arrays.toString(expected));
                    int covered = 0;
                    int firstCovered = -1;
                    for (int index = 0; index < WIDTH * HEIGHT; index++) {
                        float u = pixels[index * 4];
                        float v = pixels[index * 4 + 1];
                        if (isSentinel(u, v)) {
                            continue;
                        }
                        if (firstCovered < 0) {
                            firstCovered = index;
                        }
                        covered++;
                        assertEncodedNormal(pixels, index, expected,
                                "rotated lock pixel " + index);
                    }
                    System.out.println("[hair-normal] rotated covered=" + covered
                            + " firstEncoded=(" + pixels[firstCovered * 4] + ", "
                            + pixels[firstCovered * 4 + 1] + ")"
                            + " expected=" + java.util.Arrays.toString(expected));
                    assertTrue(covered > 40,
                            "the rotated lock must still rasterize a ribbon, covered="
                                    + covered);
                }
                assertCleanResources();
            } finally {
                GlDebug.resourceTracking(false);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void cutoutWritesOnlyTheFragmentsTheForwardPassWouldKeep() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            GlDebug.resourceTracking(true);
            try {
                HairStrandSettings full = straightLock(1.0f, 0.35f);
                HairStrandSettings cutout = straightLock(0.5f, 0.5f);
                assertTrue(cutout.transparency() == HairStrandSettings.Transparency.CUTOUT,
                        "this case exists to exercise the forward CUTOUT cutoff");
                ExternalCamera camera = camera();
                try (Fixture fullFixture = fixture(full);
                     Fixture cutoutFixture = fixture(cutout)) {
                    prefill(device, fullFixture.target());
                    float[] fullPixels = record(device, fullFixture, camera, STATIC_MODEL, full);
                    prefill(device, cutoutFixture.target());
                    float[] cutoutPixels = record(device, cutoutFixture, camera, STATIC_MODEL,
                            cutout);

                    int fullCovered = 0;
                    int cutoutCovered = 0;
                    int rootPixels = 0;
                    int rootPixelsKept = 0;
                    int survivingPixels = 0;
                    for (int index = 0; index < WIDTH * HEIGHT; index++) {
                        boolean fullIsSentinel = isSentinel(fullPixels[index * 4],
                                fullPixels[index * 4 + 1]);
                        boolean cutoutIsSentinel = isSentinel(cutoutPixels[index * 4],
                                cutoutPixels[index * 4 + 1]);
                        if (!fullIsSentinel) {
                            fullCovered++;
                        }
                        if (!cutoutIsSentinel) {
                            cutoutCovered++;
                        }
                        if (fullIsSentinel) {
                            assertTrue(cutoutIsSentinel,
                                    "the cutoff frame must not cover a pixel the full profile "
                                            + "never rasterized, pixel " + index);
                            continue;
                        }
                        // The straight lock lies on world z=0 and the camera looks down -Z,
                        // so the depth is constant and the pixel column determines the
                        // world x (and therefore the strand parameter) analytically.
                        double parameter = worldX(index % WIDTH) + 0.5;
                        if (parameter <= 0.28) {
                            rootPixels++;
                            assertTrue(cutoutIsSentinel,
                                    "sub-cutoff root pixels must discard at pixel " + index
                                            + " parameter=" + parameter);
                            rootPixelsKept++;
                        } else if (parameter >= 0.32) {
                            survivingPixels++;
                            assertTrue(!cutoutIsSentinel,
                                    "surviving fragments must still write the normal at pixel "
                                            + index + " parameter=" + parameter);
                        }
                        if (!cutoutIsSentinel) {
                            assertEncodedNormal(cutoutPixels, index,
                                    analyticRibbonNormal(STATIC_MODEL),
                                    "CUTOUT surviving pixel " + index);
                        }
                    }
                    assertTrue(fullCovered > 200, "full profile coverage=" + fullCovered);
                    assertTrue(rootPixels > 40,
                            "the fixture must rasterize a measurable sub-cutoff root region, was "
                                    + rootPixels);
                    assertEquals(rootPixels, rootPixelsKept,
                            "every sub-cutoff root pixel must keep the sentinel");
                    assertTrue(survivingPixels > 100,
                            "the fixture must rasterize a measurable surviving region, was "
                                    + survivingPixels);
                    assertTrue(cutoutCovered < fullCovered,
                            "the CUTOUT profile must cover fewer pixels than the full profile: "
                                    + cutoutCovered + " vs " + fullCovered);
                    assertTrue(cutoutCovered >= survivingPixels
                                    && cutoutCovered <= fullCovered - rootPixels,
                            "the CUTOUT frame must write exactly the surviving fragments: cutout="
                                    + cutoutCovered + " surviving=" + survivingPixels
                                    + " full=" + fullCovered + " root=" + rootPixels);
                    System.out.println("[hair-normal] cutout fullCovered=" + fullCovered
                            + " cutoutCovered=" + cutoutCovered + " rootKept="
                            + rootPixelsKept + "/" + rootPixels + " surviving="
                            + survivingPixels);
                }
                assertCleanResources();
            } finally {
                GlDebug.resourceTracking(false);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void disabledSettingsAreAnExactNoCommandNoOp() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            GlDebug.resourceTracking(true);
            try {
                HairStrandSettings settings = straightLock(1.0f, 0.35f);
                ExternalCamera camera = camera();
                try (Fixture fixture = fixture(settings)) {
                    prefill(device, fixture.target());
                    float[] before = read(fixture.target());

                    CommandBuffer commands = device.createCommandBuffer();
                    fixture.pass().recordNormals(commands, camera, STATIC_MODEL,
                            HairStrandSettings.disabled());
                    assertEquals(0, commands.commandCount(),
                            "disabled hair settings must enqueue no command");
                    device.execute(commands);
                    device.invalidateState();

                    assertArrayEqualsBits(before, read(fixture.target()),
                            "disabled hair settings must not touch the normal target");
                }
                assertCleanResources();
            } finally {
                GlDebug.resourceTracking(false);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static HairStrandSettings straightLock(float alpha, float alphaCutoff) {
        return HairStrandSettings.builder()
                .strandCount(1).segmentsPerStrand(8).strandLengthMetres(1.0f)
                .rootSpreadMetres(0.0f).curlMetres(0.0f)
                .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                .widthPixels(8.0f).minimumWidthPixels(2.0f).maximumWidthPixels(12.0f)
                .nearDistanceMetres(20.0f).farDistanceMetres(40.0f)
                .lodMinimumPixels(1.0f).lodWidthScale(1.0f)
                .alpha(alpha).alphaCutoff(alphaCutoff)
                .transparency(HairStrandSettings.Transparency.CUTOUT)
                .rootColor(new Vector3f(1.0f, 1.0f, 1.0f))
                .tipColor(new Vector3f(1.0f, 1.0f, 1.0f))
                .diffuseStrength(1.0f).specularStrength(0.0f)
                .colorVariation(0.0f).castsShadow(false).seed(11L)
                .build();
    }

    private static ExternalCamera camera() {
        Matrix4f view = new Matrix4f().lookAt((float) EYE_X, (float) EYE_Y, (float) EYE_Z,
                (float) EYE_X, (float) EYE_Y, 0.0f, 0.0f, 1.0f, 0.0f);
        Matrix4f projection = new Matrix4f().perspective(FOV_Y, WIDTH / (float) HEIGHT,
                0.1f, 100.0f);
        return ExternalCamera.of(view, projection,
                new Vector3f((float) EYE_X, (float) EYE_Y, (float) EYE_Z), 0.0f);
    }

    /**
     * World x that projects to the centre of the given pixel column for the
     * fixture camera and the z=0 lock plane. The camera looks straight down -Z,
     * so every lock pixel has the same view depth.
     */
    private static double worldX(int column) {
        double focal = 1.0 / Math.tan(FOV_Y * 0.5);
        double aspect = WIDTH / (double) HEIGHT;
        double ndcX = (column + 0.5) / WIDTH * 2.0 - 1.0;
        return EYE_X + ndcX * aspect / focal * EYE_Z;
    }

    /**
     * Independent double reference for the ribbon plane normal.
     *
     * <p>The straight lock is a line in the world; the expanded ribbon is the
     * plane containing that line and the camera-facing side direction. Its
     * normal is the component of {@code eye - origin} perpendicular to the
     * local +X tangent, evaluated from the public matrix elements in
     * {@code double}.</p>
     */
    private static double[] analyticRibbonNormal(Matrix4fc model) {
        double tangentX = model.m00();
        double tangentY = model.m01();
        double tangentZ = model.m02();
        double tangentLength = Math.sqrt(tangentX * tangentX + tangentY * tangentY
                + tangentZ * tangentZ);
        tangentX /= tangentLength;
        tangentY /= tangentLength;
        tangentZ /= tangentLength;
        double dx = EYE_X - model.m30();
        double dy = EYE_Y - model.m31();
        double dz = EYE_Z - model.m32();
        double tangentDot = tangentX * dx + tangentY * dy + tangentZ * dz;
        double nx = dx - tangentX * tangentDot;
        double ny = dy - tangentY * tangentDot;
        double nz = dz - tangentZ * tangentDot;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new double[]{nx / length, ny / length, nz / length};
    }

    /**
     * Decodes one RG16F octahedral pair exactly like {@code ssr.frag} and
     * compares both the encoded pair and the decoded world normal with the
     * independent analytical reference.
     */
    private static void assertEncodedNormal(float[] pixels, int pixel, double[] expected,
                                            String label) {
        double ox = pixels[pixel * 4];
        double oy = pixels[pixel * 4 + 1];
        double[] encoded = octEncode(expected[0], expected[1], expected[2]);
        assertEquals(encoded[0], ox, ENCODED_TOLERANCE, label + ": encoded u");
        assertEquals(encoded[1], oy, ENCODED_TOLERANCE, label + ": encoded v");
        double[] decoded = octDecode(ox, oy);
        assertEquals(expected[0], decoded[0], NORMAL_TOLERANCE, label + ": decoded x");
        assertEquals(expected[1], decoded[1], NORMAL_TOLERANCE, label + ": decoded y");
        assertEquals(expected[2], decoded[2], NORMAL_TOLERANCE, label + ": decoded z");
    }

    /** Mirrors {@code octEncode} in scene-surface.frag/hair-normal.frag in double. */
    private static double[] octEncode(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        x /= length;
        y /= length;
        z /= length;
        double denominator = Math.abs(x) + Math.abs(y) + Math.abs(z) + 1.0e-8;
        double ox = x / denominator;
        double oy = y / denominator;
        if (z < 0.0) {
            double previousX = ox;
            ox = (1.0 - Math.abs(oy)) * Math.signum(previousX + 1.0e-8);
            oy = (1.0 - Math.abs(previousX)) * Math.signum(oy + 1.0e-8);
        }
        return new double[]{ox, oy};
    }

    /** Mirrors {@code octDecode} in ssr.frag/ssgi.frag in double. */
    private static double[] octDecode(double ox, double oy) {
        double x = ox;
        double y = oy;
        double z = 1.0 - Math.abs(ox) - Math.abs(oy);
        if (z < 0.0) {
            double previousX = x;
            double previousY = y;
            x = (1.0 - Math.abs(previousY)) * (previousX >= 0.0 ? 1.0 : -1.0);
            y = (1.0 - Math.abs(previousX)) * (previousY >= 0.0 ? 1.0 : -1.0);
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / length, y / length, z / length};
    }

    private static float[] record(GlRenderDevice device, Fixture fixture, ExternalCamera camera,
                                  Matrix4f model, HairStrandSettings settings) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(fixture.target()).viewport(0, 0, WIDTH, HEIGHT);
        fixture.pass().recordNormals(commands, camera, model, settings);
        device.execute(commands);
        device.invalidateState();
        return read(fixture.target());
    }

    private static boolean isSentinel(float u, float v) {
        return u == SENTINEL_U && v == SENTINEL_V;
    }

    private static void assertCleanResources() {
        assertTrue(GlDebug.resources().liveResources().isEmpty(),
                "the hair normal fixture must release every tracked GL resource: "
                        + GlDebug.resources().liveResources());
        assertEquals(GlDebug.resources().createdCount(), GlDebug.resources().closedCount(),
                "every tracked hair normal resource must be closed");
    }

    /**
     * One pass plus one normal target. Both are created after resource tracking
     * starts and closed together so the per-case live-resource assertion is
     * meaningful.
     */
    private record Fixture(Framebuffer target, HairStrandPass pass) implements AutoCloseable {
        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                pass.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                target.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static Fixture fixture(HairStrandSettings settings) {
        return new Fixture(normalTarget(), new HairStrandPass(settings, WIDTH, HEIGHT));
    }

    private static Framebuffer normalTarget() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(WIDTH, HEIGHT)
                .colorTexture(RenderFormat.RG16F)
                .depthTexture()
                .build());
    }

    private static void prefill(GlRenderDevice device, Framebuffer target) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target).viewport(0, 0, WIDTH, HEIGHT)
                .clearColor(SENTINEL_U, SENTINEL_V, 0.0f, 1.0f).clear(true, true);
        device.execute(commands);
        device.invalidateState();
    }

    private static float[] read(Framebuffer target) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.id());
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(WIDTH * HEIGHT * 4);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_FLOAT, buffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        float[] pixels = new float[WIDTH * HEIGHT * 4];
        buffer.get(pixels);
        return pixels;
    }

    private static void assertArrayEqualsBits(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message + ": extent");
        for (int index = 0; index < expected.length; index++) {
            assertEquals(Float.floatToIntBits(expected[index]),
                    Float.floatToIntBits(actual[index]),
                    message + ": byte " + index);
        }
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(WIDTH, HEIGHT)
                .title("Hair Normal GL Test").visible(false).build();
    }
}
