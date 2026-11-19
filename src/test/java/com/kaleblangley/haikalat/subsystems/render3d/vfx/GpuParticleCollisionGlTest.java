package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;

/**
 * Real-GL acceptance for depth-based particle collision.
 *
 * <p>The fixture rasterises a real horizontal floor into a depth texture with
 * the same view projection used for the particle draw. Particles are released
 * above that plane, integrate downward with zero spawn speed and are read back
 * from the particle storage buffer after the compute dispatch. The cases are:</p>
 *
 * <ul>
 *   <li>a particle set released above the rendered floor stops on the plane,
 *       reverses and attenuates its normal velocity, and matches the independent
 *       CPU collision reference derived from the pre-collision state;</li>
 *   <li>particles projected outside the depth viewport, and an enabled owner
 *       recorded without a depth texture, keep free flight;</li>
 *   <li>disabled collision, an all-far depth texture and a thickness band
 *       smaller than the penetration reproduce the particle-indirect
 *       trajectory byte for byte;</li>
 *   <li>capacity replacement, a resized depth viewport, idempotent close and
 *       error state release every tracked GL resource;</li>
 *   <li>the production {@link GpuParticleVfx} consumer forwards the same-frame
 *       scene depth to a collision-enabled owner (bounce) while a disabled owner
 *       and an enabled owner recorded without depth keep the free-flight
 *       trajectory byte for byte.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GpuParticleCollisionGlTest {
    private static final int SIZE = 64;
    private static final int PARTICLES = 16;
    private static final float PLANE_Y = -1.0f;
    private static final float SPAWN_Y = -0.95f;
    private static final float SPAWN_HALF_EXTENT = 0.4f;
    private static final float DELTA_SECONDS = 0.05f;
    private static final float GRAVITY = 40.0f;
    private static final float THICKNESS = 0.25f;
    private static final float RESTITUTION = 0.4f;
    private static final float FRICTION = 0.0f;
    private static final float POSITION_TOLERANCE = 5.0e-3f;
    private static final float VELOCITY_TOLERANCE = 5.0e-3f;
    private static final Vector3f CAMERA = new Vector3f(0.0f, 3.0f, 3.0f);

    @Test
    void particlesReleasedAboveTheDepthPlaneBounceAndMatchTheCpuReference() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            try (Framebuffer scene = sceneTarget(SIZE, SIZE);
                 Framebuffer target = particleTarget(SIZE, SIZE)) {
                renderFloor(device, scene, viewProjection());
                assertFloorDepthWritten(scene);

                try (GpuParticleSystem disabled = new GpuParticleSystem(particleSettings());
                     GpuParticleSystem colliding = new GpuParticleSystem(particleSettings(),
                             collision(THICKNESS))) {
                    disabled.emit(PARTICLES);
                    renderFreeFlight(device, target, disabled);

                    colliding.emit(PARTICLES);
                    renderCollision(device, target, colliding, scene);

                    FloatBuffer before = readParticleFloats(disabled);
                    FloatBuffer after = readParticleFloats(colliding);
                    int collided = 0;
                    for (int index = 0; index < PARTICLES; index++) {
                        int base = index * 8;
                        float px = before.get(base);
                        float py = before.get(base + 1);
                        float pz = before.get(base + 2);
                        float vx = before.get(base + 4);
                        float vy = before.get(base + 5);
                        float vz = before.get(base + 6);
                        assertTrue(py < PLANE_Y,
                                "the fixture must integrate the particle behind the floor: y=" + py);

                        Vector3f surface = GpuParticleCollisionReference
                                .horizontalPlaneSurface(CAMERA, new Vector3f(px, py, pz), PLANE_Y);
                        GpuParticleCollisionReference.Result expected =
                                GpuParticleCollisionReference.resolve(px, py, pz, vx, vy, vz,
                                        surface.x, surface.y, surface.z,
                                        0.0f, 1.0f, 0.0f,
                                        THICKNESS, RESTITUTION, FRICTION);
                        assertTrue(expected.collided(),
                                "the CPU reference must predict a collision for particle " + index);
                        assertEquals(expected.x(), after.get(base), POSITION_TOLERANCE,
                                "resolved x differs from the CPU reference at " + index);
                        assertEquals(expected.y(), after.get(base + 1), POSITION_TOLERANCE,
                                "resolved y differs from the CPU reference at " + index);
                        assertEquals(expected.z(), after.get(base + 2), POSITION_TOLERANCE,
                                "resolved z differs from the CPU reference at " + index);
                        assertEquals(expected.velocityX(), after.get(base + 4),
                                VELOCITY_TOLERANCE, "vx differs at " + index);
                        assertEquals(expected.velocityY(), after.get(base + 5),
                                VELOCITY_TOLERANCE, "vy differs at " + index);
                        assertEquals(expected.velocityZ(), after.get(base + 6),
                                VELOCITY_TOLERANCE, "vz differs at " + index);

                        assertTrue(after.get(base + 1) > PLANE_Y,
                                "the collision response must not leave the particle below the floor");

                        assertTrue(after.get(base + 5) > 0.0f,
                                "the normal velocity must reverse after the bounce");
                        assertTrue(Math.abs(after.get(base + 5)) < Math.abs(vy),
                                "restitution must attenuate the normal velocity");
                        collided++;
                    }
                    assertEquals(PARTICLES, collided,
                            "every particle in the fixture must exercise the collision block");
                    assertTrue(nonBlackPixels(readPixels(target)) > 20,
                            "the resolved particles must still produce visible billboards");
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void offscreenParticlesAndMissingDepthKeepFreeFlight() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            try (Framebuffer scene = sceneTarget(SIZE, SIZE);
                 Framebuffer target = particleTarget(SIZE, SIZE)) {
                renderFloor(device, scene, viewProjection());

                try (GpuParticleSystem disabled = new GpuParticleSystem(offscreenSettings());
                     GpuParticleSystem colliding = new GpuParticleSystem(offscreenSettings(),
                             collision(THICKNESS))) {
                    disabled.emit(8);
                    renderFreeFlight(device, target, disabled);
                    colliding.emit(8);
                    renderCollision(device, target, colliding, scene);
                    assertBytesEqual(readParticleBytes(disabled), readParticleBytes(colliding),
                            "particles projected outside the depth viewport must keep free flight");
                }

                try (GpuParticleSystem disabled = new GpuParticleSystem(particleSettings());
                     GpuParticleSystem colliding = new GpuParticleSystem(particleSettings(),
                             collision(THICKNESS))) {
                    disabled.emit(PARTICLES);
                    renderFreeFlight(device, target, disabled);
                    colliding.emit(PARTICLES);
                    // No scene depth this frame: the owner must record the
                    // unmodified #27 update program and keep free flight.
                    renderFreeFlight(device, target, colliding);
                    assertBytesEqual(readParticleBytes(disabled), readParticleBytes(colliding),
                            "a record without a depth texture must keep free flight");
                }
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void disabledCollisionReproducesTheParticleIndirectTrajectoryByteForByte() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            try (Framebuffer floor = sceneTarget(SIZE, SIZE);
                 Framebuffer far = sceneTarget(SIZE, SIZE);
                 Framebuffer target = particleTarget(SIZE, SIZE)) {
                renderFloor(device, floor, viewProjection());
                clearDepthOnly(far, 1.0f);

                try (GpuParticleSystem disabled = new GpuParticleSystem(particleSettings());
                     GpuParticleSystem noSurface = new GpuParticleSystem(particleSettings(),
                             collision(THICKNESS));
                     GpuParticleSystem belowThickness = new GpuParticleSystem(particleSettings(),
                             collision(1.0e-3f))) {
                    disabled.emit(PARTICLES);
                    renderFreeFlight(device, target, disabled);

                    noSurface.emit(PARTICLES);
                    renderCollision(device, target, noSurface, far);
                    assertBytesEqual(readParticleBytes(disabled),
                            readParticleBytes(noSurface),
                            "an all-far depth texture must not change the #27 trajectory");

                    belowThickness.emit(PARTICLES);
                    renderCollision(device, target, belowThickness, floor);
                    FloatBuffer before = readParticleFloats(disabled);
                    for (int index = 0; index < PARTICLES; index++) {
                        assertTrue(before.get(index * 8 + 1) < PLANE_Y,
                                "fixture precondition: the particle must be behind the floor");
                    }
                    assertBytesEqual(readParticleBytes(disabled),
                            readParticleBytes(belowThickness),
                            "penetration outside the thickness band must keep the #27 trajectory");
                }
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void collisionResizeAndCloseReleaseTrackedResources() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                int baseline = GlDebug.resources().liveResources().size();
                try (Framebuffer scene = sceneTarget(SIZE, SIZE);
                     Framebuffer target = particleTarget(SIZE, SIZE)) {
                    renderFloor(device, scene, viewProjection());
                    GpuParticleSystem first = new GpuParticleSystem(particleSettings(),
                            collision(THICKNESS));
                    GpuParticleSystem candidate = null;
                    try {
                        first.emit(8);
                        renderCollision(device, target, first, scene);
                        assertTrue(readParticleFloats(first).get(1) > PLANE_Y,
                                "the published owner must collide before the resize candidate");

                        assertThrows(IllegalArgumentException.class, () -> first.resized(0));
                        first.emit(0);
                        renderCollision(device, target, first, scene);
                        assertTrue(readParticleFloats(first).get(1) > PLANE_Y,
                                "a failed resize must leave the published owner usable");

                        candidate = first.resized(32);
                        assertEquals(32, candidate.capacity());
                        assertTrue(candidate.collisionSettings().enabled(),
                                "resized(int) must carry the collision generation");
                        try (Framebuffer smallScene = sceneTarget(32, 32);
                             Framebuffer smallTarget = particleTarget(32, 32)) {
                            renderFloor(device, smallScene, viewProjection());
                            candidate.emit(8);
                            renderCollision(device, smallTarget, candidate, smallScene);
                            assertTrue(readParticleFloats(candidate).get(1) > PLANE_Y,
                                    "the resized candidate must collide against the new depth viewport");
                            assertTrue(nonBlackPixels(readPixels(smallTarget)) > 0,
                                    "the resized candidate must still draw");
                        }

                        first.close();
                        first.close();
                        final GpuParticleSystem closed = first;
                        assertThrows(GlException.class, () -> closed.emit(1));
                        assertThrows(GlException.class, () -> closed.record(
                                device.createCommandBuffer(), 0.0f, viewProjection(), view(),
                                1.0f, scene.depthAttachment(), SIZE, SIZE));
                        assertTrue(first.isClosed());
                    } finally {
                        first.close();
                        if (candidate != null) {
                            candidate.close();
                        }
                    }
                }
                assertEquals(baseline, GlDebug.resources().liveResources().size(),
                        "collision owners and their targets must release every tracked resource");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void productionConsumerForwardsTheSceneDepthToCollisionOwners() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            try (Framebuffer scene = sceneTarget(SIZE, SIZE);
                 Framebuffer target = particleTarget(SIZE, SIZE);
                 VfxRenderer freeRenderer = new VfxRenderer();
                 VfxRenderer collisionRenderer = new VfxRenderer();
                 VfxRenderer noDepthRenderer = new VfxRenderer()) {
                renderFloor(device, scene, viewProjection());
                assertFloorDepthWritten(scene);
                GpuParticleSystem free = new GpuParticleSystem(particleSettings());
                GpuParticleSystem colliding = new GpuParticleSystem(particleSettings(),
                        collision(THICKNESS));
                GpuParticleSystem collidingWithoutDepth = new GpuParticleSystem(
                        particleSettings(), collision(THICKNESS));
                try {
                    GpuParticleVfx.Settings consumerSettings = new GpuParticleVfx.Settings(
                            DELTA_SECONDS, PARTICLES, GpuParticleVfx.SortingMode.NONE);
                    freeRenderer.gpuParticleSettings(consumerSettings);
                    collisionRenderer.gpuParticleSettings(consumerSettings);
                    noDepthRenderer.gpuParticleSettings(consumerSettings);
                    assertTrue(colliding.collisionSettings().enabled(),
                            "fixture: the colliding owner must carry an enabled collision generation");

                    GpuParticleVfx.Statistics freeStatistics = recordThroughConsumer(
                            device, target, freeRenderer, free, scene, scene.depthAttachment());
                    GpuParticleVfx.Statistics collisionStatistics = recordThroughConsumer(
                            device, target, collisionRenderer, colliding, scene,
                            scene.depthAttachment());
                    GpuParticleVfx.Statistics noDepthStatistics = recordThroughConsumer(
                            device, target, noDepthRenderer, collidingWithoutDepth, scene, 0);

                    for (GpuParticleVfx.Statistics statistics : List.of(freeStatistics,
                            collisionStatistics, noDepthStatistics)) {
                        assertEquals(PARTICLES, statistics.drawn(),
                                "the consumer must hand the configured emission budget to the owner");
                        assertEquals(1L, statistics.steps(),
                                "one running record must advance exactly one fixed step");
                        assertEquals(1L, statistics.framesRecorded());
                    }

                    FloatBuffer freeParticles = readParticleFloats(free);
                    FloatBuffer collidedParticles = readParticleFloats(colliding);
                    FloatBuffer noDepthParticles = readParticleFloats(collidingWithoutDepth);
                    for (int index = 0; index < PARTICLES; index++) {
                        int base = index * 8;
                        assertTrue(freeParticles.get(base + 1) < PLANE_Y,
                                "a collision-disabled owner must pass through the rendered floor");
                        assertTrue(collidedParticles.get(base + 1) > PLANE_Y,
                                "the consumer-forwarded scene depth must resolve particle "
                                        + index + " above the floor");
                        assertTrue(collidedParticles.get(base + 5) > 0.0f,
                                "the forwarded depth must reverse the normal velocity of particle "
                                        + index);
                        assertTrue(noDepthParticles.get(base + 1) < PLANE_Y,
                                "an enabled owner recorded without a depth texture must keep "
                                        + "free flight: particle " + index);
                    }
                    assertBytesEqual(readParticleBytes(free),
                            readParticleBytes(collidingWithoutDepth),
                            "a consumer record without depth must reproduce the free-flight "
                                    + "trajectory byte for byte");
                    assertTrue(nonBlackPixels(readPixels(target)) > 20,
                            "the consumer-recorded collision frame must still draw billboards");
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally {
                    free.close();
                    colliding.close();
                    collidingWithoutDepth.close();
                }
            }
        }
    }

    private static GpuParticleVfx.Statistics recordThroughConsumer(
            GlRenderDevice device, Framebuffer target, VfxRenderer renderer,
            GpuParticleSystem particles, Framebuffer scene, int sceneDepthTexture) {
        CommandBuffer commands = clearParticleTarget(device, target);
        GpuParticleVfx.Statistics statistics = renderer.recordGpuParticles(commands, particles,
                particleSettings(), projection(), view(), sceneDepthTexture,
                scene.width(), scene.height());
        device.execute(commands);
        return statistics;
    }

    private static void bind(GlfwWindow window) {
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        while (glGetError() != GL_NO_ERROR) {
            // Drain errors left by context creation before asserting.
        }
    }

    private static void renderFloor(GlRenderDevice device, Framebuffer scene,
                                    Matrix4f viewProjection) {
        try (ShaderProgram shader = ShaderProgram.fromSources(FLOOR_VERTEX, FLOOR_FRAGMENT);
             Mesh mesh = floorMesh()) {
            CommandBuffer commands = device.createCommandBuffer();
            commands.bindFramebuffer(scene)
                    .viewport(0, 0, scene.width(), scene.height())
                    .enableScissor(false)
                    .enableBlend(false)
                    .enableCullFace(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                    .clear(true, true)
                    .bindShader(shader)
                    .setUniformMat4(shader, "uViewProjection", viewProjection)
                    .bindMesh(mesh)
                    .drawMesh(mesh);
            device.execute(commands);
        }
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static void clearDepthOnly(Framebuffer scene, float depth) {
        scene.bind();
        glViewport(0, 0, scene.width(), scene.height());
        glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        glClearDepth(depth);
        glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static void assertFloorDepthWritten(Framebuffer scene) {
        scene.bind();
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glReadPixels(SIZE / 2, SIZE / 4, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        float value = depth.get(0);
        assertTrue(value > 0.0f && value < 1.0f,
                "the floor fixture must rasterise a valid depth, got " + value);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static void renderFreeFlight(GlRenderDevice device, Framebuffer target,
                                         GpuParticleSystem particles) {
        CommandBuffer commands = clearParticleTarget(device, target);
        particles.record(commands, DELTA_SECONDS, viewProjection(), view(),
                (float) target.width() / target.height());
        device.execute(commands);
    }

    private static void renderCollision(GlRenderDevice device, Framebuffer target,
                                        GpuParticleSystem particles, Framebuffer scene) {
        CommandBuffer commands = clearParticleTarget(device, target);
        particles.record(commands, DELTA_SECONDS, viewProjection(), view(),
                (float) target.width() / target.height(), scene.depthAttachment(),
                scene.width(), scene.height());
        device.execute(commands);
    }

    private static CommandBuffer clearParticleTarget(GlRenderDevice device, Framebuffer target) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableFramebufferSrgb(false)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .clear(true, true);
        return commands;
    }

    private static FloatBuffer readParticleFloats(GpuParticleSystem particles) {
        GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
        FloatBuffer values = BufferUtils.createFloatBuffer(handles.capacity() * 8);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glGetNamedBufferSubData(handles.particleBuffer(), 0L, values);
        return values;
    }

    private static ByteBuffer readParticleBytes(GpuParticleSystem particles) {
        GpuParticleSystem.VerificationHandles handles = particles.verificationHandles();
        ByteBuffer values = BufferUtils.createByteBuffer(handles.capacity() * 8
                * Float.BYTES);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glGetNamedBufferSubData(handles.particleBuffer(), 0L, values);
        return values;
    }

    private static void assertBytesEqual(ByteBuffer expected, ByteBuffer actual,
                                         String message) {
        assertEquals(expected.capacity(), actual.capacity(), message);
        for (int offset = 0; offset < expected.capacity(); offset++) {
            assertEquals(expected.get(offset), actual.get(offset),
                    message + " (byte " + offset + ")");
        }
    }

    private static ByteBuffer readPixels(Framebuffer target) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.id());
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        ByteBuffer pixels = BufferUtils.createByteBuffer(target.width()
                * target.height() * 4);
        glReadPixels(0, 0, target.width(), target.height(), GL_RGBA, GL_UNSIGNED_BYTE,
                pixels);
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

    private static GpuParticleSettings particleSettings() {
        return settings(0.0f);
    }

    private static GpuParticleSettings offscreenSettings() {
        return settings(20.0f);
    }

    private static GpuParticleSettings settings(float centreX) {
        return new GpuParticleSettings(PARTICLES,
                new Vector3f(centreX - SPAWN_HALF_EXTENT, SPAWN_Y, -SPAWN_HALF_EXTENT),
                new Vector3f(centreX + SPAWN_HALF_EXTENT, SPAWN_Y, SPAWN_HALF_EXTENT),
                10.0f, GRAVITY, 0.0f, 0.0f, 0.35f,
                new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
    }

    private static GpuParticleCollisionSettings collision(float thickness) {
        return GpuParticleCollisionSettings.enabled(
                GpuParticleCollisionSettings.DepthSampling.TEXEL_CENTRE,
                thickness, RESTITUTION, FRICTION, 1, 0.0f);
    }

    private static Matrix4f projection() {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), 1.0f,
                0.1f, 20.0f);
    }

    private static Matrix4f view() {
        return new Matrix4f().lookAt(CAMERA, new Vector3f(0.0f, 0.0f, 0.0f),
                new Vector3f(0.0f, 1.0f, 0.0f));
    }

    private static Matrix4f viewProjection() {
        return new Matrix4f(projection()).mul(view());
    }

    private static Framebuffer sceneTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(org.lwjgl.opengl.GL30.GL_RGBA8)
                .depthTexture()
                .build());
    }

    private static Framebuffer particleTarget(int width, int height) {
        return Framebuffer.fromDescriptor(
                FramebufferDescriptor.singleColorDepthRenderbuffer(width, height));
    }

    private static Mesh floorMesh() {
        VertexAttribute position = VertexAttribute.builder()
                .index(0).size(3).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        float[] vertices = {
                -10.0f, PLANE_Y, -10.0f,
                10.0f, PLANE_Y, -10.0f,
                10.0f, PLANE_Y, 10.0f,
                -10.0f, PLANE_Y, 10.0f
        };
        return Mesh.builder()
                .vertices(vertices, 3 * Float.BYTES, position)
                .indices(new int[]{0, 1, 2, 0, 2, 3})
                .build();
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("GPU particle collision GL test").visible(false).build();
    }

    private static final String FLOOR_VERTEX = """
            #version 460 core
            layout (location = 0) in vec3 aPosition;
            uniform mat4 uViewProjection;
            void main() {
                gl_Position = uViewProjection * vec4(aPosition, 1.0);
            }
            """;

    private static final String FLOOR_FRAGMENT = """
            #version 460 core
            layout (location = 0) out vec4 oColor;
            void main() {
                oColor = vec4(0.1, 0.1, 0.1, 1.0);
            }
            """;
}
