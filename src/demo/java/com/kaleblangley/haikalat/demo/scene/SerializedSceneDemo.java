package com.kaleblangley.haikalat.demo.scene;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.AnimationGraphCompiler;
import com.kaleblangley.haikalat.subsystems.animation.AnimationGraphDocument;
import com.kaleblangley.haikalat.subsystems.animation.AnimationGraphParser;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneInstance;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceCatalog;
import com.kaleblangley.haikalat.subsystems.resources.ResourceSource;
import com.kaleblangley.haikalat.subsystems.scene.CharacterBuildPlan;
import com.kaleblangley.haikalat.subsystems.scene.SceneAssetService;
import com.kaleblangley.haikalat.subsystems.scene.SceneBuildPlan;
import com.kaleblangley.haikalat.subsystems.scene.SceneCharacterRuntimeDiagnostics;
import com.kaleblangley.haikalat.subsystems.scene.SerializedSceneDiagnostics;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.opengl.GL43.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43.GL_DONT_CARE;
import static org.lwjgl.opengl.GL43.glDebugMessageControl;

/** Content-driven glTF character proof using SceneAssetService and Graph sidecars. */
public final class SerializedSceneDemo {
    private static final AssetId DEFAULT_SCENE =
            AssetId.of("demo", "scenes/gltf/player_wild/serialized.scene.json");

    private SerializedSceneDemo() {}

