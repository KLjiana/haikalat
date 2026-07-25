package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.AnimationEvent;
import com.kaleblangley.haikalat.subsystems.animation.AnimationMixer;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.animation.BoneMask;
import com.kaleblangley.haikalat.subsystems.animation.JointTransform;
import com.kaleblangley.haikalat.subsystems.animation.PoseBuffer;
import com.kaleblangley.haikalat.subsystems.animation.RootMotionDelta;
import com.kaleblangley.haikalat.subsystems.animation.Skeleton;
import com.kaleblangley.haikalat.subsystems.animation.TwoBoneIkSolver;
import com.kaleblangley.haikalat.subsystems.postprocess.ColorGradingLut;
import com.kaleblangley.haikalat.subsystems.postprocess.ColorGradingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.VolumetricLightPass;
import com.kaleblangley.haikalat.subsystems.postprocess.VolumetricLightSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleExperiment;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiFrameStats;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiAnimationDiagnostics;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiEasing;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiTweenSpec;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.vfx.Decal;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.RibbonEmitter;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.kaleblangley.haikalat.subsystems.animation.AnimationClip.Interpolation.LINEAR;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/** 动画、PBR、后处理、CPU/GPU VFX 与 retained UI 的同帧综合验收场景。 */
public final class HaikalatShowcaseDemo {
    private static final float FIXED_DELTA_SECONDS = 1.0f / 60.0f;

