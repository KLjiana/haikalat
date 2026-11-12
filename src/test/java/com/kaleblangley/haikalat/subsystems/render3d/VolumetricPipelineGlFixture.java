package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearDepth;

/**
 * Shared real-GL fixture for the guarded pipeline-level cloud/smoke cases.
 *
 * <p>This class is not a test: it only creates a hidden window, an external
 * camera and the presentation/readback plumbing that both pipeline GL tests
 * need. Readbacks are test evidence only; production never reads back.</p>
 */
final class VolumetricPipelineGlFixture {
    private VolumetricPipelineGlFixture() {
    }

    static GlfwWindow window(int size, String title) {
        return new GlfwWindow.Builder().dimensions(size, size).visible(false)
                .title(title).build();
    }

    static RenderSettings hdr() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    static ExternalCamera camera(int width, int height, Vector3f eye, Vector3f focus,
                                 float fovDegrees, float near, float far) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fovDegrees),
                width / (float) height, near, far);
        Matrix4f view = new Matrix4f().lookAt(eye, focus, new Vector3f(0.0f, 1.0f, 0.0f));
        return ExternalCamera.of(view, projection, eye, 0.0f);
    }

    static OwnedPresentationTarget host(int size) {
        return OwnedPresentationTarget.create(size, size, RenderFormat.RGBA8, true, 1);
    }

    /** Re-declares the owned host framebuffer as a borrowed presentation target. */
    static PresentationTarget borrowed(OwnedPresentationTarget host) {
        PresentationTarget owned = host.target();
        var color = owned.color().orElseThrow();
        var depth = owned.depth().orElseThrow();
        int size = owned.width();
        return PresentationTarget.builder(size, size)
                .framebuffer(owned.drawFramebufferId())
                .color(ExternalAttachment.borrowedColor(color.textureId(), color.format(),
                        size, size))
                .depth(ExternalAttachment.borrowedDepth(depth.textureId(), depth.format(),
                        size, size))
                .build();
    }

    static void seedHostDepth(OwnedPresentationTarget host, float value) {
        float previousClearDepth = org.lwjgl.opengl.GL11.glGetFloat(
                org.lwjgl.opengl.GL11.GL_DEPTH_CLEAR_VALUE);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, host.target().drawFramebufferId());
            glClearDepth(value);
            glClear(GL_DEPTH_BUFFER_BIT);
        } finally {
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
            glClearDepth(previousClearDepth);
        }
    }

    /**
     * Declares a compute-only probe after the final pass and captures the exact
     * shared single-sample depth texture that the medium executors consumed.
     */
    static void addDepthProbe(RenderPipeline pipeline, int[] depthTexture) {
        var depth = pipeline.activeGenerationForTest().topology().sceneSurface()
                .single(SceneBufferChannel.DEPTH);
        if (depth == null) throw new IllegalStateException("fixture has no shared depth");
        pipeline.graph().addPass("MediumDepthProbe")
                .computeOnly()
                .dependsOn(pipeline.finalPassName())
                .execute((resources, commands) ->
                        depthTexture[0] = resources.depthAttachment(depth.name()));
    }

    static float[] readDepth(int texture, int width, int height) {
        int framebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, framebuffer);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0);
            org.lwjgl.opengl.GL11.glDrawBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, width, height,
                    GL_DEPTH_COMPONENT, GL_FLOAT, buffer);
            float[] result = new float[width * height];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
        }
    }

    static float[] readColorTexture(int texture, int width, int height) {
        int framebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, framebuffer);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, width, height,
                    org.lwjgl.opengl.GL11.GL_RGBA, GL_FLOAT, buffer);
            float[] result = new float[width * height * 4];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
        }
    }

    static int countDifferences(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("arrays must have the same length");
        }
        int changed = 0;
        for (int index = 0; index < left.length; index += 4) {
            if (!samePixel(left, right, index)) changed++;
        }
        return changed;
    }

    static boolean samePixel(float[] left, float[] right, int index) {
        for (int component = 0; component < 4; component++) {
            if (Float.floatToIntBits(left[index + component])
                    != Float.floatToIntBits(right[index + component])) {
                return false;
            }
        }
        return true;
    }

    static boolean sameArray(float[] left, float[] right) {
        if (left.length != right.length) return false;
        for (int index = 0; index < left.length; index++) {
            if (Float.floatToIntBits(left[index]) != Float.floatToIntBits(right[index])) {
                return false;
            }
        }
        return true;
    }

    static float[] linearHdr(RenderPipeline pipeline) {
        return pipeline.captureLinearHdrRgbaFloat();
    }

    static void requireTrackedResourcesReleased() {
        if (!GlDebug.resources().liveResources().isEmpty()) {
            throw new IllegalStateException("tracked GL resources were not released: "
                    + GlDebug.resources().liveResources());
        }
    }
}
