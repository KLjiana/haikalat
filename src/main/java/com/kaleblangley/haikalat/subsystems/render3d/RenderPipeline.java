package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraph.PassExecutor;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.EnvironmentBackgroundRenderer;
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
    private ShaderProgram instancedShadowShader;
    private String finalPassName;
    private Matrix4f lastDirectionalLightSpaceMatrix = new Matrix4f();
    private int lastShadowCasterDrawCount;
    private final PbrEnvironment pbrEnvironment;
    private PbrMaterialBinder pbrMaterialBinder;
    private EnvironmentBackgroundRenderer environmentBackground;

    public RenderPipeline(RenderWindow window, Camera camera, List<SceneObject> sceneObjects, InstancedRenderer instanced) {
        this(window, camera, sceneObjects, instanced, RenderSettings.builder().build(), null);
    }

    public RenderPipeline(RenderWindow window, Camera camera, List<SceneObject> sceneObjects,
                          InstancedRenderer instanced, RenderSettings settings) {
        this(window, camera, sceneObjects, instanced, settings, null);
    }

    public RenderPipeline(RenderWindow window, Camera camera, List<SceneObject> sceneObjects,
                          InstancedRenderer instanced, RenderSettings settings,
                          PbrEnvironment pbrEnvironment) {
        this.window = window;
        this.scene = Scene.of(camera, sceneObjects);
        this.instanced = instanced;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.pbrEnvironment = pbrEnvironment;
    }

    public RenderPipeline(RenderWindow window, Scene scene, InstancedRenderer instanced, RenderSettings settings) {
        this(window, scene, instanced, settings, null);
    }

    public RenderPipeline(RenderWindow window, Scene scene, InstancedRenderer instanced,
                          RenderSettings settings, PbrEnvironment pbrEnvironment) {
        this.window = Objects.requireNonNull(window, "window");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.instanced = instanced;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.pbrEnvironment = pbrEnvironment;
    }

    public static List<String> passNamesFor(AntiAliasingMode mode) {
        return PostProcessPassBuilder.passNamesFor(mode);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, boolean directionalShadow) {
        return PostProcessPassBuilder.passNamesFor(mode, directionalShadow);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode) {
        return PostProcessPassBuilder.passNamesFor(mode, toneMappingMode);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                            boolean directionalShadow) {
        return PostProcessPassBuilder.passNamesFor(mode, toneMappingMode, directionalShadow);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                            BloomSettings bloomSettings, boolean directionalShadow) {
        return PostProcessPassBuilder.passNamesFor(
                mode, toneMappingMode, bloomSettings, directionalShadow);
    }

    public RenderPipelineKind kind() {
        return RenderPipelineKind.FORWARD;
    }

    public void build() {
        int w = window.width();
        int h = window.height();
        closeGraphResources();

        try {
            graph = new RenderGraph(w, h);
            cameraUniforms = new CameraUniforms();
            lightingBinder = new LightingBinder(scene);
            if (hasPbrMaterials()) {
                if (!settings.hdrEnabled()) {
                    throw new IllegalStateException("metallic-roughness PBR requires HDR/ACES output");
                }
                if (pbrEnvironment == null) {
                    throw new IllegalStateException("PBR scene requires an explicit borrowed PbrEnvironment");
                }
                pbrMaterialBinder = new PbrMaterialBinder(pbrEnvironment);
                environmentBackground = new EnvironmentBackgroundRenderer(pbrEnvironment);
            }
            postProcess = PostProcessPassBuilder.create(settings, window, w, h);
            if (LightingBinder.shadowDirectionalLight(scene).isPresent()) {
                shadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                        "/shadows/directional_depth.vert", "/shadows/directional_depth.frag");
                if (instanced != null && instanced.castShadows()) {
                    instancedShadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                            "/shadows/instanced_directional_depth.vert",
                            "/shadows/directional_depth.frag");
                }
            }

            ForwardPassBuilder.addForwardPasses(graph, settings, scene, directionalShadowMap,
                    shadowExecutor(), geometryExecutor());
            postProcess.addFinalPass(graph);
            finalPassName = postProcess.finalPassName();
        } catch (RuntimeException failure) {
            try {
                closeGraphResources();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public RenderGraph graph() {
        return graph;
    }

    /**
     * 返回当前已构建管线最终写入 backbuffer 的 pass 名称。
     *
     * @return 可供兄弟 subsystem 追加 overlay 的稳定组合锚点
     * @throws IllegalStateException 管线尚未成功 build 或已经 close 时抛出
     */
    public String finalPassName() {
        String passName = finalPassName;
        if (passName == null) {
            throw new IllegalStateException(
                    "RenderPipeline must be built and open before querying finalPassName");
        }
        return passName;
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

    /** @return 最近一次 shadow pass 绘制的实例 caster 数量 */
    public int lastInstancedShadowCasterCount() {
        return instanced == null ? 0 : instanced.shadowDrawnCount();
    }

    public void execute(RenderDevice device) {
        execute(device, 1.0f / 60.0f);
    }

    /**
     * 执行一帧，并把受上限保护的真实帧间隔交给跨帧后处理。
     *
     * @param device       渲染设备
     * @param deltaSeconds 本帧秒数，必须有限且非负
     */
    public void execute(RenderDevice device, float deltaSeconds) {
        if (graph == null || postProcess == null) {
            throw new IllegalStateException("RenderPipeline must be built before execute");
        }
        postProcess.beginFrame(deltaSeconds);
        try {
            graph.execute(device);
            postProcess.frameSucceeded();
        } catch (RuntimeException failure) {
            postProcess.frameFailed();
            throw failure;
        }
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
        ShaderProgram localInstancedShadow = instancedShadowShader;
        ShaderProgram localShadow = shadowShader;
        PostProcessPassBuilder localPostProcess = postProcess;
        CameraUniforms localCameraUniforms = cameraUniforms;
        RenderGraph localGraph = graph;
        PbrMaterialBinder localPbrBinder = pbrMaterialBinder;
        EnvironmentBackgroundRenderer localBackground = environmentBackground;
        instancedShadowShader = null;
        shadowShader = null;
        postProcess = null;
        cameraUniforms = null;
        graph = null;
        lightingBinder = null;
        pbrMaterialBinder = null;
        environmentBackground = null;
        finalPassName = null;

        RuntimeException failure = null;
        failure = closeCollecting(localInstancedShadow, failure);
        failure = closeCollecting(localShadow, failure);
        failure = closeCollecting(localPostProcess, failure);
        failure = closeCollecting(localCameraUniforms, failure);
        failure = closeCollecting(localPbrBinder, failure);
        failure = closeCollecting(localBackground, failure);
        failure = closeCollecting(localGraph, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeCollecting(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception cleanupFailure) {
            if (failure == null) {
                return cleanupFailure instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException("Failed to close render resource", cleanupFailure);
            }
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
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
                    // 基准场景包含双面平面，因此阴影 pass 显式关闭剔除。
                    .enableCullFace(false)
                    .setUniformMat4(shadowShader, "uLightSpace", lastDirectionalLightSpaceMatrix);
            Matrix4f model = new Matrix4f();
            int casterDraws = 0;
            for (MeshRenderer renderer : scene.shadowDrawOrder()) {
                renderer.modelMatrix(model, frameIndex);
                cmd.setUniformMat4(shadowShader, "uModel", model)
                        .bindMesh(renderer.mesh())
                        .drawMesh(renderer.mesh());
                casterDraws++;
            }
            lastShadowCasterDrawCount = casterDraws;
            if (instanced != null && instanced.castShadows()) {
                cmd.bindShader(instancedShadowShader)
                        .setUniformMat4(instancedShadowShader, "uLightSpace",
                                lastDirectionalLightSpaceMatrix);
                instanced.renderShadow(cmd);
            }
        });
    }

    private void renderScene(CommandBuffer cmd, int shadowTexture) {
        int frameIndex = instanced == null ? 0 : instanced.frameIndex();
        cameraUniforms.update(cmd, scene.camera(), window.width(), window.height(),
                settings.antiAliasingMode(), frameIndex);
        if (environmentBackground != null) {
            environmentBackground.render(cmd, scene.camera(), window.width(), window.height());
        }

        Matrix4f model = new Matrix4f();
        for (MeshRenderer renderer : scene.forwardDrawOrder()) {
            renderer.modelMatrix(model, frameIndex);
            MaterialInstance material = renderer.material();
            material.bind(cmd);
            ShaderProgram shader = material.material().shader();
            bindFrameState(shader, cmd, shadowTexture);
            if (material.material().model() == MaterialModel.METALLIC_ROUGHNESS) {
                pbrMaterialBinder.bind(shader, cmd);
            }
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

    private boolean hasPbrMaterials() {
        for (MeshRenderer renderer : scene.forwardDrawOrder()) {
            if (renderer.material().material().model() == MaterialModel.METALLIC_ROUGHNESS) return true;
        }
        return false;
    }
}
