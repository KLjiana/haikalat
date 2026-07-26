package com.kaleblangley.haikalat.demo.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.curve.BezierPath3f;
import com.kaleblangley.haikalat.core.curve.ColorGradient;
import com.kaleblangley.haikalat.core.curve.FloatTrack;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.postprocess.BloomPass;
import com.kaleblangley.haikalat.subsystems.postprocess.ToneMappingPass;
import com.kaleblangley.haikalat.subsystems.vfx.Decal;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.RibbonEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.VfxBillboardMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaskMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.subsystems.vfx.VfxUvRegion;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;

import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/** CPU VFX 模拟、透明顺序、GPU 适配和性能计时的独立证明。 */
public final class VfxDemo {
    private static final float FIXED_DELTA_SECONDS = 1.0f / 60.0f;
    private static final float PATH_CYCLES_PER_SECOND = 0.42f;
    private static final int RIBBON_MAX_POINTS = 160;
    private static final int MIN_COMPLETE_RIBBON_SEGMENTS = 120;
    private static final float RIBBON_POINT_LIFETIME_SECONDS = 4.0f;
    private static final String PASS_NAME = "VfxPass";
    private static final String HDR_COLOR = "vfxHdrColor";
    private static final String HDR_DEPTH = "vfxHdrDepth";
    private static final String BLOOM_PASS = "VfxBloomPass";
    private static final String BLOOM_COLOR = "vfxBloomColor";
    private static final String PRESENT_PASS = "VfxPresentPass";

