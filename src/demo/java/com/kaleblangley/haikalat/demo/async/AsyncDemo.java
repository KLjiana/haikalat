package com.kaleblangley.haikalat.demo.async;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.RenderTargetManager;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.GlRenderThread;
import com.kaleblangley.haikalat.runtime.LatestFrameMailbox;
import com.kaleblangley.haikalat.runtime.PeriodicTimer;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.RenderStatistics;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/** 演示生产线程发布帧状态和上传请求，并由引擎 OpenGL 渲染线程消费。 */
public final class AsyncDemo {
    private static final String TITLE = "Async Demo";
    private static final String SCENE_TARGET = "AsyncScene";
    private static final int ACTIVE_INSTANCES = 4;
    private static final int INSTANCE_CAPACITY = 16;
    private static final int MATRIX_FLOATS = 16;
    private static final int INSTANCE_BUFFER_BYTES = INSTANCE_CAPACITY * MATRIX_FLOATS * Float.BYTES;
    private static final int INSTANCE_BLOCK_BINDING = 1;
    private static final float ROTATION_RADIANS_PER_SECOND = 12.8f;

    private AsyncDemo() {
    }

    public static void main(String[] args) {
        AsyncOptions options = AsyncOptions.parse(args);
        Camera camera = new Camera(new Vector3f(0, 0, 5));
        RenderSettings settings = RenderSettings.builder().vsync(options.vsync()).build();
        LatestFrameMailbox<FrameState> frameMailbox = new LatestFrameMailbox<>(
                new FrameState(new Matrix4f(), 0));

        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(DemoSupport.DEFAULT_WIDTH, DemoSupport.DEFAULT_HEIGHT)
                .title(TITLE)
                .build()) {
            if (!options.hidden()) {
                window.show();
            }
            run(window, camera, settings, frameMailbox, options.maxFrames());
        }
    }

    private static void run(GlfwWindow window, Camera camera, RenderSettings settings,
                            LatestFrameMailbox<FrameState> frameMailbox, int maxFrames) {
        AsyncResources resources = new AsyncResources();
        GlRenderThread renderThread = new GlRenderThread(window.handle(), settings, commands -> {
            resources.resizeIfNeeded(window.width(), window.height());
            Framebuffer sceneTarget = resources.targets.get(SCENE_TARGET);
            LatestFrameMailbox.Snapshot<FrameState> snapshot = frameMailbox.latest();
            resources.observe(snapshot.sequence());
            FrameState state = snapshot.value();
            Matrix4f projectionView = DemoSupport.perspective(
                    new Matrix4f(), window.width(), window.height()).mul(state.view());

            DemoSupport.beginScene(commands, sceneTarget);
            if (state.instanceCount() > 0) {
                commands.bindShader(resources.shader)
                        .setUniformMat4(resources.shader, DemoSupport.U_PROJECTION_VIEW, projectionView)
                        .bindUniformBuffer(INSTANCE_BLOCK_BINDING, resources.instanceMatrices,
                                0L, INSTANCE_BUFFER_BYTES)
                        .bindMesh(resources.mesh)
                        .drawMeshInstanced(resources.mesh, state.instanceCount());
            }
            commands.bindFramebuffer(GL_FRAMEBUFFER, 0)
                    .viewport(0, 0, window.width(), window.height())
                    .blitToDefault(sceneTarget, window.width(), window.height());
        });

        renderThread.onInit(() -> resources.initialize(window.width(), window.height()));
        renderThread.onCleanup(resources::close);
        CompletableFuture<Void> done = renderThread.start();

        FloatBuffer uploadData = ByteBuffer.allocateDirect(INSTANCE_BUFFER_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        try {
            FrameClock clock = new FrameClock();
            PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
            int frame = 0;
            while (!window.shouldClose() && !done.isDone()) {
                window.waitEvents(1.0 / 120.0);
                FrameClock.Tick time = clock.tick();

                DemoSupport.updateFreeCamera(window, camera, time.deltaSeconds());
                if (maxFrames < 0 || frame < maxFrames) {
                    FrameState frameState = prepareFrame(camera, time.elapsedSeconds(), uploadData);
                    if (!submitFrame(renderThread, resources.instanceMatrices,
                            uploadData, frameState, frameMailbox)) {
                        break;
                    }
                    frame++;
                } else if (resources.lastObservedSequence() >= maxFrames) {
                    window.requestClose();
                }

                if (frame > 0 && titleUpdate.poll()) {
                    GlRenderThread.UploadStats uploadStats = renderThread.uploadStats();
                    RenderStatistics.Snapshot timing = renderThread.timingStats();
                    window.setTitle(String.format(TITLE + " | FPS %.1f | CPU %.3f ms | uploaded %.1f KiB | GPU updates %d | dropped %d",
                            timing.presentFps(), timing.cpuSubmitMillis(),
                            uploadStats.bytesUploaded() / 1024.0,
                            uploadStats.gpuUpdates(), resources.droppedFrames()));
                }
            }
        } finally {
            renderThread.close();
            done.join();
        }
    }

    private static FrameState prepareFrame(Camera camera, double elapsedSeconds, FloatBuffer uploadData) {
        uploadData.clear();
        for (int i = 0; i < ACTIVE_INSTANCES; i++) {
            Matrix4f transform = new Matrix4f()
                    .translation(-1.5f + i, 0.0f, -2.0f)
                    .rotateZ((float) elapsedSeconds * ROTATION_RADIANS_PER_SECOND + i * 0.3f)
                    .scale(0.9f);
            transform.get(uploadData);
            uploadData.position(uploadData.position() + MATRIX_FLOATS);
        }
        uploadData.flip();
        return new FrameState(camera.getViewMatrix(), ACTIVE_INSTANCES);
    }

    private static boolean submitFrame(GlRenderThread renderThread, GlBuffer target,
                                       FloatBuffer data, FrameState state,
                                       LatestFrameMailbox<FrameState> mailbox) {
        return renderThread.enqueueFloatUpload(target, 0, data, () -> mailbox.publish(state));
    }

    private static final class AsyncResources implements AutoCloseable {
        private final RenderTargetManager targets = new RenderTargetManager();
        private ShaderProgram shader;
        private Mesh mesh;
        private GlBuffer instanceMatrices;
        private volatile long lastObservedSequence;
        private volatile long droppedFrames;

        void initialize(int width, int height) {
            targets.create(SCENE_TARGET,
                    FramebufferDescriptor.singleColorDepthRenderbuffer(width, height));
            shader = DemoSupport.loadAsyncInstancedShader(AsyncDemo.class);
            shader.bindUniformBlock("AsyncInstances", INSTANCE_BLOCK_BINDING);
            mesh = Mesh.from(BuiltinMeshData.named(BuiltinMeshData.QUAD));
            instanceMatrices = GlBuffer.uniformBuffer(GL_DYNAMIC_DRAW).allocate(INSTANCE_BUFFER_BYTES);
        }

        void resizeIfNeeded(int width, int height) {
            Framebuffer target = targets.get(SCENE_TARGET);
            if (target.width() != width || target.height() != height) {
                targets.resize(width, height);
            }
        }

        void observe(long sequence) {
            if (sequence > lastObservedSequence) {
                droppedFrames += Math.max(0L, sequence - lastObservedSequence - 1L);
                lastObservedSequence = sequence;
            }
        }

        long droppedFrames() {
            return droppedFrames;
        }

        long lastObservedSequence() {
            return lastObservedSequence;
        }

        @Override
        public void close() {
            if (mesh != null) mesh.close();
            if (shader != null) shader.close();
            if (instanceMatrices != null) instanceMatrices.close();
            targets.close();
        }
    }

    private record AsyncOptions(boolean hidden, boolean vsync, int maxFrames) {
        static AsyncOptions parse(String[] args) {
            boolean hidden = false;
            boolean vsync = true;
            int maxFrames = -1;
            for (String arg : args) {
                if ("--deterministic".equals(arg)) {
                    hidden = true;
                    vsync = false;
                } else if ("--hidden".equals(arg)) {
                    hidden = true;
                } else if ("--no-vsync".equals(arg)) {
                    vsync = false;
                } else if (arg.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(arg.substring("--frames=".length()));
                    if (maxFrames <= 0) {
                        throw new IllegalArgumentException("--frames must be positive");
                    }
                } else {
                    throw new IllegalArgumentException("Unknown async demo argument: " + arg);
                }
            }
            if (hidden && !vsync && maxFrames < 0) {
                maxFrames = 8;
            }
            return new AsyncOptions(hidden, vsync, maxFrames);
        }
    }
}
