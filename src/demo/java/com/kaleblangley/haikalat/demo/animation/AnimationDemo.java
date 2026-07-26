package com.kaleblangley.haikalat.demo.animation;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.RenderTargetManager;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.animation.AnimationClip;
import com.kaleblangley.haikalat.subsystems.animation.AnimationConstraint;
import com.kaleblangley.haikalat.subsystems.animation.AnimationConstraintStack;
import com.kaleblangley.haikalat.subsystems.animation.AnimationController;
import com.kaleblangley.haikalat.subsystems.animation.AnimationGraph;
import com.kaleblangley.haikalat.subsystems.animation.AnimationLayerStack;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.animation.BlendTree1D;
import com.kaleblangley.haikalat.subsystems.animation.BoneMask;
import com.kaleblangley.haikalat.subsystems.animation.ClipMotion;
import com.kaleblangley.haikalat.subsystems.animation.JointTransform;
import com.kaleblangley.haikalat.subsystems.animation.PoseBuffer;
import com.kaleblangley.haikalat.subsystems.animation.Skeleton;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.util.List;

import static com.kaleblangley.haikalat.subsystems.animation.AnimationClip.Interpolation.LINEAR;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/** CPU 骨架求值、层次传播和现有命令渲染路径的独立证明。 */
public final class AnimationDemo {
    private static final String SCENE_TARGET = "AnimationScene";
    private static final float FIXED_DELTA_SECONDS = 1.0f / 30.0f;

