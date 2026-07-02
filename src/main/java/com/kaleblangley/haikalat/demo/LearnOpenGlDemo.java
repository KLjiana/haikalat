package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.gl.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class LearnOpenGlDemo {

    private static final String SHADER_COLOR_VERT = "/color.vert";
    private static final String SHADER_COLOR_FRAG = "/color.frag";
    private static final String SHADER_TEXTURED_VERT = "/textured.vert";
    private static final String SHADER_TEXTURED_FRAG = "/textured.frag";
    private static final String SHADER_INSTANCED_VERT = "/instanced.vert";
    private static final String SHADER_INSTANCED_FRAG = "/instanced.frag";

    private static long window;
    private static int fbWidth = 1280;
    private static int fbHeight = 720;
    private static float deltaTime;
    private static float lastTime;
    private static double lastMouseX;
    private static double lastMouseY;
    private static boolean firstMouse = true;
    private static final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];

    public static void main(String[] args) {
        initGlfw();
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);

        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 5.0f));
        initCallbacks(camera);
        glfwShowWindow(window);

        demoVertexPacking();

        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .debugErrors(true)
                .build();
        RenderLoop renderLoop = new RenderLoop(settings);
        AntiAliasPipeline aaPipeline = AntiAliasPipeline.create(settings, fbWidth, fbHeight);

        ShaderProgram colorShader = ShaderProgram.fromResource(LearnOpenGlDemo.class, SHADER_COLOR_VERT, SHADER_COLOR_FRAG);
        ShaderProgram texturedShader = ShaderProgram.fromResource(LearnOpenGlDemo.class, SHADER_TEXTURED_VERT, SHADER_TEXTURED_FRAG);
        ShaderProgram instancedShader = ShaderProgram.fromResource(LearnOpenGlDemo.class, SHADER_INSTANCED_VERT, SHADER_INSTANCED_FRAG);

        Texture2D wallTexture = Texture2D.fromResource(LearnOpenGlDemo.class, "/wall.png", false);
        Texture2D faceTexture = Texture2D.fromResource(LearnOpenGlDemo.class, "/awesomeface.png", true);

        Mesh triangleMesh = createTriangleMesh();
        Mesh texturedQuadMesh = createTexturedQuadMesh();
        Mesh coloredQuadMesh = createColoredQuadMesh();

        InstancedMeshBatch instancedBatch = InstancedMeshBatch.of(coloredQuadMesh, 32, 2);
        BatchedRenderQueue batchedQueue = new BatchedRenderQueue();
        TripleBuffer<List<Matrix4f>> transformBuffers = new TripleBuffer<>(ArrayList::new);

        GpuFence frameFence = null;
        int frameIndex = 0;
        int instancedDrawn = 0;

        while (!glfwWindowShouldClose(window)) {
            updateDeltaTime();
            processKeyboardInput(camera);

            if (frameFence != null) {
                frameFence.close();
                frameFence = null;
            }

            renderLoop.beginFrame();
            glClearColor(0.08f, 0.10f, 0.14f, 1.0f);
            aaPipeline.sceneTarget().bind().clear(true, true);

            List<Matrix4f> writeTransforms = transformBuffers.write();
            writeTransforms.clear();
            populateInstanceTransforms(writeTransforms, frameIndex);
            transformBuffers.flip();

            Matrix4f projection = projectionMatrix();
            Matrix4f view = camera.getViewMatrix();

            final int fi = frameIndex;

            batchedQueue.clear();

            batchedQueue.submit(
                    new DrawSortKey(colorShader.id(), triangleMesh.id(), 0, 0),
                    () -> {
                        colorShader.use()
                                .setMat4("uProjection", projection)
                                .setMat4("uView", view)
                                .setMat4("uModel", new Matrix4f()
                                        .translation(-1.5f, 0.5f, -2.5f)
                                        .rotateZ(fi * 0.03f));
                        triangleMesh.draw();
                    }
            );

            batchedQueue.submit(
                    new DrawSortKey(texturedShader.id(), texturedQuadMesh.id(), wallTexture.id(), 0),
                    () -> {
                        wallTexture.bind(0);
                        texturedShader.use()
                                .setMat4("uProjection", projection)
                                .setMat4("uView", view)
                                .setMat4("uModel", new Matrix4f()
                                        .translation(1.0f, 0.5f, -2.5f)
                                        .rotateZ(fi * 0.02f))
                                .setInt("uTexture", 0)
                                .setVec3("uTint", new Vector3f(1.0f, 1.0f, 1.0f));
                        texturedQuadMesh.draw();
                    }
            );

            batchedQueue.submit(
                    new DrawSortKey(colorShader.id(), coloredQuadMesh.id(), 0, 1),
                    () -> {
                        colorShader.use()
                                .setMat4("uProjection", projection)
                                .setMat4("uView", view)
                                .setMat4("uModel", new Matrix4f()
                                        .translation(-1.5f, -1.0f, -3.0f)
                                        .scale(0.6f));
                        coloredQuadMesh.draw();
                    }
            );

            batchedQueue.flush();

            instancedShader.use()
                    .setMat4("uProjection", projection)
                    .setMat4("uView", view);
            instancedBatch.beginFrame();
            instancedBatch.submitAll(transformBuffers.read());
            instancedDrawn = instancedBatch.flush();
            instancedShader.stopUsing();

            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);

            faceTexture.bind(0);
            texturedShader.use()
                    .setMat4("uProjection", projection)
                    .setMat4("uView", view)
                    .setMat4("uModel", new Matrix4f()
                            .translation(1.5f, (float) Math.sin(frameIndex * 0.04f) * 0.5f - 1.0f, -3.0f)
                            .scale(0.5f))
                    .setInt("uTexture", 0)
                    .setVec3("uTint", new Vector3f(1.0f, 1.0f, 1.0f));
            texturedQuadMesh.draw();
            texturedShader.stopUsing();

            glDepthMask(true);

            frameFence = GpuFence.insert();

            aaPipeline.present(fbWidth, fbHeight);
            renderLoop.endFrame();

            if ((frameIndex % 60) == 0) {
                double fps = renderLoop.statistics().averageFps();
                glfwSetWindowTitle(window,
                        String.format("LearnOpenGL | FPS %.1f | instanced %d", fps, instancedDrawn));
            }

            if (settings.debugErrors()) {
                GlDebug.checkError("LearnOpenGlDemo.frame");
            }

            glfwSwapBuffers(window);
            glfwPollEvents();
            frameIndex++;
        }

        if (frameFence != null) {
            frameFence.close();
        }
        instancedBatch.close();
        coloredQuadMesh.close();
        texturedQuadMesh.close();
        triangleMesh.close();
        faceTexture.close();
        wallTexture.close();
        instancedShader.close();
        texturedShader.close();
        colorShader.close();
        aaPipeline.close();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static void initGlfw() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(fbWidth, fbHeight, "LearnOpenGL Demo", NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("Failed to create GLFW window");
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
    }

    private static void initCallbacks(Camera camera) {
        glfwSetFramebufferSizeCallback(window, (handle, width, height) -> {
            fbWidth = Math.max(1, width);
            fbHeight = Math.max(1, height);
            glViewport(0, 0, fbWidth, fbHeight);
        });

        glfwSetKeyCallback(window, (handle, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) {
                if (action == GLFW_PRESS) {
                    keys[key] = true;
                } else if (action == GLFW_RELEASE) {
                    keys[key] = false;
                }
            }
        });

        glfwSetCursorPosCallback(window, (handle, xpos, ypos) -> {
            if (firstMouse) {
                lastMouseX = xpos;
                lastMouseY = ypos;
                firstMouse = false;
            }
            float xOffset = (float) (xpos - lastMouseX);
            float yOffset = (float) (lastMouseY - ypos);
            lastMouseX = xpos;
            lastMouseY = ypos;
            camera.processMouseMovement(xOffset, yOffset);
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
    }

    private static void updateDeltaTime() {
        float currentTime = (float) glfwGetTime();
        deltaTime = currentTime - lastTime;
        lastTime = currentTime;
    }

    private static void processKeyboardInput(Camera camera) {
        if (keys[GLFW_KEY_ESCAPE]) {
            glfwSetWindowShouldClose(window, true);
        }
        if (keys[GLFW_KEY_W]) {
            camera.processKeyboard(Camera.Movement.FORWARD, deltaTime);
        }
        if (keys[GLFW_KEY_S]) {
            camera.processKeyboard(Camera.Movement.BACKWARD, deltaTime);
        }
        if (keys[GLFW_KEY_A]) {
            camera.processKeyboard(Camera.Movement.LEFT, deltaTime);
        }
        if (keys[GLFW_KEY_D]) {
            camera.processKeyboard(Camera.Movement.RIGHT, deltaTime);
        }
    }

    private static Mesh createTriangleMesh() {
        return Mesh.builder()
                .vertices(new float[]{
                        -0.5f, -0.5f, 0.0f, 1.0f, 0.3f, 0.2f,
                        0.5f, -0.5f, 0.0f, 0.2f, 1.0f, 0.3f,
                        0.0f, 0.5f, 0.0f, 0.2f, 0.3f, 1.0f
                }, 6 * Float.BYTES,
                        VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                        VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build())
                .build();
    }

    private static Mesh createColoredQuadMesh() {
        return Mesh.builder()
                .vertices(new float[]{
                        -0.5f, -0.5f, 0.0f, 1.0f, 0.8f, 0.2f,
                        0.5f, -0.5f, 0.0f, 0.2f, 0.8f, 1.0f,
                        0.5f, 0.5f, 0.0f, 0.8f, 0.2f, 1.0f,
                        -0.5f, -0.5f, 0.0f, 1.0f, 0.8f, 0.2f,
                        0.5f, 0.5f, 0.0f, 0.8f, 0.2f, 1.0f,
                        -0.5f, 0.5f, 0.0f, 0.2f, 1.0f, 0.8f
                }, 6 * Float.BYTES,
                        VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                        VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build())
                .build();
    }

    private static Mesh createTexturedQuadMesh() {
        return Mesh.builder()
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
    }

    private static Matrix4f projectionMatrix() {
        return new Matrix4f().perspective(
                (float) Math.toRadians(45.0), fbWidth / (float) fbHeight, 0.1f, 100.0f);
    }

    private static void populateInstanceTransforms(List<Matrix4f> target, int frameIndex) {
        int columns = 4;
        int rows = 4;
        float spacing = 0.8f;
        float startX = -1.2f;
        float startY = 1.2f;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                float px = startX + x * spacing;
                float py = startY - y * spacing;
                float angle = (frameIndex * 0.02f) + (x + y * columns) * 0.15f;
                target.add(new Matrix4f()
                        .translation(px, py, -2.5f)
                        .rotateZ(angle)
                        .scale(0.42f));
            }
        }
    }

    private static void demoVertexPacking() {
        float nx = 0.5f;
        float ny = 0.5f;
        float nz = 0.7071f;
        int packed = VertexPacking.packOctNormal(nx, ny, nz);
        float[] unpacked = VertexPacking.unpackOctNormal(packed);
        System.out.printf("[VertexPacking] original=(%.3f,%.3f,%.3f) packed=0x%08X unpacked=(%.3f,%.3f,%.3f)%n",
                nx, ny, nz, packed, unpacked[0], unpacked[1], unpacked[2]);
    }
}
