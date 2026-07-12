package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.SceneAssetConfig;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.VertexPacking;
import com.kaleblangley.haikalat.runtime.DebugOverlaySnapshot;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.PeriodicTimer;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.InstancedRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.util.Map;
import java.time.Duration;

public final class LearnOpenGlDemo {
    private LearnOpenGlDemo() {
    }

    public static void main(String[] args) {
        DemoOptions options = DemoOptions.parse(args);
        demoVertexPacking();

        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(options.antiAliasingMode())
                .vsync(!options.deterministic())
                .build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(DemoSupport.DEFAULT_WIDTH, DemoSupport.DEFAULT_HEIGHT)
                .title("LearnOpenGL Demo")
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(settings.vsync());
            if (!options.deterministic()) {
                window.show();
            }
            run(window, settings, options);
        }
    }

    private static void run(GlfwWindow window, RenderSettings settings, DemoOptions options) {
        Camera camera = new Camera(new Vector3f(0, 0, 5));
        ResourceLocator assets = ResourceLocator.classpath(LearnOpenGlDemo.class);
        SceneAssetConfig config = SceneAssetConfig.load(assets, "/demo/learnopengl.properties");
        try (FrameDriver renderLoop = new FrameDriver(settings);
             DemoSceneResources resources = DemoSceneResources.load(assets, config)) {
            Scene scene = buildScene(camera, config, resources);
            Mesh instancedMesh = resources.meshes("builtin:" + BuiltinMeshData.QUAD).getFirst();
            ShaderProgram instancedShader = resources.shader("instanced");
            InstancedMeshBatch batch = InstancedMeshBatch.of(instancedMesh,
                    DemoGrid.COUNT, BuiltinMeshData.INSTANCE_ATTRIBUTE_BASE);
            InstancedRenderer instanced = new InstancedRenderer(batch, instancedShader);
            addInstances(instanced);

            RenderPipeline pipeline = new RenderPipeline(window, scene, instanced, settings);
            try {
                pipeline.build();
                renderFrames(window, camera, renderLoop, pipeline, instanced, settings, options);
            } finally {
                pipeline.close();
                instanced.close();
            }
        }
    }

    private static void renderFrames(GlfwWindow window, Camera camera, FrameDriver renderLoop,
                                     RenderPipeline pipeline, InstancedRenderer instanced,
                                     RenderSettings settings, DemoOptions options) {
        int frame = 0;
        FrameClock clock = new FrameClock();
        PeriodicTimer titleUpdate = new PeriodicTimer(Duration.ofMillis(250));
        while (!window.shouldClose()) {
            if (options.resize() != null && frame == options.resize().frame()) {
                window.resize(options.resize().width(), options.resize().height());
            }

            FrameClock.Tick time = clock.tick();
            DemoSupport.updateFreeCamera(window, camera, time.deltaSeconds());
            if (window.consumeResize()) {
                pipeline.resize(window.width(), window.height());
            }

            instanced.beginFrame(frame);
            renderLoop.frame(pipeline.graph());
            if (options.resize() != null && frame > options.resize().frame()) {
                options.resize().verify(window, pipeline);
            }

            if (titleUpdate.poll()) {
                DebugOverlaySnapshot overlay = DebugOverlaySnapshot.from(
                        renderLoop.statistics(), instanced.statistics().drawCalls(),
                        instanced.drawnCount(), settings.antiAliasingMode());
                window.setTitle(String.format("LearnOpenGL | FPS %.1f | CPU %.3f ms | GPU %.2f ms | draw %d | inst %d | AA %s",
                        overlay.fps(), overlay.cpuSubmitMillis(), overlay.gpuMillis(),
                        overlay.drawCalls(), overlay.instanceCount(), overlay.activeAntiAliasingMode()));
            }

            GlDebug.checkError("LearnOpenGlDemo.frame");
            frame++;
            if (options.maxFrames() > 0 && frame >= options.maxFrames()) {
                window.requestClose();
            }
            renderLoop.present(window::swapBuffers);
            window.pollEvents();
        }
    }

    private static Scene buildScene(Camera camera, SceneAssetConfig config, DemoSceneResources resources) {
        Scene scene = new Scene(camera);
        for (Map.Entry<String, SceneAssetConfig.ObjectDef> entry : config.objects().entrySet()) {
            SceneAssetConfig.ObjectDef def = entry.getValue();
            Material material = resources.material(def.material());
            addModelMeshes(scene, resources.meshes(def.model()), material,
                    updaterFor(entry.getKey(), def), def.castShadows());
        }
        for (SceneAssetConfig.LightDef light : config.lights().values()) {
            scene.addLight(lightFor(light));
        }
        return scene;
    }

    static int addModelMeshes(Scene scene, java.util.List<Mesh> meshes, Material material,
                              SceneObject.ModelUpdater updater, boolean castShadows) {
        for (Mesh mesh : meshes) {
            scene.add(new SceneObject(mesh, material, updater, castShadows));
        }
        return meshes.size();
    }

    private static void addInstances(InstancedRenderer instanced) {
        for (int r = 0; r < DemoGrid.SIDE; r++) {
            for (int c = 0; c < DemoGrid.SIDE; c++) {
                final int row = r, col = c;
                instanced.addInstance(frame -> DemoGrid.transform(row, col, frame));
            }
        }
    }

    private static SceneObject.ModelUpdater updaterFor(String objectName, SceneAssetConfig.ObjectDef def) {
        return switch (objectName) {
            case "triangle" -> (matrix, frame) -> baseTransform(matrix, def).rotateZ(frame * 0.03f);
            case "wall" -> (matrix, frame) -> baseTransform(matrix, def).rotateZ(-frame * 0.02f);
            case "face" -> (matrix, frame) -> baseTransform(matrix, def)
                    .translate(0.0f, (float) Math.sin(frame * 0.04f) * 0.5f, 0.0f);
            default -> (matrix, frame) -> baseTransform(matrix, def);
        };
    }

    private static Matrix4f baseTransform(Matrix4f out, SceneAssetConfig.ObjectDef def) {
        Vector3f position = def.position();
        Vector3f rotation = def.rotationRadians();
        return out.identity()
                .translation(position)
                .rotateXYZ(rotation.x, rotation.y, rotation.z)
                .scale(def.scale());
    }

    private static SceneLight lightFor(SceneAssetConfig.LightDef def) {
        return switch (def.type().toLowerCase()) {
            case "directional" -> def.castShadows()
                    ? SceneLight.shadowedDirectional(def.positionOrDirection(), def.color(), def.intensity())
                    : SceneLight.directional(def.positionOrDirection(), def.color(), def.intensity());
            case "point" -> SceneLight.point(
                    def.positionOrDirection(), def.color(), def.intensity(), def.range());
            default -> throw new IllegalStateException("Unsupported demo light type: " + def.type());
        };
    }

    private static void demoVertexPacking() {
        int packed = VertexPacking.packOctNormal(0.5f, 0.5f, 0.7071f);
        float[] unpacked = VertexPacking.unpackOctNormal(packed);
        System.out.printf("[VertexPacking] packed=0x%08X unpacked=(%.3f,%.3f,%.3f)%n",
                packed, unpacked[0], unpacked[1], unpacked[2]);
    }

    private record DemoOptions(boolean deterministic, int maxFrames,
                               AntiAliasingMode antiAliasingMode, ResizeSpec resize) {
        static DemoOptions parse(String[] args) {
            boolean deterministic = false;
            int maxFrames = -1;
            AntiAliasingMode mode = AntiAliasingMode.FXAA;
            ResizeSpec resize = null;
            for (String arg : args) {
                if ("--deterministic".equals(arg)) {
                    deterministic = true;
                } else if (arg.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(arg.substring("--frames=".length()));
                    if (maxFrames <= 0) {
                        throw new IllegalArgumentException("--frames must be positive");
                    }
                    deterministic = true;
                } else if (arg.startsWith("--aa=")) {
                    mode = AntiAliasingMode.valueOf(arg.substring("--aa=".length()).toUpperCase());
                } else if (arg.startsWith("--resize=")) {
                    resize = ResizeSpec.parse(arg.substring("--resize=".length()));
                    deterministic = true;
                } else {
                    throw new IllegalArgumentException("Unknown demo argument: " + arg);
                }
            }
            if (deterministic && maxFrames < 0) {
                maxFrames = 8;
            }
            if (resize != null && maxFrames <= resize.frame() + 1) {
                throw new IllegalArgumentException("--frames must include one frame after --resize");
            }
            return new DemoOptions(deterministic, maxFrames, mode, resize);
        }
    }

    private record ResizeSpec(int frame, int width, int height) {
        static ResizeSpec parse(String value) {
            try {
                String[] frameAndSize = value.split(":", -1);
                String[] dimensions = frameAndSize[1].toLowerCase().split("x", -1);
                if (frameAndSize.length != 2 || dimensions.length != 2) {
                    throw new IllegalArgumentException();
                }
                int frame = Integer.parseInt(frameAndSize[0]);
                int width = Integer.parseInt(dimensions[0]);
                int height = Integer.parseInt(dimensions[1]);
                if (frame < 0 || width <= 0 || height <= 0) {
                    throw new IllegalArgumentException();
                }
                return new ResizeSpec(frame, width, height);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "--resize must use <frame>:<positive-width>x<positive-height>", invalid);
            }
        }

        void verify(GlfwWindow window, RenderPipeline pipeline) {
            if (window.width() != width || window.height() != height
                    || pipeline.graph().width() != width || pipeline.graph().height() != height) {
                throw new IllegalStateException("Deterministic resize did not reach " + width + "x" + height);
            }
        }
    }
}
