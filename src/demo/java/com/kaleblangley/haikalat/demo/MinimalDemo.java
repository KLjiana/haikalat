package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.buffer.TripleBuffer;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.VertexAttribute;
import com.kaleblangley.haikalat.core.mesh.VertexLayout;
import com.kaleblangley.haikalat.runtime.RenderStatistics;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class MinimalDemo {

    private static long window;
    private static int fbWidth = 800;
    private static int fbHeight = 600;

    public static void main(String[] args) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_CONTEXT_DEBUG, GLFW_TRUE);
        window = glfwCreateWindow(fbWidth, fbHeight, "MinimalDemo", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("Window creation failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        glEnable(GL_DEPTH_TEST);

        glfwSetFramebufferSizeCallback(window, (h, w, h2) -> {
            fbWidth = Math.max(1, w);
            fbHeight = Math.max(1, h2);
            glViewport(0, 0, fbWidth, fbHeight);
        });
        glfwShowWindow(window);

        GlRenderDevice device = new GlRenderDevice();
        RenderStatistics stats = new RenderStatistics();

        ShaderProgram colorShader = ShaderProgram.fromResource(MinimalDemo.class, "/demo/color_mvp.vert", "/demo/color_unlit.frag");
        ShaderProgram texShader = ShaderProgram.fromResource(MinimalDemo.class, "/demo/textured_mvp.vert", "/demo/textured_unlit.frag");
        ShaderProgram instShader = ShaderProgram.fromResource(MinimalDemo.class, "/demo/instanced_projview.vert", "/demo/instanced_projview.frag");

        Texture2D wallTex = Texture2D.fromResource(MinimalDemo.class, "/wall.png", false);

        VertexLayout posColorLayout = VertexLayout.interleaved(6 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build());

        Mesh triangle = Mesh.builder()
                .layout(posColorLayout)
                .attribute(0, new float[]{-0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f, 0.0f, 0.5f, 0.0f}, 3)
                .attribute(1, new float[]{1.0f, 0.3f, 0.2f, 0.2f, 1.0f, 0.3f, 0.2f, 0.3f, 1.0f}, 3)
                .build();

        Mesh quad = Mesh.builder()
                .vertices(new float[]{
                                -0.3f, -0.3f, 0.0f, 1, 1, 0,
                                0.3f, -0.3f, 0.0f, 0, 1, 1,
                                0.3f, 0.3f, 0.0f, 1, 0, 1,
                                -0.3f, -0.3f, 0.0f, 1, 1, 0,
                                0.3f, 0.3f, 0.0f, 1, 0, 1,
                                -0.3f, 0.3f, 0.0f, 0, 1, 1
                        }, 6 * Float.BYTES,
                        VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                        VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build())
                .build();

        Mesh texQuad = Mesh.builder()
                .vertices(new float[]{
                                -0.5f, -0.5f, 0.0f, 0.0f, 0.0f,
                                0.5f, -0.5f, 0.0f, 1.0f, 0.0f,
                                0.5f, 0.5f, 0.0f, 1.0f, 1.0f,
                                -0.5f, 0.5f, 0.0f, 0.0f, 1.0f
                        }, 5 * Float.BYTES,
                        VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                        VertexAttribute.builder().index(1).size(2).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build())
                .indices(new int[]{0, 1, 2, 0, 2, 3})
                .build();

        Material colorMat = Material.builder(colorShader).blendMode(BlendMode.OPAQUE).build();
        Material texMat = Material.builder(texShader)
                .texture("uTexture", wallTex)
                .setVec3("uTint", new Vector3f(1, 1, 1))
                .blendMode(BlendMode.OPAQUE)
                .build();

        InstancedMeshBatch instBatch = InstancedMeshBatch.of(quad, 16, 2);
        TripleBuffer<List<Matrix4f>> transformBuf = new TripleBuffer<>(ArrayList::new);

        Framebuffer sceneFb = Framebuffer.singleSampled(fbWidth, fbHeight);

        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(45.0),
                fbWidth / (float) fbHeight, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        Matrix4f projView = new Matrix4f();
        Matrix4f triModel = new Matrix4f();
        Matrix4f quadModel = new Matrix4f();
        Matrix4f mvp = new Matrix4f();

        int frame = 0;
        CommandBuffer cmd = device.createCommandBuffer();

        try {
            while (!glfwWindowShouldClose(window)) {
                stats.beginFrame();
                if (sceneFb.width() != fbWidth || sceneFb.height() != fbHeight) {
                    sceneFb.close();
                    sceneFb = Framebuffer.singleSampled(fbWidth, fbHeight);
                }

                triModel.identity().translation(-1.0f, 0.5f, 0).rotateZ(frame * 0.03f);
                quadModel.identity().translation(1.0f, 0.5f, 0).rotateZ(-frame * 0.02f);
                proj.identity().perspective((float) Math.toRadians(45.0),
                        fbWidth / (float) Math.max(1, fbHeight), 0.1f, 100.0f);
                proj.mul(view, projView);

                List<Matrix4f> writes = transformBuf.write();
                writes.clear();
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) {
                        writes.add(new Matrix4f()
                                .translation(-1.4f + c * 0.7f, -1.4f + r * 0.7f, 0)
                                .rotateZ(frame * 0.04f + (r + c) * 0.3f)
                                .scale(0.3f));
                    }
                }
                transformBuf.flip();

                cmd.reset();
                cmd.bindFramebuffer(sceneFb).viewport(0, 0, fbWidth, fbHeight);
                cmd.clearColor(0.08f, 0.10f, 0.14f, 1.0f).clear(true, true);

                colorMat.bind(cmd);
                projView.mul(triModel, mvp);
                cmd.setUniformMat4(colorShader, "uMvp", mvp);
                cmd.bindMesh(triangle).drawMesh(triangle);

                texMat.bind(cmd);
                projView.mul(quadModel, mvp);
                cmd.setUniformMat4(texShader, "uMvp", mvp);
                cmd.bindMesh(texQuad).drawMesh(texQuad);

                cmd.bindShader(instShader);
                cmd.setUniformMat4(instShader, "uProjView", projView);
                cmd.drawInstancedBatch(instBatch, transformBuf.read());

                cmd.bindFramebuffer(GL_FRAMEBUFFER, 0).viewport(0, 0, fbWidth, fbHeight);
                cmd.blitToDefault(sceneFb, fbWidth, fbHeight);

                device.execute(cmd);

                stats.endFrame();

                if ((frame % 60) == 0) {
                    glfwSetWindowTitle(window, String.format("Minimal | FPS %.1f | objs=2+inst",
                            stats.averageFps()));
                }

                GlDebug.checkError("MinimalDemo");
                glfwSwapBuffers(window);
                glfwPollEvents();
                frame++;
            }
        } finally {
            transformBuf.write().clear();
            instBatch.close();
            sceneFb.close();
            texQuad.close();
            quad.close();
            triangle.close();
            wallTex.close();
            instShader.close();
            texShader.close();
            colorShader.close();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }
}