    private AnimationDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        RenderSettings settings = RenderSettings.builder().vsync(!options.deterministic()).build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(DemoSupport.DEFAULT_WIDTH, DemoSupport.DEFAULT_HEIGHT)
                .title("Haikalat Animation")
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(settings.vsync());
            if (!options.deterministic()) window.show();
            run(window, settings, options);
        }
    }

    private static void run(GlfwWindow window, RenderSettings settings, Options options) {
        Skeleton skeleton = skeleton();
        AnimationConstraint.Context constraintContext = AnimationConstraint.Context.target(
                new Vector3f(0.8f, 1.4f, 0.0f),
                new Vector3f(0.0f, 0.0f, 1.0f), 0.65f, FIXED_DELTA_SECONDS);
        CharacterRuntime[] characters = new CharacterRuntime[options.characters()];
        for (int index = 0; index < characters.length; index++) {
            characters[index] = new CharacterRuntime(skeleton, constraintContext);
        }
        PoseBuffer pose = characters[0].pose;
        FrameClock clock = new FrameClock();

        try {
            try (FrameDriver frameDriver = new FrameDriver(settings);
                 RenderTargetManager targets = new RenderTargetManager();
                 ShaderProgram shader = DemoSupport.loadColorMvpShader(AnimationDemo.class);
                 Mesh cube = Mesh.from(BuiltinMeshData.coloredCube("animation-joint"))) {
            Material material = Material.builder(shader).blendMode(BlendMode.OPAQUE).build();
            targets.create(SCENE_TARGET, FramebufferDescriptor.builder(window.width(), window.height())
                    .colorTexture(RenderFormat.SRGB8_ALPHA8)
                    .depthStencilRenderbuffer()
                    .build());

            Matrix4f projection = new Matrix4f();
            Matrix4f view = new Matrix4f().lookAt(0.0f, 0.0f, 6.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
            Matrix4f projectionView = new Matrix4f();
            Matrix4f model = new Matrix4f();
            Matrix4f mvp = new Matrix4f();
            int frame = 0;
            float firstTipX = Float.NaN;
            float lastTipX = Float.NaN;

            while (!window.shouldClose()) {
                if (window.isKeyDown(GLFW_KEY_ESCAPE)) window.requestClose();
                if (window.consumeResize()) targets.resize(window.width(), window.height());
                Framebuffer target = targets.get(SCENE_TARGET);
                float delta = options.deterministic()
                        ? FIXED_DELTA_SECONDS : clock.tick().deltaSeconds();
                for (CharacterRuntime character : characters) {
                    character.update(options.scenario(), delta, constraintContext);
                }

                float tipX = pose.globalMatrix(skeleton.jointCount() - 1).m30();
                if (frame == 0) firstTipX = tipX;
                lastTipX = tipX;

                DemoSupport.perspective(projection, window.width(), window.height());
                projection.mul(view, projectionView);
                var commands = frameDriver.device().createCommandBuffer();
                commands.enableFramebufferSrgb(true).enableDepthTest(true);
                DemoSupport.beginScene(commands, target);
                material.bind(commands);
                commands.bindMesh(cube);

                for (int joint = 1; joint < skeleton.jointCount(); joint++) {
                    int parent = skeleton.joint(joint).parentIndex();
                    float length = skeleton.joint(joint).bindTransform().translation().length();
                    pose.globalMatrix(parent, model)
                            .translate(0.0f, length * 0.5f, 0.0f)
                            .scale(0.10f, length, 0.10f);
                    draw(commands, shader, cube, projectionView, model, mvp);
                }
                for (int joint = 0; joint < skeleton.jointCount(); joint++) {
                    pose.globalMatrix(joint, model).scale(0.22f);
                    draw(commands, shader, cube, projectionView, model, mvp);
                }

                commands.enableFramebufferSrgb(false)
                        .bindFramebuffer(GL_FRAMEBUFFER, 0)
                        .viewport(0, 0, window.width(), window.height())
                        .blitToDefault(target, window.width(), window.height());
                frameDriver.beginFrame();
                frameDriver.submit(commands);
                frameDriver.endFrame();
                frameDriver.present(window::swapBuffers);
                window.pollEvents();
                GlDebug.checkError("AnimationDemo.frame");

                frame++;
                if (options.maxFrames() > 0 && frame >= options.maxFrames()) {
                    window.requestClose();
                }
            }
            if (options.deterministic()
                    && (!Float.isFinite(firstTipX) || Math.abs(lastTipX - firstTipX) < 1.0e-4f)) {
                throw new IllegalStateException("Animation integration did not move the tip joint");
            }
            System.out.printf(java.util.Locale.ROOT,
                    "ANIMATION scenario=%s frames=%d characters=%d tipDelta=%.5f%n",
                    options.scenario().name().toLowerCase(java.util.Locale.ROOT),
                    frame, options.characters(), lastTipX - firstTipX);
            }
        } finally {
            for (CharacterRuntime character : characters) {
                if (character != null) character.close();
            }
        }
    }

    private static void draw(com.kaleblangley.haikalat.core.command.CommandBuffer commands,
                             ShaderProgram shader, Mesh mesh, Matrix4f projectionView,
                             Matrix4f model, Matrix4f mvp) {
        projectionView.mul(model, mvp);
        commands.setUniformMat4(shader, "uMvp", mvp).drawMesh(mesh);
    }

    private static Skeleton skeleton() {
        return new Skeleton(List.of(
                new Skeleton.Joint("root", -1, transform(0.0f, -1.35f, 0.0f)),
                new Skeleton.Joint("upper", 0, transform(0.0f, 0.9f, 0.0f)),
                new Skeleton.Joint("lower", 1, transform(0.0f, 0.9f, 0.0f)),
                new Skeleton.Joint("tip", 2, transform(0.0f, 0.75f, 0.0f))));
    }

    private static AnimationClip animation(Skeleton skeleton) {
        float[] times = {0.0f, 1.0f, 2.0f};
        return AnimationClip.builder("joint-chain", skeleton)
                .rotation(0, LINEAR, times,
                        rotation(-0.18f), rotation(0.18f), rotation(-0.18f))
                .rotation(1, LINEAR, times,
                        rotation(-0.55f), rotation(0.65f), rotation(-0.55f))
                .rotation(2, LINEAR, times,
                        rotation(0.75f), rotation(-0.45f), rotation(0.75f))
                .build();
    }

    private static AnimationClip additiveAnimation(Skeleton skeleton) {
        return AnimationClip.builder("upper-additive", skeleton)
                .rotation(1, LINEAR, new float[]{0.0f, 1.0f},
                        new Quaternionf(), new Quaternionf().rotateZ(0.35f))
                .build();
    }

    private static AnimationController controller(Skeleton skeleton) {
        ClipMotion idle = new ClipMotion(animation(skeleton));
        ClipMotion fast = new ClipMotion(AnimationClip.builder("fast", skeleton)
                .rotation(0, LINEAR, new float[]{0.0f, 1.0f},
                        rotation(-0.3f), rotation(0.3f))
                .rotation(1, LINEAR, new float[]{0.0f, 1.0f},
                        rotation(-0.7f), rotation(0.7f))
                .build());
        return AnimationGraph.builder("animation-demo", skeleton)
                .floatParameter("speed", 0.6f)
                .state("locomotion", BlendTree1D.builder("speed")
                        .child(0.0f, idle).child(1.0f, fast).build(),
                        AnimationPlayer.LoopMode.LOOP)
                .entry("locomotion").build().createController();
    }

    private static JointTransform transform(float x, float y, float z) {
        return new JointTransform(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(1.0f));
    }

    private static Quaternionf rotation(float radians) {
        return new Quaternionf().rotateZ(radians);
    }

    private enum Scenario {
        CLIP, GRAPH, ADDITIVE, CONSTRAINTS, ALL
    }

    private static final class CharacterRuntime implements AutoCloseable {
        private final PoseBuffer pose;
        private final AnimationPlayer player;
        private final AnimationController controller;
        private final AnimationLayerStack layers;
        private final AnimationConstraintStack constraints;

        private CharacterRuntime(Skeleton skeleton,
                                 AnimationConstraint.Context constraintContext) {
            pose = skeleton.createPoseBuffer();
            player = new AnimationPlayer(skeleton).play(animation(skeleton));
            controller = controller(skeleton);
            layers = new AnimationLayerStack(controller);
            layers.playAdditive("upper-additive",
                    new ClipMotion(additiveAnimation(skeleton)),
                    AnimationPlayer.LoopMode.LOOP,
                    BoneMask.builder(skeleton).subtree(1, 1.0f).build(),
                    0.35f, skeleton.createPoseBuffer().snapshot(), false);
            constraints = AnimationConstraintStack.builder(skeleton)
                    .twoBone("arm-target", 0, 1, 2, constraintContext).build();
        }

        private void update(Scenario scenario, float delta,
                            AnimationConstraint.Context constraintContext) {
            switch (scenario) {
                case CLIP -> player.update(delta, pose);
                case GRAPH -> controller.update(delta, pose);
                case ADDITIVE -> layers.update(delta, pose);
                case CONSTRAINTS -> {
                    player.update(delta, pose);
                    constraints.context("arm-target", constraintContext);
                    constraints.apply(pose);
                }
                case ALL -> {
                    layers.update(delta, pose);
                    constraints.context("arm-target", constraintContext);
                    constraints.apply(pose);
                }
            }
        }

        @Override
        public void close() {
            layers.close();
        }
    }

    private record Options(boolean deterministic, int maxFrames,
                           Scenario scenario, int characters) {
        private static Options parse(String[] arguments) {
            boolean deterministic = false;
            int maxFrames = -1;
            Scenario scenario = Scenario.CLIP;
            int characters = 1;
            for (String argument : arguments) {
                if ("--deterministic".equals(argument) || "--hidden".equals(argument)) {
                    deterministic = true;
                } else if (argument.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--scenario=")) {
                    scenario = Scenario.valueOf(argument.substring("--scenario=".length())
                            .toUpperCase(java.util.Locale.ROOT));
                } else if (argument.startsWith("--characters=")) {
                    characters = Integer.parseInt(
                            argument.substring("--characters=".length()));
                } else {
                    throw new IllegalArgumentException("Unknown AnimationDemo argument: " + argument);
                }
            }
            if (maxFrames == 0 || maxFrames < -1) {
                throw new IllegalArgumentException("--frames must be positive");
            }
            if (deterministic && maxFrames < 0) maxFrames = 8;
            if (characters <= 0) {
                throw new IllegalArgumentException("--characters must be positive");
            }
            return new Options(deterministic, maxFrames, scenario, characters);
        }
    }
}
