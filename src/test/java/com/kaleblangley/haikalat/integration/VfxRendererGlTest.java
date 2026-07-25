package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.vfx.Decal;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.RibbonEmitter;
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

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VfxRendererGlTest {
    @Test
    void particlesRibbonAndDecalRenderInSortedOrderAndCloseCleanly() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (VfxRenderer renderer = new VfxRenderer();
                 EffectAsset asset = asset();
                 EffectInstance instance = asset.instantiate(123L)) {
                for (int frame = 0; frame < 10; frame++) {
                    instance.update(0.05f, new Vector3f(frame * 0.08f - 0.35f,
                            (float) Math.sin(frame * 0.3f) * 0.2f, 0.0f));
                }
                instance.spawnDecal(new Vector3f(0.0f, -0.55f, -0.05f),
                        new Vector3f(0.0f, 0.0f, 1.0f), new Vector2f(0.8f, 0.35f), 0.15f);
                Vector3f camera = new Vector3f(0.0f, 0.0f, 4.0f);
                EffectSnapshot snapshot = instance.snapshot(camera);
                Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                        1.0f, 0.1f, 20.0f);
                Matrix4f view = new Matrix4f().lookAt(camera, new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f));

                var commands = device.createCommandBuffer();
                commands.bindDefaultFramebuffer().viewport(0, 0, 32, 32)
                        .enableFramebufferSrgb(false).enableDepthTest(true).depthMask(true)
                        .clearColor(0.0f, 0.0f, 0.0f, 1.0f).clear(true, true);
                VfxRenderer.Statistics stats = renderer.record(commands, snapshot, projection, view);
                device.execute(commands);

                assertTrue(stats.particles() > 0);
                assertTrue(stats.ribbonSegments() > 0);
                assertEquals(1, stats.decals());
                assertEquals(snapshot.primitiveCount(), stats.drawCalls());
                assertEquals((long) stats.drawCalls() * 80L, stats.uniformPayloadBytes());
                assertTrue(nonBlackPixels(readFrame()) > 4);
                assertEquals(GL_NO_ERROR, glGetError());

                renderer.close();
                assertTrue(renderer.isClosed());
                assertThrows(IllegalStateException.class,
                        () -> renderer.record(device.createCommandBuffer(), snapshot, projection, view));
            }
        }
    }

    private static ByteBuffer readFrame() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(32 * 32 * 4);
        glReadPixels(0, 0, 32, 32, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static int nonBlackPixels(ByteBuffer pixels) {
        int count = 0;
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            if (Byte.toUnsignedInt(pixels.get(offset))
                    + Byte.toUnsignedInt(pixels.get(offset + 1))
                    + Byte.toUnsignedInt(pixels.get(offset + 2)) > 12) count++;
        }
        return count;
    }

    private static EffectAsset asset() {
        return EffectAsset.builder("gl-vfx")
                .particles(new ParticleEmitter(32, 30.0f, 1.5f,
                        new Vector3f(0.0f, 1.0f, 0.0f), 0.3f, 0.2f, 0.8f,
                        new Vector3f(0.0f, -0.2f, 0.0f), 0.1f, 0.16f, 0.04f,
                        new Vector4f(1.0f, 0.55f, 0.1f, 0.85f),
                        new Vector4f(1.0f, 0.1f, 0.05f, 0.0f)))
                .ribbon(new RibbonEmitter(16, 1.2f, 0.02f, 0.12f, 0.02f,
                        new Vector4f(0.1f, 0.7f, 1.0f, 0.8f),
                        new Vector4f(0.1f, 0.2f, 1.0f, 0.0f)))
                .decals(new Decal(4, 2.0f,
                        new Vector4f(0.65f, 0.2f, 1.0f, 0.7f),
                        new Vector4f(0.2f, 0.05f, 0.4f, 0.0f)))
                .build();
    }
}
