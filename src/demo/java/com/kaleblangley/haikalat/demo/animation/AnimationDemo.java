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
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
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
        PoseBuffer pose = skeleton.createPoseBuffer();
        AnimationPlayer player = new AnimationPlayer(skeleton).play(animation(skeleton));
        FrameClock clock = new FrameClock();

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
                player.update(delta, pose);

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

    private static JointTransform transform(float x, float y, float z) {
        return new JointTransform(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(1.0f));
    }

    private static Quaternionf rotation(float radians) {
        return new Quaternionf().rotateZ(radians);
    }

    private record Options(boolean deterministic, int maxFrames) {
        private static Options parse(String[] arguments) {
            boolean deterministic = false;
            int maxFrames = -1;
            for (String argument : arguments) {
                if ("--deterministic".equals(argument) || "--hidden".equals(argument)) {
                    deterministic = true;
                } else if (argument.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(argument.substring("--frames=".length()));
                } else {
                    throw new IllegalArgumentException("Unknown AnimationDemo argument: " + argument);
                }
            }
            if (maxFrames == 0 || maxFrames < -1) {
                throw new IllegalArgumentException("--frames must be positive");
            }
            if (deterministic && maxFrames < 0) maxFrames = 8;
            return new Options(deterministic, maxFrames);
        }
    }
}
