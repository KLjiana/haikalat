package com.kaleblangley.haikalat.subsystems.render3d.planar;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.FrontFace;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * Production owner of one bounded planar reflection view.
 *
 * <p>The pass owns a single-sample RGBA16F colour texture plus a depth texture
 * and never borrows or writes the shared scene attachments.  A caller supplies
 * a {@link SceneRecorder}; the pass computes the mirrored camera, the oblique
 * near-plane projection, flips the front-face winding, binds and clears its own
 * framebuffer and then restores the caller's framebuffer, viewport and the
 * documented draw state.  This keeps the reflection view independent from the
 * main scene and makes nested reflection rendering impossible: a recorder
 * calling {@link #record} again while the pass is recording throws.</p>
 *
 * <p>The first version supports exactly one plane and is non-recursive.  It has
 * no temporal accumulation of its own: the last successfully rendered
 * reflection persists between throttled updates, which is the independent
 * history consumed by downstream water/SSR composition.  A camera on or behind
 * the retained side is skipped without modifying the target.</p>
 *
 * <p>Throttling: {@link #record} updates at most once every
 * {@link PlanarReflectionSettings#updateIntervalFrames()} successful calls.
 * Skipped calls record no commands.  {@link #requestUpdate()} forces the next
 * call to update; a target resize always forces the next call to update.</p>
 */
public final class PlanarReflectionPass implements GlResource {
    /** RenderGraph/pipeline pass name used by a future integration. */
    public static final String PASS_NAME = "PlanarReflectionPass";
    /** Smallest camera distance to the plane accepted as a valid reflection. */
    public static final float MINIMUM_CAMERA_DISTANCE = 1.0e-4f;

    private final PlanarReflectionSettings settings;
    private final Matrix4f reflectionScratch = new Matrix4f();
    private final Matrix4f sceneView = new Matrix4f();
    private final Matrix4f mirroredView = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f plane = new Vector4f();
    private final Vector4f clipPlaneWorld = new Vector4f();
    private final Vector4f clipPlaneView = new Vector4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f mirroredPosition = new Vector3f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f up = new Vector3f();
    private final Vector3f right = new Vector3f();

    private Framebuffer framebuffer;
    private int width;
    private int height;
    private int framesUntilUpdate;
    private long updateCount;
    private boolean recording;
    private boolean closed;

    /**
     * Creates an active pass and allocates its first attachments.
     *
     * @throws IllegalArgumentException when settings are disabled or extents invalid
     */
    public PlanarReflectionPass(PlanarReflectionSettings settings, int sceneWidth, int sceneHeight) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("planar reflections are disabled");
        }
        int targetWidth = settings.renderWidth(sceneWidth, sceneHeight);
        int targetHeight = settings.renderHeight(sceneWidth, sceneHeight);
        Framebuffer created = createTarget(targetWidth, targetHeight);
        framebuffer = created;
        width = targetWidth;
        height = targetHeight;
    }

    public PlanarReflectionSettings settings() {
        return settings;
    }

    /** @return current reflection target width in pixels, or 0 after close */
    public int width() {
        return closed ? 0 : width;
    }

    /** @return current reflection target height in pixels, or 0 after close */
    public int height() {
        return closed ? 0 : height;
    }

    /** @return sampled RGBA16F colour texture id, or 0 after close */
    public int colorTexture() {
        return closed || framebuffer == null ? 0 : framebuffer.colorAttachment();
    }

    /** @return sampled depth texture id, or 0 after close */
    public int depthTexture() {
        return closed || framebuffer == null ? 0 : framebuffer.depthAttachment();
    }

    /** @return number of successfully recorded reflection updates */
    public long updateCount() {
        return updateCount;
    }

    /** @return true while a {@link SceneRecorder} is being recorded */
    public boolean isRecording() {
        return recording;
    }

    /** @return true when the next {@link #record} will update regardless of the interval */
    public boolean updateRequested() {
        return framesUntilUpdate == 0;
    }

    /** Forces the next {@link #record} call to update the reflection texture. */
    public void requestUpdate() {
        framesUntilUpdate = 0;
    }

    /**
     * Explicitly resizes the reflection attachments from a scene extent.  The
     * candidate framebuffer is allocated before the old pair is closed, so an
     * allocation failure keeps the previous target active.  The next
     * {@link #record} is forced to update.
     */
    public void resize(int sceneWidth, int sceneHeight) {
        ensureOpen();
        if (recording) {
            throw new IllegalStateException("cannot resize a recording planar reflection pass");
        }
        int targetWidth = settings.renderWidth(sceneWidth, sceneHeight);
        int targetHeight = settings.renderHeight(sceneWidth, sceneHeight);
        if (targetWidth == width && targetHeight == height) {
            return;
        }
        rebuild(targetWidth, targetHeight);
        framesUntilUpdate = 0;
    }

    /**
     * Records one reflection view into this pass's own target.
     *
     * @param cmd        GPU-owner command buffer
     * @param camera     main scene camera
     * @param sceneWidth main scene width in pixels (used for the projection aspect)
     * @param sceneHeight main scene height in pixels
     * @param scene      caller supplied scene recorder operating on the mirrored camera
     * @return true when the reflection texture was updated this call
     */
    public boolean record(CommandBuffer cmd, Camera camera, int sceneWidth, int sceneHeight,
                          SceneRecorder scene) {
        return record(cmd, camera, sceneWidth, sceneHeight, null, scene);
    }

    /**
     * Records one reflection view and restores {@code restoreTarget} afterwards.
     *
     * <p>The caller's viewport is restored to the full extent of
     * {@code restoreTarget}, or to {@code (0,0,sceneWidth,sceneHeight)} when
     * the default framebuffer is restored.  Front face is restored to
     * {@code CCW}; depth test/write are enabled, cull/blend/sRGB/scissor are
     * disabled and the clear colour is reset to the GL default (0,0,0,1).
     * These are the documented draw defaults of this pass, not a full host
     * state snapshot.</p>
     *
     * @param restoreTarget caller framebuffer restored at the end, or {@code null}
     *                      for the default framebuffer
     * @return true when the reflection texture was updated this call
     */
    public boolean record(CommandBuffer cmd, Camera camera, int sceneWidth, int sceneHeight,
                          Framebuffer restoreTarget, SceneRecorder scene) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(scene, "scene");
        if (recording) {
            throw new IllegalStateException(
                    "planar reflection rendering cannot be nested or recursive");
        }
        int targetWidth = settings.renderWidth(sceneWidth, sceneHeight);
        int targetHeight = settings.renderHeight(sceneWidth, sceneHeight);
        if (restoreTarget != null) {
            restoreTarget.ensureOpen();
            if (restoreTarget == framebuffer) {
                throw new IllegalArgumentException(
                        "restore target must not be the reflection target");
            }
        }
        if (targetWidth != width || targetHeight != height) {
            rebuild(targetWidth, targetHeight);
            framesUntilUpdate = 0;
        }
        if (framesUntilUpdate > 0) {
            framesUntilUpdate--;
            return false;
        }
        Frame frame = prepareFrame(camera, sceneWidth, sceneHeight);
        if (frame == null) {
            return false;
        }
        recording = true;
        try {
            recordInto(cmd, frame, scene, restoreTarget, sceneWidth, sceneHeight);
        } finally {
            recording = false;
        }
        updateCount++;
        framesUntilUpdate = settings.updateIntervalFrames() - 1;
        return true;
    }

    /** Package-private target access for same-package real-GL tests. */
    Framebuffer framebuffer() {
        return framebuffer;
    }

    @Override
    public int id() {
        return closed || framebuffer == null ? 0 : framebuffer.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (recording) {
            throw new IllegalStateException("cannot close a recording planar reflection pass");
        }
        closed = true;
        Framebuffer target = framebuffer;
        framebuffer = null;
        if (target != null) {
            target.close();
        }
    }

    private Frame prepareFrame(Camera camera, int sceneWidth, int sceneHeight) {
        settings.planeEquation(plane);
        cameraPosition.set(camera.position());
        float signedDistance = plane.x * cameraPosition.x + plane.y * cameraPosition.y
                + plane.z * cameraPosition.z + plane.w;
        if (!(signedDistance > MINIMUM_CAMERA_DISTANCE)) {
            return null;
        }
        camera.getViewMatrix(sceneView);
        PlanarReflectionMath.mirrorView(plane, sceneView, reflectionScratch, mirroredView);
        PlanarReflectionMath.mirrorPosition(plane, cameraPosition, mirroredPosition);
        // The rows of a view matrix are right, up and -forward.  Deriving the
        // mirrored basis from the already reflected matrix avoids the
        // handedness flip a lookAt built from a reflected up vector would add.
        right.set(mirroredView.m00(), mirroredView.m10(), mirroredView.m20()).normalize();
        up.set(mirroredView.m01(), mirroredView.m11(), mirroredView.m21()).normalize();
        forward.set(-mirroredView.m02(), -mirroredView.m12(), -mirroredView.m22()).normalize();
        CameraProjection.stable(camera, sceneWidth, sceneHeight, projection);
        settings.clipPlane(clipPlaneWorld);
        PlanarReflectionMath.planeToView(clipPlaneWorld, mirroredView, clipPlaneView);
        PlanarReflectionMath.obliqueProjection(projection, clipPlaneView, projection);
        viewProjection.set(projection).mul(mirroredView);
        return new Frame(projection, mirroredView, viewProjection, mirroredPosition,
                forward, up, right, clipPlaneWorld, clipPlaneView,
                sceneWidth, sceneHeight, width, height);
    }

    private void recordInto(CommandBuffer cmd, Frame frame, SceneRecorder scene,
                            Framebuffer restoreTarget, int sceneWidth, int sceneHeight) {
        Framebuffer target = framebuffer;
        cmd.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableScissor(false)
                .enableBlend(false)
                .enableDepthTest(true)
                .depthMask(true)
                .enableFramebufferSrgb(false)
                .frontFace(FrontFace.CW)
                .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                .clear(true, true);
        try {
            // Scene recorders own their shaders, meshes and draw order.  The
            // pass has already bound the target, set the mirrored winding and
            // made the frame matrices available.
            scene.record(cmd, frame);
        } finally {
            cmd.frontFace(FrontFace.CCW)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .enableBlend(false)
                    .enableCullFace(false)
                    .enableFramebufferSrgb(false)
                    .enableScissor(false)
                    .clearColor(0.0f, 0.0f, 0.0f, 1.0f);
            if (restoreTarget != null) {
                cmd.bindFramebuffer(restoreTarget)
                        .viewport(0, 0, restoreTarget.width(), restoreTarget.height());
            } else {
                cmd.bindDefaultFramebuffer()
                        .viewport(0, 0, sceneWidth, sceneHeight);
            }
        }
    }

    private void rebuild(int targetWidth, int targetHeight) {
        Framebuffer candidate = createTarget(targetWidth, targetHeight);
        Framebuffer previous = framebuffer;
        framebuffer = candidate;
        width = targetWidth;
        height = targetHeight;
        if (previous != null) {
            // Publication happens before retirement: a close failure leaves the
            // new target active and is reported instead of pretending rollback.
            previous.close();
        }
    }

    private static Framebuffer createTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(GL_RGBA16F)
                .depthTexture()
                .build());
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("planar reflection pass is closed");
        }
    }

    @FunctionalInterface
    public interface SceneRecorder {
        /**
         * Records the scene from the mirrored camera into the already bound
         * reflection target.
         *
         * <p>The pass owns the front-face winding, so the recorder must not
         * change it; it may enable/disable culling as its materials require.
         * The recorder must not sample {@link #colorTexture()} or
         * {@link #depthTexture()} of the pass it is writing (feedback), and
         * must not start another planar reflection.</p>
         *
         * @param cmd   command buffer currently targeting the reflection FBO
         * @param frame mirrored camera matrices, basis and clip planes
         */
        void record(CommandBuffer cmd, Frame frame);
    }

    /**
     * Immutable mirrored view snapshot handed to a {@link SceneRecorder}.
     *
     * <p>The pass owns the mutable scratch matrices; this value copies all
     * exposed state so a recorder cannot observe later updates.</p>
     */
    public static final class Frame {
        private final Matrix4f view;
        private final Matrix4f projection;
        private final Matrix4f viewProjection;
        private final Matrix4f inverseView;
        private final Matrix4f inverseProjection;
        private final Vector3f position;
        private final Vector3f forward;
        private final Vector3f up;
        private final Vector3f right;
        private final Vector4f clipPlaneWorld;
        private final Vector4f clipPlaneView;
        private final int sceneWidth;
        private final int sceneHeight;
        private final int width;
        private final int height;

        private Frame(Matrix4f projection, Matrix4f view, Matrix4f viewProjection,
                      Vector3f position, Vector3f forward, Vector3f up, Vector3f right,
                      Vector4f clipPlaneWorld, Vector4f clipPlaneView,
                      int sceneWidth, int sceneHeight, int width, int height) {
            this.view = new Matrix4f(Objects.requireNonNull(view, "view"));
            this.projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
            this.viewProjection = new Matrix4f(
                    Objects.requireNonNull(viewProjection, "viewProjection"));
            if (!this.view.isFinite() || !this.projection.isFinite()
                    || !this.viewProjection.isFinite()) {
                throw new IllegalArgumentException("mirrored camera matrices must be finite");
            }
            if (this.view.determinant() >= 0.0f) {
                throw new IllegalArgumentException(
                        "mirrored view must have negative determinant");
            }
            this.position = new Vector3f(Objects.requireNonNull(position, "position"));
            this.forward = new Vector3f(Objects.requireNonNull(forward, "forward"));
            this.up = new Vector3f(Objects.requireNonNull(up, "up"));
            this.right = new Vector3f(Objects.requireNonNull(right, "right"));
            this.clipPlaneWorld = new Vector4f(
                    Objects.requireNonNull(clipPlaneWorld, "clipPlaneWorld"));
            this.clipPlaneView = new Vector4f(
                    Objects.requireNonNull(clipPlaneView, "clipPlaneView"));
            inverseView = new Matrix4f(this.view).invert();
            inverseProjection = new Matrix4f(this.projection).invert();
            if (!inverseView.isFinite() || !inverseProjection.isFinite()) {
                throw new IllegalArgumentException(
                        "mirrored camera matrices must be invertible");
            }
            this.sceneWidth = sceneWidth;
            this.sceneHeight = sceneHeight;
            this.width = width;
            this.height = height;
        }

        public boolean mirrored() {
            return true;
        }

        public int sceneWidth() { return sceneWidth; }
        public int sceneHeight() { return sceneHeight; }
        public int width() { return width; }
        public int height() { return height; }

        public Matrix4f view() { return new Matrix4f(view); }
        public Matrix4f projection() { return new Matrix4f(projection); }
        public Matrix4f viewProjection() { return new Matrix4f(viewProjection); }
        public Matrix4f inverseView() { return new Matrix4f(inverseView); }
        public Matrix4f inverseProjection() { return new Matrix4f(inverseProjection); }

        public Matrix4f view(Matrix4f destination) {
            return Objects.requireNonNull(destination, "destination").set(view);
        }

        public Matrix4f projection(Matrix4f destination) {
            return Objects.requireNonNull(destination, "destination").set(projection);
        }

        public Matrix4f viewProjection(Matrix4f destination) {
            return Objects.requireNonNull(destination, "destination").set(viewProjection);
        }

        public Matrix4f inverseView(Matrix4f destination) {
            return Objects.requireNonNull(destination, "destination").set(inverseView);
        }

        public Matrix4f inverseProjection(Matrix4f destination) {
            return Objects.requireNonNull(destination, "destination").set(inverseProjection);
        }

        public Vector3f position() { return new Vector3f(position); }
        public Vector3f forward() { return new Vector3f(forward); }
        public Vector3f up() { return new Vector3f(up); }
        public Vector3f right() { return new Vector3f(right); }

        public Vector3f position(Vector3f destination) {
            return Objects.requireNonNull(destination, "destination").set(position);
        }

        public Vector3f forward(Vector3f destination) {
            return Objects.requireNonNull(destination, "destination").set(forward);
        }

        public Vector3f up(Vector3f destination) {
            return Objects.requireNonNull(destination, "destination").set(up);
        }

        public Vector3f right(Vector3f destination) {
            return Objects.requireNonNull(destination, "destination").set(right);
        }

        public Vector4f clipPlaneWorld() { return new Vector4f(clipPlaneWorld); }
        public Vector4f clipPlaneView() { return new Vector4f(clipPlaneView); }

        public Vector4f clipPlaneWorld(Vector4f destination) {
            return Objects.requireNonNull(destination, "destination").set(clipPlaneWorld);
        }

        public Vector4f clipPlaneView(Vector4f destination) {
            return Objects.requireNonNull(destination, "destination").set(clipPlaneView);
        }
    }
}
