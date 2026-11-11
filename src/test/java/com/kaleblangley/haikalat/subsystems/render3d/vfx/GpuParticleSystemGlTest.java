package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
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
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform4f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL32.GL_FRAMEBUFFER_SRGB;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;

/**
 * Real-GL acceptance for the production GPU particle indirect owner.
 *
 * <p>The cases are the S2 #27 contract, not a shader-compile smoke:</p>
 * <ul>
 *   <li>an indirect draw of 32 live particles matches a naive full-range draw
 *       of the same storage while the GPU counter stays below capacity;</li>
 *   <li>an empty active set produces no pixels and a zero indirect count;</li>
 *   <li>continuous emission is bounded by capacity and the queue rejects
 *       overflow;</li>
 *   <li>lifetime crossing kills particles, and a runtime lifetime change
 *       re-arms them; the GPU counts are compared with an independent Java
 *       lifetime reference;</li>
 *   <li>reset replays the same emit sequence to identical pixels and the same
 *       compacted index set;</li>
 *   <li>capacity replacement, render-target resize, idempotent close and error
 *       state release every tracked GL resource.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GpuParticleSystemGlTest {
    private static final int SIZE = 64;

    @Test
    void activeSetIndirectDrawMatchesTheFullRangeDrawAndStaysBelowCapacity() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem particles =
                         new GpuParticleSystem(pointSettings(128, 10.0f))) {
                particles.emit(32);
                renderFrame(device, target, particles, 1.0f / 60.0f);

                assertEquals(32, particles.statistics().requestedEmission());
                int active = readActiveCount(particles);
                assertEquals(32, active);
                assertTrue(active < particles.capacity(),
                        "the indirect draw count must be the live subset, not capacity");

                ByteBuffer indirect = readPixels(target);
                int indirectCoverage = nonBlackPixels(indirect);
                assertTrue(indirectCoverage > 32,
                        "32 overlapping billboards must cover more than a few pixels: "
                                + indirectCoverage);

                ByteBuffer fullRange = drawFullRangeReference(device, target, particles);
                assertPixelsClose(indirect, fullRange, 2);
                assertEquals(indirectCoverage, nonBlackPixels(fullRange),
                        "the active-set draw must reproduce the full-range coverage");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void emptyActiveSetProducesZeroPixelsAndZeroIndirectCount() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem particles =
                         new GpuParticleSystem(pointSettings(64, 1.0f))) {
                particles.emit(0);
                renderFrame(device, target, particles, 1.0f / 60.0f);

                assertEquals(0, readActiveCount(particles));
                ByteBuffer pixels = readPixels(target);
                assertAllBlack(pixels);
                assertEquals(0, nonBlackPixels(pixels));
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void continuousEmissionIsCappedByCapacityAndTheQueueRejectsOverflow() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem particles =
                         new GpuParticleSystem(pointSettings(16, 100.0f))) {
                for (int frame = 0; frame < 5; frame++) {
                    particles.emit(16);
                    renderFrame(device, target, particles, 1.0f / 60.0f);
                    int active = readActiveCount(particles);
                    assertEquals(16, active,
                            "live slots must bound the active counter at capacity");
                    assertTrue(active <= particles.capacity());
                }
                assertTrue(nonBlackPixels(readPixels(target)) > 0,
                        "the saturated active set must still produce pixels");

                particles.emit(16);
                assertThrows(IllegalArgumentException.class, () -> particles.emit(1));
                renderFrame(device, target, particles, 1.0f / 60.0f);
                assertEquals(16, readActiveCount(particles));
                assertThrows(IllegalArgumentException.class, () -> particles.emit(17));
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void lifetimeCrossingKillsTheActiveSetAndRuntimeLifetimeChangeReArmsIt() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            float delta = 0.125f;

            GpuParticleSettings settings = pointSettings(32, 0.375f);
            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem particles = new GpuParticleSystem(settings)) {
                GpuParticleEmission mirror = new GpuParticleEmission(32);
                GpuParticleReferenceModel model = new GpuParticleReferenceModel(32);

                assertFrameCount(device, target, particles, mirror, model, 8, delta, 0.375f, 8);
                assertFrameCount(device, target, particles, mirror, model, 0, delta, 0.375f, 8);
                assertFrameCount(device, target, particles, mirror, model, 0, delta, 0.375f, 0);
                assertAllBlack(readPixels(target));

                GpuParticleSettings longer = settings.withLifetimeSeconds(0.625f);
                particles.updateSettings(longer);
                assertEquals(0.625f, particles.settings().lifetimeSeconds());
                particles.reset();
                mirror.reset();
                assertFrameCount(device, target, particles, mirror, model, 8, delta, 0.625f, 8);
                assertFrameCount(device, target, particles, mirror, model, 0, delta, 0.625f, 8);
                assertFrameCount(device, target, particles, mirror, model, 0, delta, 0.625f, 8);
                assertFrameCount(device, target, particles, mirror, model, 0, delta, 0.625f, 8);
                assertFrameCount(device, target, particles, mirror, model, 0, delta, 0.625f, 0);
                assertAllBlack(readPixels(target));

                assertThrows(IllegalArgumentException.class,
                        () -> particles.updateSettings(settings.withCapacity(64)));
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void resetReplaysTheSameEmissionToIdenticalPixelsAndTheSameActiveSet() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            int[] emission = {3, 4, 5, 6};

            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem particles =
                         new GpuParticleSystem(pointSettings(32, 10.0f))) {
                ByteBuffer first = runScenario(device, target, particles, emission);
                assertEquals(18, readActiveCount(particles));
                assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
                        sorted(readActiveIndices(particles, 18)));

                particles.reset();
                ByteBuffer replay = runScenario(device, target, particles, emission);
                assertEquals(18, readActiveCount(particles));
                assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
                        sorted(readActiveIndices(particles, 18)));
                assertPixelsEqual(first, replay);
                assertTrue(nonBlackPixels(replay) > 32);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void capacityReplacementResizeAndCloseReleaseTrackedResources() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                int baseline = GlDebug.resources().liveResources().size();

                try (Framebuffer target = target(SIZE, SIZE)) {
                    GpuParticleSystem first = new GpuParticleSystem(pointSettings(32, 10.0f));
                    first.emit(4);
                    renderFrame(device, target, first, 1.0f / 60.0f);
                    assertEquals(4, readActiveCount(first));

                    assertThrows(IllegalArgumentException.class, () -> first.resized(0));
                    first.emit(2);
                    renderFrame(device, target, first, 1.0f / 60.0f);
                    assertEquals(6, readActiveCount(first),
                            "a failed resize candidate must leave the published owner usable");

                    GpuParticleSystem candidate = first.resized(64);
                    renderFrame(device, target, candidate, 1.0f / 60.0f);
                    assertEquals(0, readActiveCount(candidate));
                    candidate.emit(48);
                    renderFrame(device, target, candidate, 1.0f / 60.0f);
                    assertEquals(48, readActiveCount(candidate));

                    first.close();
                    first.close();
                    assertThrows(GlException.class, () -> first.emit(1));
                    assertThrows(GlException.class,
                            () -> first.record(device.createCommandBuffer(), 0.0f,
                                    viewProjection(), 1.0f));

                    try (Framebuffer resizedTarget = target(96, 64)) {
                        renderFrame(device, resizedTarget, candidate, 1.0f / 60.0f);
                        assertEquals(48, readActiveCount(candidate));
                        assertTrue(nonBlackPixels(readPixels(resizedTarget)) > 0);
                    }
                    candidate.close();
                }

                assertEquals(baseline, GlDebug.resources().liveResources().size(),
                        "particle owners and resized targets must release every tracked resource");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static ByteBuffer runScenario(GlRenderDevice device, Framebuffer target,
                                          GpuParticleSystem particles, int[] emission) {
        for (int count : emission) {
            particles.emit(count);
            renderFrame(device, target, particles, 0.05f);
        }
        return readPixels(target);
    }

    private static void assertFrameCount(GlRenderDevice device, Framebuffer target,
                                         GpuParticleSystem particles,
                                         GpuParticleEmission mirror,
                                         GpuParticleReferenceModel model,
                                         int emit, float deltaSeconds, float lifetimeSeconds,
                                         int expected) {
        if (emit > 0) {
            particles.emit(emit);
            mirror.emit(emit);
        }
        GpuParticleEmission.Step step = mirror.beginFrame(deltaSeconds);
        renderFrame(device, target, particles, deltaSeconds);
        int gpuCount = readActiveCount(particles);
        int referenceCount = model.step(step.spawnStart(), step.spawnCount(),
                step.clearParticles(), lifetimeSeconds, deltaSeconds);
        assertEquals(expected, referenceCount,
                "independent lifetime reference must predict the documented sequence");
        assertEquals(referenceCount, gpuCount,
                "GPU active count must match the independent reference");
    }

    private static void renderFrame(GlRenderDevice device, Framebuffer target,
                                    GpuParticleSystem particles, float deltaSeconds) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableFramebufferSrgb(false)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .clear(true, true);
        particles.record(commands, deltaSeconds, viewProjection(),
                (float) target.width() / target.height());
        device.execute(commands);
    }

    private static ByteBuffer drawFullRangeReference(GlRenderDevice device, Framebuffer target,
                                                     GpuParticleSystem particles) {
        GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
        GpuParticleSettings settings = particles.settings();
        int identityBuffer = glGenBuffers();
        try {
            IntBuffer identities = BufferUtils.createIntBuffer(handles.capacity());
            for (int index = 0; index < handles.capacity(); index++) {
                identities.put(index);
            }
            identities.flip();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, identityBuffer);
            glBufferData(GL_SHADER_STORAGE_BUFFER, identities, GL_STATIC_DRAW);

            glBindFramebuffer(GL_FRAMEBUFFER, target.id());
            glViewport(0, 0, target.width(), target.height());
            glDisable(GL_FRAMEBUFFER_SRGB);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            glEnable(GL_DEPTH_TEST);
            glDepthMask(false);
            glDisable(GL_CULL_FACE);

            int program = handles.drawProgram();
            glUseProgram(program);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, handles.particleBuffer());
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, identityBuffer);
            FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
            viewProjection().get(matrix);
            glUniformMatrix4fv(glGetUniformLocation(program, "uViewProjection"),
                    false, matrix);
            glUniform1i(glGetUniformLocation(program, "uCapacity"), handles.capacity());
            glUniform1f(glGetUniformLocation(program, "uViewportAspect"),
                    (float) target.width() / target.height());
            glUniform1f(glGetUniformLocation(program, "uParticleSize"), settings.particleSize());
            Vector3f color = settings.color();
            glUniform4f(glGetUniformLocation(program, "uColor"),
                    color.x, color.y, color.z, settings.intensity());
            glBindVertexArray(handles.vertexArray());
            glDrawArrays(GL_POINTS, 0, handles.capacity());
            glFinish();
            ByteBuffer result = readPixels(target);
            glBindVertexArray(0);
            glUseProgram(0);
            return result;
        } finally {
            glDeleteBuffers(identityBuffer);
            device.invalidateState();
        }
    }

    private static int readActiveCount(GpuParticleSystem particles) {
        GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
        IntBuffer value = BufferUtils.createIntBuffer(1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glGetNamedBufferSubData(handles.counterBuffer(), 0L, value);
        return value.get(0);
    }

    private static int[] readActiveIndices(GpuParticleSystem particles, int count) {
        if (count <= 0) {
            return new int[0];
        }
        GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
        IntBuffer values = BufferUtils.createIntBuffer(count);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glGetNamedBufferSubData(handles.activeIndexBuffer(), 0L, values);
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    private static List<Integer> sorted(int[] values) {
        List<Integer> result = new ArrayList<>();
        for (int value : values) {
            result.add(value);
        }
        Set<Integer> unique = new HashSet<>(result);
        assertEquals(result.size(), unique.size(),
                "the active index list must not contain duplicates");
        result.sort(Integer::compareTo);
        return result;
    }

    private static ByteBuffer readPixels(Framebuffer target) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.id());
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        ByteBuffer pixels = BufferUtils.createByteBuffer(target.width() * target.height() * 4);
        glReadPixels(0, 0, target.width(), target.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static int nonBlackPixels(ByteBuffer pixels) {
        int count = 0;
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            int sum = Byte.toUnsignedInt(pixels.get(offset))
                    + Byte.toUnsignedInt(pixels.get(offset + 1))
                    + Byte.toUnsignedInt(pixels.get(offset + 2));
            if (sum > 12) {
                count++;
            }
        }
        return count;
    }

    private static void assertAllBlack(ByteBuffer pixels) {
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            int sum = Byte.toUnsignedInt(pixels.get(offset))
                    + Byte.toUnsignedInt(pixels.get(offset + 1))
                    + Byte.toUnsignedInt(pixels.get(offset + 2));
            assertEquals(0, sum, "empty or dead particle sets must leave the clear colour");
        }
    }

    private static void assertPixelsEqual(ByteBuffer expected, ByteBuffer actual) {
        assertEquals(expected.capacity(), actual.capacity());
        for (int offset = 0; offset < expected.capacity(); offset++) {
            assertEquals(expected.get(offset), actual.get(offset),
                    "replay must reproduce the exact pixel bytes at offset " + offset);
        }
    }

    private static void assertPixelsClose(ByteBuffer expected, ByteBuffer actual, int tolerance) {
        assertEquals(expected.capacity(), actual.capacity());
        for (int offset = 0; offset < expected.capacity(); offset++) {
            int difference = Math.abs(Byte.toUnsignedInt(expected.get(offset))
                    - Byte.toUnsignedInt(actual.get(offset)));
            assertTrue(difference <= tolerance,
                    "pixel channel differs by " + difference + " at offset " + offset);
        }
    }

    private static Matrix4f viewProjection() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(45.0),
                1.0f, 0.1f, 20.0f);
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 3.0f),
                new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f));
        return new Matrix4f(projection).mul(view);
    }

    private static GpuParticleSettings pointSettings(int capacity, float lifetimeSeconds) {
        return new GpuParticleSettings(capacity,
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 0.0f),
                lifetimeSeconds, 0.0f, 0.0f, 0.0f, 0.08f,
                new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
    }

    private static Framebuffer target(int width, int height) {
        return Framebuffer.fromDescriptor(
                FramebufferDescriptor.singleColorDepthRenderbuffer(width, height));
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("GPU particle indirect GL test").visible(false).build();
    }
}
