package com.kaleblangley.haikalat.demo.async;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.RenderTargetManager;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.buffer.TripleBuffer;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.GlRenderThread;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/** Demonstrates producer-thread frame state and uploads consumed by the engine GL render thread. */
public final class AsyncDemo {
    private static final String SCENE_TARGET = "AsyncScene";
    private static final int MAX_INSTANCES = 16;

    private AsyncDemo() {
    }

    public static void main(String[] args) {
        Camera camera = new Camera(new Vector3f(0, 0, 5));
        RenderSettings settings = RenderSettings.builder().vsync(true).build();
        TripleBuffer<FrameState> stateBuffer = new TripleBuffer<>(() ->
                new FrameState(new Matrix4f(), new CopyOnWriteArrayList<>()));

        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(800, 600)
                .title("Async Demo")
                .build()) {
            window.show();
            run(window, camera, settings, stateBuffer);
        }
    }

    private static void run(GlfwWindow window, Camera camera, RenderSettings settings,
                            TripleBuffer<FrameState> stateBuffer) {
        AsyncResources resources = new AsyncResources();
        GlRenderThread renderThread = new GlRenderThread(window.handle(), settings, commands -> {
            resources.resizeIfNeeded(window.width(), window.height());
            Framebuffer sceneTarget = resources.targets.get(SCENE_TARGET);
            FrameState state = stateBuffer.read();
            List<Matrix4f> transforms = state.transforms();

            Matrix4f projectionView = new Matrix4f().perspective(
                    (float) Math.toRadians(45.0),
                    window.width() / (float) Math.max(1, window.height()), 0.1f, 100.0f)
                    .mul(state.view());

            commands.bindFramebuffer(sceneTarget)
                    .viewport(0, 0, sceneTarget.width(), sceneTarget.height())
                    .clearColor(0.08f, 0.10f, 0.14f, 1.0f)
                    .clear(true, true);
            if (!transforms.isEmpty()) {
                commands.bindShader(resources.shader)
                        .setUniformMat4(resources.shader, "uProjView", projectionView)
                        .drawInstancedBatch(resources.batch, transforms);
            }
            commands.bindFramebuffer(GL_FRAMEBUFFER, 0)
                    .viewport(0, 0, window.width(), window.height())
                    .blitToDefault(sceneTarget, window.width(), window.height());
        });

        renderThread.onInit(() -> resources.initialize(window.width(), window.height()));
        renderThread.onCleanup(resources::close);
        CompletableFuture<Void> done = renderThread.start();

        FloatBuffer uploadData = ByteBuffer.allocateDirect(MAX_INSTANCES * 16 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        try {
            float lastTime = (float) GLFW.glfwGetTime();
            int frame = 0;
            while (!window.shouldClose() && !done.isCompletedExceptionally()) {
                float now = (float) GLFW.glfwGetTime();
                float deltaTime = Math.min(now - lastTime, 0.1f);
                lastTime = now;
                window.pollEvents();

                updateCamera(window, camera, deltaTime);
                publishFrame(stateBuffer, camera, frame, uploadData);
                renderThread.uploadQueue().uploadFloats(resources.uploadProbe, 0, uploadData);

                if ((frame % 120) == 0) {
                    window.setTitle(String.format("Async Demo | uploaded %.1f KiB | GPU updates %d",
                            renderThread.uploadQueue().totalBytesUploaded() / 1024.0,
                            renderThread.uploadQueue().totalGpuUpdates()));
                }
                frame++;
            }
        } finally {
            renderThread.close();
            try {
                done.get();
            } catch (Exception e) {
                System.err.println("Render failed: " + e.getCause());
            }
        }
    }

    private static void updateCamera(GlfwWindow window, Camera camera, float deltaTime) {
        camera.processMouseMovement((float) window.mouseDeltaX() * 0.1f,
                (float) window.mouseDeltaY() * 0.1f);
        float speed = 2.5f * deltaTime;
        if (window.isKeyDown(GLFW.GLFW_KEY_W)) camera.processKeyboard(Camera.Movement.FORWARD, speed);
        if (window.isKeyDown(GLFW.GLFW_KEY_S)) camera.processKeyboard(Camera.Movement.BACKWARD, speed);
        if (window.isKeyDown(GLFW.GLFW_KEY_A)) camera.processKeyboard(Camera.Movement.LEFT, speed);
        if (window.isKeyDown(GLFW.GLFW_KEY_D)) camera.processKeyboard(Camera.Movement.RIGHT, speed);
        if (window.isKeyDown(GLFW.GLFW_KEY_ESCAPE)) window.requestClose();
    }

    private static void publishFrame(TripleBuffer<FrameState> stateBuffer, Camera camera,
                                     int frame, FloatBuffer uploadData) {
        FrameState writes = stateBuffer.write();
        List<Matrix4f> transforms = writes.transforms();
        transforms.clear();
        uploadData.clear();
        for (int i = 0; i < 4; i++) {
            Matrix4f transform = new Matrix4f()
                    .translation(-1.5f + i, 0.0f, -2.0f)
                    .rotateZ(frame * 0.04f + i * 0.3f)
                    .scale(0.9f);
            transforms.add(transform);
            transform.get(uploadData);
            uploadData.position(uploadData.position() + 16);
        }
        uploadData.flip();
        writes.view().set(camera.getViewMatrix());
        stateBuffer.flip();
    }

    private static final class AsyncResources implements AutoCloseable {
        private final RenderTargetManager targets = new RenderTargetManager();
        private ShaderProgram shader;
        private Mesh mesh;
        private InstancedMeshBatch batch;
        private GlBuffer uploadProbe;

        void initialize(int width, int height) {
            targets.create(SCENE_TARGET,
                    FramebufferDescriptor.singleColorDepthRenderbuffer(width, height));
            shader = ShaderProgram.fromResource(AsyncDemo.class,
                    "/demo/instanced_projview.vert", "/demo/instanced_projview.frag");
            mesh = Mesh.from(BuiltinMeshData.coloredQuad("async-instanced-quad"));
            batch = InstancedMeshBatch.of(mesh, MAX_INSTANCES, 3);
            uploadProbe = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW)
                    .allocate(MAX_INSTANCES * 16L * Float.BYTES);
        }

        void resizeIfNeeded(int width, int height) {
            Framebuffer target = targets.get(SCENE_TARGET);
            if (target.width() != width || target.height() != height) {
                targets.resize(width, height);
            }
        }

        @Override
        public void close() {
            if (batch != null) batch.close();
            if (mesh != null) mesh.close();
            if (shader != null) shader.close();
            if (uploadProbe != null) uploadProbe.close();
            targets.close();
        }
    }
}
