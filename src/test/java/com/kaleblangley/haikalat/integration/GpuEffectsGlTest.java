package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.postprocess.VolumetricLightPass;
import com.kaleblangley.haikalat.subsystems.postprocess.VolumetricLightSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleExperiment;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
class GpuEffectsGlTest {
    @Test
    void computeParticlesAndBoundedVolumetricLightProducePixelsAndClose() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(55.0),
                    1.0f, 0.1f, 20.0f);
            Vector3f camera = new Vector3f(0.0f, 0.0f, 3.0f);
            Matrix4f view = new Matrix4f().lookAt(camera, new Vector3f(),
                    new Vector3f(0.0f, 1.0f, 0.0f));
            Matrix4f viewProjection = new Matrix4f(projection).mul(view);

            try (GpuParticleExperiment particles = new GpuParticleExperiment(256)) {
                for (int frame = 0; frame < 4; frame++) {
                    var commands = device.createCommandBuffer();
                    commands.bindDefaultFramebuffer().viewport(0, 0, 64, 64)
                            .enableFramebufferSrgb(false)
                            .clearColor(0.0f, 0.0f, 0.0f, 1.0f).clear(true, true);
                    GpuParticleExperiment.Statistics stats = particles.record(
                            commands, 1.0f / 60.0f, viewProjection, 1.0f);
                    device.execute(commands);
                    assertEquals(4, stats.workGroups());
                    assertEquals(8_192L, stats.storageBytes());
                }
                assertTrue(nonBlackPixels(readFrame()) > 12);
                particles.close();
                assertThrows(GlException.class, () -> particles.record(
                        device.createCommandBuffer(), 0.0f, viewProjection, 1.0f));
            }

            try (VolumetricLightPass volume = new VolumetricLightPass()) {
                Matrix4f inverseViewProjection = new Matrix4f(viewProjection).invert();
                VolumetricLightSettings settings = new VolumetricLightSettings(
                        32, 7.0f, 1.2f, 0.2f, 5.0f,
                        (float) Math.cos(Math.toRadians(12.0)),
                        (float) Math.cos(Math.toRadians(30.0)),
                        18.0f, new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(0.0f, 0.0f, -1.0f),
                        new Vector3f(0.2f, 0.55f, 1.0f));
                var commands = device.createCommandBuffer();
                commands.bindDefaultFramebuffer().viewport(0, 0, 64, 64)
                        .enableFramebufferSrgb(false)
                        .clearColor(0.0f, 0.0f, 0.0f, 1.0f).clear(true, true);
                VolumetricLightPass.Statistics stats = volume.recordIntoCurrentTarget(
                        commands, inverseViewProjection, camera, settings);
                device.execute(commands);

                assertEquals(32, stats.samplesPerPixel());
                assertTrue(nonBlackPixels(readFrame()) > 12);
                assertEquals(GL_NO_ERROR, glGetError());
                volume.close();
                assertThrows(GlException.class, volume::statistics);
            }
        }
    }

    private static ByteBuffer readFrame() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
        glReadPixels(0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
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
}
