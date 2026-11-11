package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.EqualKeyPolicy;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.SortDirection;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.SortKey;
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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL15.glBindBuffer;
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
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40.glDrawArraysIndirect;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;

/**
 * Real-GL acceptance for the sorted alpha-particle path (#26).
 *
 * <p>The cases cover the contract published in
 * {@code docs/planning/v0.28.1-s3-particle-sorting.md}:</p>
 *
 * <ul>
 *   <li>two emitters with different depth and colour composite in the
 *       far-to-near order computed by the CPU oracle, and the reversed order
 *       visibly differs;</li>
 *   <li>swapping the emission order (the same particles in different storage
 *       slots) leaves the depth-sorted sequence and the pixels identical;</li>
 *   <li>empty, single and non-power-of-two sort capacities use sentinels and
 *       skip the network without CPU count reads;</li>
 *   <li>equal depth keys under the stable policy keep the published active
 *       order, also on top of non-equal keys;</li>
 *   <li>sort key and direction select the documented order;</li>
 *   <li>a disabled owner allocates no sort resources, records no sort dispatch
 *       and reproduces a hand-recorded #27 indirect draw byte for byte;</li>
 *   <li>capacity replacement, resize and close release every tracked
 *       resource.</li>
 * </ul>
 *
 * <p>The pixel oracle is {@link GpuParticleSortingReference}; GPU results are
 * compared after a real framebuffer readback. The tests never drive production
 * code through a CPU count read: they inspect the counter and the sorted index
 * buffer only as a diagnostic oracle after execution.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GpuParticleSortingGlTest {
    private static final int SIZE = 64;
    private static final int PARTICLE_BYTES = 32;
    private static final int SENTINEL = 0xFFFFFFFF;

    @Test
    void sortedAlphaCompositeOfTwoDepthAndColourEmittersMatchesTheCpuOracle() {
        try (GlfwWindow window = hiddenWindow("particle sorting composite")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            GpuParticleSettings farSettings = emitterSettings(4, -1.0f,
                    new Vector3f(1.0f, 0.0f, 0.0f), 1.0f);
            GpuParticleSettings nearSettings = emitterSettings(4, 1.0f,
                    new Vector3f(0.0f, 0.0f, 1.0f), 0.5f);
            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem far = new GpuParticleSystem(farSettings,
                         sortedSettings(4));
                 GpuParticleSystem near = new GpuParticleSystem(nearSettings,
                         sortedSettings(4))) {
                prime(device, target, far);
                prime(device, target, near);
                upload(device, far, List.of(new Vector3f(0.0f, 0.0f, -1.0f)), 10.0f);
                upload(device, near, List.of(new Vector3f(0.0f, 0.0f, 1.0f)), 10.0f);

                renderScene(device, target, far, near);
                assertEquals(1, readActiveCount(far));
                assertEquals(1, readActiveCount(near));
                assertEquals(0, readSortedIndices(far, 4)[0],
                        "the single far particle must be the sorted list head");
                assertEquals(0, readSortedIndices(near, 4)[0]);

                ByteBuffer pixels = readPixels(target);
                byte[] expectedFarToNear = GpuParticleSortingReference.composite(
                        SIZE, SIZE, viewProjection(), List.of(billboard(farSettings),
                                billboard(nearSettings)));
                assertPixelsClose(expectedFarToNear, pixels, 2,
                        "far red then near blue");
                assertTrue(nonBlackPixels(pixels) > 100,
                        "the two world-size billboards must cover a meaningful disc");

                renderScene(device, target, near, far);
                ByteBuffer reversed = readPixels(target);
                byte[] expectedNearToFar = GpuParticleSortingReference.composite(
                        SIZE, SIZE, viewProjection(), List.of(billboard(nearSettings),
                                billboard(farSettings)));
                assertPixelsClose(expectedNearToFar, reversed, 2,
                        "near blue then far red");
                assertTrue(pixelDifference(pixels, reversed, SIZE / 2, SIZE / 2) > 20,
                        "the two draw orders must produce different centre pixels");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void swappingTheEmissionOrderKeepsTheSortedSequenceAndThePixels() {
        try (GlfwWindow window = hiddenWindow("particle sorting emission order")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            GpuParticleSettings settings = emitterSettings(16, 0.0f,
                    new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
            List<Vector3f> ascending = List.of(new Vector3f(0.0f, 0.0f, 1.0f),
                    new Vector3f(0.0f, 0.0f, -1.0f), new Vector3f(0.0f, 0.0f, 0.0f));
            List<Vector3f> swapped = List.of(new Vector3f(0.0f, 0.0f, -1.0f),
                    new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f));
            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem first = new GpuParticleSystem(settings, sortedSettings(8));
                 GpuParticleSystem second = new GpuParticleSystem(settings, sortedSettings(8))) {
                prime(device, target, first);
                prime(device, target, second);
                upload(device, first, ascending, 10.0f);
                upload(device, second, swapped, 10.0f);

                renderScene(device, target, first);
                ByteBuffer firstPixels = readPixels(target);
                int[] firstSorted = sortedPrefix(first, 3);
                renderScene(device, target, second);
                ByteBuffer secondPixels = readPixels(target);
                int[] secondSorted = sortedPrefix(second, 3);

                assertDepthsAscending(firstSorted, ascending);
                assertDepthsAscending(secondSorted, swapped);
                assertDepthsEqual(firstSorted, ascending, secondSorted, swapped);
                assertPixelsEqual(firstPixels, secondPixels,
                        "swapping the emission order must reproduce the same pixels");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void emptySingleAndNonPowerOfTwoSortCapacitiesUseSentinels() {
        try (GlfwWindow window = hiddenWindow("particle sorting sentinels")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            GpuParticleSettings settings = emitterSettings(8, 0.0f,
                    new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem particles = new GpuParticleSystem(settings,
                         sortedSettings(5))) {
                assertEquals(8, particles.verificationHandles().sortCapacity(),
                        "five sorted particles require a padded capacity of eight");
                prime(device, target, particles);
                assertEquals(0, readActiveCount(particles));
                assertSentinels(readSortedIndices(particles, 8));
                assertAllBlack(readPixels(target));
                assertEquals(7, particles.statistics().sortDispatches(),
                        "the padded capacity has six bitonic steps plus preparation");

                upload(device, particles, List.of(new Vector3f(0.0f, 0.0f, 0.0f)), 10.0f);
                renderScene(device, target, particles);
                assertEquals(1, readActiveCount(particles));
                int[] single = readSortedIndices(particles, 8);
                assertEquals(0, single[0]);
                assertSentinels(Arrays.copyOfRange(single, 1, 8));
                assertTrue(nonBlackPixels(readPixels(target)) > 100,
                        "the single billboard must still be drawn");

                List<Vector3f> five = List.of(new Vector3f(0.8f, 0.0f, -2.0f),
                        new Vector3f(-0.8f, 0.0f, -1.0f),
                        new Vector3f(0.0f, 0.8f, 0.0f),
                        new Vector3f(0.0f, -0.8f, 1.0f),
                        new Vector3f(0.0f, 0.0f, 2.0f));
                upload(device, particles, five, 10.0f);
                renderScene(device, target, particles);
                assertEquals(5, readActiveCount(particles));
                int[] active = readActiveIndices(particles, 5);
                int[] sorted = readSortedIndices(particles, 8);
                int[] oracle = oracleOrder(active, five);
                assertArrayPrefix(oracle, sorted, 5);
                assertSentinels(Arrays.copyOfRange(sorted, 5, 8));
                assertEquals(5, uniqueCount(sorted, 5));
                assertTrue(nonBlackPixels(readPixels(target)) > 100);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void equalDepthKeysKeepTheActiveOrderUnderTheStablePolicy() {
        try (GlfwWindow window = hiddenWindow("particle sorting stable keys")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            GpuParticleSettings settings = emitterSettings(8, 0.0f,
                    new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
            List<Vector3f> equalDepths = List.of(new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 0.0f, 0.0f));
            List<Vector3f> mixed = List.of(new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 0.0f, 2.0f), new Vector3f(0.0f, 0.0f, 2.0f));
            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem stable = new GpuParticleSystem(settings,
                         sortedSettings(8));
                 GpuParticleSystem unstable = new GpuParticleSystem(settings,
                         sortedSettings(SortKey.VIEW_DEPTH, SortDirection.FRONT_TO_BACK,
                                 8, EqualKeyPolicy.UNSTABLE))) {
                prime(device, target, stable);
                prime(device, target, unstable);

                upload(device, stable, equalDepths, 10.0f);
                renderScene(device, target, stable);
                int[] equalActive = readActiveIndices(stable, 4);
                int[] equalSorted = sortedPrefix(stable, 4);
                assertArrayEquals(equalActive, equalSorted,
                        "equal keys must keep the published active order");

                upload(device, stable, mixed, 10.0f);
                renderScene(device, target, stable);
                int[] mixedActive = readActiveIndices(stable, 3);
                int[] mixedSorted = sortedPrefix(stable, 3);
                int[] mixedOracle = oracleOrder(mixedActive, mixed);
                assertArrayEquals(mixedOracle, mixedSorted,
                        "stable ties must survive a mixed key set");

                upload(device, unstable, mixed, 10.0f);
                renderScene(device, target, unstable);
                int[] unstableActive = readActiveIndices(unstable, 3);
                int[] unstableSorted = sortedPrefix(unstable, 3);
                assertEquals(3, uniqueCount(unstableSorted, 3),
                        "the unstable policy must still produce a permutation");
                assertDepthsNonDecreasing(unstableSorted, mixed);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void sortKeyAndDirectionSelectTheDocumentedOrder() {
        try (GlfwWindow window = hiddenWindow("particle sorting key and direction")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            GpuParticleSettings settings = emitterSettings(8, 0.0f,
                    new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
            List<Vector3f> positions = List.of(new Vector3f(4.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 0.0f, 2.0f));
            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem depth = new GpuParticleSystem(settings,
                         sortedSettings(8));
                 GpuParticleSystem distance = new GpuParticleSystem(settings,
                         sortedSettings(SortKey.DISTANCE, SortDirection.FRONT_TO_BACK,
                                 8, EqualKeyPolicy.STABLE));
                 GpuParticleSystem backToFront = new GpuParticleSystem(settings,
                         sortedSettings(SortKey.VIEW_DEPTH, SortDirection.BACK_TO_FRONT,
                                 8, EqualKeyPolicy.STABLE))) {
                prime(device, target, depth);
                prime(device, target, distance);
                prime(device, target, backToFront);
                upload(device, depth, positions, 10.0f);
                upload(device, distance, positions, 10.0f);
                upload(device, backToFront, positions, 10.0f);

                renderScene(device, target, depth);
                int[] depthActive = readActiveIndices(depth, 3);
                int[] depthSorted = sortedPrefix(depth, 3);
                assertArrayEquals(oracleOrder(depthActive, positions), depthSorted);
                // Particles 0 and 1 share the same view depth; the stable policy
                // keeps their relative active order instead of using distance.
                assertEquals(Integer.compare(indexOfActive(depthActive, 0),
                                indexOfActive(depthActive, 1)),
                        Integer.compare(indexOfActive(depthSorted, 0),
                                indexOfActive(depthSorted, 1)),
                        "equal view depths must keep their relative active order");

                renderScene(device, target, distance);
                int[] distanceActive = readActiveIndices(distance, 3);
                int[] distanceSorted = sortedPrefix(distance, 3);
                assertArrayEquals(oracleOrder(distanceActive, positions), distanceSorted);
                assertEquals(1, indexOfActive(distanceSorted, 1),
                        "eye distance puts the centred particle before the far right one");

                renderScene(device, target, backToFront);
                int[] reversedActive = readActiveIndices(backToFront, 3);
                int[] reversedSorted = sortedPrefix(backToFront, 3);
                assertArrayEquals(oracleOrder(reversedActive, positions,
                        SortDirection.BACK_TO_FRONT), reversedSorted);
                assertEquals(3, uniqueCount(reversedSorted, 3));
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void disabledSortingKeepsTheIndirectPathAndAllocatesNoSortResources() {
        try (GlfwWindow window = hiddenWindow("particle sorting disabled")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            GpuParticleSettings settings = emitterSettings(8, 0.0f,
                    new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
            List<Vector3f> positions = List.of(new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(0.0f, 0.0f, -1.0f));
            try (Framebuffer target = target(SIZE, SIZE);
                 GpuParticleSystem particles = new GpuParticleSystem(settings)) {
                GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
                assertEquals(0, handles.sortKeyBuffer(),
                        "a disabled owner must not allocate sort scratch");
                assertEquals(0, handles.sortedIndexBuffer());
                assertEquals(0, handles.sortKeyProgram());
                assertEquals(0, handles.sortStepProgram());
                assertEquals(0, handles.sortedDrawProgram());
                assertEquals(0, handles.sortCapacity());
                assertEquals(0, particles.statistics().sortDispatches());

                prime(device, target, particles);
                upload(device, particles, positions, 10.0f);
                renderScene(device, target, particles);
                assertEquals(3, readActiveCount(particles));
                ByteBuffer pixels = readPixels(target);
                assertTrue(nonBlackPixels(pixels) > 100);

                // The disabled owner must reproduce the untouched #27 command
                // sequence: reset, update, one additive indirect draw. Record
                // that sequence by hand against the same GPU buffers and demand
                // byte-identical pixels.
                ByteBuffer legacy = drawLegacyIndirectReference(device, target, particles);
                assertPixelsEqual(pixels, legacy,
                        "a disabled owner must render exactly the #27 indirect path");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void sortingCapacityReplacementResizeAndCloseReleaseTrackedResources() {
        try (GlfwWindow window = hiddenWindow("particle sorting lifecycle")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                int baseline = GlDebug.resources().liveResources().size();
                try (Framebuffer target = target(SIZE, SIZE)) {
                    GpuParticleSettings settings = emitterSettings(32, 0.0f,
                            new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
                    GpuParticleSystem first = new GpuParticleSystem(settings,
                            sortedSettings(16));
                    prime(device, target, first);
                    upload(device, first, List.of(new Vector3f()), 10.0f);
                    renderScene(device, target, first);
                    assertEquals(1, readActiveCount(first));

                    assertThrows(IllegalArgumentException.class, () -> first.resized(8),
                            "a candidate capacity must fit the sort bound");
                    assertThrows(IllegalArgumentException.class,
                            () -> new GpuParticleSystem(emitterSettings(8, 0.0f,
                                    new Vector3f(1.0f, 1.0f, 1.0f), 1.0f),
                                    sortedSettings(16)),
                            "a sort bound larger than capacity is rejected at construction");
                    assertThrows(IllegalArgumentException.class, () -> first.resized(0));

                    GpuParticleSystem candidate = first.resized(64);
                    renderScene(device, target, candidate);
                    upload(device, candidate, List.of(new Vector3f(0.0f, 0.0f, 1.0f)),
                            10.0f);
                    renderScene(device, target, candidate);
                    assertEquals(1, readActiveCount(candidate));
                    assertEquals(16, candidate.sortingSettings().maximumSortedParticles(),
                            "the candidate keeps the published sort bound");

                    GpuParticleSystem reSorted = first.resized(32,
                            GpuParticleSortingSettings.disabled());
                    assertEquals(0, reSorted.verificationHandles().sortCapacity());
                    assertEquals(0, reSorted.statistics().sortDispatches());

                    first.close();
                    first.close();
                    assertThrows(GlException.class, () -> first.emit(1));
                    assertThrows(GlException.class,
                            () -> first.record(device.createCommandBuffer(), 0.0f,
                                    viewProjection(), view(), 1.0f));
                    candidate.close();
                    reSorted.close();

                    try (Framebuffer resizedTarget = target(96, 64)) {
                        GpuParticleSystem resizedOwner = new GpuParticleSystem(settings,
                                sortedSettings(16));
                        renderScene(device, resizedTarget, resizedOwner);
                        upload(device, resizedOwner,
                                List.of(new Vector3f(0.0f, 0.0f, 0.0f)), 10.0f);
                        renderScene(device, resizedTarget, resizedOwner);
                        assertEquals(1, readActiveCount(resizedOwner));
                        assertTrue(nonBlackPixels(readPixels(resizedTarget)) > 100);
                        resizedOwner.close();
                    }
                }
                assertEquals(baseline, GlDebug.resources().liveResources().size(),
                        "sorted owners and resized targets must release every tracked resource");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static void prime(GlRenderDevice device, Framebuffer target,
                              GpuParticleSystem particles) {
        // The first record clears every storage slot and the indirect count; it
        // also clears the colour target, so it must run before fixture uploads.
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableFramebufferSrgb(false)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .clear(true, true);
        particles.record(commands, 0.0f, viewProjection(), view(),
                (float) target.width() / target.height());
        device.execute(commands);
        glFinish();
    }

    private static void upload(GlRenderDevice device, GpuParticleSystem particles,
                               List<Vector3f> positions, float lifetimeSeconds) {
        GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
        if (positions.size() > handles.capacity()) {
            throw new IllegalArgumentException("fixture exceeds owner capacity");
        }
        ByteBuffer data = BufferUtils.createByteBuffer(handles.capacity() * PARTICLE_BYTES);
        for (Vector3f position : positions) {
            data.putFloat(position.x).putFloat(position.y).putFloat(position.z)
                    .putFloat(lifetimeSeconds);
            data.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        }
        for (int index = positions.size(); index < handles.capacity(); index++) {
            data.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
            data.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        }
        data.flip();
        glNamedBufferSubData(handles.particleBuffer(), 0L, data);
        device.invalidateState();
        glFinish();
    }

    private static void renderScene(GlRenderDevice device, Framebuffer target,
                                    GpuParticleSystem... particles) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableFramebufferSrgb(false)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .clear(true, true);
        for (GpuParticleSystem particle : particles) {
            particle.record(commands, 0.0f, viewProjection(), view(),
                    (float) target.width() / target.height());
        }
        device.execute(commands);
    }

    private static ByteBuffer drawLegacyIndirectReference(GlRenderDevice device,
                                                         Framebuffer target,
                                                         GpuParticleSystem particles) {
        GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
        GpuParticleSettings settings = particles.settings();
        glBindFramebuffer(GL_FRAMEBUFFER, target.id());
        glViewport(0, 0, target.width(), target.height());
        glDisable(GL_FRAMEBUFFER_SRGB);
        glDepthMask(true);
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
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, handles.activeIndexBuffer());
        FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
        viewProjection().get(matrix);
        glUniformMatrix4fv(glGetUniformLocation(program, "uViewProjection"), false, matrix);
        glUniform1i(glGetUniformLocation(program, "uCapacity"), handles.capacity());
        glUniform1f(glGetUniformLocation(program, "uViewportAspect"),
                (float) target.width() / target.height());
        glUniform1f(glGetUniformLocation(program, "uParticleSize"), settings.particleSize());
        Vector3f color = settings.color();
        glUniform4f(glGetUniformLocation(program, "uColor"),
                color.x, color.y, color.z, settings.intensity());
        glBindVertexArray(handles.vertexArray());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, handles.counterBuffer());
        glDrawArraysIndirect(GL_POINTS, 0L);
        glFinish();
        ByteBuffer result = readPixels(target);
        glBindVertexArray(0);
        glUseProgram(0);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        device.invalidateState();
        return result;
    }

    private static GpuParticleSortingSettings sortedSettings(int maximumSortedParticles) {
        return sortedSettings(SortKey.VIEW_DEPTH, SortDirection.FRONT_TO_BACK,
                maximumSortedParticles, EqualKeyPolicy.STABLE);
    }

    private static GpuParticleSortingSettings sortedSettings(SortKey key,
                                                             SortDirection direction,
                                                             int maximumSortedParticles,
                                                             EqualKeyPolicy equalKeys) {
        return GpuParticleSortingSettings.enabled(key, direction, maximumSortedParticles,
                equalKeys);
    }

    private static GpuParticleSettings emitterSettings(int capacity, float worldZ,
                                                       Vector3f color, float intensity) {
        Vector3f point = new Vector3f(0.0f, 0.0f, worldZ);
        return new GpuParticleSettings(capacity, point, point, 10.0f,
                0.0f, 0.0f, 0.0f, 0.5f, color, intensity);
    }

    private static GpuParticleSortingReference.Billboard billboard(
            GpuParticleSettings settings) {
        Vector3f center = settings.spawnMinimum();
        return new GpuParticleSortingReference.Billboard(center,
                settings.lifetimeSeconds(), settings.color(), settings.intensity(),
                settings.particleSize());
    }

    private static void assertDepthsAscending(int[] sorted, List<Vector3f> slotPositions) {
        float previous = Float.NEGATIVE_INFINITY;
        for (int slot : sorted) {
            float depth = GpuParticleSortingReference.metric(view(),
                    slotPositions.get(slot), SortKey.VIEW_DEPTH);
            assertTrue(depth >= previous,
                    "sorted depths must increase front-to-back: " + depth + " after " + previous);
            previous = depth;
        }
    }

    private static void assertDepthsNonDecreasing(int[] sorted, List<Vector3f> slotPositions) {
        assertDepthsAscending(sorted, slotPositions);
    }

    private static void assertDepthsEqual(int[] firstSorted, List<Vector3f> firstPositions,
                                          int[] secondSorted, List<Vector3f> secondPositions) {
        assertEquals(firstSorted.length, secondSorted.length);
        for (int index = 0; index < firstSorted.length; index++) {
            float firstDepth = GpuParticleSortingReference.metric(view(),
                    firstPositions.get(firstSorted[index]), SortKey.VIEW_DEPTH);
            float secondDepth = GpuParticleSortingReference.metric(view(),
                    secondPositions.get(secondSorted[index]), SortKey.VIEW_DEPTH);
            assertEquals(firstDepth, secondDepth, 1.0e-6f,
                    "the swapped emission must produce the same depth sequence at " + index);
        }
    }

    private static int[] oracleOrder(int[] active, List<Vector3f> slotPositions) {
        return oracleOrder(active, slotPositions, SortDirection.FRONT_TO_BACK);
    }

    private static int[] oracleOrder(int[] active, List<Vector3f> slotPositions,
                                     SortDirection direction) {
        float[] metrics = new float[active.length];
        for (int index = 0; index < active.length; index++) {
            metrics[index] = GpuParticleSortingReference.metric(view(),
                    slotPositions.get(active[index]), SortKey.VIEW_DEPTH);
        }
        return GpuParticleSortingReference.stableSort(active, metrics, direction);
    }

    private static int indexOfActive(int[] values, int slot) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == slot) {
                return index;
            }
        }
        throw new AssertionError("slot " + slot + " is not active: "
                + Arrays.toString(values));
    }

    private static int uniqueCount(int[] values, int count) {
        java.util.Set<Integer> unique = new java.util.HashSet<>();
        for (int index = 0; index < count; index++) {
            unique.add(values[index]);
        }
        return unique.size();
    }

    private static void assertSentinels(int[] values) {
        for (int value : values) {
            assertEquals(SENTINEL, value, "padding slots must stay sentinels");
        }
    }

    private static void assertArrayPrefix(int[] expected, int[] actual, int count) {
        assertEquals(count, expected.length);
        for (int index = 0; index < count; index++) {
            assertEquals(expected[index], actual[index],
                    "sorted index differs at position " + index);
        }
    }

    private static int[] sortedPrefix(GpuParticleSystem particles, int count) {
        int capacity = particles.verificationHandles().sortCapacity();
        if (count > capacity) {
            throw new IllegalArgumentException("sorted prefix exceeds the sort capacity");
        }
        return Arrays.copyOf(readSortedIndices(particles, capacity), count);
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

    private static int[] readSortedIndices(GpuParticleSystem particles, int count) {
        GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
        if (handles.sortCapacity() == 0) {
            throw new AssertionError("owner has no sort scratch");
        }
        int[] result = new int[handles.sortCapacity()];
        IntBuffer values = BufferUtils.createIntBuffer(handles.sortCapacity());
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glGetNamedBufferSubData(handles.sortedIndexBuffer(), 0L, values);
        for (int index = 0; index < handles.sortCapacity(); index++) {
            result[index] = values.get(index);
        }
        return count == handles.sortCapacity() ? result : Arrays.copyOf(result, count);
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
            assertEquals(0, sum, "an empty active set must leave the clear colour");
        }
    }

    private static void assertPixelsEqual(ByteBuffer expected, ByteBuffer actual,
                                          String message) {
        assertEquals(expected.capacity(), actual.capacity());
        for (int offset = 0; offset < expected.capacity(); offset++) {
            assertEquals(expected.get(offset), actual.get(offset),
                    message + " at byte " + offset);
        }
    }

    private static void assertPixelsClose(byte[] expected, ByteBuffer actual, int tolerance,
                                          String message) {
        assertEquals(expected.length, actual.capacity());
        int mismatches = 0;
        for (int offset = 0; offset < expected.length; offset++) {
            int difference = Math.abs(Byte.toUnsignedInt(expected[offset])
                    - Byte.toUnsignedInt(actual.get(offset)));
            if (difference > tolerance) {
                mismatches++;
                if (mismatches <= 8) {
                    int pixel = offset / 4;
                    fail(message + ": channel differs by " + difference
                            + " at pixel (" + (pixel % SIZE) + "," + (pixel / SIZE)
                            + ") channel " + (offset % 4));
                }
            }
        }
        assertEquals(0, mismatches, message + ": " + mismatches
                + " channels differ by more than " + tolerance);
    }

    private static int pixelDifference(ByteBuffer left, ByteBuffer right, int x, int y) {
        int offset = (y * SIZE + x) * 4;
        int difference = 0;
        for (int channel = 0; channel < 3; channel++) {
            difference += Math.abs(Byte.toUnsignedInt(left.get(offset + channel))
                    - Byte.toUnsignedInt(right.get(offset + channel)));
        }
        return difference;
    }

    private static ByteBuffer readPixels(Framebuffer target) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.id());
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        ByteBuffer pixels = BufferUtils.createByteBuffer(target.width() * target.height() * 4);
        glReadPixels(0, 0, target.width(), target.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static Matrix4f view() {
        return new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 3.0f),
                new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f));
    }

    private static Matrix4f viewProjection() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(45.0), 1.0f, 0.1f, 20.0f);
        return new Matrix4f(projection).mul(view());
    }

    private static Framebuffer target(int width, int height) {
        return Framebuffer.fromDescriptor(
                FramebufferDescriptor.singleColorDepthRenderbuffer(width, height));
    }

    private static GlfwWindow hiddenWindow(String title) {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title(title).visible(false).build();
    }
}
