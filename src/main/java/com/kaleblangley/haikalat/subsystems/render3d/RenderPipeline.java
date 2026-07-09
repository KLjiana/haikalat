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
    private final RenderWindow window;
    private final Scene scene;
    private final InstancedRenderer instanced;
    private final RenderSettings settings;
    private final DirectionalShadowMap directionalShadowMap = DirectionalShadowMap.defaults();
    private RenderGraph graph;
    private CameraUniforms cameraUniforms;
    private LightingBinder lightingBinder;
    private PostProcessPassBuilder postProcess;
    private Matrix4f lastDirectionalLightSpaceMatrix = new Matrix4f();

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

        ForwardPassBuilder.addForwardPasses(graph, settings, scene, shadowExecutor(), geometryExecutor());
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
        lightingBinder = null;
    }

    private PassExecutor geometryExecutor() {
        return (res, cmd) -> renderScene(cmd);
    }

    private PassExecutor shadowExecutor() {
        return (res, cmd) -> scene.firstShadowCastingDirectionalLight().ifPresent(light ->
                lastDirectionalLightSpaceMatrix = directionalShadowMap.lightSpaceMatrix(
                        light, scene.camera().position()));
    }

    private void renderScene(CommandBuffer cmd) {
        int frameIndex = instanced == null ? 0 : instanced.frameIndex();
        cameraUniforms.update(cmd, scene.camera(), window.width(), window.height(),
                settings.antiAliasingMode(), frameIndex);

        Matrix4f model = new Matrix4f();
        for (MeshRenderer renderer : scene.renderers()) {
            renderer.modelMatrix(model, frameIndex);
            MaterialInstance material = renderer.material();
            material.bind(cmd);
            ShaderProgram shader = material.material().shader();
            bindFrameState(shader, cmd);
            cmd.setUniformMat4(shader, "uModel", model)
                    .bindMesh(renderer.mesh())
                    .drawMesh(renderer.mesh());
        }

        if (instanced != null) {
            bindFrameState(instanced.shader(), cmd);
            instanced.render(cmd);
        }
    }

    private void bindFrameState(ShaderProgram shader, CommandBuffer cmd) {
        cameraUniforms.bind(shader);
        lightingBinder.bind(shader, cmd, lastDirectionalLightSpaceMatrix);
    }
}
