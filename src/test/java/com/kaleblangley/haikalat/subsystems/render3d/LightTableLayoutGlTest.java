package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;

/**
 * GPU-side layout proof for the frozen std430 light table.  A compute shader
 * reads records through the exact declaration used by PBR and writes sentinel
 * words back for the CPU to verify.
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class LightTableLayoutGlTest {
    private static final String PROBE_SOURCE = """
            #version 460 core
            layout(local_size_x = 1) in;
            struct LightRecord {
                vec4 positionRange;
                vec4 directionOuter;
                vec4 colorIntensity;
                vec4 extra;
                ivec4 metadata;
            };
            layout(std430, binding = 0) readonly buffer LightTableBlock {
                uvec4 header;
                LightRecord lights[];
            };
            layout(std430, binding = 1) buffer ProbeBlock {
                uint values[];
            };
            void main() {
                values[0] = header.x;
                values[1] = header.y;
                values[2] = header.z;
                values[3] = floatBitsToUint(lights[0].colorIntensity.a);
                values[4] = floatBitsToUint(lights[0].positionRange.w);
                values[5] = floatBitsToUint(lights[1].positionRange.w);
                values[6] = floatBitsToUint(lights[1].colorIntensity.a);
                values[7] = floatBitsToUint(lights[1].extra.y);
                values[8] = uint(lights[1].metadata.x);
                values[9] = uint(lights[1].metadata.y);
            }
            """;

    @Test
    void computeReadsTheExactJavaStd430Layout() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32).title("LightTableLayoutGlTest").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Scene scene = new Scene(new Camera());
            scene.addLight(SceneLight.directional(new Vector3f(0, -1, 0),
                    new Vector3f(1, 1, 1), 2.5f));
            scene.addLight(SceneLight.point(new Vector3f(1, 2, 3),
                    new Vector3f(1, 0, 0), 4.0f, 12.0f));
            FrameLightTable table = FrameLightTable.build(scene.lightEntries(),
                    new Matrix4f().translation(1.0f, 0.0f, 0.0f),
                    ClusteredLightingSettings.defaults());
            ByteBuffer packed = LightTablePacker.allocate(8);
            LightTablePacker.pack(table, ShadowFramePlan.EMPTY, packed);

            try (GlBuffer lightTable = GlBuffer.shaderStorageBuffer(GL15.GL_DYNAMIC_DRAW)
                    .upload(packed);
                 GlBuffer probe = GlBuffer.shaderStorageBuffer(GL15.GL_DYNAMIC_DRAW)
                         .allocateStorage(10L * Integer.BYTES,
                                 GL_DYNAMIC_STORAGE_BIT | GL_CLIENT_STORAGE_BIT);
                 ShaderProgram program = ShaderProgram.fromComputeSource(PROBE_SOURCE)) {
                GL43.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, lightTable.id(),
                        0L, packed.capacity());
                GL43.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 1, probe.id(),
                        0L, 10L * Integer.BYTES);
                GL43.glUseProgram(program.id());
                GL43.glDispatchCompute(1, 1, 1);
                GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT
                        | GL43.GL_BUFFER_UPDATE_BARRIER_BIT);

                ByteBuffer result = BufferUtils.createByteBuffer(10 * Integer.BYTES)
                        .order(ByteOrder.nativeOrder());
                probe.read(0L, result);
                IntBuffer values = result.asIntBuffer();

                assertEquals(1, values.get(0), "directionalCount");
                assertEquals(1, values.get(1), "localCount");
                assertEquals(2, values.get(2), "totalCount");
                assertEquals(2.5f, Float.intBitsToFloat(values.get(3)), 1.0e-6f);
                assertEquals(0.0f, Float.intBitsToFloat(values.get(4)));
                assertEquals(12.0f, Float.intBitsToFloat(values.get(5)), 1.0e-6f);
                assertEquals(4.0f, Float.intBitsToFloat(values.get(6)), 1.0e-6f);
                assertEquals(2.0f, Float.intBitsToFloat(values.get(7)), 1.0e-6f);
                assertEquals(1, values.get(8), "point type id");
                assertEquals(-1, values.get(9), "no shadow slot");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }
}
