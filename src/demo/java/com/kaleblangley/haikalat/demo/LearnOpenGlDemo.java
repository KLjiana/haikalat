package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.MaterialDef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.SceneAssetConfig;
import com.kaleblangley.haikalat.core.assets.ShaderAsset;
import com.kaleblangley.haikalat.core.assets.TextureAssetCache;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.VertexPacking;
import com.kaleblangley.haikalat.runtime.DebugOverlaySnapshot;
import com.kaleblangley.haikalat.runtime.FrameDriver;
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
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LearnOpenGlDemo {
    public static void main(String[] args) {
        DemoOptions options = DemoOptions.parse(args);
        demoVertexPacking();

        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(options.antiAliasingMode())
                .vsync(!options.deterministic())
                .build();
        GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(1280, 720)
                .title("LearnOpenGL Demo")
                .build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        window.setVsync(settings.vsync());
        if (!options.deterministic()) {
            window.show();
        }
        Camera camera = new Camera(new Vector3f(0, 0, 5));

        FrameDriver renderLoop = new FrameDriver(settings);
        ResourceLocator assets = ResourceLocator.classpath(LearnOpenGlDemo.class);
        SceneAssetConfig sceneConfig = SceneAssetConfig.load(assets, "/demo/learnopengl.properties");
        TextureAssetCache textureCache = new TextureAssetCache(ref -> loadTexture(ref, sceneConfig));

        ShaderProgram colorShader = loadShader("color", sceneConfig);
        ShaderProgram texturedShader = loadShader("textured", sceneConfig);
        ShaderProgram instancedShader = loadShader("instanced", sceneConfig);
        Map<String, ShaderProgram> shaders = Map.of(
                "color", colorShader,
                "textured", texturedShader,
                "instanced", instancedShader);

        Map<String, Material> materials = buildMaterials(sceneConfig, shaders, textureCache);
        Map<String, Mesh> meshes = buildBuiltinMeshes(sceneConfig);
        Scene scene = buildScene(camera, sceneConfig, meshes, materials);
        Mesh instancedMesh = meshes.get(BuiltinMeshData.QUAD);
        if (instancedMesh == null) {
            throw new IllegalStateException("Builtin instanced mesh is not configured: " + BuiltinMeshData.QUAD);
        }
        InstancedMeshBatch instBatch = InstancedMeshBatch.of(instancedMesh, 16, 3);
        InstancedRenderer instanced = new InstancedRenderer(instBatch, instancedShader);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                final int row = r, col = c;
                instanced.addInstance(f -> new Matrix4f()
                        .translation(-1.4f + col * 0.7f, -1.4f + row * 0.7f, 0)
                        .rotateZ(f * 0.04f + (row + col) * 0.3f)
                        .scale(0.3f));
            }
        }

        RenderPipeline pipeline = new RenderPipeline(window, scene, instanced, settings);
        try {
            pipeline.build();

            int frame = 0;
            float lastTime = (float) GLFW.glfwGetTime();
            while (!window.shouldClose()) {
                float now = (float) GLFW.glfwGetTime();
                float deltaTime = Math.min(now - lastTime, 0.1f);
                lastTime = now;
                processInput(window, camera, deltaTime);
                if (window.consumeResize()) pipeline.resize(window.width(), window.height());

                instanced.beginFrame(frame);
                renderLoop.frame(pipeline.graph());

                if ((frame % 60) == 0) {
                    DebugOverlaySnapshot overlay = DebugOverlaySnapshot.from(
                            renderLoop.statistics(),
                            instanced.statistics().drawCalls(),
                            instanced.drawnCount(),
                            settings.antiAliasingMode());
                    window.setTitle(String.format("LearnOpenGL | FPS %.1f | draw %d | inst %d | GPU %.2f ms | AA %s",
                            overlay.fps(), overlay.drawCalls(), overlay.instanceCount(),
                            overlay.gpuMillis(), overlay.activeAntiAliasingMode()));
                }

                GlDebug.checkError("LearnOpenGlDemo.frame");
                frame++;
                if (options.maxFrames() > 0 && frame >= options.maxFrames()) {
                    window.requestClose();
                }
                window.swapBuffers();
                window.pollEvents();
            }
        } finally {
            renderLoop.close();
            pipeline.close();
            instanced.close();
            meshes.values().forEach(Mesh::close);
            textureCache.close();
            instancedShader.close();
            texturedShader.close();
            colorShader.close();
            window.close();
        }
    }

    private static void processInput(GlfwWindow window, Camera camera, float deltaTime) {
        if (window.isKeyDown(GLFW.GLFW_KEY_ESCAPE)) window.requestClose();
        camera.processMouseMovement(
                (float) window.mouseDeltaX() * 0.1f,
                (float) window.mouseDeltaY() * 0.1f);
        float speed = 2.5f * deltaTime;
        if (window.isKeyDown(GLFW.GLFW_KEY_W)) camera.processKeyboard(Camera.Movement.FORWARD, speed);
        if (window.isKeyDown(GLFW.GLFW_KEY_S)) camera.processKeyboard(Camera.Movement.BACKWARD, speed);
        if (window.isKeyDown(GLFW.GLFW_KEY_A)) camera.processKeyboard(Camera.Movement.LEFT, speed);
        if (window.isKeyDown(GLFW.GLFW_KEY_D)) camera.processKeyboard(Camera.Movement.RIGHT, speed);
    }

    private static Map<String, Material> buildMaterials(SceneAssetConfig config, Map<String, ShaderProgram> shaders,
                                                        TextureAssetCache textureCache) {
        Map<String, Material> materials = new LinkedHashMap<>();
        for (String name : config.materials().keySet()) {
            materials.put(name, buildMaterial(name, config, shaders, textureCache));
        }
        return materials;
    }

    private static Map<String, Mesh> buildBuiltinMeshes(SceneAssetConfig config) {
        Map<String, Mesh> meshes = new LinkedHashMap<>();
        for (SceneAssetConfig.ObjectDef object : config.objects().values()) {
            if (object.builtinMesh()) {
                meshes.computeIfAbsent(object.builtinMeshName(), name -> Mesh.from(BuiltinMeshData.named(name)));
            }
        }
        return meshes;
    }

    private static Scene buildScene(Camera camera, SceneAssetConfig config, Map<String, Mesh> meshes,
                                    Map<String, Material> materials) {
        Scene scene = new Scene(camera);
        for (Map.Entry<String, SceneAssetConfig.ObjectDef> entry : config.objects().entrySet()) {
            SceneAssetConfig.ObjectDef def = entry.getValue();
            Mesh mesh = meshFor(def, meshes);
            Material material = materials.get(def.material());
            if (material == null) {
                throw new IllegalStateException("Material not configured for object " + entry.getKey() + ": " + def.material());
            }
            scene.add(new SceneObject(mesh, material, updaterFor(entry.getKey(), def), def.castShadows()));
        }
        for (SceneAssetConfig.LightDef light : config.lights().values()) {
            scene.addLight(lightFor(light));
        }
        return scene;
    }

    private static Mesh meshFor(SceneAssetConfig.ObjectDef def, Map<String, Mesh> meshes) {
        if (!def.builtinMesh()) {
            throw new IllegalStateException("Only builtin demo meshes are wired in LearnOpenGlDemo: " + def.model());
        }
        Mesh mesh = meshes.get(def.builtinMeshName());
        if (mesh == null) {
            throw new IllegalStateException("Builtin mesh not loaded: " + def.model());
        }
        return mesh;
    }

    private static SceneObject.ModelUpdater updaterFor(String objectName, SceneAssetConfig.ObjectDef def) {
        return switch (objectName) {
            case "triangle" -> (m, f) -> baseTransform(m, def).rotateZ(f * 0.03f);
            case "wall" -> (m, f) -> baseTransform(m, def).rotateZ(-f * 0.02f);
            case "face" -> (m, f) -> baseTransform(m, def)
                    .translate(0.0f, (float) Math.sin(f * 0.04f) * 0.5f, 0.0f);
            default -> (m, f) -> baseTransform(m, def);
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
        String type = def.type().toLowerCase();
        return switch (type) {
            case "directional" -> def.castShadows()
                    ? SceneLight.shadowedDirectional(def.positionOrDirection(), def.color(), def.intensity())
                    : SceneLight.directional(def.positionOrDirection(), def.color(), def.intensity());
            case "point" -> SceneLight.point(def.positionOrDirection(), def.color(), def.intensity(), def.range());
            default -> throw new IllegalStateException("Unsupported demo light type: " + def.type());
        };
    }

    private static ShaderProgram loadShader(String name, SceneAssetConfig config) {
        ShaderAsset asset = config.shaders().get(name);
        if (asset == null) {
            throw new IllegalStateException("Shader not configured: " + name);
        }
        return ShaderProgram.fromResource(LearnOpenGlDemo.class,
                asset.vertexShader().path(), asset.fragmentShader().path());
    }

    private static Texture2D loadTexture(AssetRef ref, SceneAssetConfig config) {
        boolean flip = config.textures().values().stream()
                .filter(texture -> texture.path().equals(ref))
                .findFirst()
                .map(SceneAssetConfig.TextureDef::flipVertically)
                .orElse(true);
        return Texture2D.fromResource(LearnOpenGlDemo.class, ref.path(), flip);
    }

    private static Material buildMaterial(String name, SceneAssetConfig config, Map<String, ShaderProgram> shaders,
                                          TextureAssetCache textureCache) {
        MaterialDef def = config.materials().get(name);
        if (def == null) {
            throw new IllegalStateException("Material not configured: " + name);
        }
        ShaderProgram shader = shaders.get(def.shader());
        if (shader == null) {
            throw new IllegalStateException("Shader not loaded for material " + name + ": " + def.shader());
        }
        Material.Builder builder = Material.builder(shader)
                .blendMode(def.blendMode())
                .depthTest(def.depthTest());
        for (MaterialDef.TextureBinding binding : def.textures()) {
            if (binding.sampler() != null) {
                throw new IllegalStateException("Sampler definitions are not wired yet: " + binding.sampler());
            }
            SceneAssetConfig.TextureDef texture = config.textures().get(binding.texture());
            if (texture == null) {
                throw new IllegalStateException("Texture not configured for material " + name + ": " + binding.texture());
            }
            builder.texture(binding.unit(), binding.samplerName(), textureCache.get(texture.path().path()));
        }
        if (!def.textures().isEmpty()) {
            builder.setVec3("uTint", new Vector3f(1, 1, 1));
        }
        return builder.build();
    }

    private static void demoVertexPacking() {
        float[] u = VertexPacking.unpackOctNormal(
                VertexPacking.packOctNormal(0.5f, 0.5f, 0.7071f));
        System.out.printf("[VertexPacking] packed=0x%08X unpacked=(%.3f,%.3f,%.3f)%n",
                VertexPacking.packOctNormal(0.5f, 0.5f, 0.7071f), u[0], u[1], u[2]);
    }

    private record DemoOptions(boolean deterministic, int maxFrames, AntiAliasingMode antiAliasingMode) {
        static DemoOptions parse(String[] args) {
            boolean deterministic = false;
            int maxFrames = -1;
            AntiAliasingMode mode = AntiAliasingMode.FXAA;
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
                } else {
                    throw new IllegalArgumentException("Unknown demo argument: " + arg);
                }
            }
            if (deterministic && maxFrames < 0) {
                maxFrames = 8;
            }
            return new DemoOptions(deterministic, maxFrames, mode);
        }
    }
}
