package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SceneReactiveGlTest {
    @Test
    void skinAndMorphMasksCoverDeformedRatherThanRestPositions() {
        try (var window = new GlfwWindow.Builder().dimensions(96, 96).title("Reactive deformation").visible(false).build()) {
            window.bindContext(); GL.createCapabilities();
            // The compact quad has no joint attributes: supply an explicit
            // joint-0, weight-1 fixture instead of OpenGL's default w=1.
            org.lwjgl.opengl.GL20.glVertexAttrib4f(5, 0, 0, 0, 0);
            org.lwjgl.opengl.GL20.glVertexAttrib4f(6, 1, 0, 0, 0);
            try (var shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/surface/scene-surface.vert",
                    "/shaders/render3d/surface/scene-reactive.frag");
                 var mesh = Mesh.from(BuiltinMeshData.coloredQuad("reactive"));
                 var target = Framebuffer.fromDescriptor(FramebufferDescriptor.builder(96, 96)
                         .colorTexture(RenderFormat.R8).build());
                 var cameraUniforms = new CameraUniforms();
                 var pass = new SceneReactivePass();
                 var joints = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(64);
                 var deltas = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(6 * 12 * 4);
                 var weights = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(4)) {
                Material material = Material.builder(shader).temporalReactive(1).build();
                try {
                    var device = new GlRenderDevice();
                    for (boolean skin : new boolean[]{true, false}) {
                        var palette = BufferUtils.createByteBuffer(64);
                        new Matrix4f().translation(skin ? 1.5f : 0, 0, 0).get(palette.asFloatBuffer());
                        var deltaBytes = BufferUtils.createByteBuffer(6 * 12 * 4);
                        for (int vertex = 0; vertex < 6; vertex++) {
                            for (int component = 0; component < 12; component++) {
                                deltaBytes.putFloat(component == 0 ? 1.5f : 0);
                            }
                        }
                        deltaBytes.flip();
                        var weightBytes = BufferUtils.createByteBuffer(4).putFloat(1).flip();
                        SceneDrawBinding binding = new SceneDrawBinding() {
                            @Override public boolean skinningEnabled() { return skin; }
                            @Override public int morphTargetCount() { return skin ? 0 : 1; }
                            @Override public void record(CommandBuffer cmd, ShaderProgram program, int index, Pass kind) {
                                cmd.uploadBufferRegion(joints, 0, palette).bindStorageBuffer(7, joints, 0, 64);
                                cmd.uploadBufferRegion(deltas, 0, deltaBytes).bindStorageBuffer(8, deltas, 0, 6 * 12 * 4);
                                cmd.uploadBufferRegion(weights, 0, weightBytes).bindStorageBuffer(9, weights, 0, 4);
                                cmd.trySetUniformInt(program, "uMorphVertexCount", 6);
                            }
                        };
                        Camera camera = new Camera(new Vector3f(0, 0, 5));
                        Scene scene = new Scene(camera).add(new SceneObject(mesh, material,
                                (model, frame) -> model.identity(), false, binding));
                        var frame = new SceneFrameBuilder().build(scene, 96, 96, new Matrix4f(), false, false, 0);
                        var cmd = device.createCommandBuffer();
                        cmd.bindFramebuffer(GL_FRAMEBUFFER, target.id()).viewport(0, 0, 96, 96)
                                .clearColor(0, 0, 0, 0).clear(true, false);
                        cameraUniforms.update(cmd, camera, 96, 96, AntiAliasingMode.NONE, 0);
                        pass.record(cmd, frame, cameraUniforms);
                        device.execute(cmd);
                        target.bind();
                        var pixels = BufferUtils.createByteBuffer(96 * 96);
                        glReadPixels(0, 0, 96, 96, GL_RED, GL_UNSIGNED_BYTE, pixels);
                        assertEquals(0, Byte.toUnsignedInt(pixels.get(48 * 96 + 48)), "rest position must not be marked");
                        int marked = 0;
                        for (int y = 0; y < 96; y++) for (int x = 60; x < 96; x++) {
                            if (Byte.toUnsignedInt(pixels.get(y * 96 + x)) > 200) marked++;
                        }
                        assertTrue(marked > 100, "deformed mask missing: skin=" + skin);
                    }
                } finally { material.close(); }
            }
        }
    }
}
