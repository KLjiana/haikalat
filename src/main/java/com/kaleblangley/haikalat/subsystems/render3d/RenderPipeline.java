package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraph.PassExecutor;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

public final class RenderPipeline {
    private static final int SHADOW_TEXTURE_UNIT = 7;
    private final RenderWindow window;
    private final Scene scene;
    private final InstancedRenderer instanced;
    private final RenderSettings settings;
    private final DirectionalShadowMap directionalShadowMap = DirectionalShadowMap.defaults();
    private RenderGraph graph;
    private CameraUniforms cameraUniforms;
    private LightingBinder lightingBinder;
    private PostProcessPassBuilder postProcess;
    private ShaderProgram shadowShader;
    private Matrix4f lastDirectionalLightSpaceMatrix = new Matrix4f();
    private int lastShadowCasterDrawCount;

    public RenderPipeline(RenderWindow window, Camera camera, List<SceneObject> sceneObjects, InstancedRenderer instanced) {
        this(window, camera, sceneObjects, instanced, RenderSettings.builder().build());
    }

    public RenderPipeline(RenderWindow window, Camera camera, List<SceneObject> sceneObjects,
                          InstancedRenderer instanced, RenderSettings settings) {
        this.window = window;
        this.scene = Scene.of(camera, sceneObjects);
        this.instanced = instanced;
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public RenderPipeline(RenderWindow window, Scene scene, InstancedRenderer instanced, RenderSettings settings) {
        this.window = Objects.requireNonNull(window, "window");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.instanced = instanced;
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public static List<String> passNamesFor(AntiAliasingMode mode) {
        return PostProcessPassBuilder.passNamesFor(mode);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, boolean directionalShadow) {
        return PostProcessPassBuilder.passNamesFor(mode, directionalShadow);
    }

    public RenderPipelineKind kind() {
        return RenderPipelineKind.FORWARD;
    }

    public void build() {
        int w = window.width();
        int h = window.height();
        closeGraphResources();

        graph = new RenderGraph(w, h);
        cameraUniforms = new CameraUniforms();
        lightingBinder = new LightingBinder(scene);
        postProcess = PostProcessPassBuilder.create(settings, window, w, h);
        if (LightingBinder.shadowDirectionalLight(scene).isPresent()) {
            shadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                    "/shadows/directional_depth.vert", "/shadows/directional_depth.frag");
        }

        ForwardPassBuilder.addForwardPasses(graph, settings, scene, directionalShadowMap,
                shadowExecutor(), geometryExecutor());
        postProcess.addFinalPass(graph);
    }

    public RenderGraph graph() {
        return graph;
    }

    public Scene scene() {
        return scene;
    }

    public Matrix4f lastDirectionalLightSpaceMatrix() {
        return new Matrix4f(lastDirectionalLightSpaceMatrix);
    }

    public int lastShadowCasterDrawCount() {
        return lastShadowCasterDrawCount;
    }

    public void execute(RenderDevice device) {
        graph.execute(device);
    }

    public void resize(int w, int h) {
        if (graph != null) {
            graph.resize(w, h);
        }
        if (postProcess != null) {
            postProcess.resize(w, h);
        }
    }

    public void close() {
        closeGraphResources();
    }

    private void closeGraphResources() {
        if (graph != null) {
            graph.close();
            graph = null;
        }
        if (cameraUniforms != null) {
            cameraUniforms.close();
            cameraUniforms = null;
        }
        if (postProcess != null) {
            postProcess.close();
            postProcess = null;
        }
        if (shadowShader != null) {
            shadowShader.close();
            shadowShader = null;
        }
        lightingBinder = null;
    }

    private PassExecutor geometryExecutor() {
        return (res, cmd) -> renderScene(cmd, LightingBinder.shadowDirectionalLight(scene).isPresent()
                ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME)
                : 0);
    }

    private PassExecutor shadowExecutor() {
        return (res, cmd) -> LightingBinder.shadowDirectionalLight(scene).ifPresent(selection -> {
            SceneLight light = selection.light();
            lastDirectionalLightSpaceMatrix = directionalShadowMap.lightSpaceMatrix(
                    light, scene.camera().position());
            int frameIndex = instanced == null ? 0 : instanced.frameIndex();
            cmd.bindShader(shadowShader)
                    .enableBlend(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    // The baseline scene uses two-sided planes, so culling is deliberately disabled.
                    .enableCullFace(false)
                    .setUniformMat4(shadowShader, "uLightSpace", lastDirectionalLightSpaceMatrix);
            Matrix4f model = new Matrix4f();
            int casterDraws = 0;
            for (MeshRenderer renderer : scene.renderers()) {
                if (!renderer.castShadows()) {
                    continue;
                }
                renderer.modelMatrix(model, frameIndex);
                cmd.setUniformMat4(shadowShader, "uModel", model)
                        .bindMesh(renderer.mesh())
                        .drawMesh(renderer.mesh());
                casterDraws++;
            }
            lastShadowCasterDrawCount = casterDraws;
        });
    }

    private void renderScene(CommandBuffer cmd, int shadowTexture) {
        int frameIndex = instanced == null ? 0 : instanced.frameIndex();
        cameraUniforms.update(cmd, scene.camera(), window.width(), window.height(),
                settings.antiAliasingMode(), frameIndex);

        Matrix4f model = new Matrix4f();
        for (MeshRenderer renderer : scene.renderers()) {
            renderer.modelMatrix(model, frameIndex);
            MaterialInstance material = renderer.material();
            material.bind(cmd);
            ShaderProgram shader = material.material().shader();
            bindFrameState(shader, cmd, shadowTexture);
            cmd.setUniformMat4(shader, "uModel", model)
                    .bindMesh(renderer.mesh())
                    .drawMesh(renderer.mesh());
        }

        if (instanced != null) {
            cmd.bindShader(instanced.shader());
            cmd.enableBlend(false).depthMask(true).enableDepthTest(true);
            bindFrameState(instanced.shader(), cmd, shadowTexture);
            instanced.render(cmd);
        }
    }

    private void bindFrameState(ShaderProgram shader, CommandBuffer cmd, int shadowTexture) {
        cameraUniforms.bind(shader);
        lightingBinder.bind(shader, cmd, lastDirectionalLightSpaceMatrix);
        boolean hasShadow = shadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasDirectionalShadow", hasShadow ? 1 : 0)
                .trySetUniformInt(shader, "uShadowMap", SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uShadowBias", directionalShadowMap.settings().bias());
        if (hasShadow) {
            cmd.bindTexture(SHADOW_TEXTURE_UNIT, shadowTexture);
        }
    }
}
