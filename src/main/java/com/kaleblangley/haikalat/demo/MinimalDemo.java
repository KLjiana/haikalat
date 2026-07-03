package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.gl.BlendMode;
import com.kaleblangley.haikalat.gl.GlDebug;
import com.kaleblangley.haikalat.gl.RenderStatistics;
import com.kaleblangley.haikalat.gl.buffer.*;
import com.kaleblangley.haikalat.gl.command.*;
import com.kaleblangley.haikalat.gl.fb.*;
import com.kaleblangley.haikalat.gl.material.*;
import com.kaleblangley.haikalat.gl.mesh.*;
import com.kaleblangley.haikalat.gl.render.*;
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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(fbWidth, fbHeight, "MinimalDemo", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("Window creation failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);

        glfwSetFramebufferSizeCallback(window, (h, w, h2) -> {
            fbWidth = Math.max(1, w);
            fbHeight = Math.max(1, h2);
            glViewport(0, 0, fbWidth, fbHeight);
        });
        glfwShowWindow(window);

        RenderDevice device = new RenderDevice();
        RenderStatistics stats = new RenderStatistics();

        ShaderProgram colorShader = ShaderProgram.fromSources(
                """
                        #version 330 core
                        layout (location = 0) in vec3 aPos; layout (location = 1) in vec3 aColor;
                        out vec3 vColor; uniform mat4 uMvp;
                        void main() { vColor = aColor; gl_Position = uMvp * vec4(aPos, 1.0); }""",
                """
                        #version 330 core
                        in vec3 vColor; out vec4 FragColor;
                        void main() { FragColor = vec4(vColor, 1.0); }""");

        ShaderProgram texShader = ShaderProgram.fromSources(
                """
                        #version 330 core
                        layout (location = 0) in vec3 aPos; layout (location = 1) in vec2 aTexCoord;
                        out vec2 vTexCoord; uniform mat4 uMvp;
                        void main() { vTexCoord = aTexCoord; gl_Position = uMvp * vec4(aPos, 1.0); }""",
                """
                        #version 330 core
                        in vec2 vTexCoord; out vec4 FragColor;
                        uniform sampler2D uTexture; uniform vec3 uTint;
                        void main() { FragColor = texture(uTexture, vTexCoord) * vec4(uTint, 1.0); }""");

        ShaderProgram instShader = ShaderProgram.fromSources(
                """
                        #version 330 core
                        layout (location = 0) in vec3 aPos; layout (location = 1) in vec3 aColor;
                        layout (location = 2) in mat4 aInstanceMatrix;
                        out vec3 vColor; uniform mat4 uProjView;
                        void main() {
                          vColor = mix(vec3(1,0,0), vec3(0,1,0), float(gl_InstanceID%4)/3.0);
                          gl_Position = uProjView * aInstanceMatrix * vec4(aPos, 1.0);
                        }""",
                """
                        #version 330 core
                        in vec3 vColor; out vec4 FragColor;
                        void main() { FragColor = vec4(vColor, 1.0); }""");

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

        while (!glfwWindowShouldClose(window)) {
            stats.beginFrame();

            triModel.identity().translation(-1.0f, 0.5f, 0).rotateZ(frame * 0.03f);
            quadModel.identity().translation(1.0f, 0.5f, 0).rotateZ(-frame * 0.02f);
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
            cmd.custom(() -> {
                instBatch.beginFrame();
                instBatch.submitAll(transformBuf.read());
                instBatch.flush();
            });

            cmd.bindFramebuffer(GL_FRAMEBUFFER, 0).viewport(0, 0, fbWidth, fbHeight);
            cmd.custom(() -> sceneFb.blitToDefault(fbWidth, fbHeight));

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