    public static void main(String[] args) {
        Options options = Options.parse(args);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width, options.height)
                .title("Haikalat Serialized Scene")
                .visible(!options.hidden)
                .cursorMode(GlfwWindow.CursorMode.DISABLED)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            // NVIDIA may report a benign one-time shader variant recompile when
            // the graph-driven skinned instance is first submitted.
            glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
                    GL_DONT_CARE, 131218, false);
            window.setVsync(!options.hidden);
            run(window, options);
        }
    }

    private static void run(GlfwWindow window, Options options) {
        ResourceCatalog catalog = ResourceCatalog.builder()
                .mount("demo", ResourceSource.classpath("demo", SerializedSceneDemo.class))
                .build();
        RenderSettings settings = RenderSettings.builder()
                .vsync(!options.hidden)
                .antiAliasingMode(options.aa)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.MANUAL)
                .bloomSettings(BloomSettings.builder().enabled(false).build())
                .build();
        try (FrameDriver driver = new FrameDriver(settings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                     SerializedSceneDemo.class, "/environments/pbr/studio-small.hdr",
                     PbrEnvironmentSettings.quality("test"));
             GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
             SceneAssetService service = new SceneAssetService(catalog)) {
            SceneBuildPlan plan = service.loadPlan(options.scene).join();
            if (plan.characters().isEmpty()) {
                throw new IllegalStateException("serialized scene contains no character descriptor");
            }
            CharacterBuildPlan character = plan.characters().stream()
                    .filter(value -> options.character == null
                            || value.definition().id().equals(options.character))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("character not found: "
                            + options.character));
            SerializedSceneDiagnostics diagnostics = SerializedSceneDiagnostics.from(
                    plan, service.snapshot());
            SerializedSceneDiagnostics.CharacterDiagnostics characterDiagnostics =
                    diagnostics.characters().stream()
                            .filter(value -> value.id().equals(character.definition().id()))
                            .findFirst().orElseThrow();
            System.out.printf(Locale.ROOT,
                    "SerializedSceneDiagnostics: scene=%s generation=%d character=%s "
                            + "graphStates=%d parameters=%s dependencies=%d%n",
                    diagnostics.scene(), diagnostics.generation().value(), characterDiagnostics.id(),
                    characterDiagnostics.graphStateCount(), characterDiagnostics.parameterNames(),
                    diagnostics.assetSnapshot().dependencyEdgeCount());
            LoadedGltfScene loaded = plan.gltfAssets().entrySet().stream()
                    .filter(entry -> entry.getKey().asset().equals(character.model()))
                    .map(Map.Entry::getValue).findFirst()
                    .orElseThrow(() -> new IllegalStateException("character model was not decoded"));
            try (GltfSceneAsset gpu = GltfSceneAsset.upload(loaded, library);
                 GltfSceneInstance instance = gpu.instantiateAnimated(
                         new Matrix4f().translation(0.0f, -1.0f, 0.0f).scale(2.0f), false)) {
                AnimationGraphDocument graphDocument = AnimationGraphParser.parse(
                        character.animationGraph(), read(catalog, character.animationGraph()));
                if (options.state != null) graphDocument = graphDocument.withEntry(options.state);
                Map<String, AnimationClip> clips = new HashMap<>();
                for (int i = 0; i < instance.animationCount(); i++) {
                    clips.put(instance.animationNames().get(i), instance.animationClip(i));
                }
                instance.attachAnimationGraph(AnimationGraphCompiler.compile(character.definition().id(),
                        graphDocument, instance.animationSkeleton(), clips));
                applyParameters(instance, character);

                Camera camera = new Camera(new Vector3f(0.0f, 0.8f, 8.0f));
                Scene scene = new Scene(camera);
                instance.objects().forEach(scene::add);
                scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.35f, -1.0f, -0.55f),
                        new Vector3f(1.0f, 0.94f, 0.84f), 3.0f));
                RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
                try {
                    pipeline.build();
                    render(window, driver, pipeline, instance, camera, scene, settings, options);
                    SceneCharacterRuntimeDiagnostics runtimeDiagnostics =
                            SceneCharacterRuntimeDiagnostics.capture(character.definition().id(),
                                    plan.generation(), instance);
                    System.out.printf(Locale.ROOT,
                            "CharacterRuntimeDiagnostics: character=%s generation=%d state=%s "
                                    + "time=%.3f normalized=%.3f target=%s weight=%.3f "
                                    + "reason=%s windows=%s%n",
                            runtimeDiagnostics.character(), runtimeDiagnostics.generation().value(),
                            runtimeDiagnostics.state(), runtimeDiagnostics.timeSeconds(),
                            runtimeDiagnostics.normalizedTime(), runtimeDiagnostics.transitionTarget(),
                            runtimeDiagnostics.transitionWeight(), runtimeDiagnostics.transitionReason(),
                            runtimeDiagnostics.activeWindows());
                } finally {
                    pipeline.close();
                }
            }
        }
    }

    private static byte[] read(ResourceCatalog catalog, AssetId asset) {
        try {
            return catalog.readBytes(asset, 1024 * 1024);
        } catch (IOException failure) {
            throw new IllegalArgumentException("could not read " + asset, failure);
        }
    }

    private static void applyParameters(GltfSceneInstance instance, CharacterBuildPlan character) {
        character.definition().parameters().forEach((name, value) -> {
            switch (value.type()) {
                case BOOLEAN -> instance.animationController().setBoolean(name, value.booleanValue());
                case FLOAT -> instance.animationController().setFloat(name, value.floatValue());
                case INTEGER -> instance.animationController().setInteger(name, value.intValue());
            }
        });
    }

    private static void render(GlfwWindow window, FrameDriver driver, RenderPipeline pipeline,
                              GltfSceneInstance instance, Camera camera, Scene scene,
                              RenderSettings settings, Options options) {
        FrameClock clock = new FrameClock();
        int frame = 0;
        while (!window.shouldClose()) {
            float delta = options.deterministic ? 1.0f / 60.0f : clock.tick().deltaSeconds();
            if (frame == 3) instance.animationController().fireTrigger("attack");
            instance.animationController().setFloat("speed", frame < 3 ? 0.0f : 0.8f);
            instance.update(delta * 0.65f);
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());
            driver.beginFrame();
            pipeline.execute(driver.device(), delta);
            driver.recordGraph(pipeline.graph());
            driver.endFrame();
            driver.present(window::swapBuffers);
            window.pollEvents();
            frame++;
            if (options.frames > 0 && frame >= options.frames) window.requestClose();
        }
        System.out.printf(Locale.ROOT,
                "SerializedSceneDemo: scene=%s frames=%d characters=%d state=%s time=%.3f "
                        + "visible=%d size=%dx%d%n", options.scene, frame, scene.renderers().size(),
                instance.currentAnimationState(), instance.animationTimeSeconds(),
                pipeline.lastVisibilityStatistics().available()
                        ? pipeline.lastVisibilityStatistics().forwardVisible() : -1,
                window.width(), window.height());
    }

    private static final class Options {
        private final AssetId scene;
        private final int frames;
        private final int width;
        private final int height;
        private final boolean hidden;
        private final boolean deterministic;
        private final AntiAliasingMode aa;
        private final String character;
        private final String state;

        private Options(AssetId scene, int frames, int width, int height, boolean hidden,
                        boolean deterministic, AntiAliasingMode aa, String character,
                        String state) {
            this.scene = scene;
            this.frames = frames;
            this.width = width;
            this.height = height;
            this.hidden = hidden;
            this.deterministic = deterministic;
            this.aa = aa;
            this.character = character;
            this.state = state;
        }

        private static Options parse(String[] args) {
            AssetId scene = DEFAULT_SCENE;
            int frames = -1, width = 1280, height = 720;
            boolean hidden = false, deterministic = false;
            AntiAliasingMode aa = AntiAliasingMode.NONE;
            String character = null, state = null;
            for (String arg : args) {
                if (arg.equals("--hidden")) hidden = true;
                else if (arg.equals("--deterministic")) deterministic = true;
                else if (arg.startsWith("--scene=")) scene = AssetId.parse(arg.substring(8));
                else if (arg.startsWith("--character=")) character = arg.substring(12);
                else if (arg.startsWith("--state=")) state = arg.substring(8);
                else if (arg.startsWith("--frames=")) frames = Integer.parseInt(arg.substring(9));
                else if (arg.startsWith("--size=")) {
                    String[] size = arg.substring(7).toLowerCase(Locale.ROOT).split("x", -1);
                    if (size.length != 2) throw new IllegalArgumentException("--size must be WxH");
                    width = Integer.parseInt(size[0]); height = Integer.parseInt(size[1]);
                } else if (arg.startsWith("--aa=")) aa = AntiAliasingMode.valueOf(
                        arg.substring(5).toUpperCase(Locale.ROOT));
                else throw new IllegalArgumentException("unknown option: " + arg);
            }
            if (frames == 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("frames must be non-zero and size positive");
            }
            return new Options(scene, frames, width, height, hidden, deterministic, aa,
                    character, state);
        }
    }
}
