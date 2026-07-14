package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;

/** 验证隐藏上下文、基础像素回读和 compute shader 的最小真实 OpenGL 能力。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GlContextSmokeTest {
    private static final String COMPUTE_SOURCE = """
            #version 460 core
            layout(local_size_x = 1) in;
            layout(std430, binding = 0) buffer Result {
                uint value;
            };
            uniform uint uInput;
            void main() {
                value = uInput + 1u;
            }
            """;

    @Test
    void hiddenWindowCanClearAndReadBackPixel() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            glViewport(0, 0, 32, 32);
            glClearColor(0.25f, 0.5f, 0.75f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);

            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);

            int red = Byte.toUnsignedInt(pixel.get(0));
            int green = Byte.toUnsignedInt(pixel.get(1));
            int blue = Byte.toUnsignedInt(pixel.get(2));
            assertTrue(red > 40 && green > 90 && blue > 150,
                    "Expected readback pixel to reflect the clear color");
        }
    }

    @Test
    void computeProgramWritesThroughNamedStorageBlock() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (ShaderProgram shader = ShaderProgram.fromComputeSource(COMPUTE_SOURCE);
                 GlBuffer result = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(Integer.BYTES)) {
                shader.bindStorageBlock("Result", 0).setUInt("uInput", 41);
                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer()
                        .bindShader(shader)
                        .bindStorageBuffer(0, result, 0, Integer.BYTES)
                        .dispatchCompute(1, 1, 1)
                        .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT));

                ByteBuffer readback = BufferUtils.createByteBuffer(Integer.BYTES);
                result.read(0, readback);
                assertTrue(shader.isCompute());
                assertTrue(shader.hasStage(ShaderStage.COMPUTE));
                assertEquals(42, readback.getInt(0));
                GlDebug.checkError("computeProgramWritesThroughNamedStorageBlock");
            }
        }
    }
}