    private HaikalatShowcaseDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat Showcase")
                .visible(!options.hidden())
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(!options.hidden());
            Summary summary = run(window, options);
            if (!GlDebug.resources().liveResources().isEmpty()) {
                throw new IllegalStateException("showcase leaked GL resources: "
                        + GlDebug.resources().liveResources());
            }
            System.out.println(summary.format());
        }
    }

    private static Summary run(GlfwWindow window, Options options) {
        RenderSettings renderSettings = RenderSettings.builder()
                .vsync(!options.hidden())
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.MANUAL)
                .bloomSettings(BloomSettings.builder().enabled(true).maxLevels(3).build())
                .build();
        Skeleton skeleton = skeleton();
        PoseBuffer pose = skeleton.createPoseBuffer();
        AnimationMixer mixer = mixer(skeleton);
        Vector3f cameraPosition = new Vector3f(0.0f, 0.25f, 8.0f);
        Camera camera = new Camera(cameraPosition);
        EffectSnapshot[] effectSnapshot = new EffectSnapshot[1];
        Matrix4f projection = new Matrix4f();
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f viewProjection = new Matrix4f();
        Matrix4f inverseViewProjection = new Matrix4f();
        float[] visualRootMotion = {0.0f};

        try (FrameDriver driver = new FrameDriver(renderSettings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                     HaikalatShowcaseDemo.class, "/pbr/studio-small.hdr",
                     PbrEnvironmentSettings.quality("test"));
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             ShaderProgram pbrShader = ShaderProgram.fromResource(HaikalatShowcaseDemo.class,
                     "/render3d/pbr/pbr_forward.vert", "/render3d/pbr/pbr_forward.frag");
             Mesh sphere = Mesh.from(PbrSphereMesh.create(24, 16));
             Material material = PbrMaterials.create(pbrShader,
                     new PbrMaterialProperties(new Vector4f(0.24f, 0.62f, 0.92f, 1.0f),
                             0.58f, 0.24f, 1.0f, 1.0f,
                             new Vector3f(0.025f, 0.05f, 0.09f), Map.of()),
                     Map.of(), fallbacks);
             VfxRenderer cpuVfxRenderer = new VfxRenderer();
             EffectAsset effectAsset = effectAsset();
             EffectInstance effect = effectAsset.instantiate(0x4841494b414c4154L);
             GpuParticleExperiment gpuParticles = new GpuParticleExperiment(options.gpuParticles());
             VolumetricLightPass volumetric = new VolumetricLightPass()) {
            Scene scene = scene(camera, sphere, material, pose, visualRootMotion);
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    renderSettings, environment).postProcessSettings(postProcessSettings());
            try {
                pipeline.build();
                try (UiSystem ui = UiSystem.create(window, UiConfig.defaults())) {
                    ShowcaseUi showcaseUi = installUi(ui);
                    VolumetricLightSettings volumetricSettings = volumetricSettings();
                    ui.attachTo(pipeline.graph(), pipeline.finalPassName(), (resources, commands) -> {
                        commands.bindDefaultFramebuffer().viewport(0, 0, window.width(), window.height());
                        volumetric.recordIntoCurrentTarget(commands, inverseViewProjection,
                                cameraPosition, volumetricSettings);
                        EffectSnapshot snapshot = effectSnapshot[0];
                        if (snapshot != null) {
                            cpuVfxRenderer.record(commands, snapshot, projection, view);
                        }
                        gpuParticles.record(commands, FIXED_DELTA_SECONDS, viewProjection,
                                window.width() / (float) window.height());
                    });
                    return renderFrames(window, options, driver, pipeline, ui, showcaseUi,
                            mixer, pose, effect, effectSnapshot, cameraPosition,
                            projection, view, viewProjection, inverseViewProjection,
                            visualRootMotion, cpuVfxRenderer, gpuParticles, volumetric);
                }
            } finally {
                pipeline.close();
            }
        }
    }

    private static Summary renderFrames(GlfwWindow window, Options options, FrameDriver driver,
                                        RenderPipeline pipeline, UiSystem ui, ShowcaseUi showcaseUi,
                                        AnimationMixer mixer, PoseBuffer pose, EffectInstance effect,
                                        EffectSnapshot[] effectSnapshot, Vector3f cameraPosition,
                                        Matrix4f projection, Matrix4f view, Matrix4f viewProjection,
                                        Matrix4f inverseViewProjection, float[] visualRootMotion,
                                        VfxRenderer cpuVfxRenderer,
                                        GpuParticleExperiment gpuParticles,
                                        VolumetricLightPass volumetric) {
        FrameClock clock = new FrameClock();
        long animationEvents = 0L;
        double rootMotionDistance = 0.0;
        long updateNanos = 0L;
        long gpuNanos = 0L;
        int gpuSamples = 0;
        int nonBlackPixels = -1;
        GlDebug.ResourceSnapshot stableResources = null;
        int frame = 0;

        while (!window.shouldClose()) {
            if (window.isKeyDown(GLFW_KEY_ESCAPE)) window.requestClose();
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());
            float delta = options.hidden() ? FIXED_DELTA_SECONDS : clock.tick().deltaSeconds();
            long updateStart = System.nanoTime();
            RootMotionDelta rootMotion = mixer.updateWithRootMotion(delta, pose, 0, true);
            rootMotionDistance += rootMotion.translation().length();
            visualRootMotion[0] = wrap(visualRootMotion[0] + rootMotion.translation().x(), 3.2f);
            float phase = frame * FIXED_DELTA_SECONDS;
            TwoBoneIkSolver.solve(pose, 0, 1, 2,
                    new Vector3f(0.55f + 0.35f * (float) Math.sin(phase * 1.7f),
                            0.75f + 0.22f * (float) Math.cos(phase * 1.3f), 0.0f),
                    new Vector3f(0.0f, 0.0f, 1.0f), 0.72f);
            Vector3f emitter = jointPosition(pose, 2).add(visualRootMotion[0], 0.0f, 0.0f);
            effect.update(delta, emitter);
            List<AnimationEvent> events = mixer.drainEvents();
            animationEvents += events.size();
            for (AnimationEvent ignored : events) {
                effect.spawnDecal(new Vector3f(emitter.x, -1.0f, -0.1f),
                        new Vector3f(0.0f, 0.0f, 1.0f), new Vector2f(0.72f, 0.24f),
                        phase * 0.3f);
            }
            effectSnapshot[0] = effect.snapshot(cameraPosition);
            DemoSupport.perspective(projection, window.width(), window.height());
            projection.mul(view, viewProjection);
            inverseViewProjection.set(viewProjection).invert();
            if (frame == 1) {
                ui.animations().tweenOpacity(showcaseUi.panel(), 0.62f,
                        new UiTweenSpec(0.8f, UiEasing.EASE_OUT_CUBIC));
            } else if (frame > 1 && frame % 120 == 0) {
                float target = ((frame / 120) & 1) == 0 ? 0.62f : 1.0f;
                ui.animations().tweenOpacity(showcaseUi.panel(), target,
                        new UiTweenSpec(0.55f, UiEasing.SPRING));
            }
            if (frame % 30 == 0) {
                showcaseUi.status().text(String.format(Locale.ROOT,
                        "frame %d  cpu %d  gpu %d", frame,
                        effectSnapshot[0].particles().size(), gpuParticles.capacity()));
            }
            ui.update(window.inputSnapshot(), delta);
            updateNanos += System.nanoTime() - updateStart;

            driver.beginFrame();
            pipeline.execute(driver.device(), delta);
            driver.recordGraph(pipeline.graph());
            driver.endFrame();
            long frameGpuNanos = pipeline.graph().lastFrameProfile().totalGpuNanos();
            if (frame >= options.warmup() && frameGpuNanos > 0L) {
                gpuNanos += frameGpuNanos;
                gpuSamples++;
            }
            boolean finalFrame = frame + 1 >= options.frames();
            if (options.verifyPixels() && finalFrame) {
                nonBlackPixels = countNonBlackPixels(window.width(), window.height());
            }
            if (options.stability() && frame == options.warmup()) {
                stableResources = GlDebug.resources();
            }
            driver.present(window::swapBuffers);
            window.pollEvents();
            frame++;
            if (finalFrame) window.requestClose();
        }

        EffectSnapshot snapshot = effectSnapshot[0];
        UiFrameStats uiStats = ui.statistics();
        UiAnimationDiagnostics uiAnimations = ui.animations().diagnostics();
        if (options.verifyPixels() && nonBlackPixels <= 0) {
            throw new IllegalStateException("showcase framebuffer contains no rendered pixels");
        }
        if (animationEvents <= 0L || rootMotionDistance <= 0.0
                || snapshot == null || snapshot.particles().isEmpty()
                || snapshot.ribbonSegments().isEmpty() || snapshot.decals().isEmpty()
                || cpuVfxRenderer.statistics().drawCalls() != snapshot.primitiveCount()
                || gpuParticles.statistics().framesRecorded() != options.frames()
                || volumetric.statistics().framesRecorded() != options.frames()
                || uiStats.quads() <= 0 || uiAnimations.started() <= 0L) {
            throw new IllegalStateException("showcase did not exercise every required subsystem");
        }
        if (options.stability()) verifyStableResources(stableResources, GlDebug.resources());
        return new Summary(frame, animationEvents, rootMotionDistance,
                snapshot.particles().size(), snapshot.ribbonSegments().size(), snapshot.decals().size(),
                gpuParticles.capacity(), volumetric.statistics().samplesPerPixel(), uiStats.quads(),
                uiAnimations.started(), updateNanos / 1_000_000.0 / Math.max(1, frame),
                gpuSamples == 0 ? 0.0 : gpuNanos / 1_000_000.0 / gpuSamples,
                nonBlackPixels, options.stability());
    }

    private static Scene scene(Camera camera, Mesh sphere, Material material,
                               PoseBuffer pose, float[] visualRootMotion) {
        Scene scene = new Scene(camera);
        scene.add(new SceneObject(sphere, material, (out, frame) -> {
            Matrix4f joint = pose.globalMatrix(2, new Matrix4f());
            out.identity().translation(visualRootMotion[0], 0.0f, 0.0f)
                    .mul(joint).scale(0.72f);
        }));
        scene.add(SceneObject.fixed(sphere, material,
                new Matrix4f().translation(0.0f, -1.55f, -0.35f).scale(2.8f, 0.18f, 1.4f)));
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.5f, -1.0f, -0.7f),
                new Vector3f(1.0f, 0.92f, 0.82f), 2.4f));
        scene.addLight(SceneLight.point(new Vector3f(2.0f, 2.2f, 3.0f),
                new Vector3f(0.1f, 0.45f, 1.0f), 12.0f, 10.0f));
        return scene;
    }

    private static Skeleton skeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, transform(-1.2f, -0.25f, 0.0f)),
                new Skeleton.Joint("middle", 0, transform(1.25f, 0.0f, 0.0f)),
                new Skeleton.Joint("tip", 1, transform(1.05f, 0.0f, 0.0f))));
    }

    private static AnimationMixer mixer(Skeleton skeleton) {
        AnimationClip locomotion = AnimationClip.builder("locomotion", skeleton)
                .translation(0, LINEAR, new float[]{0.0f, 2.0f},
                        new Vector3f(-1.2f, -0.25f, 0.0f),
                        new Vector3f(1.2f, -0.25f, 0.0f))
                .event(0.0f, "start")
                .event(0.1f, "burst", "showcase")
                .event(1.0f, "step")
                .build();
        AnimationClip layer = AnimationClip.builder("upper-layer", skeleton)
                .rotation(1, LINEAR, new float[]{0.0f, 1.0f},
                        new Quaternionf().rotateZ(-0.4f), new Quaternionf().rotateZ(0.55f))
                .build();
        return new AnimationMixer(skeleton)
                .playBase(locomotion, AnimationPlayer.LoopMode.LOOP)
                .playLayer(layer, AnimationPlayer.LoopMode.LOOP)
                .layerMask(BoneMask.builder(skeleton).subtree(1, 1.0f).build())
                .layerWeight(0.45f)
                .synchronizeLayer(true);
    }

    private static EffectAsset effectAsset() {
        return EffectAsset.builder("showcase-vfx")
                .particles(new ParticleEmitter(256, 180.0f, 1.8f,
                        new Vector3f(0.0f, 0.8f, 0.0f), 0.45f, 0.35f, 1.25f,
                        new Vector3f(0.0f, -0.55f, 0.0f), 0.08f,
                        0.13f, 0.025f,
                        new Vector4f(1.0f, 0.55f, 0.08f, 0.82f),
                        new Vector4f(0.1f, 0.45f, 1.0f, 0.0f)))
                .ribbon(new RibbonEmitter(48, 1.5f, 0.015f, 0.13f, 0.02f,
                        new Vector4f(0.08f, 0.75f, 1.0f, 0.72f),
                        new Vector4f(0.12f, 0.2f, 0.9f, 0.0f)))
                .decals(new Decal(8, 3.0f,
                        new Vector4f(0.55f, 0.16f, 1.0f, 0.58f),
                        new Vector4f(0.2f, 0.04f, 0.45f, 0.0f)))
                .build();
    }

    private static PostProcessSettings postProcessSettings() {
        ColorGradingLut lut = ColorGradingLut.generate(16,
                (red, green, blue) -> new ColorGradingLut.Rgb(
                        Math.min(1.0f, red * 1.04f), green * 0.96f,
                        Math.min(1.0f, blue * 1.08f)));
        return PostProcessSettings.builder()
                .colorGrading(ColorGradingSettings.of(lut, 0.55f))
                .fog(FogSettings.builder().color(0.14f, 0.19f, 0.28f)
                        .distanceDensity(0.012f).heightDensity(0.018f)
                        .heightFalloff(0.2f).baseHeight(-1.5f)
                        .maximumOpacity(0.62f).build())
                .build();
    }

    private static VolumetricLightSettings volumetricSettings() {
        return new VolumetricLightSettings(24, 10.0f, 0.6f, 0.25f, 7.0f,
                (float) Math.cos(Math.toRadians(13.0)),
                (float) Math.cos(Math.toRadians(28.0)), 8.0f,
                new Vector3f(-1.8f, 2.8f, 3.5f),
                new Vector3f(0.35f, -0.45f, -1.0f),
                new Vector3f(0.2f, 0.48f, 1.0f));
    }

    private static ShowcaseUi installUi(UiSystem ui) {
        Panel root = ui.document().root();
        root.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.percent(100))
                .padding(UiInsets.points(16)).alignItems(UiStyle.AlignItems.FLEX_START).build());
        Panel panel = new Panel();
        panel.debugName("ShowcaseDiagnostics");
        panel.style(UiStyle.builder().width(UiLength.points(290)).height(UiLength.points(92))
                .padding(UiInsets.points(10)).gap(5)
                .flexDirection(UiStyle.FlexDirection.COLUMN).build());
        Label title = new Label("Haikalat");
        title.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(30)).build());
        Label status = new Label("frame 0  cpu 0  gpu 0");
        status.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(28)).build());
        panel.add(title).add(status);
        root.add(panel);
        return new ShowcaseUi(panel, status);
    }

    private static void verifyStableResources(GlDebug.ResourceSnapshot expected,
                                              GlDebug.ResourceSnapshot actual) {
        if (expected == null) {
            throw new IllegalStateException("stability warmup snapshot was not captured");
        }
        Set<Long> expectedIds = resourceSequences(expected);
        Set<Long> actualIds = resourceSequences(actual);
        if (!expectedIds.equals(actualIds) || expected.estimatedBytes() != actual.estimatedBytes()) {
            throw new IllegalStateException("GL resources changed after warmup: expected="
                    + expectedIds.size() + "/" + expected.estimatedBytes() + " actual="
                    + actualIds.size() + "/" + actual.estimatedBytes());
        }
    }

    private static Set<Long> resourceSequences(GlDebug.ResourceSnapshot snapshot) {
        Set<Long> sequences = new HashSet<>();
        snapshot.liveResources().forEach(resource -> sequences.add(resource.resourceSequence()));
        return Set.copyOf(sequences);
    }

    private static int countNonBlackPixels(int width, int height) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(width * height, 4));
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        int count = 0;
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            if (Byte.toUnsignedInt(pixels.get(offset))
                    + Byte.toUnsignedInt(pixels.get(offset + 1))
                    + Byte.toUnsignedInt(pixels.get(offset + 2)) > 12) count++;
        }
        return count;
    }

    private static Vector3f jointPosition(PoseBuffer pose, int joint) {
        Matrix4f matrix = pose.globalMatrix(joint, new Matrix4f());
        return new Vector3f(matrix.m30(), matrix.m31(), matrix.m32());
    }

    private static float wrap(float value, float halfRange) {
        float range = halfRange * 2.0f;
        float wrapped = (value + halfRange) % range;
        if (wrapped < 0.0f) wrapped += range;
        return wrapped - halfRange;
    }

    private static JointTransform transform(float x, float y, float z) {
        return new JointTransform(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(1.0f));
    }

    private record ShowcaseUi(Panel panel, Label status) {
    }

    public record Summary(int frames, long animationEvents, double rootMotionDistance,
                          int cpuParticles, int ribbons, int decals, int gpuParticles,
                          int volumeSteps, long uiQuads, long uiAnimations,
                          double averageUpdateMillis, double averageGpuMillis,
                          int nonBlackPixels, boolean stabilityVerified) {
        String format() {
            return String.format(Locale.ROOT,
                    "SHOWCASE frames=%d events=%d rootMotion=%.3f cpuParticles=%d ribbons=%d "
                            + "decals=%d gpuParticles=%d volumeSteps=%d uiQuads=%d uiAnimations=%d "
                            + "cpuUpdateMs=%.4f gpuMs=%.4f pixels=%d stability=%s",
                    frames, animationEvents, rootMotionDistance, cpuParticles, ribbons, decals,
                    gpuParticles, volumeSteps, uiQuads, uiAnimations, averageUpdateMillis,
                    averageGpuMillis, nonBlackPixels, stabilityVerified);
        }
    }

    private record Options(boolean hidden, int frames, int warmup, int width, int height,
                           int gpuParticles, boolean verifyPixels, boolean stability) {
        private static Options parse(String[] arguments) {
            boolean hidden = false;
            int frames = -1;
            int warmup = 0;
            int width = 960;
            int height = 540;
            int gpuParticles = 512;
            boolean verifyPixels = false;
            boolean stability = false;
            for (String argument : arguments) {
                if ("--hidden".equals(argument) || "--deterministic".equals(argument)) {
                    hidden = true;
                } else if (argument.startsWith("--frames=")) {
                    frames = positive(argument, "--frames=");
                } else if (argument.startsWith("--warmup=")) {
                    warmup = nonNegative(argument, "--warmup=");
                } else if (argument.startsWith("--size=")) {
                    String[] size = argument.substring("--size=".length())
                            .toLowerCase(Locale.ROOT).split("x", 2);
                    if (size.length != 2) throw new IllegalArgumentException("--size must be WIDTHxHEIGHT");
                    width = Integer.parseInt(size[0]);
                    height = Integer.parseInt(size[1]);
                } else if (argument.startsWith("--gpu-particles=")) {
                    gpuParticles = positive(argument, "--gpu-particles=");
                } else if ("--verify-pixels".equals(argument)) {
                    verifyPixels = true;
                } else if ("--stability".equals(argument)) {
                    stability = true;
                } else {
                    throw new IllegalArgumentException("Unknown showcase option: " + argument);
                }
            }
            if (hidden && frames < 0) frames = 24;
            if (frames < 0) frames = Integer.MAX_VALUE;
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("--size must be positive");
            if (warmup < 0 || warmup >= frames) {
                throw new IllegalArgumentException("--warmup must be below --frames");
            }
            if (stability && warmup == 0) {
                throw new IllegalArgumentException("--stability requires a positive --warmup");
            }
            return new Options(hidden, frames, warmup, width, height,
                    gpuParticles, verifyPixels, stability);
        }

        private static int positive(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " requires a positive integer");
            return value;
        }

        private static int nonNegative(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException(prefix + " requires a non-negative integer");
            return value;
        }
    }
}
