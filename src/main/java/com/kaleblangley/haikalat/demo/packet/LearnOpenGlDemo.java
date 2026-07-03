package com.kaleblangley.haikalat.demo.packet;

import com.kaleblangley.haikalat.gl.AntiAliasingMode;
import com.kaleblangley.haikalat.gl.BlendMode;
import com.kaleblangley.haikalat.gl.GlDebug;
import com.kaleblangley.haikalat.gl.RenderSettings;
import com.kaleblangley.haikalat.gl.buffer.*;
import com.kaleblangley.haikalat.gl.camera.*;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class LearnOpenGlDemo {

    private static final String SHADER_COLOR_VERT = "/color.vert";
    private static final String SHADER_COLOR_FRAG = "/color.frag";
    private static final String SHADER_TEXTURED_VERT = "/textured.vert";
    private static final String SHADER_TEXTURED_FRAG = "/textured.frag";
    private static final String SHADER_INSTANCED_VERT = "/instanced.vert";
    private static final String SHADER_INSTANCED_FRAG = "/instanced.frag";

    private static final VertexLayout POS_COLOR_LAYOUT = VertexLayout.interleaved(6 * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
            VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build());

    private static long window;
    private static int fbWidth = 1280;
    private static int fbHeight = 720;
    private static float deltaTime;
    private static float lastTime;
    private static double lastMouseX;
    private static double lastMouseY;
    private static boolean firstMouse = true;
    private static final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private static boolean framebufferResized;

    public static void main(String[] args) {
        initGlfw();
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);

        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 5.0f));
        initCallbacks(camera);
        glfwShowWindow(window);

        demoVertexPacking();

        AntiAliasingMode aaMode = AntiAliasingMode.FXAA;

        RenderSettings settings = RenderSettings.builder()
                .debugErrors(true)
                .build();
        RenderLoop renderLoop = new RenderLoop(settings);
        RenderDevice device = renderLoop.device();

        ShaderProgram colorShader = ShaderProgram.fromResource(LearnOpenGlDemo.class, SHADER_COLOR_VERT, SHADER_COLOR_FRAG);
        ShaderProgram texturedShader = ShaderProgram.fromResource(LearnOpenGlDemo.class, SHADER_TEXTURED_VERT, SHADER_TEXTURED_FRAG);
        ShaderProgram instancedShader = ShaderProgram.fromResource(LearnOpenGlDemo.class, SHADER_INSTANCED_VERT, SHADER_INSTANCED_FRAG);

        Texture2D wallTexture = Texture2D.fromResource(LearnOpenGlDemo.class, "/wall.png", false);
        Texture2D faceTexture = Texture2D.fromResource(LearnOpenGlDemo.class, "/awesomeface.png", true);

        Material colorMaterial = Material.builder(colorShader)
                .blendMode(BlendMode.OPAQUE)
                .build();

        Material texturedMaterial = Material.builder(texturedShader)
                .texture("uTexture", wallTexture)
                .setVec3("uTint", new Vector3f(1.0f, 1.0f, 1.0f))
                .blendMode(BlendMode.OPAQUE)
                .build();

        Material transparentMaterial = Material.builder(texturedShader)
                .texture("uTexture", faceTexture)
                .setVec3("uTint", new Vector3f(1.0f, 1.0f, 1.0f))
                .blendMode(BlendMode.ALPHA)
                .build();

        Mesh triangleMesh = Mesh.builder()
                .layout(POS_COLOR_LAYOUT)
                .attribute(0, new float[]{-0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f, 0.0f, 0.5f, 0.0f}, 3)
                .attribute(1, new float[]{1.0f, 0.3f, 0.2f, 0.2f, 1.0f, 0.3f, 0.2f, 0.3f, 1.0f}, 3)
                .build();

        Mesh coloredQuadMesh = Mesh.builder()
                .layout(POS_COLOR_LAYOUT)
                .attribute(0, new float[]{
                        -0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f, 0.5f, 0.5f, 0.0f,
                        -0.5f, -0.5f, 0.0f, 0.5f, 0.5f, 0.0f, -0.5f, 0.5f, 0.0f
                }, 3)
                .attribute(1, new float[]{
                        1.0f, 0.8f, 0.2f, 0.2f, 0.8f, 1.0f, 0.8f, 0.2f, 1.0f,
                        1.0f, 0.8f, 0.2f, 0.8f, 0.2f, 1.0f, 0.2f, 1.0f, 0.8f
                }, 3)
                .build();

        Mesh texturedQuadMesh = createTexturedQuadMesh();

        InstancedMeshBatch instancedBatch = InstancedMeshBatch.of(coloredQuadMesh, 32, 2);
        TripleBuffer<List<Matrix4f>> transformBuffers = new TripleBuffer<>(ArrayList::new);

        AtomicInteger frameIndex = new AtomicInteger(0);
        AtomicInteger instancedDrawn = new AtomicInteger(0);

        FxaaPostProcessor fxaaProcessor = null;
        TemporalAccumulationPass taaPass = null;
        final Framebuffer[] taaHistoryFbo = new Framebuffer[1];

        if (aaMode == AntiAliasingMode.FXAA) {
            fxaaProcessor = new FxaaPostProcessor();
        } else if (aaMode == AntiAliasingMode.TAA) {
            taaPass = new TemporalAccumulationPass();
            taaHistoryFbo[0] = Framebuffer.singleSampled(fbWidth, fbHeight);
        }

        RenderGraph graph = buildGraph(fbWidth, fbHeight, aaMode,
                colorShader, colorMaterial, texturedShader, texturedMaterial,
                transparentMaterial, triangleMesh, texturedQuadMesh, coloredQuadMesh,
                instancedShader, instancedBatch, transformBuffers,
                fxaaProcessor, taaPass, taaHistoryFbo,
                camera, frameIndex, instancedDrawn);

        GpuFence frameFence = null;

        while (!glfwWindowShouldClose(window)) {
            updateDeltaTime();
            processKeyboardInput(camera);

            if (framebufferResized) {
                graph.resize(fbWidth, fbHeight);
                if (taaHistoryFbo[0] != null) {
                    taaHistoryFbo[0].close();
                    taaHistoryFbo[0] = Framebuffer.singleSampled(fbWidth, fbHeight);
                }
                framebufferResized = false;
            }

            if (frameFence != null) {
                frameFence.close();
                frameFence = null;
            }

            renderLoop.beginFrame();

            List<Matrix4f> writeTransforms = transformBuffers.write();
            writeTransforms.clear();
            populateInstanceTransforms(writeTransforms, frameIndex.get());
            transformBuffers.flip();

            graph.execute(device);

            frameFence = GpuFence.insert();

            renderLoop.endFrame();

            if ((frameIndex.get() % 60) == 0) {
                double fps = renderLoop.statistics().averageFps();
                glfwSetWindowTitle(window,
                        String.format("LearnOpenGL | FPS %.1f | inst %d | %s | %s",
                                fps, instancedDrawn.get(),
                                instancedBatch.isPersistent() ? "pers" : "glSub",
                                aaMode.name()));
            }

            if (settings.debugErrors()) {
                GlDebug.checkError("LearnOpenGlDemo.frame");
            }

            glfwSwapBuffers(window);
            glfwPollEvents();
            frameIndex.incrementAndGet();
        }

        if (frameFence != null) {
            frameFence.close();
        }
        graph.close();
        if (fxaaProcessor != null) {
            fxaaProcessor.close();
        }
        if (taaPass != null) {
            taaPass.close();
        }
        if (taaHistoryFbo[0] != null) {
            taaHistoryFbo[0].close();
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
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static RenderGraph buildGraph(
            int w, int h, AntiAliasingMode aaMode,
            ShaderProgram colorShader, Material colorMaterial,
            ShaderProgram texturedShader, Material texturedMaterial,
            Material transparentMaterial,
            Mesh triangleMesh, Mesh texturedQuadMesh, Mesh coloredQuadMesh,
            ShaderProgram instancedShader,
            InstancedMeshBatch instancedBatch,
            TripleBuffer<List<Matrix4f>> transformBuffers,
            FxaaPostProcessor fxaaProcessor,
            TemporalAccumulationPass taaPass,
            Framebuffer[] taaHistoryFbo,
            Camera camera, AtomicInteger frameIndex, AtomicInteger instancedDrawn) {

        RenderGraph graph = new RenderGraph(w, h);

        boolean msaa = aaMode == AntiAliasingMode.MSAA;
        int samples = 4;
        String geoOutputName = msaa ? "sceneMS" : "sceneColor";

        if (msaa) {
            graph.addPass("GeometryPass")
                    .createColorMS(geoOutputName, GL_RGBA8, samples)
                    .createDepth()
                    .clearColor(0.08f, 0.10f, 0.14f, 1.0f)
                    .execute(geometryExecutor(colorShader, colorMaterial,
                            texturedShader, texturedMaterial, transparentMaterial,
                            triangleMesh, texturedQuadMesh, coloredQuadMesh,
                            instancedShader, instancedBatch, transformBuffers,
                            camera, frameIndex, instancedDrawn));

            graph.addPass("ResolvePass")
                    .createColor("sceneColor", GL_RGBA8)
                    .noClear()
                    .execute((resources, cmd) -> {
                        Framebuffer msFb = resources.getFramebuffer("GeometryPass");
                        Framebuffer dstFb = resources.getFramebuffer();
                        if (msFb != null && dstFb != null) {
                            cmd.custom(() -> msFb.blitColorTo(dstFb));
                        }
                    });
        } else {
            graph.addPass("GeometryPass")
                    .createColor(geoOutputName, GL_RGBA8)
                    .createDepth()
                    .clearColor(0.08f, 0.10f, 0.14f, 1.0f)
                    .execute(geometryExecutor(colorShader, colorMaterial,
                            texturedShader, texturedMaterial, transparentMaterial,
                            triangleMesh, texturedQuadMesh, coloredQuadMesh,
                            instancedShader, instancedBatch, transformBuffers,
                            camera, frameIndex, instancedDrawn));
        }

        switch (aaMode) {
            case NONE, MSAA -> {
                graph.addPass("PresentPass")
                        .writeToBackbuffer()
                        .noClear()
                        .execute((resources, cmd) -> {
                            Framebuffer geoFb = resources.getFramebuffer(
                                    msaa ? "ResolvePass" : "GeometryPass");
                            if (geoFb != null) {
                                cmd.custom(() -> geoFb.blitToDefault(geoFb.width(), geoFb.height()));
                            }
                        });
            }
            case FXAA -> {
                graph.addPass("FXAAPass")
                        .writeToBackbuffer()
                        .noClear()
                        .execute((resources, cmd) -> {
                            Framebuffer geoFb = resources.getFramebuffer("GeometryPass");
                            if (geoFb != null && fxaaProcessor != null) {
                                cmd.custom(() -> fxaaProcessor.render(geoFb, geoFb.width(), geoFb.height()));
                            }
                        });
            }
            case TAA -> {
                graph.addPass("TAAPass")
                        .writeToBackbuffer()
                        .noClear()
                        .execute((resources, cmd) -> {
                            Framebuffer geoFb = resources.getFramebuffer("GeometryPass");
                            Framebuffer histFb = taaHistoryFbo[0];
                            if (geoFb != null && taaPass != null && histFb != null) {
                                cmd.custom(() -> {
                                    glBindFramebuffer(GL_FRAMEBUFFER, geoFb.id());
                                    glClear(GL_COLOR_BUFFER_BIT);
                                    taaPass.render(geoFb.colorAttachment(),
                                            histFb.colorAttachment(), 0.90f,
                                            geoFb.width(), geoFb.height());
                                    geoFb.blitColorTo(histFb);
                                });
                            }
                        });

                graph.addPass("PresentPass")
                        .writeToBackbuffer()
                        .noClear()
                        .execute((resources, cmd) -> {
                            Framebuffer geoFb = resources.getFramebuffer("GeometryPass");
                            if (geoFb != null) {
                                cmd.custom(() -> geoFb.blitToDefault(geoFb.width(), geoFb.height()));
                            }
                        });
            }
        }

        return graph;
    }

    private static RenderGraph.PassExecutor geometryExecutor(
            ShaderProgram colorShader, Material colorMaterial,
            ShaderProgram texturedShader, Material texturedMaterial,
            Material transparentMaterial,
            Mesh triangleMesh, Mesh texturedQuadMesh, Mesh coloredQuadMesh,
            ShaderProgram instancedShader,
            InstancedMeshBatch instancedBatch,
            TripleBuffer<List<Matrix4f>> transformBuffers,
            Camera camera, AtomicInteger frameIndex, AtomicInteger instancedDrawn) {
        return (resources, cmd) -> {
            Matrix4f projection = projectionMatrix();
            Matrix4f view = camera.getViewMatrix();

            colorMaterial.bind(cmd);
            cmd.setUniformMat4(colorShader, "uProjection", projection)
                    .setUniformMat4(colorShader, "uView", view)
                    .setUniformMat4(colorShader, "uModel", new Matrix4f()
                            .translation(-1.5f, 0.5f, -1.5f))
                    .bindMesh(triangleMesh)
                    .drawMesh(triangleMesh);

            texturedMaterial.bind(cmd);
            cmd.setUniformMat4(texturedShader, "uProjection", projection)
                    .setUniformMat4(texturedShader, "uView", view)
                    .setUniformMat4(texturedShader, "uModel", new Matrix4f()
                            .translation(1.0f, 0.5f, -3.5f))
                    .bindMesh(texturedQuadMesh)
                    .drawMesh(texturedQuadMesh);
            colorMaterial.bind(cmd);
            cmd.setUniformMat4(colorShader, "uProjection", projection)
                    .setUniformMat4(colorShader, "uView", view)
                    .setUniformMat4(colorShader, "uModel", new Matrix4f()
                            .translation(-1.5f, -1.0f, -3.0f)
                            .scale(0.6f))
                    .bindMesh(coloredQuadMesh)
                    .drawMesh(coloredQuadMesh);

            cmd.bindShader(instancedShader);
            cmd.setUniformMat4(instancedShader, "uProjection", projection);
            cmd.setUniformMat4(instancedShader, "uView", view);
            cmd.custom(() -> {
                instancedBatch.beginFrame();
                instancedBatch.submitAll(transformBuffers.read());
                instancedDrawn.set(instancedBatch.flush());
            });

            transparentMaterial.bind(cmd);
            cmd.setUniformMat4(texturedShader, "uProjection", projection)
                    .setUniformMat4(texturedShader, "uView", view)
                    .setUniformMat4(texturedShader, "uModel", new Matrix4f()
                            .translation(1.5f, (float) Math.sin(frameIndex.get() * 0.04f) * 0.5f - 1.0f, -3.0f)
                            .scale(0.5f));
            cmd.bindMesh(texturedQuadMesh);
            cmd.drawMesh(texturedQuadMesh);
        };
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
            framebufferResized = true;
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
