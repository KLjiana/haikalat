package com.kaleblangley.haikalat.subsystems.render3d.caustics;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
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
 * Real-GL contract for the bounded caustics pass.
 *
 * <p>The fixture uses a synthetic shared depth buffer cleared to a constant
 * view-space distance, so the receiver is a known plane parallel to the
 * camera. The A-B case then rasterises a real foreground quad between the
 * camera and the floor at a closer view distance; its depth is read back and
 * asserted before the caustics pass, so the rejection is proven against actual
 * rasterised geometry rather than a raw depth-clear side effect. Expected
 * colours come from {@link CausticsReference} and the documented formula, never
 * from the shader output.</p>
 *
 * <p>Cases: (a) time-varying contribution matches the reference, (b) pixels
 * outside the water region / receiver band / maximum distance stay unchanged,
 * (c) a foreground depth region is not lit while visible floor pixels remain
 * lit, (d) disabled and zero-intensity paths leave the target unchanged, and
 * (e) resize/close are transactional and idempotent. Optional pattern-texture
 * parity is covered by the white 1x1 test resource. MSAA depth, real scene
 * geometry, TAA/jitter and performance are not evaluated by this class.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class CausticsPassGlTest {
    private static final int SIZE = 64;
    private static final float BACKGROUND_RED = 0.05f;
    private static final float BACKGROUND_GREEN = 0.10f;
    private static final float BACKGROUND_BLUE = 0.15f;
    private static final float VIEW_DISTANCE = 10.0f;
    private static final float FOREGROUND_VIEW_DISTANCE = 3.0f;
    private static final float TIME_A = 0.4f;
    private static final float TIME_B = 1.9f;
    private static final float PIXEL_TOLERANCE = 5.0e-3f;
    /**
     * RGBA16F stores the colour-clear background with half precision: red
     * 0.05 reads back as 0.049987793. An unchanged background also sits next to
     * smooth region/height fades where float-vs-double boundary classification
     * can differ by a sub-threshold amount, so 1e-3 is the meaningful bound; a
     * real misplaced caustic is two orders of magnitude larger.
     */
    private static final float BACKGROUND_TOLERANCE = 1.0e-3f;
    private static final Vector3f LIGHT = new Vector3f(0.24f, -1.0f, 0.18f).normalize();
    /** Half-size of the foreground quad in pixels at its 3 m view distance. */
    private static final float FOREGROUND_HALF_EXTENT_PIXELS = 5.0f;

    private static final String FOREGROUND_VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aViewPosition;
            uniform mat4 uProjection;
            void main() {
                gl_Position = uProjection * vec4(aViewPosition, 1.0);
            }
            """;

    private static final String FOREGROUND_FRAGMENT = """
            #version 330 core
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
            """;

    @Test
    void timeVaryingContributionMatchesTheIndependentReference() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Framebuffer source = sceneSource()) {
                Scene scene = scene(source);
                CausticsSettings settings = referenceSettings();
                float floorDepth = constantDepth(VIEW_DISTANCE, scene.projection());
                clearScene(source, floorDepth);
                try (CausticsPass pass = new CausticsPass(settings, 1, SIZE, SIZE)) {
                    FloatBuffer first = render(scene, pass, TIME_A);
                    FloatBuffer later = render(scene, pass, TIME_B);

                    int checked = 0;
                    int changed = 0;
                    for (int y = 2; y < SIZE - 2 && checked < 24; y++) {
                        for (int x = 2; x < SIZE - 2 && checked < 24; x++) {
                            double amountFirst = expectedAmount(scene, settings, floorDepth,
                                    TIME_A, x, y);
                            double amountLater = expectedAmount(scene, settings, floorDepth,
                                    TIME_B, x, y);
                            if (!(amountFirst > 0.20)
                                    || Math.abs(amountLater - amountFirst) < 0.01) {
                                continue;
                            }
                            assertPixel(expectedColor(settings, amountFirst), first,
                                    pixel(x, y), PIXEL_TOLERANCE, "first-frame caustics reference");
                            assertPixel(expectedColor(settings, amountLater), later,
                                    pixel(x, y), PIXEL_TOLERANCE, "later-frame caustics reference");
                            changed++;
                            checked++;
                        }
                    }
                    assertTrue(changed >= 8, "expected several lit pixels whose caustics change "
                            + "with time, got " + changed);
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void outsideRegionBandAndMaximumDistanceStayUnchanged() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Framebuffer source = sceneSource()) {
                Scene scene = scene(source);
                float floorDepth = constantDepth(VIEW_DISTANCE, scene.projection());
                clearScene(source, floorDepth);

                CausticsSettings regional = CausticsSettings.builder()
                        .enabled(true).intensity(1.5f).color(0.55f, 0.80f, 1.0f)
                        .worldScaleMetres(2.5f).animationSpeed(0.4f)
                        .maximumProjectionDistance(25.0f).waterPlane(0.0f)
                        .waterRegion(-4.0f, 0.0f, 4.0f, 8.0f)
                        .receiverHeight(-4.0f, -0.6f)
                        .depthBias(0.05f).distanceFalloff(0.12f).edgeFadeDistance(0.5f)
                        .maximumReceiverSlopeDegrees(55.0f)
                        .build();
                try (CausticsPass pass = new CausticsPass(regional, 1, SIZE, SIZE)) {
                    FloatBuffer pixels = render(scene, pass, TIME_A);
                    int lit = 0;
                    int rejected = 0;
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            double amount = expectedAmount(scene, regional, floorDepth,
                                    TIME_A, x, y);
                            if (amount > 0.0) {
                                lit++;
                                assertPixel(expectedColor(regional, amount), pixels, pixel(x, y),
                                        PIXEL_TOLERANCE, "lit caustics receiver");
                            } else {
                                rejected++;
                                assertPixel(background(), pixels, pixel(x, y),
                                        BACKGROUND_TOLERANCE,
                                        "rejected receiver must keep the copied background");
                            }
                        }
                    }
                    assertTrue(lit > 50, "regional test must light receivers, got " + lit);
                    assertTrue(rejected > 500, "regional test must reject most pixels, got "
                            + rejected);
                    assertEquals(GL_NO_ERROR, glGetError());
                }

                CausticsSettings tooFar = regional.toBuilder()
                        .maximumProjectionDistance(0.5f).build();
                try (CausticsPass pass = new CausticsPass(tooFar, 1, SIZE, SIZE)) {
                    FloatBuffer pixels = render(scene, pass, TIME_A);
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            assertPixel(background(), pixels, pixel(x, y), BACKGROUND_TOLERANCE,
                                    "maximum projection distance rejection");
                        }
                    }
                    assertEquals(GL_NO_ERROR, glGetError());
                }

                CausticsSettings wrongBand = regional.toBuilder()
                        .receiverHeight(2.0f, 3.0f).build();
                try (CausticsPass pass = new CausticsPass(wrongBand, 1, SIZE, SIZE)) {
                    FloatBuffer pixels = render(scene, pass, TIME_A);
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            assertPixel(background(), pixels, pixel(x, y), BACKGROUND_TOLERANCE,
                                    "receiver height band rejection");
                        }
                    }
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void foregroundDepthRegionIsNotLitWhileVisibleFloorRemainsLit() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Framebuffer source = sceneSource()) {
                Scene scene = scene(source);
                CausticsSettings settings = referenceSettings();
                float floorDepth = constantDepth(VIEW_DISTANCE, scene.projection());
                float foregroundDepth = constantDepth(FOREGROUND_VIEW_DISTANCE, scene.projection());
                clearScene(source, floorDepth);

                int[] covered = findLitPixel(scene, settings, floorDepth, TIME_A);
                int[] visible = findOtherLitPixel(scene, settings, floorDepth, TIME_A, covered);
                try (CausticsPass pass = new CausticsPass(settings, 1, SIZE, SIZE)) {
                    clearScene(source, floorDepth);
                    assertEquals(floorDepth, readDepth(source, covered[0], covered[1]), 1.0e-4f,
                            "A side depth must be the synthetic floor");
                    FloatBuffer withoutOccluder = render(scene, pass, TIME_A);
                    assertPixel(expectedColor(settings, expectedAmount(scene, settings,
                                    floorDepth, TIME_A, covered[0], covered[1])),
                            withoutOccluder, pixel(covered[0], covered[1]), PIXEL_TOLERANCE,
                            "A side must light the receiver");

                    clearScene(source, floorDepth);
                    drawnForegroundDepth(scene, covered, FOREGROUND_VIEW_DISTANCE);
                    float writtenDepth = readDepth(source, covered[0], covered[1]);
                    assertEquals(foregroundDepth, writtenDepth, 1.0e-4f,
                            "foreground geometry must write its own depth, not the floor depth");
                    assertTrue(Math.abs(writtenDepth - floorDepth) > 1.0e-2f,
                            "foreground depth must be clearly closer than the floor");
                    Vector3f foregroundWorld = unproject(scene.frame().inverseJitteredProjection(),
                            scene.inverseView(), covered[0], covered[1], writtenDepth);
                    assertTrue(foregroundWorld.y > 0.0f,
                            "foreground geometry must be above the water plane, y="
                                    + foregroundWorld.y);
                    FloatBuffer withOccluder = render(scene, pass, TIME_A);
                    assertPixel(background(), withOccluder, pixel(covered[0], covered[1]),
                            BACKGROUND_TOLERANCE,
                            "B side foreground must not receive caustics intended for the floor");
                    double visibleAmount = expectedAmount(scene, settings, floorDepth, TIME_A,
                            visible[0], visible[1]);
                    assertPixel(expectedColor(settings, visibleAmount), withOccluder,
                            pixel(visible[0], visible[1]), PIXEL_TOLERANCE,
                            "visible floor outside the foreground region must stay lit");
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void disabledAndZeroIntensityAreNoOps() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Framebuffer source = sceneSource()) {
                Scene scene = scene(source);
                assertThrows(IllegalArgumentException.class,
                        () -> new CausticsPass(CausticsSettings.disabled(), 1, SIZE, SIZE),
                        "disabled settings must not create a pass");

                CausticsSettings zero = referenceSettings().toBuilder().intensity(0.0f).build();
                float floorDepth = constantDepth(VIEW_DISTANCE, scene.projection());
                clearScene(source, floorDepth);
                try (CausticsPass pass = new CausticsPass(zero, 1, SIZE, SIZE)) {
                    FloatBuffer pixels = render(scene, pass, TIME_A);
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            assertPixel(background(), pixels, pixel(x, y), BACKGROUND_TOLERANCE,
                                    "zero intensity must copy the scene colour within half-float quantization");
                        }
                    }
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void resizeAndCloseAreTransactionalAndIdempotent() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Framebuffer source = sceneSource()) {
                Scene scene = scene(source);
                CausticsSettings settings = referenceSettings();
                float floorDepth = constantDepth(VIEW_DISTANCE, scene.projection());
                clearScene(source, floorDepth);
                int liveBefore = GlDebug.resources().liveResources().size();
                CausticsPass pass = new CausticsPass(settings, 1, SIZE, SIZE);
                int oldTexture = pass.colorTexture();
                assertTrue(oldTexture > 0);
                try {
                    pass.resize(32, 32);
                    assertEquals(32, pass.width());
                    assertEquals(32, pass.height());
                    assertTrue(pass.colorTexture() > 0);
                    assertTrue(pass.id() > 0);
                    assertNotEquals(oldTexture, pass.colorTexture());
                    assertThrows(IllegalArgumentException.class, () -> pass.resize(0, 32));
                    try (Framebuffer small = Framebuffer.fromDescriptor(
                            FramebufferDescriptor.builder(32, 32)
                                    .colorTexture(RenderFormat.RGBA16F)
                                    .depthTexture()
                                    .build())) {
                        Camera smallCamera = camera();
                        Matrix4f smallProjection = CameraProjection.stable(
                                smallCamera, 32, 32, new Matrix4f());
                        Matrix4f smallView = smallCamera.getViewMatrix(new Matrix4f());
                        TemporalFrameState.FrameParameters smallFrame = frame(
                                smallCamera, smallProjection, smallView, 32);
                        clearScene(small, floorDepth);
                        var commands = scene.device().createCommandBuffer();
                        pass.record(commands, small, small.depthAttachment(), smallFrame,
                                LIGHT, TIME_A);
                        scene.device().execute(commands);
                        assertEquals(GL_NO_ERROR, glGetError());
                    }
                } finally {
                    pass.close();
                }
                pass.close();
                assertTrue(pass.isClosed());
                assertEquals(0, pass.colorTexture());
                assertEquals(0, pass.id());
                assertEquals(0, pass.width());
                assertEquals(0, pass.height());
                assertThrows(GlException.class, () -> pass.resize(32, 32));
                assertEquals(liveBefore, GlDebug.resources().liveResources().size(),
                        "closing the pass must release its shader, mesh, target and texture");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void whitePatternTextureMatchesTheProceduralPath() {
        assertTrue(CausticsPass.class.getResource("/caustics/white-1x1.png") != null,
                "white pattern test resource must be on the test classpath");
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Framebuffer source = sceneSource()) {
                Scene scene = scene(source);
                float floorDepth = constantDepth(VIEW_DISTANCE, scene.projection());
                clearScene(source, floorDepth);
                CausticsSettings procedural = referenceSettings();
                CausticsSettings textured = procedural.toBuilder()
                        .patternTexture("/caustics/white-1x1.png").build();
                try (CausticsPass plain = new CausticsPass(procedural, 1, SIZE, SIZE);
                     CausticsPass masked = new CausticsPass(textured, 1, SIZE, SIZE)) {
                    FloatBuffer plainPixels = render(scene, plain, TIME_A);
                    FloatBuffer maskedPixels = render(scene, masked, TIME_A);
                    int compared = 0;
                    for (int y = 2; y < SIZE - 2 && compared < 16; y++) {
                        for (int x = 2; x < SIZE - 2 && compared < 16; x++) {
                            double amount = expectedAmount(scene, procedural, floorDepth,
                                    TIME_A, x, y);
                            if (amount > 0.1) {
                                int index = pixel(x, y);
                                for (int channel = 0; channel < 3; channel++) {
                                    assertEquals(plainPixels.get(index * 4 + channel),
                                            maskedPixels.get(index * 4 + channel),
                                            PIXEL_TOLERANCE,
                                            "white alpha mask must preserve the procedural pattern");
                                }
                                compared++;
                            }
                        }
                    }
                    assertTrue(compared >= 8, "texture parity needs lit pixels, got " + compared);
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    private static void bind(GlfwWindow window) {
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        while (glGetError() != GL_NO_ERROR) {
            // drain stale errors from context creation before asserting.
        }
    }

    private static Scene scene(Framebuffer source) {
        Camera camera = camera();
        Matrix4f projection = CameraProjection.stable(camera, SIZE, SIZE, new Matrix4f());
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        return new Scene(new GlRenderDevice(), camera, projection, view,
                new Matrix4f(view).invert(), frame(camera, projection, view, SIZE), source);
    }

    private static Camera camera() {
        return new Camera(new Vector3f(0.0f, 6.0f, 10.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                -90.0f, -50.0f);
    }

    private static TemporalFrameState.FrameParameters frame(Camera camera, Matrix4f projection,
                                                             Matrix4f view, int extent) {
        Vector3f position = camera.position();
        Vector3f front = camera.front();
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        return new TemporalFrameState.FrameParameters(
                0L, extent, extent, CameraProjection.NEAR_PLANE, CameraProjection.FAR_PLANE,
                position.x, position.y, position.z, front.x, front.y, front.z, 0.0f, 0.0f,
                projection, projection, new Matrix4f(projection).invert(), view, viewProjection,
                new Matrix4f(viewProjection).invert(), 0L);
    }

    private static CausticsSettings referenceSettings() {
        return CausticsSettings.builder()
                .enabled(true)
                .intensity(1.5f)
                .color(0.55f, 0.80f, 1.0f)
                .worldScaleMetres(2.5f)
                .animationSpeed(0.4f)
                .maximumProjectionDistance(25.0f)
                .waterPlane(0.0f)
                .waterRegion(-20.0f, -20.0f, 20.0f, 20.0f)
                .receiverHeight(-8.0f, -0.5f)
                .depthBias(0.05f)
                .distanceFalloff(0.06f)
                .edgeFadeDistance(1.0f)
                .maximumReceiverSlopeDegrees(55.0f)
                .patternTexture("")
                .build();
    }

    private static Framebuffer sceneSource() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA16F)
                .depthTexture()
                .build());
    }

    private static FloatBuffer render(Scene scene, CausticsPass pass, float time) {
        var commands = scene.device().createCommandBuffer();
        pass.record(commands, scene.source(), scene.source().depthAttachment(),
                scene.frame(), LIGHT, time);
        scene.device().execute(commands);
        return read(pass);
    }

    private static FloatBuffer read(CausticsPass pass) {
        pass.colorTarget().bind();
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static double expectedAmount(Scene scene, CausticsSettings settings, float depth,
                                         float time, int x, int y) {
        Vector3f world = unproject(scene.frame().inverseJitteredProjection(),
                scene.inverseView(), x, y, depth);
        double upDot = Math.abs(scene.camera().front().y);
        return CausticsReference.amount(world.x, world.y, world.z, upDot, settings,
                LIGHT, time);
    }

    private static Vector3f expectedColor(CausticsSettings settings, double amount) {
        if (!(amount > 0.0)) return background();
        Vector3f color = settings.color();
        return new Vector3f(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE)
                .add(color.mul((float) amount));
    }

    private static int[] findLitPixel(Scene scene, CausticsSettings settings, float depth,
                                      float time) {
        for (int y = 8; y < SIZE - 8; y++) {
            for (int x = 8; x < SIZE - 8; x++) {
                if (expectedAmount(scene, settings, depth, time, x, y) > 0.35) {
                    return new int[]{x, y};
                }
            }
        }
        throw new AssertionError("test fixture did not produce a visibly lit receiver pixel");
    }

    private static int[] findOtherLitPixel(Scene scene, CausticsSettings settings, float depth,
                                           float time, int[] excluded) {
        for (int y = 8; y < SIZE - 8; y++) {
            for (int x = 8; x < SIZE - 8; x++) {
                if (Math.abs(x - excluded[0]) <= 7 && Math.abs(y - excluded[1]) <= 7) {
                    continue;
                }
                if (expectedAmount(scene, settings, depth, time, x, y) > 0.35) {
                    return new int[]{x, y};
                }
            }
        }
        throw new AssertionError("test fixture did not produce a second lit receiver pixel");
    }

    private static void clearScene(Framebuffer source, float depth) {
        source.bind();
        glViewport(0, 0, source.width(), source.height());
        // Raw clears in this fixture set the state they need explicitly; the
        // foreground depth in the A-B case comes from rasterised geometry below,
        // not from a scissored depth clear.
        glDisable(GL_SCISSOR_TEST);
        glDepthMask(true);
        glClearColor(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f);
        glClearDepth(depth);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    /**
     * Rasterises a small view-space-aligned quad at {@code viewDistance} in
     * front of the camera, covering the given pixel. The fragment colour is the
     * fixture background so the source colour attachment stays unchanged; only
     * the depth attachment is exercised.
     */
    private static void drawnForegroundDepth(Scene scene, int[] center, float viewDistance) {
        try (ShaderProgram shader = ShaderProgram.fromSources(
                FOREGROUND_VERTEX, FOREGROUND_FRAGMENT);
             Mesh mesh = foregroundQuad(scene, center, viewDistance)) {
            var commands = scene.device().createCommandBuffer();
            commands.bindFramebuffer(scene.source())
                    .viewport(0, 0, SIZE, SIZE)
                    .enableScissor(false)
                    .enableBlend(false)
                    .enableCullFace(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .bindShader(shader)
                    .setUniformMat4(shader, "uProjection", scene.projection())
                    .setUniformVec4(shader, "uColor", new Vector4f(
                            BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f))
                    .bindMesh(mesh)
                    .drawMesh(mesh);
            scene.device().execute(commands);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static Mesh foregroundQuad(Scene scene, int[] center, float viewDistance) {
        float tanHalf = (float) Math.tan(Math.toRadians(scene.camera().zoom() * 0.5f));
        float ndcX = (center[0] + 0.5f) / SIZE * 2.0f - 1.0f;
        float ndcY = (center[1] + 0.5f) / SIZE * 2.0f - 1.0f;
        float centerX = ndcX * viewDistance * tanHalf;
        float centerY = ndcY * viewDistance * tanHalf;
        float halfExtent = FOREGROUND_HALF_EXTENT_PIXELS
                * (2.0f * viewDistance * tanHalf / SIZE);
        float z = -viewDistance;
        float[] positions = {
                centerX - halfExtent, centerY - halfExtent, z,
                centerX + halfExtent, centerY - halfExtent, z,
                centerX + halfExtent, centerY + halfExtent, z,
                centerX - halfExtent, centerY + halfExtent, z
        };
        VertexAttribute position = VertexAttribute.builder()
                .index(0).size(3).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        return Mesh.builder()
                .vertices(positions, 3 * Float.BYTES, position)
                .indices(new int[]{0, 1, 2, 0, 2, 3})
                .build();
    }

    private static float readDepth(Framebuffer source, int x, int y) {
        source.bind();
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glReadPixels(x, y, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        assertEquals(GL_NO_ERROR, glGetError());
        return depth.get(0);
    }

    private static float constantDepth(float viewDistance, Matrix4f projection) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDistance, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static Vector3f unproject(Matrix4f inverseProjection, Matrix4f inverseView,
                                      int x, int y, float depth) {
        float ndcX = (x + 0.5f) / SIZE * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / SIZE * 2.0f - 1.0f;
        Vector4f clip = new Vector4f(ndcX, ndcY, depth * 2.0f - 1.0f, 1.0f)
                .mul(inverseProjection);
        clip.div(clip.w);
        Vector4f world = new Vector4f(clip.x, clip.y, clip.z, 1.0f).mul(inverseView);
        world.div(world.w);
        return new Vector3f(world.x, world.y, world.z);
    }

    private static Vector3f background() {
        return new Vector3f(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE);
    }

    private static void assertPixel(Vector3f expected, FloatBuffer pixels, int index,
                                    float tolerance, String message) {
        assertEquals(expected.x, pixels.get(index * 4), tolerance, message + " red");
        assertEquals(expected.y, pixels.get(index * 4 + 1), tolerance, message + " green");
        assertEquals(expected.z, pixels.get(index * 4 + 2), tolerance, message + " blue");
    }

    private static int pixel(int x, int y) {
        return y * SIZE + x;
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Caustics pass test").visible(false).build();
    }

    private record Scene(GlRenderDevice device, Camera camera, Matrix4f projection,
                         Matrix4f view, Matrix4f inverseView,
                         TemporalFrameState.FrameParameters frame, Framebuffer source) {
    }
}
