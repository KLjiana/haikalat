package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalAccumulationPass;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * GPU oracle for the frozen TAA resolve baseline (planning doc section 16).
 * Inputs are artificial so the expected output is analytic.
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class TaaResolveGlTest {
    private static final int SIZE = 8;
    private static final float EXPECTED_DEPTH = 0.75f;
    private static final TaaSettings SETTINGS = new TaaSettings(0.8f, 0.01f, 0.01f, 1.0f,
            false, 1);

    @Test
    void historyBlendClampAndRejectionFollowTheBaselineContract() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(64, 64)
                .title("TAA Resolve GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            try (Framebuffer current = Framebuffer.fromDescriptor(
                    FramebufferDescriptor.builder(SIZE, SIZE)
                            .colorTexture(RenderFormat.RGBA16F).build());
                 Texture2D sceneDepth = Texture2D.createEmpty(SIZE, SIZE, GL_R32F);
                 Texture2D velocity = Texture2D.createEmpty(SIZE, SIZE, GL_R32F);
                 Texture2D previousDepth = Texture2D.createEmpty(SIZE, SIZE, GL_R32F);
                 Texture2D validity = Texture2D.createEmpty(SIZE, SIZE, GL_R8);
                 Texture2D reactive = Texture2D.createEmpty(SIZE, SIZE, GL_R8);
                 TaaHistory history = new TaaHistory(SIZE, SIZE, RenderFormat.RGBA16F);
                 TemporalAccumulationPass pass = new TemporalAccumulationPass()) {
                fillFloat(sceneDepth, 0.5f);
                fillFloat(previousDepth, EXPECTED_DEPTH);
                fillByte(validity, 255);
                fillByte(reactive, 0);

                // H01: center current 0.25 with neighbors 0 and 1; valid history
                // 0.75 and weight 0.8 must resolve to 0.65 without clamping.
                fillCurrent(current, 0.25f, 0.25f, 0.0f, 1.0f);
                fillFloat(velocity, 0.0f);
                fillHistory(history, 0.75f, EXPECTED_DEPTH, 0.75f);
                float h01 = resolve(history, pass, current, sceneDepth, velocity,
                        previousDepth, validity, reactive);
                assertEquals(0.65f, h01, 0.02f, "H01 neighborhood clamp must not mask the blend");

                // H02: validity 0 forces current even with valid history.
                fillByte(validity, 0);
                float h02 = resolve(history, pass, current, sceneDepth, velocity,
                        previousDepth, validity, reactive);
                assertEquals(0.25f, h02, 0.01f, "H02 invalid surface must output current");
                fillByte(validity, 255);

                // H03: bright history clamped by an all-0.25 current neighborhood.
                fillCurrent(current, 0.25f, 0.25f, 0.25f, 0.25f);
                fillHistory(history, 10.0f, EXPECTED_DEPTH, 0.75f);
                float h03 = resolve(history, pass, current, sceneDepth, velocity,
                        previousDepth, validity, reactive);
                assertEquals(0.25f, h03, 0.01f, "H03 clamp must remove the bright history");

                // H04: history UV outside the extent must fall back to current.
                fillFloat(velocity, 2.0f);
                float h04 = resolve(history, pass, current, sceneDepth, velocity,
                        previousDepth, validity, reactive);
                assertEquals(0.25f, h04, 0.01f, "H04 out-of-bounds history must be rejected");

                // H05: HDR current above 1.0 must not be truncated by the resolve.
                fillFloat(velocity, 0.0f);
                fillCurrent(current, 4.0f, 4.0f, 4.0f, 4.0f);
                float h05 = resolve(history, pass, current, sceneDepth, velocity,
                        previousDepth, validity, reactive);
                assertTrue(h05 > 1.0f, "H05 HDR value must survive the resolve");

                // Sky: inverse jittered projection has already removed current
                // jitter. One pixel of jitter must shift history by ONE pixel.
                fillCurrent(current, 0, 0, 0, 1);
                fillFloat(sceneDepth, 1.0f);
                fillHistory(history, 0, -1, 0);
                history.readFramebuffer().bind();
                glEnable(GL_SCISSOR_TEST);
                for (int x = 0; x < SIZE; x++) {
                    glScissor(x, 0, 1, SIZE);
                    float value = x / (float) SIZE;
                    GL30.glClearBufferfv(GL30.GL_COLOR, 0, new float[]{value, value, value, 1});
                }
                glDisable(GL_SCISSOR_TEST);
                Matrix4f stable = new Matrix4f().perspective((float) Math.toRadians(60), 1, .1f, 100);
                Matrix4f jittered = new Matrix4f(stable);
                jittered.m20(jittered.m20() - 2.0f / SIZE);
                var skyInputs = new TaaResolveInputs(current.colorAttachment(0), history.readColorTexture(),
                        history.readDepthTexture(), sceneDepth.id(), velocity.id(), previousDepth.id(),
                        validity.id(), reactive.id(), true, SIZE, SIZE, 1.0f / SIZE, 0, 0, 0,
                        jittered.invert(), stable, new Matrix4f(), SETTINGS);
                var device = new GlRenderDevice();
                var commands = device.createCommandBuffer();
                pass.recordResolve(commands, history.writeFramebuffer(), skyInputs);
                device.execute(commands);
                assertEquals(.3f, readColor(history.writeFramebuffer(), 4, 4), .003f,
                        "sky history must remove current jitter only once");

                history.readFramebuffer().bind();
                glEnable(GL_SCISSOR_TEST);
                glScissor(4, 0, 1, SIZE);
                GL30.glClearBufferfv(GL30.GL_COLOR, 0, new float[]{10, 10, 10, 1});
                GL30.glClearBufferfv(GL30.GL_COLOR, 1, new float[]{5, 0, 0, 0});
                glDisable(GL_SCISSOR_TEST);
                var edgeInputs = new TaaResolveInputs(current.colorAttachment(0), history.readColorTexture(),
                        history.readDepthTexture(), sceneDepth.id(), velocity.id(), previousDepth.id(),
                        validity.id(), reactive.id(), true, SIZE, SIZE, 1.0f / SIZE, 0, .25f / SIZE, 0,
                        jittered, stable, new Matrix4f(), SETTINGS);
                device.invalidateState();
                commands = device.createCommandBuffer();
                pass.recordResolve(commands, history.writeFramebuffer(), edgeInputs);
                device.execute(commands);
                assertEquals(.3f, readColor(history.writeFramebuffer(), 4, 4), .003f,
                        "sky bilinear taps must reject foreground colors individually");

                fillCurrent(current, 0, 0, 0, 0);
                device.invalidateState();
                commands = device.createCommandBuffer();
                pass.recordResolve(commands, history.writeFramebuffer(), edgeInputs);
                device.execute(commands);
                assertEquals(0, readColor(history.writeFramebuffer(), 4, 4), .003f,
                        "flat background must reject stale accumulated color");

                GlDebug.checkError("taaResolveBaseline");
            }
        }
    }

    private static float resolve(TaaHistory history, TemporalAccumulationPass pass,
                                 Framebuffer current, Texture2D sceneDepth, Texture2D velocity,
                                 Texture2D previousDepth, Texture2D validity,
                                 Texture2D reactive) {
        history.prepareFrame();
        Framebuffer candidate = history.writeFramebuffer();
        TaaResolveInputs inputs = new TaaResolveInputs(
                current.colorAttachment(0), history.readColorTexture(), history.readDepthTexture(),
                sceneDepth.id(), velocity.id(), previousDepth.id(), validity.id(),
                reactive.id(), history.valid(), SIZE, SIZE, 0.0f, 0.0f, 0.0f, 0.0f,
                new Matrix4f(), new Matrix4f(), new Matrix4f(), SETTINGS);
        GlRenderDevice device = new GlRenderDevice();
        var commands = device.createCommandBuffer();
        pass.recordResolve(commands, candidate, inputs);
        device.execute(commands);
        return readColor(candidate, SIZE / 2, SIZE / 2);
    }

    private static float readColor(Framebuffer framebuffer, int x, int y) {
        framebuffer.bind();
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        return pixel.get(0);
    }

    private static void fillCurrent(Framebuffer target, float center, float base,
                                    float low, float high) {
        target.bind();
        glDisable(GL_SCISSOR_TEST);
        glClearColor(base, base, base, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        clearPixel(SIZE / 2, SIZE / 2, center);
        clearPixel(SIZE / 2 - 1, SIZE / 2, low);
        clearPixel(SIZE / 2 + 1, SIZE / 2, high);
        glDisable(GL_SCISSOR_TEST);
    }

    private static void clearPixel(int x, int y, float value) {
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, 1, 1);
        glClearColor(value, value, value, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static void fillFloat(Texture2D texture, float value) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE);
        for (int index = 0; index < SIZE * SIZE; index++) {
            pixels.put(value);
        }
        pixels.flip();
        texture.uploadRegion(0, 0, SIZE, SIZE, asBytes(pixels));
    }

    private static void fillByte(Texture2D texture, int value) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE);
        for (int index = 0; index < SIZE * SIZE; index++) {
            pixels.put((byte) value);
        }
        pixels.flip();
        texture.uploadRegion(0, 0, SIZE, SIZE, pixels);
    }

    private static void fillHistory(TaaHistory history, float color, float depth,
                                    float uniform) {
        Framebuffer slot = history.writeFramebuffer();
        slot.bind();
        glClearColor(uniform, uniform, uniform, uniform);
        glClear(GL_COLOR_BUFFER_BIT);
        GL30.glClearBufferfv(GL30.GL_COLOR, 0, new float[]{color, color, color, 1.0f});
        GL30.glClearBufferfv(GL30.GL_COLOR, 1, new float[]{depth, depth, depth, depth});
        history.prepareFrame();
        history.commitSuccessfulFrame();
    }

    private static ByteBuffer asBytes(FloatBuffer buffer) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(buffer.remaining() * Float.BYTES);
        while (buffer.hasRemaining()) {
            bytes.putFloat(buffer.get());
        }
        bytes.flip();
        return bytes;
    }
}
