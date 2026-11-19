package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * Real-GL acceptance for the production GPU-particle VFX consumer.
 *
 * <p>The cases prove the consumer's production use, not only the owner API:</p>
 * <ul>
 *   <li>a same-frame single-sample depth occluder hides the particles behind it
 *       and the depth-tested draw leaves the depth attachment unchanged;</li>
 *   <li>the particle draw is recorded before a transparent collector on the
 *       same colour target, which is the required OIT/stochastic boundary
 *       ordering (flipping the order changes the alpha composition);</li>
 *   <li>a transactional capacity resize publishes a usable candidate, keeps the
 *       previous owner on failure, survives a viewport resize, and releases
 *       every tracked GL resource.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GpuParticleVfxGlTest {
    private static final int SIZE = 64;
    private static final float OCCLUDER_DEPTH = 0.1f;

    @Test
    void sameFrameDepthOccludesParticlesAndTheDrawWritesNoDepth() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (Framebuffer target = depthTarget(SIZE, SIZE);
                 VfxRenderer renderer = new VfxRenderer();
                 GpuParticleSystem particles = new GpuParticleSystem(settings(SIZE))) {
                renderer.gpuParticleSettings(new GpuParticleVfx.Settings(
                        1.0f / 60.0f, 48, GpuParticleVfx.SortingMode.NONE));

                clear(device, target);
                // Left half: a near opaque depth plane in front of the particle
                // billboards. Right half stays at the far clear value.
                glEnable(GL_SCISSOR_TEST);
                glScissor(0, 0, SIZE / 2, SIZE);
                glClearDepth(OCCLUDER_DEPTH);
                glClear(GL_DEPTH_BUFFER_BIT);
                glDisable(GL_SCISSOR_TEST);
                glClearDepth(1.0f);
                device.invalidateState();

                CommandBuffer commands = device.createCommandBuffer();
                GpuParticleVfx.Statistics statistics = renderer.recordGpuParticles(commands,
                        particles, settings(SIZE), projection(), view(), target.depthAttachment(),
                        target.width(), target.height());
                device.execute(commands);

                assertEquals(48, statistics.drawn());
                assertEquals(1L, statistics.steps());
                ByteBuffer color = readColor(target);
                int stride = target.width();
                int leftCoverage = nonBlackPixels(color, stride, 0, 0, SIZE / 2, SIZE);
                int rightCoverage = nonBlackPixels(color, stride, SIZE / 2, 0, SIZE / 2, SIZE);
                assertEquals(0, leftCoverage,
                        "particles behind the same-frame depth plane must be hidden");
                assertTrue(rightCoverage > 100,
                        "particles in front of the far depth must remain visible: "
                                + rightCoverage);

                FloatBuffer depth = readDepth(target);
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        float expected = x < SIZE / 2 ? OCCLUDER_DEPTH : 1.0f;
                        assertEquals(expected, depth.get(y * SIZE + x), 1.0e-4f,
                                "the particle draw must not write depth at " + x + "," + y);
                    }
                }
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void transparentCollectorIsRecordedAfterTheParticleDraw() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (Framebuffer target = depthTarget(SIZE, SIZE);
                 VfxRenderer renderer = new VfxRenderer();
                 GpuParticleSystem particles = new GpuParticleSystem(settings(SIZE));
                 ShaderProgram collector = ShaderProgram.fromResource(
                         GpuParticleVfxGlTest.class,
                         "/shaders/gpu-particle-vfx/collector.vert",
                         "/shaders/gpu-particle-vfx/collector.frag");
                 VertexArray vertexArray = new VertexArray()) {
                renderer.gpuParticleSettings(new GpuParticleVfx.Settings(
                        1.0f / 60.0f, 48, GpuParticleVfx.SortingMode.NONE));

                clear(device, target);
                CommandBuffer first = device.createCommandBuffer();
                renderer.recordGpuParticles(first, particles, settings(SIZE), projection(), view(),
                        target.depthAttachment(), target.width(), target.height());
                recordCollector(first, collector, vertexArray);
                device.execute(first);
                ByteBuffer collectorAfterParticles = readColor(target);

                renderer.gpuParticleVfx().replay();
                clear(device, target);
                CommandBuffer second = device.createCommandBuffer();
                recordCollector(second, collector, vertexArray);
                renderer.recordGpuParticles(second, particles, settings(SIZE), projection(), view(),
                        target.depthAttachment(), target.width(), target.height());
                device.execute(second);
                ByteBuffer collectorBeforeParticles = readColor(target);

                int index = maxRedPixel(collectorBeforeParticles);
                int redAfter = red(collectorAfterParticles, index);
                int redBefore = red(collectorBeforeParticles, index);
                int blueAfter = blue(collectorAfterParticles, index);
                int blueBefore = blue(collectorBeforeParticles, index);
                assertTrue(redAfter > 32,
                        "the alpha-over frame must keep a visible particle contribution: "
                                + redAfter);
                assertTrue(Math.abs(redAfter * 2 - redBefore) <= 3,
                        "the collector must composite after particles; red after=" + redAfter
                                + " before=" + redBefore);
                assertEquals(blueAfter, blueBefore, 2,
                        "the collector's blue contribution must be identical in both orders");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void resizePublishesACandidateKeepsTheOldOwnerOnFailureAndReleasesResources() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                int baseline = GlDebug.resources().liveResources().size();
                GlRenderDevice device = new GlRenderDevice();
                GpuParticleSortingSettings sorting = GpuParticleSortingSettings.enabled(
                        GpuParticleSortingSettings.SortKey.VIEW_DEPTH,
                        GpuParticleSortingSettings.SortDirection.BACK_TO_FRONT,
                        SIZE, GpuParticleSortingSettings.EqualKeyPolicy.STABLE);
                try (Framebuffer first = depthTarget(SIZE, SIZE);
                     Framebuffer resized = depthTarget(96, SIZE);
                     VfxRenderer renderer = new VfxRenderer()) {
                    GpuParticleSystem owner = new GpuParticleSystem(settings(SIZE), sorting);
                    GpuParticleSystem candidate = null;
                    try {
                        renderer.gpuParticleSettings(new GpuParticleVfx.Settings(
                                1.0f / 60.0f, 12, GpuParticleVfx.SortingMode.GPU));
                        clear(device, first);
                        CommandBuffer commands = device.createCommandBuffer();
                        renderer.recordGpuParticles(commands, owner, settings(SIZE),
                                projection(), view(), first.depthAttachment(),
                                first.width(), first.height());
                        device.execute(commands);
                        assertTrue(nonBlackPixels(readColor(first), first.width(),
                                        0, 0, SIZE, SIZE) > 0,
                                "the sorting consumer must draw particles before resize");

                        candidate = renderer.resizeGpuParticles(owner, SIZE * 2);
                        GpuParticleSystem published = candidate;
                        owner.close();
                        assertEquals(GpuParticleVfx.SortingMode.GPU,
                                renderer.gpuParticleVfx().statistics().sortingMode());
                        assertThrows(IllegalArgumentException.class,
                                () -> renderer.resizeGpuParticles(published, 4),
                                "a resize that cannot fit the emission budget must fail closed");

                        clear(device, resized);
                        CommandBuffer resizedCommands = device.createCommandBuffer();
                        renderer.recordGpuParticles(resizedCommands, published,
                                settings(SIZE * 2), projection(), view(),
                                resized.depthAttachment(), resized.width(), resized.height());
                        device.execute(resizedCommands);
                        assertTrue(nonBlackPixels(readColor(resized), resized.width(),
                                        0, 0, resized.width(), resized.height()) > 0,
                                "the published candidate must survive a viewport resize");
                        assertEquals(1L,
                                renderer.gpuParticleVfx().statistics().framesRecorded(),
                                "a successful resize restarts the deterministic consumer counters");
                    } finally {
                        if (candidate != null) candidate.close();
                        owner.close();
                    }
                }
                assertEquals(baseline, GlDebug.resources().liveResources().size(),
                        "the consumer, candidate and resize targets must release every tracked resource");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    private static void clear(GlRenderDevice device, Framebuffer target) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableFramebufferSrgb(false)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .clear(true, true);
        device.execute(commands);
    }

    private static void recordCollector(CommandBuffer commands, ShaderProgram shader,
                                        VertexArray vertexArray) {
        commands.materialState(BlendMode.ALPHA, false)
                .blendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD)
                .bindShader(shader)
                .bindVertexArray(vertexArray.id())
                .drawArrays(GL_TRIANGLES, 0, 3)
                .materialState(BlendMode.OPAQUE, true);
    }

    private static ByteBuffer readColor(Framebuffer target) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.id());
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        ByteBuffer pixels = BufferUtils.createByteBuffer(
                target.width() * target.height() * 4);
        glReadPixels(0, 0, target.width(), target.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static FloatBuffer readDepth(Framebuffer target) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.id());
        FloatBuffer depths = BufferUtils.createFloatBuffer(target.width() * target.height());
        glReadPixels(0, 0, target.width(), target.height(), GL_DEPTH_COMPONENT, GL_FLOAT, depths);
        return depths;
    }

    private static int nonBlackPixels(ByteBuffer pixels, int stride, int x, int y,
                                      int width, int height) {
        int startX = Math.max(0, x);
        int startY = Math.max(0, y);
        int total = 0;
        for (int row = startY; row < startY + height; row++) {
            for (int column = startX; column < startX + width; column++) {
                int offset = (row * stride + column) * 4;
                int sum = Byte.toUnsignedInt(pixels.get(offset))
                        + Byte.toUnsignedInt(pixels.get(offset + 1))
                        + Byte.toUnsignedInt(pixels.get(offset + 2));
                if (sum > 12) total++;
            }
        }
        return total;
    }

    private static int maxRedPixel(ByteBuffer pixels) {
        int best = 0;
        int bestRed = -1;
        for (int index = 0; index < pixels.capacity() / 4; index++) {
            int value = red(pixels, index);
            if (value > bestRed) {
                bestRed = value;
                best = index;
            }
        }
        return best;
    }

    private static int red(ByteBuffer pixels, int pixel) {
        return Byte.toUnsignedInt(pixels.get(pixel * 4));
    }

    private static int blue(ByteBuffer pixels, int pixel) {
        return Byte.toUnsignedInt(pixels.get(pixel * 4 + 2));
    }

    private static GpuParticleSettings settings(int capacity) {
        return new GpuParticleSettings(capacity,
                new Vector3f(-1.2f, -0.8f, 0.0f), new Vector3f(1.2f, 0.8f, 0.0f),
                10.0f, 0.0f, 0.0f, 0.0f, 0.3f,
                new Vector3f(1.0f, 0.0f, 0.0f), 1.0f);
    }

    private static Matrix4f projection() {
        return new Matrix4f().perspective((float) Math.toRadians(45.0), 1.0f, 0.1f, 20.0f);
    }

    private static Matrix4f view() {
        return new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 3.0f), new Vector3f(),
                new Vector3f(0.0f, 1.0f, 0.0f));
    }

    private static Framebuffer depthTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(GL_RGBA8)
                .depthTexture()
                .build());
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("GPU particle VFX GL test").visible(false).build();
    }
}
