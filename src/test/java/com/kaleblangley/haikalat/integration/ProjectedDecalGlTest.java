package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
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
import java.util.List;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * Real-GL contract for the projected-decal receiver volume.
 *
 * <p>One scene pass renders depth for the receiver under test, then the decal
 * renderer projects onto that depth and the result is read back.  The cases
 * cover a sloped receiver, a perpendicular wall (facing rejection), a wall
 * behind the configured thickness and a wall in front of the surface offset
 * (depth rejection in both directions), a moving receiver, an exact
 * disabled/no-receiver no-op, and a resize.  Written but not executed in this
 * worker; run with the command recorded in the worker report.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ProjectedDecalGlTest {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 72;
    private static final AssetRef DECAL_TEXTURE =
            AssetRef.of("/vfx/decals/kenney/particle_pack/scorch_01.png");
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
                    ByteBuffer baseline = read(target, WIDTH, HEIGHT);
                    VfxRenderer.ProjectedDecalStatistics stats = renderDecals(
                            device, renderer, target, settings, List.of(decal), projection, view);
                    ByteBuffer sloped = read(target, WIDTH, HEIGHT);
                    assertEquals(1, stats.drawn());
                    assertEquals(1, stats.drawCalls());
                    assertTrue(changed(baseline, sloped, center[0], center[1], 4,
                                    WIDTH, HEIGHT) > 0,
                            "sloped receiver inside the volume must receive the decal");
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
            }
        }
    }

    private static ProjectedDecal decal(Vector3f center) {
        return new ProjectedDecal(1L, DECAL_TEXTURE, center,
                new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                new Vector2f(1.2f, 1.2f), new Vector4f(1.0f, 0.45f, 0.15f, 1.0f), 2.0f);
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
                .bindShader(shader);
        commands.setUniformMat4(shader, "uMvp",
                        new Matrix4f(projectionView).mul(receiver))
                .drawMesh(quad);
        device.execute(commands);
    }

    private static VfxRenderer.ProjectedDecalStatistics renderDecals(
            GlRenderDevice device, VfxRenderer renderer, Framebuffer target,
            ProjectedDecalSettings settings, List<ProjectedDecal> decals,
            Matrix4f projection, Matrix4f view) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height());
        VfxRenderer.ProjectedDecalStatistics stats = renderer.recordProjectedDecals(
                commands, settings, decals, projection, view, target.depthAttachment(),
                target.width(), target.height());
        device.execute(commands);
        return stats;
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

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
