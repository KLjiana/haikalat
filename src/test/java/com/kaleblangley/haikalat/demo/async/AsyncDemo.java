package com.kaleblangley.haikalat.demo.async;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.buffer.TripleBuffer;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.VertexAttribute;
import com.kaleblangley.haikalat.core.mesh.VertexLayout;
import com.kaleblangley.haikalat.core.upload.UploadSystem;
import com.kaleblangley.haikalat.runtime.GlRenderThread;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

public final class AsyncDemo {

    private static final VertexLayout POS_COLOR_LAYOUT = VertexLayout.interleaved(6 * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
            VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build());

    private static final int MAX_INSTANCES = 16;

    public static void main(String[] args) {
        Camera camera = new Camera(new Vector3f(0, 0, 5));
        RenderSettings settings = RenderSettings.builder().debugErrors(true).build();
        TripleBuffer<FrameState> stateBuffer = new TripleBuffer<>(() ->
                new FrameState(new Matrix4f(), new CopyOnWriteArrayList<>()));

        GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(800, 600).title("Async Demo").build();
        window.show();

        Framebuffer[] sceneFb = new Framebuffer[1];
        ShaderProgram[] instShader = new ShaderProgram[1];
        Mesh[] quad = new Mesh[1];
        InstancedMeshBatch[] batch = new InstancedMeshBatch[1];
        GlBuffer[] dynBuf = new GlBuffer[1];
        UploadSystem[] uploadSystem = new UploadSystem[1];

        GlRenderThread renderThread = new GlRenderThread(window.handle(), settings, cmd -> {
            uploadSystem[0].flush();
            FrameState state = stateBuffer.read();
            List<Matrix4f> transforms = state.transforms();
            if (transforms.isEmpty()) return;

            Matrix4f proj = new Matrix4f().perspective(
                    (float) Math.toRadians(45.0),
                    window.width() / (float) window.height(), 0.1f, 100.0f);

            cmd.bindFramebuffer(sceneFb[0]).viewport(0, 0, window.width(), window.height());
            cmd.clearColor(0.08f, 0.10f, 0.14f, 1.0f).clear(true, true);

            cmd.bindShader(instShader[0]);
            cmd.setUniformMat4(instShader[0], "uProjView", new Matrix4f(proj).mul(state.view()));
            cmd.custom(() -> {
                batch[0].beginFrame();
                batch[0].submitAll(transforms);
                batch[0].flush();
            });

            cmd.bindFramebuffer(GL_FRAMEBUFFER, 0).viewport(0, 0, window.width(), window.height());
            cmd.custom(() -> sceneFb[0].blitToDefault(window.width(), window.height()));
        });

        renderThread.onInit(() -> {
            sceneFb[0] = Framebuffer.singleSampled(window.width(), window.height());
            instShader[0] = ShaderProgram.fromResource(AsyncDemo.class,
                    "/demo/instanced_projview.vert", "/demo/instanced_projview.frag");
            quad[0] = Mesh.builder().layout(POS_COLOR_LAYOUT)
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
            batch[0] = InstancedMeshBatch.of(quad[0], MAX_INSTANCES, 2);
            dynBuf[0] = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW).allocate(MAX_INSTANCES * 16L * Float.BYTES);
            uploadSystem[0] = new UploadSystem();
        });

        renderThread.onCleanup(() -> {
            if (sceneFb[0] != null) sceneFb[0].close();
            if (batch[0] != null) batch[0].close();
            if (quad[0] != null) quad[0].close();
            if (instShader[0] != null) instShader[0].close();
            if (dynBuf[0] != null) dynBuf[0].close();
        });

        CompletableFuture<Void> done = renderThread.start();

        FloatBuffer uploadFb = ByteBuffer.allocateDirect(MAX_INSTANCES * 16 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        float lastTime = (float) GLFW.glfwGetTime();
        int frame = 0;
        while (!window.shouldClose() && !done.isCompletedExceptionally()) {
            float now = (float) GLFW.glfwGetTime();
            float dt = Math.min(now - lastTime, 0.1f);
            lastTime = now;

            window.pollEvents();

            float sens = 0.1f;
            camera.processMouseMovement(
                    (float) window.mouseDeltaX() * sens,
                    (float) window.mouseDeltaY() * sens);

            float speed = 2.5f * dt;
            if (window.isKeyDown(GLFW.GLFW_KEY_W)) camera.processKeyboard(Camera.Movement.FORWARD, speed);
            if (window.isKeyDown(GLFW.GLFW_KEY_S)) camera.processKeyboard(Camera.Movement.BACKWARD, speed);
            if (window.isKeyDown(GLFW.GLFW_KEY_A)) camera.processKeyboard(Camera.Movement.LEFT, speed);
            if (window.isKeyDown(GLFW.GLFW_KEY_D)) camera.processKeyboard(Camera.Movement.RIGHT, speed);
            if (window.isKeyDown(GLFW.GLFW_KEY_ESCAPE)) break;

            FrameState writes = stateBuffer.write();
            List<Matrix4f> t = writes.transforms();
            t.clear();
            uploadFb.clear();
            for (int i = 0; i < 4; i++) {
                Matrix4f m = new Matrix4f()
                        .translation(-1.5f + i * 1.0f, 0, -2)
                        .rotateZ(frame * 0.04f + i * 0.3f)
                        .scale(0.9f);
                t.add(m);
                m.get(uploadFb);
                uploadFb.position(uploadFb.position() + 16);
            }
            uploadFb.flip();
            uploadSystem[0].uploadFloats(dynBuf[0], 0, uploadFb);

            writes.view().set(camera.getViewMatrix());
            stateBuffer.flip();

            frame++;
        }

        renderThread.close();
        try { done.get(); } catch (Exception e) { System.err.println("Render failed: " + e.getCause()); }
        window.releaseContext();
        window.close();
    }
}
