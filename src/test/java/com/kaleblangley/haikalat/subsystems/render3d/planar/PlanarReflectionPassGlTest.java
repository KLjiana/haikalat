package com.kaleblangley.haikalat.subsystems.render3d.planar;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.lwjgl.opengl.GL11.GL_CCW;



import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_FRONT_FACE;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glIsTexture;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL acceptance for the bounded planar reflection pass.
 *
 * <p>The fixture supplies its own simple colour-only scene recorder; the pass
 * owns the RGBA16F colour and depth FBO.  Expectations come from projecting the
 * mirrored world point with the unmirrored camera in test code, never from the
 * pass's own matrices, so a wrong mirror camera or a non-inverted winding is
 * observable.  All cases are behind the explicit {@code haikalat.glSmoke}
 * guard and must not be treated as executed unless the GL task selected them.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class PlanarReflectionPassGlTest {
    private static final int REQUESTED_SIZE = 128;
    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uViewProjection;
            uniform mat4 uModel;
            void main() {
                gl_Position = uViewProjection * uModel * vec4(aPosition, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
            """;
    private static final float[] QUAD_POSITIONS = {
            -0.5f, -0.5f, 0.0f,
            0.5f, -0.5f, 0.0f,
            0.5f, 0.5f, 0.0f,
            -0.5f, 0.5f, 0.0f
    };
    private static final int[] CCW_INDICES = {0, 1, 2, 0, 2, 3};
    private static final int[] CW_INDICES = {0, 2, 1, 0, 3, 2};

    @Test
    void mirroredObjectAppearsAtTheExpectedMirroredScreenPosition() {
        try (GlfwWindow window = window("Planar reflection mirror position")) {
            Fixture fixture = fixture(window);
            try (PlanarReflectionPass pass = pass(1.0f, 1, 512, window);
                 ShaderProgram shader = fixture.shader;
                 Mesh quad = quad(false)) {
                int width = window.width();
                int height = window.height();
                Camera camera = camera();
                Vector3f object = new Vector3f(0.3f, 0.5f, 0.0f);
                Matrix4f model = model(object, 0.5f);
                CommandBuffer cmd = new CommandBuffer();
                boolean updated = pass.record(cmd, camera, width, height, null,
                        drawQuad(shader, quad, model, 1.0f, 0.0f, 0.0f, false));
                assertTrue(updated, "the first reflection must update");
                fixture.device.execute(cmd);

                assertRestoredDefaultTarget(width, height);
                float[] pixels = readColor(pass);
                int[] direct = project(camera, width, height, object.x, object.y, object.z);
                int[] reflected = project(camera, width, height,
                        object.x, -object.y, object.z);
                assertTrue(Math.abs(direct[1] - reflected[1]) > 2,
                        "the mirrored point must land on a different scanline");
                assertPixel(pixels, pass.width(), pass.height(), reflected[0], reflected[1],
                        1.0f, 0.0f, 0.0f);
                int backgroundX = pass.width() - 1 - reflected[0];
                int backgroundY = pass.height() - 1 - reflected[1];
                assertBackground(pixels, pass.width(), pass.height(), backgroundX, backgroundY);
                GlDebug.assertNoError("planar reflection mirror position");
            }
        }
    }

    @Test
    void belowPlaneGeometryIsRemovedByTheObliqueNearPlane() {
        try (GlfwWindow window = window("Planar reflection oblique clip")) {
            Fixture fixture = fixture(window);
            try (PlanarReflectionPass pass = pass(1.0f, 1, 512, window);
                 ShaderProgram shader = fixture.shader;
                 Mesh quad = quad(false)) {
                int width = window.width();
                int height = window.height();
                Camera camera = camera();
                Vector3f above = new Vector3f(0.45f, 0.5f, 0.0f);
                Vector3f below = new Vector3f(-0.45f, -0.4f, 0.0f);
                Matrix4f aboveModel = model(above, 0.5f);
                Matrix4f belowModel = model(below, 0.5f);
                CommandBuffer cmd = new CommandBuffer();
                boolean updated = pass.record(cmd, camera, width, height, null,
                        (commands, frame) -> {
                            Matrix4f viewProjection = frame.viewProjection(new Matrix4f());
                            commands.enableCullFace(false);
                            drawQuad(commands, shader, quad, aboveModel, 1.0f, 0.0f, 0.0f,
                                    viewProjection);
                            drawQuad(commands, shader, quad, belowModel, 0.0f, 1.0f, 0.0f,
                                    viewProjection);
                        });
                assertTrue(updated);
                fixture.device.execute(cmd);

                float[] pixels = readColor(pass);
                int[] belowPixel = project(camera, width, height,
                        below.x, -below.y, below.z);
                assertInside(pass.width(), pass.height(), belowPixel[0], belowPixel[1], 4);
                assertTrue(countColor(pixels, pass.width(), pass.height(),
                        1.0f, 0.0f, 0.0f) > 20, "the above-plane quad must be drawn");
                assertEquals(0, countColor(pixels, pass.width(), pass.height(),
                                0.0f, 1.0f, 0.0f),
                        "geometry between the mirrored camera and the water plane must be clipped");
                GlDebug.assertNoError("planar reflection oblique clip");
            }
        }
    }

    @Test
    void mirroredFrontFaceKeepsBackFaceCullingCorrect() {
        try (GlfwWindow window = window("Planar reflection winding")) {
            Fixture fixture = fixture(window);
            try (PlanarReflectionPass pass = pass(1.0f, 1, 512, window);
                 ShaderProgram shader = fixture.shader;
                 Mesh ccw = quad(false);
                 Mesh cw = quad(true)) {
                int width = window.width();
                int height = window.height();
                Camera camera = camera();
                Matrix4f redModel = model(new Vector3f(-0.45f, 0.5f, 0.0f), 0.5f);
                Matrix4f blueModel = model(new Vector3f(0.45f, 0.5f, 0.0f), 0.5f);

                float[] cullingOff = renderWindingScene(fixture, pass, shader, ccw, cw,
                        redModel, blueModel, false, width, height, camera);
                float[] cullingOn = renderWindingScene(fixture, pass, shader, ccw, cw,
                        redModel, blueModel, true, width, height, camera);

                int redOff = countColor(cullingOff, pass.width(), pass.height(),
                        1.0f, 0.0f, 0.0f);
                int blueOff = countColor(cullingOff, pass.width(), pass.height(),
                        0.0f, 0.0f, 1.0f);
                assertTrue(redOff > 20 && blueOff > 20,
                        "the control render without culling must show both quads");
                int redOn = countColor(cullingOn, pass.width(), pass.height(),
                        1.0f, 0.0f, 0.0f);
                int blueOn = countColor(cullingOn, pass.width(), pass.height(),
                        0.0f, 0.0f, 1.0f);
                assertTrue(redOn > 20,
                        "a world counter-clockwise front face must stay front after mirroring");
                assertEquals(0, blueOn,
                        "a world clockwise back face must stay culled after mirroring");
                assertTrue(Math.abs(redOff - redOn) <= 2,
                        "the visible front-face area must not change when culling is enabled");
                GlDebug.assertNoError("planar reflection winding");
            }
        }
    }

    @Test
    void updateIntervalPreservesAndThenRefreshesTheReflectionTexture() {
        try (GlfwWindow window = window("Planar reflection throttling")) {
            Fixture fixture = fixture(window);
            try (PlanarReflectionPass pass = pass(1.0f, 3, 512, window);
                 ShaderProgram shader = fixture.shader;
                 Mesh quad = quad(false)) {
                int width = window.width();
                int height = window.height();
                Camera camera = camera();
                Vector3f object = new Vector3f(0.3f, 0.5f, 0.0f);
                Matrix4f model = model(object, 0.5f);
                float[] color = {1.0f, 0.0f, 0.0f};
                AtomicInteger sceneCalls = new AtomicInteger();
                int[] pixel = project(camera, width, height, object.x, -object.y, object.z);

                CommandBuffer first = new CommandBuffer();
                assertTrue(pass.record(first, camera, width, height, null, (commands, frame) -> {
                    sceneCalls.incrementAndGet();
                    Matrix4f viewProjection = frame.viewProjection(new Matrix4f());
                    commands.enableCullFace(false);
                    drawQuad(commands, shader, quad, model, color[0], color[1], color[2],
                            viewProjection);
                }));
                fixture.device.execute(first);
                assertEquals(1, sceneCalls.get());
                float[] red = readColor(pass);
                assertPixel(red, pass.width(), pass.height(), pixel[0], pixel[1],
                        1.0f, 0.0f, 0.0f);

                color[0] = 0.0f;
                color[1] = 0.0f;
                color[2] = 1.0f;
                for (int call = 0; call < 2; call++) {
                    CommandBuffer skipped = new CommandBuffer();
                    assertFalse(pass.record(skipped, camera, width, height, null,
                            (commands, frame) -> {
                                sceneCalls.incrementAndGet();
                                fail("a throttled call must not invoke the scene recorder");
                            }));
                    assertEquals(0, skipped.commandCount(),
                            "a throttled call must record no GPU commands");
                }
                assertEquals(1, sceneCalls.get());
                float[] stillRed = readColor(pass);
                assertPixel(stillRed, pass.width(), pass.height(), pixel[0], pixel[1],
                        1.0f, 0.0f, 0.0f);

                CommandBuffer refreshed = new CommandBuffer();
                assertTrue(pass.record(refreshed, camera, width, height, null,
                        (commands, frame) -> {
                            sceneCalls.incrementAndGet();
                            Matrix4f viewProjection = frame.viewProjection(new Matrix4f());
                            commands.enableCullFace(false);
                            drawQuad(commands, shader, quad, model, color[0], color[1],
                                    color[2], viewProjection);
                        }));
                fixture.device.execute(refreshed);
                assertEquals(2, sceneCalls.get());
                float[] blue = readColor(pass);
                assertPixel(blue, pass.width(), pass.height(), pixel[0], pixel[1],
                        0.0f, 0.0f, 1.0f);

                pass.requestUpdate();
                CommandBuffer forced = new CommandBuffer();
                assertTrue(pass.record(forced, camera, width, height, null, (commands, frame) -> {
                    sceneCalls.incrementAndGet();
                    Matrix4f viewProjection = frame.viewProjection(new Matrix4f());
                    commands.enableCullFace(false);
                    drawQuad(commands, shader, quad, model, color[0], color[1], color[2],
                            viewProjection);
                }));
                fixture.device.execute(forced);
                assertEquals(3, sceneCalls.get());
                GlDebug.assertNoError("planar reflection throttling");
            }
        }
    }

    @Test
    void resizeRebuildsAttachmentsAtTheScaledExtent() {
        try (GlfwWindow window = window("Planar reflection resize")) {
            Fixture fixture = fixture(window);
            try (PlanarReflectionPass pass = pass(0.5f, 1, 512, window);
                 ShaderProgram shader = fixture.shader;
                 Mesh quad = quad(false)) {
                int width = window.width();
                int height = window.height();
                Camera camera = camera();
                Vector3f object = new Vector3f(0.3f, 0.5f, 0.0f);
                Matrix4f model = model(object, 0.5f);
                int oldWidth = pass.width();
                int oldHeight = pass.height();
                int oldColor = pass.colorTexture();
                int oldDepth = pass.depthTexture();
                assertEquals(Math.max(1, Math.round(width * 0.5f)), oldWidth);
                assertEquals(Math.max(1, Math.round(height * 0.5f)), oldHeight);

                CommandBuffer first = new CommandBuffer();
                assertTrue(pass.record(first, camera, width, height, null,
                        drawQuad(shader, quad, model, 1.0f, 0.0f, 0.0f, false)));
                fixture.device.execute(first);
                float[] firstPixels = readColor(pass);
                int[] firstPixel = project(camera, width, height,
                        object.x, -object.y, object.z);
                assertPixel(firstPixels, pass.width(), pass.height(),
                        firstPixel[0] * pass.width() / width,
                        firstPixel[1] * pass.height() / height, 1.0f, 0.0f, 0.0f);

                pass.resize(160, 120);
                assertEquals(80, pass.width());
                assertEquals(60, pass.height());
                assertNotEquals(oldColor, pass.colorTexture());
                assertNotEquals(oldDepth, pass.depthTexture());
                assertFalse(glIsTexture(oldColor), "the old colour texture must be retired");
                assertFalse(glIsTexture(oldDepth), "the old depth texture must be retired");

                CommandBuffer resized = new CommandBuffer();
                assertTrue(pass.record(resized, camera, 160, 120, null,
                        drawQuad(shader, quad, model, 1.0f, 0.0f, 0.0f, false)));
                fixture.device.execute(resized);
                float[] resizedPixels = readColor(pass);
                int[] resizedPixel = project(camera, 160, 120, object.x, -object.y, object.z);
                assertPixel(resizedPixels, pass.width(), pass.height(),
                        resizedPixel[0] * pass.width() / 160,
                        resizedPixel[1] * pass.height() / 120, 1.0f, 0.0f, 0.0f);
                assertTrue(countColor(resizedPixels, pass.width(), pass.height(),
                        1.0f, 0.0f, 0.0f) > 10);

                int retainedWidth = pass.width();
                assertThrows(IllegalArgumentException.class, () -> pass.resize(0, 120));
                assertEquals(retainedWidth, pass.width(),
                        "a rejected resize must keep the previous attachments");
                GlDebug.assertNoError("planar reflection resize");
            }
        }
    }

    @Test
    void disabledAndClosedPassesLeaveNoGlResourcesOrErrors() {
        try (GlfwWindow window = window("Planar reflection lifecycle")) {
            Fixture fixture = fixture(window);
            assertThrows(IllegalArgumentException.class,
                    () -> new PlanarReflectionPass(PlanarReflectionSettings.disabled(),
                            window.width(), window.height()));
            try (PlanarReflectionPass pass = pass(1.0f, 1, 512, window);
                 ShaderProgram shader = fixture.shader;
                 Mesh quad = quad(false)) {
                int width = window.width();
                int height = window.height();
                int color = pass.colorTexture();
                int depth = pass.depthTexture();
                CommandBuffer cmd = new CommandBuffer();
                assertTrue(pass.record(cmd, camera(), width, height, null,
                        drawQuad(shader, quad, new Matrix4f(), 1.0f, 0.0f, 0.0f, false)));
                fixture.device.execute(cmd);
                pass.close();
                assertTrue(pass.isClosed());
                assertEquals(0, pass.width());
                assertEquals(0, pass.height());
                assertEquals(0, pass.colorTexture());
                assertEquals(0, pass.depthTexture());
                assertFalse(glIsTexture(color));
                assertFalse(glIsTexture(depth));
                pass.close();
                assertThrows(GlException.class, () -> pass.record(new CommandBuffer(), camera(),
                        width, height, null, (commands, frame) -> fail("closed pass must not record")));
                GlDebug.assertNoError("planar reflection lifecycle");
            }
        }
    }

    private static float[] renderWindingScene(Fixture fixture, PlanarReflectionPass pass,
                                              ShaderProgram shader, Mesh ccw, Mesh cw,
                                              Matrix4f redModel, Matrix4f blueModel,
                                              boolean culling, int width, int height,
                                              Camera camera) {
        CommandBuffer cmd = new CommandBuffer();
        assertTrue(pass.record(cmd, camera, width, height, null, (commands, frame) -> {
            Matrix4f viewProjection = frame.viewProjection(new Matrix4f());
            commands.enableCullFace(culling);
            drawQuad(commands, shader, ccw, redModel, 1.0f, 0.0f, 0.0f, viewProjection);
            drawQuad(commands, shader, cw, blueModel, 0.0f, 0.0f, 1.0f, viewProjection);
        }));
        fixture.device.execute(cmd);
        return readColor(pass);
    }

    private static PlanarReflectionPass pass(float scale, int interval, int maxResolution, GlfwWindow window) {
        PlanarReflectionSettings settings = PlanarReflectionSettings.builder()
                .enabled(true)
                .planeY(0.0f)
                .resolutionScale(scale)
                .maxResolution(maxResolution)
                .updateIntervalFrames(interval)
                .nearPlaneOffset(0.01f)
                .build();
        return new PlanarReflectionPass(settings, window.width(), window.height());
    }

    private static Camera camera() {
        return new Camera(new Vector3f(0.0f, 1.0f, 3.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -10.0f);
    }

    private static Matrix4f model(Vector3f center, float size) {
        return new Matrix4f().translate(center).scale(size, size, size);
    }

    private static PlanarReflectionPass.SceneRecorder drawQuad(
            ShaderProgram shader, Mesh mesh, Matrix4f model,
            float red, float green, float blue, boolean cull) {
        return (commands, frame) -> {
            commands.enableCullFace(cull);
            drawQuad(commands, shader, mesh, model, red, green, blue,
                    frame.viewProjection(new Matrix4f()));
        };
    }

    private static void drawQuad(CommandBuffer commands, ShaderProgram shader, Mesh mesh,
                                 Matrix4f model, float red, float green, float blue,
                                 Matrix4f viewProjection) {
        commands.bindShader(shader)
                .setUniformMat4(shader, "uViewProjection", viewProjection)
                .setUniformMat4(shader, "uModel", model)
                .setUniformVec4(shader, "uColor", new Vector4f(red, green, blue, 1.0f))
                .bindMesh(mesh)
                .drawMesh(mesh);
    }

    private static Mesh quad(boolean clockwise) {
        VertexAttribute position = VertexAttribute.builder()
                .index(0).size(3).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        return Mesh.builder()
                .vertices(QUAD_POSITIONS, 3 * Float.BYTES, position)
                .indices(clockwise ? CW_INDICES : CCW_INDICES)
                .build();
    }

    private static int[] project(Camera camera, int width, int height,
                                 float x, float y, float z) {
        Matrix4f projection = CameraProjection.stable(camera, width, height, new Matrix4f());
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Vector4f clip = projection.mul(view, new Matrix4f())
                .transform(new Vector4f(x, y, z, 1.0f));
        assertTrue(clip.w > 0.0f, "fixture point must be in front of the camera");
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        int pixelX = Math.round((ndcX * 0.5f + 0.5f) * width);
        int pixelY = Math.round((ndcY * 0.5f + 0.5f) * height);
        assertInside(width, height, pixelX, pixelY, 4);
        return new int[]{pixelX, pixelY};
    }

    private static void assertInside(int width, int height, int x, int y, int margin) {
        assertTrue(x >= margin && x < width - margin && y >= margin && y < height - margin,
                "expected fixture pixel " + x + "," + y + " must be inside " + width + "x"
                        + height + " with margin " + margin);
    }

    private static float[] readColor(PlanarReflectionPass pass) {
        int width = pass.width();
        int height = pass.height();
        glBindFramebuffer(GL_FRAMEBUFFER, pass.framebuffer().id());
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, buffer);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        float[] pixels = new float[width * height * 4];
        buffer.get(pixels);
        return pixels;
    }

    private static void assertPixel(float[] pixels, int width, int height, int x, int y,
                                    float red, float green, float blue) {
        assertInside(width, height, x, y, 1);
        for (int sampleY = Math.max(0, y - 1); sampleY <= Math.min(height - 1, y + 1);
             sampleY++) {
            for (int sampleX = Math.max(0, x - 1); sampleX <= Math.min(width - 1, x + 1);
                 sampleX++) {
                int offset = (sampleY * width + sampleX) * 4;
                if (Math.abs(pixels[offset] - red) < 0.05f
                        && Math.abs(pixels[offset + 1] - green) < 0.05f
                        && Math.abs(pixels[offset + 2] - blue) < 0.05f
                        && pixels[offset + 3] > 0.5f) {
                    return;
                }
            }
        }
        fail("expected colour (" + red + "," + green + "," + blue + ") near " + x + "," + y);
    }

    private static void assertBackground(float[] pixels, int width, int height, int x, int y) {
        int offset = (y * width + x) * 4;
        assertTrue(pixels[offset] < 0.01f && pixels[offset + 1] < 0.01f
                        && pixels[offset + 2] < 0.01f,
                "expected transparent black background at " + x + "," + y);
    }

    private static int countColor(float[] pixels, int width, int height,
                                  float red, float green, float blue) {
        int count = 0;
        for (int index = 0; index < width * height; index++) {
            int offset = index * 4;
            if (Math.abs(pixels[offset] - red) < 0.1f
                    && Math.abs(pixels[offset + 1] - green) < 0.1f
                    && Math.abs(pixels[offset + 2] - blue) < 0.1f
                    && pixels[offset + 3] > 0.5f) {
                count++;
            }
        }
        return count;
    }

    private static void assertRestoredDefaultTarget(int width, int height) {
        assertEquals(0, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
                "the pass must restore the default framebuffer");
        assertEquals(GL_CCW, glGetInteger(GL_FRONT_FACE),
                "the pass must restore counter-clockwise front faces");
        IntBuffer viewport = BufferUtils.createIntBuffer(4);
        org.lwjgl.opengl.GL11.glGetIntegerv(GL_VIEWPORT, viewport);
        assertEquals(0, viewport.get(0));
        assertEquals(0, viewport.get(1));
        assertEquals(width, viewport.get(2));
        assertEquals(height, viewport.get(3));
    }

    private static GlfwWindow window(String title) {
        return new GlfwWindow.Builder()
                .dimensions(REQUESTED_SIZE, REQUESTED_SIZE)
                .title(title)
                .visible(false)
                .build();
    }

    private static Fixture fixture(GlfwWindow window) {
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        return new Fixture(new GlRenderDevice(),
                ShaderProgram.fromSources(VERTEX, FRAGMENT));
    }

    private record Fixture(GlRenderDevice device, ShaderProgram shader) {
    }
}
