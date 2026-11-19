package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TaaHistory;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TaaResolveInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TaaSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalAccumulationPass;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecal;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
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
import java.nio.FloatBuffer;
import java.util.List;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL contract for the projected-decal receiver volume.
 *
 * <p>One scene pass renders depth for the receiver under test, then the decal
 * renderer projects onto that depth and the result is read back.  The cases
 * cover a sloped receiver, a perpendicular wall (facing rejection), a wall
 * behind the configured thickness and a wall in front of the surface offset
 * (depth rejection in both directions), a moving receiver, an exact
 * disabled/no-receiver no-op, and a resize.  The receiver pass binds its own
 * VAO and the decal pass renders into a colour-only view of the same colour
 * texture, so sampling the borrowed scene depth cannot form a feedback loop.
 * The sloped case first reads back the receiver depth and requires a real
 * receiver before the decal draw; that assertion fails if the fixture stops
 * writing scene depth instead of silently sampling the clear value.  Run with
 * the command recorded in the worker report.</p>
 *
 * <p>The boundary cases add a test-only varying-alpha texture and cover the
 * {@code alphaMask} pixel branch against {@code alphaMask=false}, the
 * documented MSAA rejection (no sampleable single-sample window depth), the
 * decal-inside-the-TAA-input composition with a production temporal resolve,
 * and shadow receive/occlusion (a caster in front of the volume never receives
 * the decal).</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ProjectedDecalGlTest {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 72;
    private static final AssetRef DECAL_TEXTURE =
            AssetRef.of("/vfx/decals/kenney/particle_pack/scorch_01.png");
    /**
     * Test-only 8x8 texture: constant RGB with per-column alpha
     * {@code [0, 0, 64, 128, 192, 255, 255, 255]}.  The bundled decal texture
     * is fully opaque, so without this fixture the {@code alphaMask} branch is
     * a no-op.
     */
    private static final AssetRef ALPHA_TEXTURE =
            AssetRef.of("/vfx/test/decal-alpha-columns-8x8.png");
    private static final String RECEIVER_VERTEX = """
            #version 460 core
            layout (location = 0) in vec3 aPosition;
            uniform mat4 uMvp;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
            """;
    private static final String RECEIVER_FRAGMENT = """
            #version 460 core
            layout (location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(0.22, 0.24, 0.28, 1.0);
            }
            """;
    /** Constant-colour overlay used as the transparent-queue scene feature. */
    private static final String OVERLAY_FRAGMENT = """
            #version 460 core
            layout (location = 0) out vec4 fragColor;
            uniform vec4 uColor;
            void main() {
                fragColor = uColor;
            }
            """;
    /** World-position pass-through used by the shadow/caster fixture. */
    private static final String WORLD_VERTEX = """
            #version 460 core
            layout (location = 0) in vec3 aPosition;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            out vec3 vWorldPosition;
            void main() {
                vec4 world = uModel * vec4(aPosition, 1.0);
                vWorldPosition = world.xyz;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
            """;
    /**
     * Test-owned shadowed receiver: the -X half is shadowed and the +X half is
     * lit.  The decal shader never samples a shadow map, so the source of the
     * darkening is irrelevant to the contract under test: the decal must blend
     * over both bands instead of applying its own darkening/lighting term.
     */
    private static final String SHADOWED_RECEIVER_FRAGMENT = """
            #version 460 core
            in vec3 vWorldPosition;
            layout (location = 0) out vec4 fragColor;
            void main() {
                vec3 lit = vec3(0.62, 0.55, 0.42);
                vec3 shadowed = vec3(0.12, 0.11, 0.09);
                fragColor = vec4(mix(shadowed, lit, step(0.0, vWorldPosition.x)), 1.0);
            }
            """;
    /** Flat foreground caster that hides part of the decal's receiver volume. */
    private static final String OCCLUDER_FRAGMENT = """
            #version 460 core
            layout (location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(0.30, 0.35, 0.28, 1.0);
            }
            """;

    @Test
    void projectedDecalFollowsSlopedReceiverAndRejectsWallsAcrossResize() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram receiverShader = ShaderProgram.fromSources(
                    RECEIVER_VERTEX, RECEIVER_FRAGMENT);
                 Mesh quad = Mesh.from(BuiltinMeshData.texturedQuad(
                         "projected-decal-receiver"));
                 VfxRenderer renderer = new VfxRenderer()) {
                Vector3f cameraPosition = new Vector3f(2.4f, 1.5f, 4.2f);
                Matrix4f view = new Matrix4f().lookAt(cameraPosition, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));
                Matrix4f projection = projection(WIDTH, HEIGHT);
                ProjectedDecalSettings settings = new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, true, 8);
                ProjectedDecal decal = decal(new Vector3f());
                int[] center = project(new Vector3f(), projection, view, WIDTH, HEIGHT);

                try (Framebuffer target = target(WIDTH, HEIGHT)) {
                    // Sloped receiver: the volume accepts a receiver whose
                    // normal is 25 degrees away from the projection normal.
                    Matrix4f slope = new Matrix4f()
                            .rotateX((float) -Math.toRadians(25.0))
                            .scale(1.6f, 1.6f, 1.0f);
                    renderScene(device, target, receiverShader, quad,
                            projectionView(projection, view), slope);
                    // The receiver pass must actually write depth.  Without the
                    // explicit VAO bind the draw is invalid and the decal pass
                    // samples the cleared value (1.0), making every fragment
                    // discard and turning the pixel assertion below vacuous.
                    float receiverDepth = readDepth(target, center[0], center[1]);
                    assertTrue(receiverDepth > 0.0f && receiverDepth < 0.999f,
                            "the scene pass must write receiver depth at the decal centre, was "
                                    + receiverDepth);
                    ByteBuffer baseline = read(target, WIDTH, HEIGHT);
                    // Colour readback discriminates "receiver drawn" from
                    // "receiver draw invalid but the decal statistics still
                    // report a draw": the clear colour below is (0.05,0.06,
                    // 0.08) while the receiver shader writes (0.22,0.24,0.28).
                    assertReceiverColour(baseline, center, WIDTH);
                    VfxRenderer.ProjectedDecalStatistics stats = renderDecals(
                            device, renderer, target, settings, List.of(decal), projection, view);
                    ByteBuffer sloped = read(target, WIDTH, HEIGHT);
                    assertEquals(1, stats.drawn());
                    assertEquals(1, stats.drawCalls());
                    assertTrue(changed(baseline, sloped, center[0], center[1], 4,
                                    WIDTH, HEIGHT) > 0,
                            "sloped receiver inside the volume must receive the decal");
                    int[] outside = project(new Vector3f(0.75f, 0.0f, 0.0f),
                            projection, view, WIDTH, HEIGHT);
                    assertEquals(0, changed(baseline, sloped, outside[0], outside[1], 1,
                                    WIDTH, HEIGHT),
                            "the decal must not leak outside its receiver rectangle");
                    assertEquals(GL_NO_ERROR, glGetError());

                    // Disabled settings must be an exact image no-op.
                    ByteBuffer beforeDisabled = read(target, WIDTH, HEIGHT);
                    VfxRenderer.ProjectedDecalStatistics disabledStats = renderDecals(
                            device, renderer, target, ProjectedDecalSettings.disabled(),
                            List.of(decal), projection, view);
                    ByteBuffer afterDisabled = read(target, WIDTH, HEIGHT);
                    assertEquals(0, disabledStats.drawCalls());
                    assertEquals(1, disabledStats.rejected());
                    assertExact("disabled projected decals must not change the image",
                            beforeDisabled, afterDisabled);

                    // Perpendicular wall: position is inside the volume but the
                    // receiver normal is 90 degrees from the decal normal.
                    Matrix4f perpendicularWall = new Matrix4f()
                            .rotateY((float) (Math.PI / 2.0))
                            .scale(12.0f, 12.0f, 1.0f);
                    renderScene(device, target, receiverShader, quad,
                            projectionView(projection, view), perpendicularWall);
                    ByteBuffer wallBaseline = read(target, WIDTH, HEIGHT);
                    renderDecals(device, renderer, target, settings, List.of(decal),
                            projection, view);
                    ByteBuffer wallResult = read(target, WIDTH, HEIGHT);
                    assertExact("a perpendicular wall must not receive the decal",
                            wallBaseline, wallResult);

                    // Wall farther than the configured thickness.
                    Matrix4f farWall = new Matrix4f().translate(0.0f, 0.0f, -1.0f)
                            .scale(12.0f, 12.0f, 1.0f);
                    renderScene(device, target, receiverShader, quad,
                            projectionView(projection, view), farWall);
                    ByteBuffer farBaseline = read(target, WIDTH, HEIGHT);
                    renderDecals(device, renderer, target, settings, List.of(decal),
                            projection, view);
                    ByteBuffer farResult = read(target, WIDTH, HEIGHT);
                    assertExact("a receiver deeper than thickness must reject the decal",
                            farBaseline, farResult);

                    // Thin wall in front of the decal plane: its visible face is
                    // outside the surface-offset tolerance.
                    Matrix4f nearWall = new Matrix4f().translate(0.0f, 0.0f, 0.15f)
                            .scale(12.0f, 12.0f, 1.0f);
                    renderScene(device, target, receiverShader, quad,
                            projectionView(projection, view), nearWall);
                    ByteBuffer nearBaseline = read(target, WIDTH, HEIGHT);
                    renderDecals(device, renderer, target, settings, List.of(decal),
                            projection, view);
                    ByteBuffer nearResult = read(target, WIDTH, HEIGHT);
                    assertExact("a wall in front of the surface offset must reject the decal",
                            nearBaseline, nearResult);

                    // Moving receiver: the decal is world-anchored, so it must
                    // appear at the new receiver position and not stay on screen.
                    Vector3f movedCenter = new Vector3f(1.6f, 0.0f, 0.0f);
                    Matrix4f movedSlope = new Matrix4f().translate(movedCenter)
                            .rotateX((float) -Math.toRadians(25.0))
                            .scale(1.6f, 1.6f, 1.0f);
                    renderScene(device, target, receiverShader, quad,
                            projectionView(projection, view), movedSlope);
                    ByteBuffer movedBaseline = read(target, WIDTH, HEIGHT);
                    ProjectedDecal movedDecal = decal(movedCenter);
                    renderDecals(device, renderer, target, settings, List.of(movedDecal),
                            projection, view);
                    ByteBuffer movedResult = read(target, WIDTH, HEIGHT);
                    int[] moved = project(movedCenter, projection, view, WIDTH, HEIGHT);
                    assertTrue(changed(movedBaseline, movedResult, moved[0], moved[1], 4,
                                    WIDTH, HEIGHT) > 0,
                            "the decal must follow the moved receiver");
                    assertEquals(0, changed(movedBaseline, movedResult, center[0], center[1], 2,
                                    WIDTH, HEIGHT),
                            "the decal must not remain attached to the old screen position");
                }

                // Resize: a fresh colour/depth target at a different extent must
                // keep the same world-space placement and depth contract.
                try (Framebuffer resized = target(48, 36)) {
                    Matrix4f resizedProjection = projection(48, 36);
                    Matrix4f resizedView = view;
                    Matrix4f slope = new Matrix4f()
                            .rotateX((float) -Math.toRadians(25.0))
                            .scale(1.6f, 1.6f, 1.0f);
                    renderScene(device, resized, receiverShader, quad,
                            projectionView(resizedProjection, resizedView), slope);
                    ByteBuffer resizedBaseline = read(resized, 48, 36);
                    VfxRenderer.ProjectedDecalStatistics resizedStats = renderDecals(
                            device, renderer, resized, settings, List.of(decal),
                            resizedProjection, resizedView);
                    ByteBuffer resizedResult = read(resized, 48, 36);
                    int[] resizedCenter = project(new Vector3f(), resizedProjection,
                            resizedView, 48, 36);
                    assertEquals(1, resizedStats.drawn());
                    assertTrue(changed(resizedBaseline, resizedResult,
                                    resizedCenter[0], resizedCenter[1], 3, 48, 36) > 0,
                            "the decal must keep its world placement after resize");
                    assertEquals(GL_NO_ERROR, glGetError());
                }

                // Camera inside the decal volume: the near face is behind the
                // camera and only the far/exit face can cover the receiver.
                // A near-face culling convention drops the decal here.
                Vector3f insideEye = new Vector3f(0.0f, 0.0f, 0.01f);
                Matrix4f insideView = new Matrix4f().lookAt(insideEye,
                        new Vector3f(0.0f, 0.0f, -1.0f), new Vector3f(0.0f, 1.0f, 0.0f));
                try (Framebuffer inside = target(WIDTH, HEIGHT)) {
                    Matrix4f insideReceiver = new Matrix4f().translate(0.0f, 0.0f, -0.5f)
                            .scale(1.6f, 1.6f, 1.0f);
                    renderScene(device, inside, receiverShader, quad,
                            projectionView(projection, insideView), insideReceiver);
                    int[] insideCenter = project(new Vector3f(0.0f, 0.0f, -0.5f),
                            projection, insideView, WIDTH, HEIGHT);
                    float insideDepth = readDepth(inside, insideCenter[0], insideCenter[1]);
                    assertTrue(insideDepth > 0.0f && insideDepth < 0.999f,
                            "the inside-camera scene pass must write receiver depth, was "
                                    + insideDepth);
                    ByteBuffer insideBaseline = read(inside, WIDTH, HEIGHT);
                    VfxRenderer.ProjectedDecalStatistics insideStats = renderDecals(
                            device, renderer, inside, settings, List.of(decal),
                            projection, insideView);
                    ByteBuffer insideResult = read(inside, WIDTH, HEIGHT);
                    assertEquals(1, insideStats.drawn());
                    assertTrue(changed(insideBaseline, insideResult,
                                    insideCenter[0], insideCenter[1], 4, WIDTH, HEIGHT) > 0,
                            "a camera inside the decal volume must still receive the decal "
                                    + "through the far/exit face");
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void thinWallBackFaceIsRejectedOrAcceptedByReceiveBackFaces() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram receiverShader = ShaderProgram.fromSources(
                    RECEIVER_VERTEX, RECEIVER_FRAGMENT);
                 Mesh wall = Mesh.from(BuiltinMeshData.coloredCube("decal-backface-wall"));
                 VfxRenderer renderer = new VfxRenderer();
                 Framebuffer target = target(WIDTH, HEIGHT)) {
                // The camera looks from -Z at a thin wall slab spanning
                // z = [-0.4, +0.1]. Its visible face at z = -0.4 is inside the
                // decal volume [-0.6, +0.02] seen from that side, and its back
                // face at z = +0.1 is behind the volume's far end. The visible
                // face points at -Z, opposite the +Z decal normal, so the
                // facing dot is -1.
                Vector3f cameraPosition = new Vector3f(2.4f, 1.5f, -4.2f);
                Matrix4f view = new Matrix4f().lookAt(cameraPosition, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));
                Matrix4f projection = projection(WIDTH, HEIGHT);
                Matrix4f projectionView = projectionView(projection, view);
                Matrix4f wallTransform = new Matrix4f().translate(0.0f, 0.0f, -0.15f)
                        .scale(4.0f, 4.0f, 0.5f);
                renderScene(device, target, receiverShader, wall, projectionView,
                        wallTransform);
                int[] probe = project(new Vector3f(0.0f, 0.0f, -0.4f), projection, view,
                        WIDTH, HEIGHT);
                float receiverDepth = readDepth(target, probe[0], probe[1]);
                assertTrue(receiverDepth > 0.0f && receiverDepth < 0.999f,
                        "the thin wall must write visible receiver depth, was " + receiverDepth);
                ByteBuffer baseline = read(target, WIDTH, HEIGHT);
                assertReceiverColour(baseline, probe, WIDTH);
                ProjectedDecal backFace = decal(new Vector3f());
                VfxRenderer.ProjectedDecalStatistics rejected = renderDecals(device, renderer,
                        target, new ProjectedDecalSettings(true, 0.6f, 0.02f, 0.5f, false,
                                true, 8), List.of(backFace), projection, view);
                assertEquals(1, rejected.drawn());
                assertEquals(1, rejected.drawCalls());
                ByteBuffer rejectedImage = read(target, WIDTH, HEIGHT);
                assertExact("a receiver whose visible face is the decal's back face must be "
                        + "rejected when receiveBackFaces is false", baseline, rejectedImage);
                assertEquals(receiverDepth, readDepth(target, probe[0], probe[1]), 0.0f,
                        "the rejected decal must not write depth");

                VfxRenderer.ProjectedDecalStatistics accepted = renderDecals(device, renderer,
                        target, new ProjectedDecalSettings(true, 0.6f, 0.02f, 0.5f, true,
                                true, 8), List.of(backFace), projection, view);
                assertEquals(1, accepted.drawn());
                ByteBuffer acceptedImage = read(target, WIDTH, HEIGHT);
                assertTrue(changed(rejectedImage, acceptedImage, probe[0], probe[1], 3,
                                WIDTH, HEIGHT) > 0,
                        "receiveBackFaces=true must accept the same thin wall");
                assertEquals(receiverDepth, readDepth(target, probe[0], probe[1]), 0.0f,
                        "the accepted decal must still not write depth");
                int[] outside = project(new Vector3f(0.9f, 0.0f, -0.4f), projection, view,
                        WIDTH, HEIGHT);
                assertEquals(0, changed(rejectedImage, acceptedImage, outside[0], outside[1],
                                2, WIDTH, HEIGHT),
                        "receiveBackFaces must not leak outside the decal rectangle");
                assertReceiverProbe(target, outside, WIDTH, HEIGHT,
                        "thin wall outside the decal rectangle");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void movingAndResizedReceiversKeepProbedDepthColourAndWorldPlacement() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram receiverShader = ShaderProgram.fromSources(
                    RECEIVER_VERTEX, RECEIVER_FRAGMENT);
                 Mesh quad = Mesh.from(BuiltinMeshData.texturedQuad(
                         "projected-decal-moving-receiver"));
                 VfxRenderer renderer = new VfxRenderer()) {
                Vector3f cameraPosition = new Vector3f(2.4f, 1.5f, 4.2f);
                Matrix4f view = new Matrix4f().lookAt(cameraPosition, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));
                Matrix4f projection = projection(WIDTH, HEIGHT);
                Matrix4f projectionView = projectionView(projection, view);
                ProjectedDecalSettings settings = new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, true, 8);
                Vector3f movedCenter = new Vector3f(1.6f, 0.0f, 0.0f);
                Matrix4f movedSlope = new Matrix4f().translate(movedCenter)
                        .rotateX((float) -Math.toRadians(25.0))
                        .scale(1.6f, 1.6f, 1.0f);
                int[] moved = project(movedCenter, projection, view, WIDTH, HEIGHT);
                int[] old = project(new Vector3f(), projection, view, WIDTH, HEIGHT);
                try (Framebuffer target = target(WIDTH, HEIGHT)) {
                    renderScene(device, target, receiverShader, quad, projectionView,
                            movedSlope);
                    assertReceiverProbe(target, moved, WIDTH, HEIGHT,
                            "moved receiver");
                    assertBackgroundProbe(target, old, WIDTH, HEIGHT,
                            "the old decal position after the receiver moved");
                    ByteBuffer movedBaseline = read(target, WIDTH, HEIGHT);
                    float movedDepth = readDepth(target, moved[0], moved[1]);
                    ProjectedDecal movedDecal = decal(movedCenter, 0.8f);
                    VfxRenderer.ProjectedDecalStatistics movedStats = renderDecals(device,
                            renderer, target, settings, List.of(movedDecal), projection, view);
                    ByteBuffer movedResult = read(target, WIDTH, HEIGHT);
                    assertEquals(1, movedStats.drawn());
                    assertTrue(changed(movedBaseline, movedResult, moved[0], moved[1], 4,
                                    WIDTH, HEIGHT) > 0,
                            "the decal must follow the moved receiver");
                    assertEquals(movedDepth, readDepth(target, moved[0], moved[1]), 0.0f,
                            "the moving-receiver decal must not write depth");
                    assertBackgroundProbe(target, old, WIDTH, HEIGHT,
                            "the old decal screen position must stay background");
                    assertEquals(0, changed(movedBaseline, movedResult, old[0], old[1], 2,
                                    WIDTH, HEIGHT),
                            "the decal must not remain attached to the old screen position");

                    // Resize: probe depth and colour before the draw and require
                    // the same world placement afterwards.
                    try (Framebuffer resized = target(48, 36)) {
                        Matrix4f resizedProjection = projection(48, 36);
                        renderScene(device, resized, receiverShader, quad,
                                projectionView(resizedProjection, view), movedSlope);
                        int[] resizedProbe = project(movedCenter, resizedProjection, view, 48, 36);
                        int[] resizedOutside = project(new Vector3f(2.3f, 0.0f, 0.0f),
                                resizedProjection, view, 48, 36);
                        assertReceiverProbe(resized, resizedProbe, 48, 36,
                                "resized receiver");
                        float resizedDepth = readDepth(resized, resizedProbe[0], resizedProbe[1]);
                        ByteBuffer resizedBaseline = read(resized, 48, 36);
                        VfxRenderer.ProjectedDecalStatistics resizedStats = renderDecals(
                                device, renderer, resized, settings, List.of(movedDecal),
                                resizedProjection, view);
                        ByteBuffer resizedResult = read(resized, 48, 36);
                        assertEquals(1, resizedStats.drawn());
                        assertTrue(changed(resizedBaseline, resizedResult, resizedProbe[0],
                                        resizedProbe[1], 3, 48, 36) > 0,
                                "the decal must keep its world placement after resize");
                        assertEquals(resizedDepth, readDepth(resized, resizedProbe[0],
                                        resizedProbe[1]), 0.0f,
                                "the resized decal must not write depth");
                        assertEquals(0, changed(resizedBaseline, resizedResult,
                                        resizedOutside[0], resizedOutside[1], 1, 48, 36),
                                "the resized decal must not leak outside its rectangle");
                        assertReceiverProbe(resized, resizedOutside, 48, 36,
                                "resized receiver outside the decal rectangle");
                        assertEquals(GL_NO_ERROR, glGetError());
                    }
                }
            }
        }
    }

    @Test
    void transparentQueueCompositesOverTheDecalWithoutDepthWrites() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram receiverShader = ShaderProgram.fromSources(
                    RECEIVER_VERTEX, RECEIVER_FRAGMENT);
                 ShaderProgram overlayShader = ShaderProgram.fromSources(
                         RECEIVER_VERTEX, OVERLAY_FRAGMENT);
                 Mesh quad = Mesh.from(BuiltinMeshData.texturedQuad(
                         "projected-decal-transparent-queue"));
                 VfxRenderer renderer = new VfxRenderer();
                 Framebuffer target = target(WIDTH, HEIGHT)) {
                Vector3f cameraPosition = new Vector3f(2.4f, 1.5f, 4.2f);
                Matrix4f view = new Matrix4f().lookAt(cameraPosition, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));
                Matrix4f projection = projection(WIDTH, HEIGHT);
                Matrix4f projectionView = projectionView(projection, view);
                Matrix4f slope = new Matrix4f()
                        .rotateX((float) -Math.toRadians(25.0))
                        .scale(1.6f, 1.6f, 1.0f);
                int[] probe = project(new Vector3f(), projection, view, WIDTH, HEIGHT);
                ProjectedDecalSettings settings = new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, true, 8);
                ProjectedDecal decal = decal(new Vector3f());
                Vector4f overlayColor = new Vector4f(0.1f, 0.8f, 0.2f, 0.5f);
                Matrix4f overlayTransform = new Matrix4f().translate(0.0f, 0.0f, 0.7f)
                        .scale(2.0f, 2.0f, 1.0f);

                renderScene(device, target, receiverShader, quad, projectionView, slope);
                assertReceiverProbe(target, probe, WIDTH, HEIGHT, "transparent-queue receiver");
                ByteBuffer baseline = read(target, WIDTH, HEIGHT);
                float receiverDepth = readDepth(target, probe[0], probe[1]);

                renderDecals(device, renderer, target, settings, List.of(decal),
                        projection, view);
                ByteBuffer decalOnly = read(target, WIDTH, HEIGHT);
                assertEquals(receiverDepth, readDepth(target, probe[0], probe[1]), 0.0f,
                        "the decal must not write depth");
                assertTrue(changed(baseline, decalOnly, probe[0], probe[1], 3,
                                WIDTH, HEIGHT) > 0,
                        "the decal must be visible before the transparent queue");

                renderScene(device, target, receiverShader, quad, projectionView, slope);
                renderOverlay(device, target, overlayShader, quad, projectionView,
                        overlayTransform, overlayColor);
                ByteBuffer overlayOnly = read(target, WIDTH, HEIGHT);
                assertEquals(receiverDepth, readDepth(target, probe[0], probe[1]), 0.0f,
                        "the transparent overlay must not write depth");

                renderScene(device, target, receiverShader, quad, projectionView, slope);
                renderDecals(device, renderer, target, settings, List.of(decal),
                        projection, view);
                renderOverlay(device, target, overlayShader, quad, projectionView,
                        overlayTransform, overlayColor);
                ByteBuffer decalThenOverlay = read(target, WIDTH, HEIGHT);
                assertEquals(receiverDepth, readDepth(target, probe[0], probe[1]), 0.0f,
                        "decal + transparent overlay must leave scene depth unchanged");
                assertTrue(changed(overlayOnly, decalThenOverlay, probe[0], probe[1], 3,
                                WIDTH, HEIGHT) > 0,
                        "the decal must remain visible underneath the transparent queue");
                assertOverlayBlend(decalOnly, decalThenOverlay, probe, WIDTH, HEIGHT);

                renderScene(device, target, receiverShader, quad, projectionView, slope);
                renderOverlay(device, target, overlayShader, quad, projectionView,
                        overlayTransform, overlayColor);
                renderDecals(device, renderer, target, settings, List.of(decal),
                        projection, view);
                ByteBuffer overlayThenDecal = read(target, WIDTH, HEIGHT);
                assertEquals(receiverDepth, readDepth(target, probe[0], probe[1]), 0.0f,
                        "overlay then decal must leave scene depth unchanged");
                assertTrue(changed(decalThenOverlay, overlayThenDecal, probe[0], probe[1], 3,
                                WIDTH, HEIGHT) > 0,
                        "decal-before-transparent and transparent-before-decal must differ, "
                                + "proving the documented queue order");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    /**
     * Alpha-mask pixel coverage: the bundled decal texture is fully opaque, so
     * the mask branch needs a texture whose alpha varies per texel.  The
     * test-only texture keeps the RGB constant and sets the alpha columns to
     * {@code 0, 0, 64, 128, 192, 255, 255, 255}.
     */
    @Test
    void alphaMaskCoverageKeepsMaskedTexelsAndTheUnmaskedComparison() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram receiverShader = ShaderProgram.fromSources(
                    RECEIVER_VERTEX, RECEIVER_FRAGMENT);
                 Mesh quad = Mesh.from(BuiltinMeshData.texturedQuad(
                         "projected-decal-alpha-mask"));
                 VfxRenderer renderer = new VfxRenderer();
                 Framebuffer target = target(WIDTH, HEIGHT)) {
                Vector3f cameraPosition = new Vector3f(0.0f, 0.0f, 4.2f);
                Matrix4f view = new Matrix4f().lookAt(cameraPosition, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));
                Matrix4f projection = projection(WIDTH, HEIGHT);
                Matrix4f projectionView = projectionView(projection, view);
                Matrix4f receiver = new Matrix4f().scale(3.0f, 3.0f, 1.0f);
                float size = 2.4f;
                ProjectedDecal decal = new ProjectedDecal(21L, ALPHA_TEXTURE,
                        new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), new Vector2f(size, size),
                        new Vector4f(1.0f, 1.0f, 1.0f, 1.0f), 1.0f);
                // Texel centres: u*8 = 1.0 (two zero-alpha columns), 3.5 (50%
                // alpha) and 7.5 (fully opaque last column).
                int[] maskedOut = decalColumn(0.125f, size, projection, view);
                int[] partial = decalColumn(0.4375f, size, projection, view);
                int[] opaque = decalColumn(0.9375f, size, projection, view);
                int[] outside = project(new Vector3f(size * 0.5625f, 0.0f, 0.0f),
                        projection, view, WIDTH, HEIGHT);

                renderScene(device, target, receiverShader, quad, projectionView, receiver);
                ByteBuffer baseline = read(target, WIDTH, HEIGHT);
                assertReceiverProbe(target, partial, WIDTH, HEIGHT, "alpha-mask receiver");
                float depthBefore = readDepth(target, opaque[0], opaque[1]);
                ProjectedDecalSettings maskedSettings = new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, true, 8);
                VfxRenderer.ProjectedDecalStatistics maskedStats = renderDecals(
                        device, renderer, target, maskedSettings, List.of(decal),
                        projection, view);
                ByteBuffer masked = read(target, WIDTH, HEIGHT);
                assertEquals(1, maskedStats.drawn());
                assertEquals(1, maskedStats.drawCalls());
                assertSamePixel(baseline, masked, maskedOut[0], maskedOut[1], WIDTH,
                        "zero-alpha texels must keep the background byte-exact");
                int partialDelta = pixelDelta(baseline, masked, partial[0], partial[1], WIDTH);
                int opaqueDelta = pixelDelta(baseline, masked, opaque[0], opaque[1], WIDTH);
                assertTrue(opaqueDelta > 0, "opaque texels must receive the decal");
                assertTrue(partialDelta > 0,
                        "the 50% alpha texel must receive a partial decal");
                assertTrue(partialDelta < opaqueDelta,
                        "coverage must follow the texture alpha: partial=" + partialDelta
                                + " opaque=" + opaqueDelta);
                assertEquals(0, pixelDelta(baseline, masked, outside[0], outside[1], WIDTH),
                        "the alpha mask must not extend the decal rectangle");
                assertEquals(depthBefore, readDepth(target, opaque[0], opaque[1]), 0.0f,
                        "the masked decal must not write scene depth");
                assertEquals(GL_NO_ERROR, glGetError());

                // alphaMask=false must ignore the texture alpha: the same
                // zero-alpha texel now receives the decal, and the constant
                // texture makes every covered pixel the same colour.
                renderScene(device, target, receiverShader, quad, projectionView, receiver);
                ByteBuffer unmaskedBaseline = read(target, WIDTH, HEIGHT);
                ProjectedDecalSettings unmaskedSettings = new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, false, 8);
                renderDecals(device, renderer, target, unmaskedSettings, List.of(decal),
                        projection, view);
                ByteBuffer unmasked = read(target, WIDTH, HEIGHT);
                assertTrue(pixelDelta(unmaskedBaseline, unmasked, maskedOut[0], maskedOut[1],
                                WIDTH) > 0,
                        "alphaMask=false must apply the decal where the texture alpha is zero");
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(rgbChannel(unmasked, maskedOut, WIDTH, channel),
                            rgbChannel(unmasked, opaque, WIDTH, channel), 0,
                            "alphaMask=false coverage must be independent of the texture alpha");
                }
                assertEquals(depthBefore, readDepth(target, opaque[0], opaque[1]), 0.0f,
                        "the unmasked decal must not write scene depth");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    /**
     * Shadow receive/reject.  A decal over a shadowed receiver must follow the
     * alpha-blend equation on both sides of the shadow boundary (no extra
     * darkening or bleaching of the receiver contrast), and a foreground caster
     * that occludes the receiver volume must not receive the decal.
     *
     * <p>The shadow band is painted by the test-owned receiver shader: the
     * stencil-free decal consumes only scene depth, so the origin of the
     * darkening cannot change the blend contract under test.  The caster is a
     * real opaque quad drawn into the same scene depth.</p>
     */
    @Test
    void shadowedReceiverKeepsContrastAndTheCasterDoesNotLeakTheDecal() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram receiverShader = ShaderProgram.fromSources(
                    WORLD_VERTEX, SHADOWED_RECEIVER_FRAGMENT);
                 ShaderProgram occluderShader = ShaderProgram.fromSources(
                         WORLD_VERTEX, OCCLUDER_FRAGMENT);
                 Mesh quad = Mesh.from(BuiltinMeshData.texturedQuad(
                         "projected-decal-shadow-receiver"));
                 VfxRenderer renderer = new VfxRenderer();
                 Framebuffer target = target(WIDTH, HEIGHT)) {
                Vector3f cameraPosition = new Vector3f(0.0f, 0.0f, 4.2f);
                Matrix4f view = new Matrix4f().lookAt(cameraPosition, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));
                Matrix4f projection = projection(WIDTH, HEIGHT);
                Matrix4f projectionView = projectionView(projection, view);
                Matrix4f receiver = new Matrix4f().scale(3.0f, 3.0f, 1.0f);
                Matrix4f occluder = new Matrix4f().translate(-0.9f, 0.0f, 0.8f)
                        .scale(0.5f, 2.0f, 1.0f);
                ProjectedDecal decal = new ProjectedDecal(31L, ALPHA_TEXTURE,
                        new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), new Vector2f(2.4f, 2.4f),
                        new Vector4f(1.0f, 1.0f, 1.0f, 0.5f), 1.0f);
                ProjectedDecalSettings settings = new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, false, 8);
                int[] shadowed = project(new Vector3f(-0.45f, 0.0f, 0.0f),
                        projection, view, WIDTH, HEIGHT);
                int[] lit = project(new Vector3f(0.45f, 0.0f, 0.0f),
                        projection, view, WIDTH, HEIGHT);
                int[] occluded = project(new Vector3f(-0.9f, 0.0f, 0.0f),
                        projection, view, WIDTH, HEIGHT);

                renderShadowScene(device, target, receiverShader, occluderShader, quad,
                        projectionView, receiver, occluder);
                ByteBuffer baseline = read(target, WIDTH, HEIGHT);
                float shadowDepth = readDepth(target, shadowed[0], shadowed[1]);
                float litDepth = readDepth(target, lit[0], lit[1]);
                float occluderDepth = readDepth(target, occluded[0], occluded[1]);
                assertTrue(shadowDepth > 0.0f && shadowDepth < 0.999f,
                        "the shadowed receiver must write depth, was " + shadowDepth);
                assertTrue(litDepth > 0.0f && litDepth < 0.999f,
                        "the lit receiver must write depth, was " + litDepth);
                assertTrue(occluderDepth > 0.0f && occluderDepth < shadowDepth,
                        "the caster must be in front of the receiver, was " + occluderDepth
                                + " vs " + shadowDepth);
                assertTrue(rgbChannel(baseline, shadowed, WIDTH, 0)
                                < rgbChannel(baseline, lit, WIDTH, 0),
                        "the fixture shadow band must be darker than the lit band");
                assertOccluderColour(baseline, occluded, WIDTH);

                VfxRenderer.ProjectedDecalStatistics stats = renderDecals(device, renderer,
                        target, settings, List.of(decal), projection, view);
                ByteBuffer after = read(target, WIDTH, HEIGHT);
                assertEquals(1, stats.drawCalls());

                // Occlusion: the caster hides the receiver volume, so its own
                // pixels and depth must stay byte-exact.
                assertSamePixel(baseline, after, occluded[0], occluded[1], WIDTH,
                        "a caster in front of the receiver volume must not receive the decal");
                assertEquals(occluderDepth, readDepth(target, occluded[0], occluded[1]), 0.0f,
                        "the occluded decal must not write depth");
                assertEquals(shadowDepth, readDepth(target, shadowed[0], shadowed[1]), 0.0f,
                        "the shadowed receiver depth must not change");
                assertEquals(litDepth, readDepth(target, lit[0], lit[1]), 0.0f,
                        "the lit receiver depth must not change");

                // Independent blend oracle: coverage 0.5 over the sampled sRGB
                // texture colour (constant RGB 240/160/64).  Both bands must
                // follow the same equation, so the shadow contrast scales by
                // (1 - coverage) instead of being darkened or bleached.
                float coverage = 0.5f;
                int[] textureRgb = {240, 160, 64};
                for (int channel = 0; channel < 3; channel++) {
                    double decalLinear = srgbToLinear(textureRgb[channel] / 255.0);
                    int shadowBefore = rgbChannel(baseline, shadowed, WIDTH, channel);
                    int litBefore = rgbChannel(baseline, lit, WIDTH, channel);
                    int expectedShadow = (int) Math.round(
                            (1.0 - coverage) * shadowBefore + coverage * 255.0 * decalLinear);
                    int expectedLit = (int) Math.round(
                            (1.0 - coverage) * litBefore + coverage * 255.0 * decalLinear);
                    assertEquals(expectedShadow, rgbChannel(after, shadowed, WIDTH, channel), 2,
                            "the shadowed band must follow the alpha-blend equation (channel "
                                    + channel + ")");
                    assertEquals(expectedLit, rgbChannel(after, lit, WIDTH, channel), 2,
                            "the lit band must follow the alpha-blend equation (channel "
                                    + channel + ")");
                    int beforeContrast = litBefore - shadowBefore;
                    int afterContrast = rgbChannel(after, lit, WIDTH, channel)
                            - rgbChannel(after, shadowed, WIDTH, channel);
                    assertEquals((1.0 - coverage) * beforeContrast, afterContrast, 2.0,
                            "the decal must preserve the shadow contrast (channel " + channel
                                    + ")");
                }
                assertTrue(rgbChannel(after, shadowed, WIDTH, 0)
                                < rgbChannel(after, lit, WIDTH, 0) - 32,
                        "the shadowed band must stay visibly darker after the decal");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    /**
     * MSAA documented rejection.  A multisampled target only has a depth
     * renderbuffer, and the backend refuses a multisampled depth texture, so no
     * sampleable single-sample window depth exists for the decal contract.  The
     * renderer must therefore fail closed when no depth texture is supplied.
     */
    @Test
    void multisampledDepthHasNoSampleableTextureSoTheDecalFailsClosed() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram receiverShader = ShaderProgram.fromSources(
                    RECEIVER_VERTEX, RECEIVER_FRAGMENT);
                 Mesh quad = Mesh.from(BuiltinMeshData.texturedQuad(
                         "projected-decal-msaa"));
                 VfxRenderer renderer = new VfxRenderer();
                 Framebuffer target = Framebuffer.fromDescriptor(
                         FramebufferDescriptor.multisampledColorDepthRenderbuffer(
                                 WIDTH, HEIGHT, 4))) {
                Vector3f cameraPosition = new Vector3f(0.0f, 0.0f, 4.2f);
                Matrix4f view = new Matrix4f().lookAt(cameraPosition, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));
                Matrix4f projection = projection(WIDTH, HEIGHT);
                renderScene(device, target, receiverShader, quad,
                        projectionView(projection, view),
                        new Matrix4f().scale(3.0f, 3.0f, 1.0f));
                assertEquals(4, target.samples());
                assertFalse(target.depthAttachmentIsTexture(),
                        "an MSAA target can only provide a depth renderbuffer");
                assertTrue(target.depthAttachment() > 0);
                assertEquals(GL_NO_ERROR, glGetError());

                IllegalArgumentException unsupported = assertThrows(
                        IllegalArgumentException.class,
                        () -> FramebufferDescriptor.builder(WIDTH, HEIGHT)
                                .samples(4).depthTexture().build());
                assertTrue(unsupported.getMessage().contains(
                                "multisampled depth textures are not supported yet"),
                        unsupported.getMessage());

                ProjectedDecalSettings settings = new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, true, 8);
                CommandBuffer commands = device.createCommandBuffer();
                IllegalArgumentException rejected = assertThrows(
                        IllegalArgumentException.class,
                        () -> renderer.recordProjectedDecals(commands, settings,
                                List.of(decal(new Vector3f())), projection, view, 0,
                                WIDTH, HEIGHT));
                assertTrue(rejected.getMessage().contains("require a scene depth texture"),
                        "the documented rejection must name the missing depth contract: "
                                + rejected.getMessage());
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    /**
     * TAA composition.  The decal is recorded into the single-sample scene
     * colour before the temporal resolve; the first resolve (no trusted
     * history) must therefore output the decal-composited current colour.  A
     * decal recorded after the resolve would be absent from that output.  The
     * pass samples the borrowed scene depth while writing a colour-only view of
     * the same colour texture, and the resolve owns its own history target, so
     * no depth feedback or scene-depth write may occur.
     */
    @Test
    void decalCompositesIntoTheTaaResolveInputWithoutDepthFeedback() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram receiverShader = ShaderProgram.fromSources(
                    RECEIVER_VERTEX, RECEIVER_FRAGMENT);
                 Mesh quad = Mesh.from(BuiltinMeshData.texturedQuad(
                         "projected-decal-taa"));
                 VfxRenderer renderer = new VfxRenderer();
                 Framebuffer target = target(WIDTH, HEIGHT);
                 Texture2D velocity = Texture2D.createEmpty(WIDTH, HEIGHT, GL_R32F);
                 Texture2D previousDepth = Texture2D.createEmpty(WIDTH, HEIGHT, GL_R32F);
                 Texture2D validity = Texture2D.createEmpty(WIDTH, HEIGHT, GL_R8);
                 Texture2D reactive = Texture2D.createEmpty(WIDTH, HEIGHT, GL_R8);
                 TemporalAccumulationPass resolve = new TemporalAccumulationPass();
                 TaaHistory history = new TaaHistory(WIDTH, HEIGHT,
                         RenderFormat.RGBA16F)) {
                fillR32F(velocity, WIDTH, HEIGHT, 0.0f);
                fillR32F(previousDepth, WIDTH, HEIGHT, 0.0f);
                fillR8(validity, WIDTH, HEIGHT, 255);
                fillR8(reactive, WIDTH, HEIGHT, 0);
                Vector3f cameraPosition = new Vector3f(0.0f, 0.0f, 4.2f);
                Matrix4f view = new Matrix4f().lookAt(cameraPosition, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));
                Matrix4f projection = projection(WIDTH, HEIGHT);
                Matrix4f projectionView = projectionView(projection, view);
                int[] probe = project(new Vector3f(), projection, view, WIDTH, HEIGHT);
                ProjectedDecalSettings settings = new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, false, 8);
                ProjectedDecal decal = new ProjectedDecal(41L, ALPHA_TEXTURE,
                        new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), new Vector2f(2.4f, 2.4f),
                        new Vector4f(1.0f, 1.0f, 1.0f, 1.0f), 2.0f);

                renderScene(device, target, receiverShader, quad, projectionView,
                        new Matrix4f().scale(3.0f, 3.0f, 1.0f));
                ByteBuffer receiver = read(target, WIDTH, HEIGHT);
                assertEquals(56, rgbChannel(receiver, probe, WIDTH, 0), 2,
                        "the scene pass must write the receiver");
                float depthBefore = readDepth(target, probe[0], probe[1]);
                assertTrue(depthBefore > 0.0f && depthBefore < 0.999f);
                renderDecals(device, renderer, target, settings, List.of(decal),
                        projection, view);
                ByteBuffer current = read(target, WIDTH, HEIGHT);
                assertTrue(pixelDelta(receiver, current, probe[0], probe[1], WIDTH) > 0,
                        "the decal must composite into the TAA resolve input");
                assertEquals(depthBefore, readDepth(target, probe[0], probe[1]), 0.0f,
                        "the decal must not write scene depth");

                TaaSettings taaSettings = new TaaSettings(0.9f, 0.01f, 0.01f, 1.0f,
                        false, 1);
                history.prepareFrame();
                TaaResolveInputs inputs = new TaaResolveInputs(target.colorAttachment(0),
                        history.readColorTexture(), history.readDepthTexture(),
                        target.depthAttachment(), velocity.id(), previousDepth.id(),
                        validity.id(), reactive.id(), history.valid(), WIDTH, HEIGHT,
                        0.0f, 0.0f, 0.0f, 0.0f, new Matrix4f(), new Matrix4f(),
                        new Matrix4f(), taaSettings);
                CommandBuffer commands = device.createCommandBuffer();
                resolve.recordResolve(commands, history.writeFramebuffer(), inputs);
                device.execute(commands);
                float[] resolved = readFloat4(history.writeFramebuffer(), probe[0], probe[1]);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(rgbChannel(current, probe, WIDTH, channel) / 255.0f,
                            resolved[channel], 0.01f,
                            "the first TAA resolve must pass the decal-composited current "
                                    + "colour through (channel " + channel + ")");
                }
                history.commitSuccessfulFrame();
                assertTrue(history.valid(), "the resolve must publish its first history");
                assertEquals(depthBefore, readDepth(target, probe[0], probe[1]), 0.0f,
                        "the temporal resolve must not feed back into the scene depth");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    private static ProjectedDecal decal(Vector3f center, float size) {
        return new ProjectedDecal(1L, DECAL_TEXTURE, center,
                new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                new Vector2f(size, size), new Vector4f(1.0f, 0.45f, 0.15f, 1.0f), 2.0f);
    }

    private static ProjectedDecal decal(Vector3f center) {
        return decal(center, 1.2f);
    }

    private static Framebuffer target(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(GL_RGBA8).depthTexture().build());
    }

    private static Matrix4f projection(int width, int height) {
        return new Matrix4f().perspective((float) Math.toRadians(55.0),
                width / (float) height, 0.1f, 20.0f);
    }

    private static Matrix4f projectionView(Matrix4f projection, Matrix4f view) {
        return new Matrix4f(projection).mul(view);
    }

    private static void renderScene(GlRenderDevice device, Framebuffer target,
                                    ShaderProgram shader, Mesh quad,
                                    Matrix4f projectionView, Matrix4f receiver) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableFramebufferSrgb(false)
                .enableBlend(false)
                .enableCullFace(false)
                .enableDepthTest(true)
                .depthMask(true)
                .clearColor(0.05f, 0.06f, 0.08f, 1.0f)
                .clear(true, true)
                .bindShader(shader)
                // DRAW_MESH executes Mesh.drawBound(), which requires the
                // mesh's VAO to be current.  Omitting this bind makes the
                // draw invalid (no depth/colour) while the statistics still
                // report one recorded draw.
                .bindMesh(quad);
        commands.setUniformMat4(shader, "uMvp",
                        new Matrix4f(projectionView).mul(receiver))
                .drawMesh(quad);
        device.execute(commands);
    }

    private static void assertReceiverProbe(Framebuffer target, int[] probe,
                                            int width, int height, String label) {
        float depth = readDepth(target, probe[0], probe[1]);
        assertTrue(depth > 0.0f && depth < 0.999f,
                label + " must write receiver depth at (" + probe[0] + "," + probe[1]
                        + "), was " + depth);
        assertReceiverColour(read(target, width, height), probe, width);
    }

    private static void assertBackgroundProbe(Framebuffer target, int[] probe,
                                              int width, int height, String label) {
        float depth = readDepth(target, probe[0], probe[1]);
        assertEquals(1.0f, depth, 0.0f,
                label + " must be cleared background at (" + probe[0] + "," + probe[1] + ")");
        ByteBuffer image = read(target, width, height);
        int offset = (probe[1] * width + probe[0]) * 4;
        assertEquals(13, Byte.toUnsignedInt(image.get(offset)), 2,
                label + " clear colour red");
        assertEquals(15, Byte.toUnsignedInt(image.get(offset + 1)), 2,
                label + " clear colour green");
        assertEquals(20, Byte.toUnsignedInt(image.get(offset + 2)), 2,
                label + " clear colour blue");
    }

    private static void renderOverlay(GlRenderDevice device, Framebuffer target,
                                      ShaderProgram shader, Mesh quad,
                                      Matrix4f projectionView, Matrix4f transform,
                                      Vector4f color) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableFramebufferSrgb(false)
                .materialState(BlendMode.ALPHA, false)
                .bindShader(shader)
                .bindMesh(quad)
                .setUniformMat4(shader, "uMvp", new Matrix4f(projectionView).mul(transform))
                .setUniformVec4(shader, "uColor", color)
                .drawMesh(quad);
        device.execute(commands);
    }

    /** The overlay must composite over the decal, not replace or hide it. */
    private static void assertOverlayBlend(ByteBuffer decalOnly,
                                           ByteBuffer decalThenOverlay, int[] probe,
                                           int width, int height) {
        int offset = (probe[1] * width + probe[0]) * 4;
        double[] overlayRgb = {0.1, 0.8, 0.2};
        for (int channel = 0; channel < 3; channel++) {
            int under = Byte.toUnsignedInt(decalOnly.get(offset + channel));
            int actual = Byte.toUnsignedInt(decalThenOverlay.get(offset + channel));
            double expected = 0.5 * overlayRgb[channel] * 255.0 + 0.5 * under;
            assertEquals(expected, actual, 1.0,
                    "the transparent overlay must blend over the decal (channel " + channel
                            + ")");
        }
    }

    private static VfxRenderer.ProjectedDecalStatistics renderDecals(            GlRenderDevice device, VfxRenderer renderer, Framebuffer target,
            ProjectedDecalSettings settings, List<ProjectedDecal> decals,
            Matrix4f projection, Matrix4f view) {
        // The renderer samples the borrowed scene depth while it draws.  Bind a
        // colour-only view of the same colour texture for the decal pass so the
        // sampled depth texture is not attached to the draw framebuffer.
        try (Framebuffer decalTarget = Framebuffer.fromDescriptorSharingAttachments(
                FramebufferDescriptor.colorOnly(target.width(), target.height(), GL_RGBA8),
                target, null)) {
            CommandBuffer commands = device.createCommandBuffer();
            commands.bindFramebuffer(decalTarget)
                    .viewport(0, 0, target.width(), target.height())
                    .enableFramebufferSrgb(false);
            VfxRenderer.ProjectedDecalStatistics stats = renderer.recordProjectedDecals(
                    commands, settings, decals, projection, view, target.depthAttachment(),
                    target.width(), target.height());
            device.execute(commands);
            return stats;
        }
    }

    private static float readDepth(Framebuffer target, int x, int y) {
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glBindFramebuffer(GL_FRAMEBUFFER, target.id());
        glReadPixels(x, y, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        return depth.get(0);
    }

    private static ByteBuffer read(Framebuffer target, int width, int height) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glBindFramebuffer(GL_FRAMEBUFFER, target.id());
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static int[] project(Vector3f world, Matrix4f projection, Matrix4f view,
                                 int width, int height) {
        Vector4f clip = new Vector4f(world, 1.0f);
        new Matrix4f(projection).mul(view).transform(clip);
        float inverseW = 1.0f / clip.w;
        int x = Math.round((clip.x * inverseW * 0.5f + 0.5f) * width);
        int y = Math.round((clip.y * inverseW * 0.5f + 0.5f) * height);
        return new int[]{clamp(x, 0, width - 1), clamp(y, 0, height - 1)};
    }

    private static void assertReceiverColour(ByteBuffer image, int[] center, int width) {
        int offset = (center[1] * width + center[0]) * 4;
        String label = "the receiver shader must write (0.22,0.24,0.28) at the decal centre, "
                + "not the clear colour";
        assertEquals(56, Byte.toUnsignedInt(image.get(offset)), 2, label);
        assertEquals(61, Byte.toUnsignedInt(image.get(offset + 1)), 2, label);
        assertEquals(71, Byte.toUnsignedInt(image.get(offset + 2)), 2, label);
    }

    private static int changed(ByteBuffer baseline, ByteBuffer result,
                               int centerX, int centerY, int radius,
                               int width, int height) {
        int changed = 0;
        for (int y = Math.max(0, centerY - radius); y <= Math.min(height - 1, centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(width - 1, centerX + radius); x++) {
                int offset = (y * width + x) * 4;
                if (baseline.get(offset) != result.get(offset)
                        || baseline.get(offset + 1) != result.get(offset + 1)
                        || baseline.get(offset + 2) != result.get(offset + 2)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static void assertExact(String message, ByteBuffer expected, ByteBuffer actual) {
        assertEquals(expected.capacity(), actual.capacity(), message);
        for (int index = 0; index < expected.capacity(); index++) {
            if (expected.get(index) != actual.get(index)) {
                fail(message + " at byte " + index + ": expected "
                        + Byte.toUnsignedInt(expected.get(index)) + " but was "
                        + Byte.toUnsignedInt(actual.get(index)));
            }
        }
    }

    /** Projects the world point at decal texture column centre {@code u}. */
    private static int[] decalColumn(float u, float size, Matrix4f projection,
                                     Matrix4f view) {
        return project(new Vector3f(size * (u - 0.5f), 0.0f, 0.0f),
                projection, view, WIDTH, HEIGHT);
    }

    private static int rgbChannel(ByteBuffer image, int[] probe, int width, int channel) {
        return Byte.toUnsignedInt(image.get((probe[1] * width + probe[0]) * 4 + channel));
    }

    /** Sum of absolute RGB differences at one probe pixel. */
    private static int pixelDelta(ByteBuffer expected, ByteBuffer actual, int x, int y,
                                  int width) {
        int offset = (y * width + x) * 4;
        int delta = 0;
        for (int channel = 0; channel < 3; channel++) {
            delta += Math.abs(Byte.toUnsignedInt(expected.get(offset + channel))
                    - Byte.toUnsignedInt(actual.get(offset + channel)));
        }
        return delta;
    }

    private static void assertSamePixel(ByteBuffer expected, ByteBuffer actual, int x, int y,
                                        int width, String message) {
        int offset = (y * width + x) * 4;
        for (int channel = 0; channel < 4; channel++) {
            assertEquals(Byte.toUnsignedInt(expected.get(offset + channel)),
                    Byte.toUnsignedInt(actual.get(offset + channel)),
                    message + " (channel " + channel + " at " + x + "," + y + ")");
        }
    }

    private static void assertOccluderColour(ByteBuffer image, int[] probe, int width) {
        int offset = (probe[1] * width + probe[0]) * 4;
        String label = "the caster shader must write (0.30,0.35,0.28) at the occluded probe";
        assertEquals(77, Byte.toUnsignedInt(image.get(offset)), 2, label);
        assertEquals(89, Byte.toUnsignedInt(image.get(offset + 1)), 2, label);
        assertEquals(71, Byte.toUnsignedInt(image.get(offset + 2)), 2, label);
    }

    private static void renderShadowScene(GlRenderDevice device, Framebuffer target,
                                          ShaderProgram receiverShader,
                                          ShaderProgram occluderShader, Mesh quad,
                                          Matrix4f projectionView, Matrix4f receiver,
                                          Matrix4f occluder) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableFramebufferSrgb(false)
                .enableBlend(false)
                .enableCullFace(false)
                .enableDepthTest(true)
                .depthMask(true)
                .clearColor(0.05f, 0.06f, 0.08f, 1.0f)
                .clear(true, true)
                .bindShader(receiverShader)
                .bindMesh(quad)
                .setUniformMat4(receiverShader, "uMvp",
                        new Matrix4f(projectionView).mul(receiver))
                .setUniformMat4(receiverShader, "uModel", receiver)
                .drawMesh(quad)
                .bindShader(occluderShader)
                .bindMesh(quad)
                .setUniformMat4(occluderShader, "uMvp",
                        new Matrix4f(projectionView).mul(occluder))
                .drawMesh(quad);
        device.execute(commands);
    }

    private static void fillR32F(Texture2D texture, int width, int height, float value) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(width * height * Float.BYTES);
        for (int index = 0; index < width * height; index++) {
            bytes.putFloat(value);
        }
        bytes.flip();
        texture.uploadRegion(0, 0, width, height, bytes);
    }

    private static void fillR8(Texture2D texture, int width, int height, int value) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(width * height);
        for (int index = 0; index < width * height; index++) {
            bytes.put((byte) value);
        }
        bytes.flip();
        texture.uploadRegion(0, 0, width, height, bytes);
    }

    private static float[] readFloat4(Framebuffer framebuffer, int x, int y) {
        FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer.id());
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        return new float[]{pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3)};
    }

    private static double srgbToLinear(double encoded) {
        return encoded <= 0.04045
                ? encoded / 12.92
                : Math.pow((encoded + 0.055) / 1.055, 2.4);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