    private VfxDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        RenderSettings settings = RenderSettings.builder().vsync(!options.hidden()).build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat VFX")
                .visible(!options.hidden())
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(settings.vsync());
            run(window, settings, options);
        }
    }

    private static void run(GlfwWindow window, RenderSettings settings, Options options) {
        Vector3f camera = new Vector3f(0.0f, 0.2f, 5.0f);
        Matrix4f projection = new Matrix4f();
        Matrix4f view = new Matrix4f().lookAt(camera, new Vector3f(),
                new Vector3f(0.0f, 1.0f, 0.0f));
        EffectSnapshot[] current = new EffectSnapshot[2];
        VfxRenderer.Statistics[] frameStatistics = new VfxRenderer.Statistics[1];
        long updateNanos = 0L;
        long gpuNanos = 0L;
        int gpuSamples = 0;
        FrameClock clock = new FrameClock();
        InfinityPath emitterPath = new InfinityPath();
        Vector3f origin = new Vector3f();
        float pathDistance = 0.0f;

        try (FrameDriver driver = new FrameDriver(settings);
             VfxRenderer renderer = new VfxRenderer();
             BloomPass bloom = new BloomPass();
             ToneMappingPass toneMapping = new ToneMappingPass();
             EffectAsset asset = asset(options.maxParticles(), options.curved(), options.effect());
             EffectInstance instance = asset.instantiate(0x5eedL);
             EffectAsset smokeAsset = smokeAsset(options.maxParticles());
             EffectInstance smoke = smokeAsset.instantiate(0x5eed5eedL);
             RenderGraph graph = graph(window, renderer, bloom, toneMapping,
                     current, frameStatistics, projection, view, options)) {
            int frame = 0;
            while (!window.shouldClose()) {
                if (window.isKeyDown(GLFW_KEY_ESCAPE)) window.requestClose();
                if (options.resizeFrame() == frame) {
                    window.resize(options.resizeWidth(), options.resizeHeight());
                    window.pollEvents();
                }
                if (window.consumeResize()) graph.resize(window.width(), window.height());

                float delta = options.hidden() ? FIXED_DELTA_SECONDS : clock.tick().deltaSeconds();
                if (frame > 0) {
                    pathDistance = (pathDistance + delta * PATH_CYCLES_PER_SECOND) % 1.0f;
                }
                emitterPath.sample(pathDistance, origin);
                long updateStart = System.nanoTime();
                instance.update(delta, origin);
                if (options.effect().smoke) {
                    smoke.update(delta, new Vector3f(origin).add(0.0f, 0.08f, -0.02f));
                }
                if (asset.decal().isPresent() && (frame == 0 || frame == options.maxFrames() / 2)) {
                    instance.spawnDecal(new Vector3f(origin.x, -1.0f, origin.z - 0.1f),
                            new Vector3f(0.0f, 0.0f, 1.0f), new Vector2f(0.9f, 0.35f),
                            frame * 0.08f);
                }
                current[0] = instance.snapshot(camera);
                current[1] = options.effect().smoke ? smoke.snapshot(camera) : null;
                updateNanos += System.nanoTime() - updateStart;

                driver.frame(graph);
                long frameGpuNanos = graph.lastFrameProfile().totalGpuNanos();
                if (frame >= options.warmupFrames() && frameGpuNanos > 0L) {
                    gpuNanos += frameGpuNanos;
                    gpuSamples++;
                }
                driver.present(window::swapBuffers);
                window.pollEvents();
                frame++;
                if (options.maxFrames() > 0 && frame >= options.maxFrames()) window.requestClose();
            }

            EffectSnapshot snapshot = current[0];
            EffectSnapshot smokeSnapshot = current[1];
            VfxRenderer.Statistics renderStats = frameStatistics[0];
            int expectedPrimitives = snapshot == null ? 0 : snapshot.primitiveCount();
            if (smokeSnapshot != null) expectedPrimitives += smokeSnapshot.primitiveCount();
            if (options.hidden() && (snapshot == null
                    || options.effect().particles && snapshot.particles().isEmpty()
                    || options.effect().smoke && (smokeSnapshot == null
                            || smokeSnapshot.particles().isEmpty())
                    || options.effect().ribbon && snapshot.ribbonSegments().isEmpty()
                    || options.effect().decal && snapshot.decals().isEmpty()
                    || renderStats == null || renderStats.drawCalls() != expectedPrimitives)) {
                throw new IllegalStateException("VFX integration did not render every effect primitive");
            }
            if (options.hidden() && options.effect().smoke
                    && (renderStats.alphaDraws() == 0 || renderStats.additiveDraws() == 0
                            || options.softParticles() && renderStats.softParticleDraws() == 0)) {
                throw new IllegalStateException(
                        "VFX fire/smoke proof did not exercise alpha, additive and soft-particle draws");
            }
            boolean completedPathCycle = options.maxFrames() > 0
                    && options.maxFrames() * FIXED_DELTA_SECONDS * PATH_CYCLES_PER_SECOND >= 1.0f;
            if (options.hidden() && completedPathCycle
                    && snapshot.ribbonSegments().size() < MIN_COMPLETE_RIBBON_SEGMENTS) {
                throw new IllegalStateException("VFX integration did not retain the complete ribbon path");
            }
            int measuredFrames = Math.max(1, options.maxFrames());
            System.out.printf(Locale.ROOT,
                    "VFX frames=%d particles=%d ribbons=%d decals=%d meshes=%d draws=%d "
                            + "cpuUpdateMs=%.4f gpuMs=%.4f uniformBytes=%d resized=%s mode=%s%n",
                    options.maxFrames(), (snapshot == null ? 0 : snapshot.particles().size())
                            + (smokeSnapshot == null ? 0 : smokeSnapshot.particles().size()),
                    snapshot == null ? 0 : snapshot.ribbonSegments().size(),
                    snapshot == null ? 0 : snapshot.decals().size(),
                    snapshot == null ? 0 : snapshot.meshes().size(), renderStats.drawCalls(),
                    updateNanos / 1_000_000.0 / measuredFrames,
                    gpuSamples == 0 ? 0.0 : gpuNanos / 1_000_000.0 / gpuSamples,
                    renderStats.uniformPayloadBytes(), options.resizeFrame() >= 0,
                    (options.curved() ? "curved" : "linear") + "/" + options.effect().name().toLowerCase(Locale.ROOT)
                            + "/hdr=" + options.hdr() + "/bloom=" + options.bloom()
                            + "/soft=" + options.softParticles());
        }
    }

    private static RenderGraph graph(GlfwWindow window, VfxRenderer renderer,
                                     BloomPass bloom, ToneMappingPass toneMapping,
                                     EffectSnapshot[] current,
                                     VfxRenderer.Statistics[] frameStatistics,
                                     Matrix4f projection,
                                     Matrix4f view, Options options) {
        RenderGraph graph = new RenderGraph(window.width(), window.height());
        RenderGraph.PassBuilder vfx = graph.addPass(PASS_NAME);
        if (options.hdr()) {
            vfx.createColor(HDR_COLOR, RenderFormat.RGBA16F).createDepthTexture(HDR_DEPTH)
                    .clearColor(0.012f, 0.018f, 0.028f, 1.0f);
        } else {
            vfx.writeToBackbuffer().noClear();
        }
        vfx.execute((resources, commands) -> {
            DemoSupport.perspective(projection, window.width(), window.height());
            if (!options.hdr()) {
                commands.bindDefaultFramebuffer().viewport(0, 0, window.width(), window.height())
                        .enableFramebufferSrgb(false).enableDepthTest(true).depthMask(true)
                        .clearColor(0.012f, 0.018f, 0.028f, 1.0f).clear(true, true);
            }
            if (current[0] != null) {
                int depth = options.hdr() && options.softParticles()
                        ? resources.depthAttachment(HDR_DEPTH) : 0;
                VfxRenderer.Statistics total = renderer.record(commands, current[0], projection,
                        view, depth, window.width(), window.height());
                if (current[1] != null) {
                    total = add(total, renderer.record(commands, current[1], projection,
                            view, depth, window.width(), window.height()));
                }
                frameStatistics[0] = total;
            }
        });
        if (options.hdr() && options.bloom()) {
            graph.addPass(BLOOM_PASS).createColor(BLOOM_COLOR, RenderFormat.RGBA16F)
                    .relativeSize(0.5f).noClear().dependsOn(PASS_NAME)
                    .execute((resources, commands) -> bloom.recordExtract(commands,
                            resources.colorAttachment(HDR_COLOR), window.width(), window.height(),
                            1.0f, 0.5f));
        }
        if (options.hdr()) {
            graph.addPass(PRESENT_PASS).writeToBackbuffer().noClear()
                    .dependsOn(options.bloom() ? BLOOM_PASS : PASS_NAME)
                    .execute((resources, commands) -> toneMapping.recordIntoCurrentTarget(commands,
                            resources.colorAttachment(HDR_COLOR),
                            options.bloom() ? resources.colorAttachment(BLOOM_COLOR) : 0,
                            1.0f, options.bloom() ? 0.12f : 0.0f));
        }
        graph.compile();
        return graph;
    }

    private static EffectAsset asset(int maxParticles, boolean curved, EffectMode effect) {
        EffectAsset.Builder builder = EffectAsset.builder("demo-vfx");
        if (effect.particles) builder.particles(new ParticleEmitter(maxParticles,
                        Math.min(600.0f, maxParticles * 1.5f),
                        2.0f, new Vector3f(0.0f, 1.0f, 0.0f), 0.55f,
                        0.5f, 2.0f, new Vector3f(0.0f, -0.7f, 0.0f), 0.15f,
                        0.16f, 0.02f, new Vector4f(1.0f, 0.62f, 0.12f, 0.9f),
                        new Vector4f(0.9f, 0.08f, 0.03f, 0.0f)));
        if (effect.ribbon) builder.ribbon(new RibbonEmitter(RIBBON_MAX_POINTS,
                        RIBBON_POINT_LIFETIME_SECONDS,
                        0.025f, 0.24f, 0.025f,
                        new Vector4f(0.08f, 0.75f, 1.0f, 0.78f),
                        new Vector4f(0.1f, 0.15f, 0.8f, 0.0f)));
        if (effect.decal) builder.decals(new Decal(8, 4.0f,
                        new Vector4f(0.62f, 0.18f, 1.0f, 0.65f),
                        new Vector4f(0.25f, 0.04f, 0.5f, 0.0f)));
        if (effect != EffectMode.LEGACY) {
            builder.particleMaterial(VfxMaterial.builder("demo-fire")
                            .texture(AssetRef.of("/vfx/particles/kenney/particle_pack/fire_01.png"))
                            .maskMode(VfxMaskMode.LUMINANCE).blendMode(BlendMode.ADDITIVE)
                            .emissiveIntensity(4.0f)
                            .billboardMode(VfxBillboardMode.VELOCITY_ALIGNED)
                            .velocityStretch(0.3f).maximumStretch(2.5f)
                            .softParticleDistance(0.1f).build())
                    .ribbonMaterial(VfxMaterial.builder("demo-trail")
                            .texture(AssetRef.of("/vfx/masks/kenney/light_masks/transparent/streaks_composed_a_noise.png"))
                            .maskMode(VfxMaskMode.ALPHA).blendMode(BlendMode.ADDITIVE)
                            .uvRegion(new VfxUvRegion(0.0f, 0.75f, 1.0f, 1.0f))
                            .emissiveIntensity(3.0f).softParticleDistance(0.06f).build())
                    .decalMaterial(VfxMaterial.builder("demo-impact")
                            .texture(AssetRef.of("/vfx/masks/kenney/light_masks/transparent/ring_a_streaks.png"))
                            .maskMode(VfxMaskMode.ALPHA).blendMode(BlendMode.ADDITIVE)
                            .emissiveIntensity(5.0f).softParticleDistance(0.04f).build());
        }
        if (curved) {
            if (effect.particles) builder.particleSizeOverLife(new FloatTrack(
                            new FloatTrack.Key(0.0f, 0.16f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(0.18f, 0.29f, FloatTrack.Interpolation.CUBIC_HERMITE),
                            new FloatTrack.Key(1.0f, 0.02f, 0.0f, 0.0f,
                                    FloatTrack.Interpolation.LINEAR)))
                    .particleColorOverLife(new ColorGradient(
                            new ColorGradient.Stop(0.0f, 1.8f, 1.0f, 0.15f, 0.95f),
                            new ColorGradient.Stop(0.35f, 1.0f, 0.28f, 0.04f, 0.72f),
                            new ColorGradient.Stop(1.0f, 0.35f, 0.01f, 0.0f, 0.0f)))
                    .particleRotationOverLife(new FloatTrack(
                            new FloatTrack.Key(0.0f, 0.0f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(1.0f, 2.2f, FloatTrack.Interpolation.LINEAR)));
            if (effect.ribbon) builder.ribbonWidthOverLife(new FloatTrack(
                            new FloatTrack.Key(0.0f, 0.24f, 0.0f, -0.35f,
                                    FloatTrack.Interpolation.CUBIC_HERMITE),
                            new FloatTrack.Key(1.0f, 0.025f, 0.0f, 0.0f,
                                    FloatTrack.Interpolation.LINEAR)))
                    .ribbonColorOverLife(new ColorGradient(
                            new ColorGradient.Stop(0.0f, 0.08f, 0.75f, 1.4f, 0.78f),
                            new ColorGradient.Stop(1.0f, 0.05f, 0.1f, 0.65f, 0.0f)));
            if (effect.decal) builder.decalScaleOverLife(new FloatTrack(
                            new FloatTrack.Key(0.0f, 0.82f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(0.18f, 1.12f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(1.0f, 0.68f, FloatTrack.Interpolation.LINEAR)))
                    .decalColorOverLife(new ColorGradient(
                            new ColorGradient.Stop(0.0f, 0.62f, 0.18f, 1.0f, 0.65f),
                            new ColorGradient.Stop(1.0f, 0.25f, 0.04f, 0.5f, 0.0f)));
        }
        return builder.build();
    }

    private static EffectAsset smokeAsset(int maxParticles) {
        VfxMaterial smoke = VfxMaterial.builder("demo-smoke")
                .texture(AssetRef.of("/vfx/particles/kenney/particle_pack/smoke_03.png"))
                .maskMode(VfxMaskMode.LUMINANCE)
                .blendMode(BlendMode.ALPHA)
                .emissiveIntensity(0.3f)
                .billboardMode(VfxBillboardMode.CAMERA_FACING)
                .softParticleDistance(0.18f)
                .fogInfluence(1.0f)
                .build();
        return EffectAsset.builder("demo-smoke")
                .particles(new ParticleEmitter(Math.max(16, maxParticles / 2),
                        Math.min(90.0f, maxParticles * 0.3f), 3.0f,
                        new Vector3f(0.0f, 1.0f, 0.0f), 0.75f,
                        0.08f, 0.4f, new Vector3f(0.0f, 0.12f, 0.0f), 0.35f,
                        0.18f, 0.55f, new Vector4f(0.45f, 0.5f, 0.55f, 0.32f),
                        new Vector4f(0.16f, 0.18f, 0.2f, 0.0f)))
                .particleMaterial(smoke)
                .particleSizeOverLife(new FloatTrack(
                        new FloatTrack.Key(0.0f, 0.18f, FloatTrack.Interpolation.LINEAR),
                        new FloatTrack.Key(1.0f, 0.55f, FloatTrack.Interpolation.LINEAR)))
                .particleColorOverLife(new ColorGradient(
                        new ColorGradient.Stop(0.0f, 0.45f, 0.5f, 0.55f, 0.32f),
                        new ColorGradient.Stop(1.0f, 0.16f, 0.18f, 0.2f, 0.0f)))
                .build();
    }

    private static VfxRenderer.Statistics add(VfxRenderer.Statistics left,
                                               VfxRenderer.Statistics right) {
        return new VfxRenderer.Statistics(left.drawCalls() + right.drawCalls(),
                left.particles() + right.particles(),
                left.ribbonSegments() + right.ribbonSegments(),
                left.decals() + right.decals(),
                left.meshes() + right.meshes(),
                left.uniformPayloadBytes() + right.uniformPayloadBytes(),
                left.textureBinds() + right.textureBinds(),
                left.materialSwitches() + right.materialSwitches(),
                left.alphaDraws() + right.alphaDraws(),
                left.additiveDraws() + right.additiveDraws(),
                left.softParticleDraws() + right.softParticleDraws());
    }

    /** Two arc-length-parameterized cubic loops with matching tangents at the center crossing. */
    private static final class InfinityPath {
        private final BezierPath3f left = new BezierPath3f(
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(-1.6f, 0.9f, 0.0f),
                new Vector3f(-1.6f, -0.9f, 0.0f), new Vector3f(0.0f, 0.0f, 0.0f));
        private final BezierPath3f right = new BezierPath3f(
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(1.6f, 0.9f, 0.0f),
                new Vector3f(1.6f, -0.9f, 0.0f), new Vector3f(0.0f, 0.0f, 0.0f));
        private final float leftShare = left.approximateLength()
                / (left.approximateLength() + right.approximateLength());

        private void sample(float normalizedDistance, Vector3f destination) {
            if (normalizedDistance < leftShare) {
                float localDistance = normalizedDistance / leftShare;
                left.sample(left.parameterAtNormalizedDistance(localDistance), destination);
                return;
            }
            float localDistance = (normalizedDistance - leftShare) / (1.0f - leftShare);
            right.sample(right.parameterAtNormalizedDistance(localDistance), destination);
        }
    }

    private enum EffectMode {
        LEGACY(true, true, true, false), FIRE(true, false, false, true),
        IMPACT(true, false, true, false), TRAIL(false, true, false, false),
        ALL(true, true, true, true);

        private final boolean particles;
        private final boolean ribbon;
        private final boolean decal;
        private final boolean smoke;

        EffectMode(boolean particles, boolean ribbon, boolean decal, boolean smoke) {
            this.particles = particles; this.ribbon = ribbon; this.decal = decal;
            this.smoke = smoke;
        }
    }

    private record Options(boolean hidden, boolean curved, EffectMode effect,
                           boolean hdr, boolean bloom, boolean softParticles,
                           int width, int height,
                           int maxFrames, int warmupFrames,
                           int maxParticles, int resizeFrame,
                           int resizeWidth, int resizeHeight) {
        private static Options parse(String[] arguments) {
            boolean hidden = false;
            boolean curved = true;
            EffectMode effect = EffectMode.ALL;
            boolean hdr = true;
            boolean bloom = true;
            boolean softParticles = true;
            int width = DemoSupport.DEFAULT_WIDTH;
            int height = DemoSupport.DEFAULT_HEIGHT;
            int frames = -1;
            int warmup = 0;
            int particles = 256;
            int resizeFrame = -1;
            int resizeWidth = -1;
            int resizeHeight = -1;
            for (String argument : arguments) {
                if ("--hidden".equals(argument) || "--deterministic".equals(argument)) {
                    hidden = true;
                } else if ("--linear".equals(argument)) {
                    curved = false;
                } else if ("--curved".equals(argument)) {
                    curved = true;
                } else if (argument.startsWith("--frames=")) {
                    frames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--warmup=")) {
                    warmup = Integer.parseInt(argument.substring("--warmup=".length()));
                } else if (argument.startsWith("--particles=")) {
                    particles = Integer.parseInt(argument.substring("--particles=".length()));
                } else if (argument.startsWith("--resize=")) {
                    String[] frameAndSize = argument.substring("--resize=".length()).split(":", 2);
                    String[] size = frameAndSize[1].toLowerCase(Locale.ROOT).split("x", 2);
                    resizeFrame = Integer.parseInt(frameAndSize[0]);
                    resizeWidth = Integer.parseInt(size[0]);
                    resizeHeight = Integer.parseInt(size[1]);
                } else if (argument.startsWith("--size=")) {
                    String[] size = argument.substring("--size=".length())
                            .toLowerCase(Locale.ROOT).split("x", 2);
                    width = Integer.parseInt(size[0]);
                    height = Integer.parseInt(size[1]);
                } else if (argument.startsWith("--effect=")) {
                    effect = EffectMode.valueOf(argument.substring("--effect=".length())
                            .toUpperCase(Locale.ROOT));
                } else if (argument.startsWith("--hdr=")) {
                    hdr = parseToggle(argument, "--hdr=");
                } else if (argument.startsWith("--bloom=")) {
                    bloom = parseToggle(argument, "--bloom=");
                } else if (argument.startsWith("--soft-particles=")) {
                    softParticles = parseToggle(argument, "--soft-particles=");
                } else {
                    throw new IllegalArgumentException("Unknown VfxDemo argument: " + argument);
                }
            }
            if (hidden && frames < 0) frames = 16;
            if (frames == 0 || frames < -1) throw new IllegalArgumentException("--frames must be positive");
            if (warmup < 0 || frames > 0 && warmup >= frames) {
                throw new IllegalArgumentException("--warmup must be non-negative and below --frames");
            }
            if (particles <= 0) throw new IllegalArgumentException("--particles must be positive");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("--size must be WIDTHxHEIGHT with positive values");
            }
            if (bloom && !hdr) throw new IllegalArgumentException("--bloom=on requires --hdr=on");
            if (softParticles && !hdr) {
                throw new IllegalArgumentException("--soft-particles=on requires --hdr=on");
            }
            if (resizeFrame >= 0 && (resizeWidth <= 0 || resizeHeight <= 0
                    || frames > 0 && resizeFrame >= frames)) {
                throw new IllegalArgumentException("--resize must fit FRAME:WIDTHxHEIGHT");
            }
            return new Options(hidden, curved, effect, hdr, bloom, softParticles, width, height,
                    frames, warmup, particles,
                    resizeFrame, resizeWidth, resizeHeight);
        }

        private static boolean parseToggle(String argument, String prefix) {
            return switch (argument.substring(prefix.length())) {
                case "on" -> true;
                case "off" -> false;
                default -> throw new IllegalArgumentException(prefix + " expects on or off");
            };
        }
    }
}
