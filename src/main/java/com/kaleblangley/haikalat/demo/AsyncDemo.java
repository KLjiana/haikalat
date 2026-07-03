package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.gl.*;
import com.kaleblangley.haikalat.gl.buffer.TripleBuffer;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;
import com.kaleblangley.haikalat.gl.command.RenderDevice;
import com.kaleblangley.haikalat.gl.fb.Framebuffer;
import com.kaleblangley.haikalat.gl.material.Material;
import com.kaleblangley.haikalat.gl.material.ShaderProgram;
import com.kaleblangley.haikalat.gl.mesh.Mesh;
import com.kaleblangley.haikalat.gl.mesh.VertexAttribute;
import com.kaleblangley.haikalat.gl.mesh.VertexLayout;
import com.kaleblangley.haikalat.gl.render.GlRenderThread;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

public final class AsyncDemo {

    private static final VertexLayout POS_COLOR_LAYOUT = VertexLayout.interleaved(6 * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
            VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build());

    public static void main(String[] args) throws InterruptedException {
        AppWindow window = new AppWindow(800, 600, "Async Demo");
        window.show();

        RenderSettings settings = RenderSettings.builder().debugErrors(true).build();

        ShaderProgram shader = ShaderProgram.fromSources(
                "#version 330 core\n"
                        + "layout (location = 0) in vec3 aPos; layout (location = 1) in vec3 aColor;\n"
                        + "out vec3 vColor; uniform mat4 uMvp;\n"
                        + "void main() { vColor = aColor; gl_Position = uMvp * vec4(aPos, 1.0); }",
                "#version 330 core\n"
                        + "in vec3 vColor; out vec4 FragColor;\n"
                        + "void main() { FragColor = vec4(vColor, 1.0); }");

        Material mat = Material.builder(shader).blendMode(BlendMode.OPAQUE).build();

        Mesh tri = Mesh.builder().layout(POS_COLOR_LAYOUT)
                .attribute(0, new float[]{-0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f, 0.0f, 0.5f, 0.0f}, 3)
                .attribute(1, new float[]{1.0f, 0.3f, 0.2f, 0.2f, 1.0f, 0.3f, 0.2f, 0.3f, 1.0f}, 3).build();

        Framebuffer sceneFb = Framebuffer.singleSampled(window.width(), window.height());
        TripleBuffer<List<Matrix4f>> models = new TripleBuffer<>(CopyOnWriteArrayList::new);
        long win = window.windowHandle();
        GlRenderThread[] holder = new GlRenderThread[1];
        holder[0] = new GlRenderThread(settings, () -> {
            GLFW.glfwMakeContextCurrent(win);
            GL.createCapabilities();

            List<Matrix4f> readModels = new ArrayList<>(models.read());
            if (readModels.isEmpty()) {
                GLFW.glfwSwapBuffers(win);
                return;
            }

            RenderDevice device = holder[0].renderLoop().device();
            CommandBuffer cmd = device.createCommandBuffer();
            Matrix4f view = new Matrix4f().lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
            Matrix4f proj = new Matrix4f().perspective(
                    (float) Math.toRadians(45.0),
                    window.width() / (float) window.height(), 0.1f, 100.0f);
            Matrix4f mvp = new Matrix4f();

            cmd.bindFramebuffer(sceneFb).viewport(0, 0, window.width(), window.height());
            cmd.clearColor(0.08f, 0.10f, 0.14f, 1.0f).clear(true, true);

            for (Matrix4f model : readModels) {
                mat.bind(cmd);
                proj.mul(view, mvp).mul(model);
                cmd.setUniformMat4(shader, "uMvp", mvp);
                cmd.bindMesh(tri).drawMesh(tri);
            }

            cmd.bindFramebuffer(GL_FRAMEBUFFER, 0).viewport(0, 0, window.width(), window.height());
            cmd.custom(() -> sceneFb.blitToDefault(window.width(), window.height()));

            device.execute(cmd);
            GLFW.glfwSwapBuffers(win);
        });

        GlRenderThread renderThread = holder[0];

        List<Matrix4f> first = models.write();
        for (int i = 0; i < 5; i++) {
            first.add(new Matrix4f().translation(-1.2f + i * 0.6f, 0, -3).scale(0.3f));
        }
        models.flip();

        GLFW.glfwMakeContextCurrent(0);
        renderThread.start();

        int frame = 0;
        while (!window.shouldClose() && renderThread.isAlive()) {
            if (renderThread.failure() != null) {
                System.err.println("Render thread failed: " + renderThread.failure());
                break;
            }

            List<Matrix4f> writes = models.write();
            writes.clear();
            for (int i = 0; i < 5; i++) {
                writes.add(new Matrix4f()
                        .translation(-1.2f + i * 0.6f, 0, -3)
                        .rotateZ(frame * 0.03f + i * 0.5f)
                        .scale(0.3f));
            }
            models.flip();

            GLFW.glfwPollEvents();
            frame++;
        }

        renderThread.close();
        renderThread.join();

        GLFW.glfwMakeContextCurrent(win);
        sceneFb.close();
        tri.close();
        shader.close();
        window.close();
    }
}
