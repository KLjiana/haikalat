package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.core.buffer.PackedInstanceBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.core.mesh.PackedInstanceLayout;
import com.kaleblangley.haikalat.core.upload.UploadSystem;
import com.kaleblangley.haikalat.demo.stress.GeneratedStressPrimitive;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.util.DirectBuffers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;

/** 验证 indexed/SSBO、实例属性和异步上传路径能够驱动真实像素输出。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class InstanceUploadGlTest {
    @Test
    void indexedProceduralCubeWithCompactSsboProducesPixelsWithoutVbo() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            GeneratedStressPrimitive cube = GeneratedStressPrimitive.CUBE;
            ByteBuffer packedData = PackedInstanceLayout.allocate(1);
            PackedInstanceLayout.pack(packedData, 0,
                    0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f,
                    PackedInstanceLayout.packRgba8(1.0f, 0.25f, 0.1f, 1.0f));
            try (ShaderProgram shader = ShaderProgram.fromResource(GlContextSmokeTest.class,
                    cube.indexedSsboShaderResource(), "/demo/vertex_color_unlit.frag");
                 GlBuffer elementBuffer = GlBuffer.elementArrayBuffer(GL_STATIC_DRAW)
                         .upload(DirectBuffers.copyOf(cube.indices()));
                 VertexArray vao = new VertexArray();
                 PackedInstanceBuffer instances = PackedInstanceBuffer.immutable(packedData, 1);
                 Framebuffer target = Framebuffer.singleSampled(32, 32)) {
                vao.bindElementBuffer(elementBuffer);
                shader.bindStorageBlock("PackedInstances", 0);

                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer()
                        .bindFramebuffer(target)
                        .viewport(0, 0, 32, 32)
                        .clearColor(0, 0, 0, 1)
                        .clear(true, true)
                        .enableDepthTest(true)
                        .enableCullFace(true)
                        .bindShader(shader)
                        .setUniformMat4(shader, "uProjView", new org.joml.Matrix4f())
                        .setUniformVec2(shader, "uTimeRotation", 1.0f, 0.0f)
                        .bindStorageBuffer(0, instances.buffer(), 0, instances.bindingSizeBytes())
                        .bindVertexArray(vao.id())
                        .drawElementsInstanced(GL_TRIANGLES, cube.indexCount(),
                                cube.indexType(), 0L, 1));

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                assertTrue(Byte.toUnsignedInt(pixel.get(0)) > 100,
                        "Indexed compact cube must produce a non-empty center pixel");
                GlDebug.checkError("indexedProceduralCubeWithCompactSsboProducesPixelsWithoutVbo");
            }
        }
    }

    @Test
    void dynamicCompactSsboRingSlotsMeetDriverOffsetAlignment() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            ByteBuffer packedData = PackedInstanceLayout.allocate(7);
            try (PackedInstanceBuffer instances = PackedInstanceBuffer.dynamic(packedData, 7)) {
                assertEquals(0L, instances.slotStrideBytes() % instances.offsetAlignment());
                for (int slot = 0; slot < 3; slot++) {
                    instances.beginFrame();
                    assertEquals(0L, instances.bindingOffsetBytes() % instances.offsetAlignment());
                    assertEquals(7L * PackedInstanceLayout.STRIDE_BYTES,
                            instances.lastSynchronizedBytes());
                    instances.finishFrame();
                    glFinish();
                }

                ByteBuffer oneChangedInstance = PackedInstanceLayout.allocate(1);
                PackedInstanceLayout.pack(oneChangedInstance, 0,
                        1, 2, 3, 0.5f, 1, 0,
                        PackedInstanceLayout.packRgba8(1, 1, 1, 1));
                instances.updateRange(4, oneChangedInstance, 1);
                instances.beginFrame();
                assertEquals(PackedInstanceLayout.STRIDE_BYTES, instances.lastSynchronizedBytes(),
                        "A one-instance change must not rewrite the complete ring slot");
                instances.finishFrame();
                glFinish();
            }
        }
    }

    @Test
    void minimalAndAsyncInstanceAttributeContractProducesPixels() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Path resources = Path.of("src", "demo", "resources", "demo");
            ShaderProgram shader = ShaderProgram.fromSources(
                    Files.readString(resources.resolve("instanced_projview.vert")),
                    Files.readString(resources.resolve("vertex_color_unlit.frag")));
            VertexLayout layout = VertexLayout.interleaved(6 * Float.BYTES,
                    VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                    VertexAttribute.builder().index(1).size(3).type(GL_FLOAT)
                            .offsetBytes(3L * Float.BYTES).build());
            Mesh mesh = Mesh.from(MeshData.of("attribute-2-smoke", new float[]{
                    -0.8f, -0.8f, 0.0f, 1.0f, 0.0f, 0.0f,
                    0.8f, -0.8f, 0.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 0.8f, 0.0f, 0.0f, 0.0f, 1.0f
            }, layout));
            InstancedMeshBatch batch = InstancedMeshBatch.of(mesh, 1, 3);
            Framebuffer target = Framebuffer.singleSampled(32, 32);
            try {
                GlRenderDevice device = new GlRenderDevice();
                var commands = device.createCommandBuffer();
                commands.bindFramebuffer(target)
                        .viewport(0, 0, 32, 32)
                        .clearColor(0, 0, 0, 1)
                        .clear(true, true)
                        .bindShader(shader)
                        .setUniformMat4(shader, "uProjView", new org.joml.Matrix4f())
                        .drawInstancedBatch(batch, List.of(new org.joml.Matrix4f()));
                device.execute(commands);

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                assertTrue(Byte.toUnsignedInt(pixel.get(0))
                                + Byte.toUnsignedInt(pixel.get(1))
                                + Byte.toUnsignedInt(pixel.get(2)) > 0,
                        "Attribute-3 instance matrices must produce visible batch geometry");
                GlDebug.checkError("minimalAndAsyncInstanceAttributeContractProducesPixels");
            } finally {
                target.close();
                batch.close();
                mesh.close();
                shader.close();
            }
        }
    }

    @Test
    void instancedBatchFinishesRingFrameWhenALaterMeshFails() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Path resources = Path.of("src", "demo", "resources", "demo");
            ShaderProgram shader = ShaderProgram.fromSources(
                    Files.readString(resources.resolve("instanced_projview.vert")),
                    Files.readString(resources.resolve("vertex_color_unlit.frag")));
            VertexLayout layout = VertexLayout.interleaved(6 * Float.BYTES,
                    VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                    VertexAttribute.builder().index(1).size(3).type(GL_FLOAT)
                            .offsetBytes(3L * Float.BYTES).build());
            MeshData data = MeshData.of("batch-recovery", new float[]{
                    -0.8f, -0.8f, 0.0f, 1.0f, 0.0f, 0.0f,
                    0.8f, -0.8f, 0.0f, 0.0f, 1.0f, 0.0f,
                    0.0f, 0.8f, 0.0f, 0.0f, 0.0f, 1.0f
            }, layout);
            Mesh first = Mesh.from(data);
            Mesh failing = Mesh.from(data);
            InstancedMeshBatch batch = InstancedMeshBatch.of(List.of(first, failing), 2, 3);
            Framebuffer target = Framebuffer.singleSampled(32, 32);
            try {
                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer()
                        .bindFramebuffer(target)
                        .viewport(0, 0, 32, 32)
                        .bindShader(shader)
                        .setUniformMat4(shader, "uProjView", new org.joml.Matrix4f()));

                batch.beginFrame()
                        .submit(first, new org.joml.Matrix4f())
                        .submit(failing, new org.joml.Matrix4f());
                failing.close();
                assertThrows(GlException.class, batch::flush,
                        "The closed second mesh must fail after the first draw was submitted");

                batch.beginFrame().submit(first, new org.joml.Matrix4f());
                assertEquals(1, batch.flush(),
                        "The ring must be reusable after the failed frame was fenced and cleared");
                glFinish();
                GlDebug.checkError("instancedBatchFinishesRingFrameWhenALaterMeshFails");
            } finally {
                target.close();
                batch.close();
                first.close();
                failing.close();
                shader.close();
            }
        }
    }

    @Test
    void failedCommandBatchInvalidatesCachedVertexArray() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            VertexLayout layout = VertexLayout.interleaved(3 * Float.BYTES,
                    VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build());
            Mesh mesh = Mesh.from(MeshData.of("failed-command-batch", new float[]{
                    -0.8f, -0.8f, 0.0f,
                    0.8f, -0.8f, 0.0f,
                    0.0f, 0.8f, 0.0f
            }, layout));
            InstancedMeshBatch batch = InstancedMeshBatch.of(mesh, 1, 3);
            try (VertexArray cachedVao = new VertexArray()) {
                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer().bindVertexArray(cachedVao.id()));

                mesh.close();
                assertThrows(GlException.class, () -> device.execute(device.createCommandBuffer()
                        .drawInstancedBatch(batch, List.of(new org.joml.Matrix4f()))));

                long beforeRebind = device.stateStatistics().appliedChanges();
                device.execute(device.createCommandBuffer().bindVertexArray(cachedVao.id()));
                assertEquals(beforeRebind + 1, device.stateStatistics().appliedChanges(),
                        "The VAO bind after a failed batch must not be skipped by stale cache state");
                assertEquals(cachedVao.id(),
                        glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING));
                GlDebug.checkError("failedCommandBatchInvalidatesCachedVertexArray");
            } finally {
                batch.close();
                mesh.close();
            }
        }
    }

    @Test
    void asyncUploadBufferDrivesInstancedDrawPixels() throws Exception {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            Path resources = Path.of("src", "demo", "resources", "demo");
            ShaderProgram shader = ShaderProgram.fromSources(
                    Files.readString(resources.resolve("async_instanced.vert")),
                    Files.readString(resources.resolve("vertex_color_unlit.frag")));
            shader.bindUniformBlock("AsyncInstances", 1);
            Mesh mesh = Mesh.from(BuiltinMeshData.named(BuiltinMeshData.QUAD));
            GlBuffer matrices = GlBuffer.uniformBuffer(GL_DYNAMIC_DRAW).allocate(16L * 16 * Float.BYTES);
            Framebuffer target = Framebuffer.singleSampled(32, 32);
            UploadSystem uploads = new UploadSystem();
            AtomicBoolean published = new AtomicBoolean();
            try {
                FloatBuffer matrixData = BufferUtils.createFloatBuffer(16);
                new org.joml.Matrix4f().get(matrixData);
                matrixData.position(16).flip();
                uploads.uploadFloats(matrices, 0, matrixData, () -> published.set(true));
                uploads.flush();

                GlRenderDevice device = new GlRenderDevice();
                var commands = device.createCommandBuffer();
                commands.bindFramebuffer(target)
                        .viewport(0, 0, 32, 32)
                        .clearColor(0, 0, 0, 1)
                        .clear(true, true)
                        .bindShader(shader)
                        .setUniformMat4(shader, "uProjView", new org.joml.Matrix4f())
                        .bindUniformBuffer(1, matrices, 0, 16L * 16 * Float.BYTES)
                        .bindMesh(mesh)
                        .drawMeshInstanced(mesh, 1);
                device.execute(commands);

                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                assertTrue(published.get(), "Frame metadata must publish after the matrix upload");
                assertTrue(Byte.toUnsignedInt(pixel.get(0))
                                + Byte.toUnsignedInt(pixel.get(1))
                                + Byte.toUnsignedInt(pixel.get(2)) > 0,
                        "The upload-managed UBO must drive visible instance geometry");
                GlDebug.checkError("asyncUploadBufferDrivesInstancedDrawPixels");
            } finally {
                uploads.close();
                target.close();
                matrices.close();
                mesh.close();
                shader.close();
            }
        }
    }
}
