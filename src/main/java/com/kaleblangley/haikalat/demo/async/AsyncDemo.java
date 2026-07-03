package com.kaleblangley.haikalat.demo.async;

import com.kaleblangley.haikalat.gl.*;
import com.kaleblangley.haikalat.gl.buffer.TripleBuffer;
import com.kaleblangley.haikalat.gl.camera.Camera;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;
import com.kaleblangley.haikalat.gl.fb.Framebuffer;
import com.kaleblangley.haikalat.gl.material.Material;
import com.kaleblangley.haikalat.gl.material.ShaderProgram;
import com.kaleblangley.haikalat.gl.mesh.Mesh;
import com.kaleblangley.haikalat.gl.mesh.VertexAttribute;
import com.kaleblangley.haikalat.gl.mesh.VertexLayout;
import com.kaleblangley.haikalat.gl.render.GlRenderThread;
import com.kaleblangley.haikalat.gl.render.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

public final class AsyncDemo {

    private static final VertexLayout POS_COLOR_LAYOUT = VertexLayout.interleaved(6 * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
            VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build());

    public static void main(String[] args) throws Exception {
        Camera camera = new Camera(new Vector3f(0, 0, 5));
        RenderSettings settings = RenderSettings.builder().debugErrors(true).build();
        TripleBuffer<FrameState> stateBuffer = new TripleBuffer<>(() ->
                new FrameState(new Matrix4f(), new CopyOnWriteArrayList<>()));

        GlfwWindow window = new GlfwWindow(800, 600, "Async Demo");
        window.show();

        Framebuffer[] sceneFb = new Framebuffer[1];
        ShaderProgram[] shader = new ShaderProgram[1];
        Mesh[] tri = new Mesh[1];
        Material[] mat = new Material[1];

        GlRenderThread renderThread = new GlRenderThread(window.handle(), settings, cmd -> {
            FrameState state = stateBuffer.read();
            List<Matrix4f> transforms = state.transforms();
            if (transforms.isEmpty()) {
                return;
            }

            Matrix4f proj = new Matrix4f().perspective(
                    (float) Math.toRadians(45.0),
                    window.width() / (float) window.height(), 0.1f, 100.0f);
            Matrix4f mvp = new Matrix4f();

            cmd.bindFramebuffer(sceneFb[0]).viewport(0, 0, window.width(), window.height());
            cmd.clearColor(0.08f, 0.10f, 0.14f, 1.0f).clear(true, true);

            for (Matrix4f model : transforms) {
                mat[0].bind(cmd);
                proj.mul(state.view(), mvp).mul(model);
                cmd.setUniformMat4(shader[0], "uMvp", mvp);
                cmd.bindMesh(tri[0]).drawMesh(tri[0]);
            }

            cmd.bindFramebuffer(GL_FRAMEBUFFER, 0).viewport(0, 0, window.width(), window.height());
            cmd.custom(() -> sceneFb[0].blitToDefault(window.width(), window.height()));
        });

        renderThread.onInit(() -> {
            sceneFb[0] = Framebuffer.singleSampled(window.width(), window.height());
            shader[0] = ShaderProgram.fromSources(
                    "#version 330 core\n"
                            + "layout (location = 0) in vec3 aPos; layout (location = 1) in vec3 aColor;\n"
                            + "out vec3 vColor; uniform mat4 uMvp;\n"
                            + "void main() { vColor = aColor; gl_Position = uMvp * vec4(aPos, 1.0); }",
                    "#version 330 core\n"
                            + "in vec3 vColor; out vec4 FragColor;\n"
                            + "void main() { FragColor = vec4(vColor, 1.0); }");
            tri[0] = Mesh.builder().layout(POS_COLOR_LAYOUT)
                    .attribute(0, new float[]{-0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f, 0.0f, 0.5f, 0.0f}, 3)
                    .attribute(1, new float[]{1.0f, 0.3f, 0.2f, 0.2f, 1.0f, 0.3f, 0.2f, 0.3f, 1.0f}, 3).build();
            mat[0] = Material.builder(shader[0]).blendMode(BlendMode.OPAQUE).build();
        });

        CompletableFuture<Void> done = renderThread.start();

        float lastTime = (float) GLFW.glfwGetTime();
        int frame = 0;
        while (!window.shouldClose() && !done.isCompletedExceptionally()) {
            float now = (float) GLFW.glfwGetTime();
            float dt = Math.min(now - lastTime, 0.1f);
            lastTime = now;

            window.pollEvents();

            float mouseSens = 0.1f;
            camera.processMouseMovement(
                    (float) window.mouseDeltaX() * mouseSens,
                    (float) window.mouseDeltaY() * mouseSens);

            float speed = 2.5f * dt;
            if (window.isKeyDown(GLFW.GLFW_KEY_W)) camera.processKeyboard(Camera.Movement.FORWARD, speed);
            if (window.isKeyDown(GLFW.GLFW_KEY_S)) camera.processKeyboard(Camera.Movement.BACKWARD, speed);
            if (window.isKeyDown(GLFW.GLFW_KEY_A)) camera.processKeyboard(Camera.Movement.LEFT, speed);
            if (window.isKeyDown(GLFW.GLFW_KEY_D)) camera.processKeyboard(Camera.Movement.RIGHT, speed);
            if (window.isKeyDown(GLFW.GLFW_KEY_ESCAPE)) break;

            FrameState writes = stateBuffer.write();
            writes.transforms().clear();
            for (int i = 0; i < 5; i++) {
                writes.transforms().add(new Matrix4f()
                        .translation(-1.2f + i * 0.6f, 0, -2)
                        .rotateZ(frame * 0.03f + i * 0.5f)
                        .scale(0.8f));
            }
            writes.view().set(camera.getViewMatrix());
            stateBuffer.flip();

            frame++;
        }

        renderThread.close();
        try {
            done.get();
        } catch (Exception e) {
            System.err.println("Render failed: " + e.getCause());
        }

        window.makeContextCurrent();
        GL.createCapabilities();
        if (sceneFb[0] != null) sceneFb[0].close();
        if (tri[0] != null) tri[0].close();
        if (shader[0] != null) shader[0].close();
        window.releaseContext();
        window.close();
    }
}
