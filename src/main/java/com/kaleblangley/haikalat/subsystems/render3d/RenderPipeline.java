package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraph.PassExecutor;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.FrontFace;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.EnvironmentBackgroundRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewController;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewRenderer;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

public final class RenderPipeline {
    private static final int SHADOW_TEXTURE_UNIT = 7;
    private static final int POINT_SHADOW_TEXTURE_UNIT = 11;
    private static final int SPOT_SHADOW_TEXTURE_UNIT = 12;
    private static final AtomicLong PREVIEW_GENERATIONS = new AtomicLong();
    private final RenderWindow window;
    private final Scene scene;
    private final InstancedRenderer instanced;
    private final RenderSettings settings;
    private final DirectionalShadowMap directionalShadowMap = DirectionalShadowMap.defaults();
    private final PointShadowAtlas pointShadowAtlas = PointShadowAtlas.defaults();
    private final SpotShadowMap spotShadowMap = SpotShadowMap.defaults();
    private RenderGraph graph;
    private CameraUniforms cameraUniforms;
    private LightingBinder lightingBinder;
    private PostProcessPassBuilder postProcess;
    private ShaderProgram shadowShader;
    private ShaderProgram instancedShadowShader;
    private String finalPassName;
    private Matrix4f lastDirectionalLightSpaceMatrix = new Matrix4f();
    private List<Matrix4f> lastPointLightSpaceMatrices = List.of();
    private Matrix4f lastSpotLightSpaceMatrix = new Matrix4f();
    private int lastShadowCasterDrawCount;
    private int lastPointShadowCasterDrawCount;
    private int lastSpotShadowCasterDrawCount;
    private final PbrEnvironment pbrEnvironment;
    private PbrMaterialBinder pbrMaterialBinder;
    private EnvironmentBackgroundRenderer environmentBackground;
    private SceneFrameBuilder sceneFrameBuilder;
    private SceneFrame currentSceneFrame;
    private int activeFrameIndex;
    private int pipelineFrameIndex;
    private VisibilityStatistics lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;
    private final GraphPreviewController previewController = new GraphPreviewController();
    private final Set<RenderDevice> usedDevices = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Material, Boolean> frameStateInvalidationByMaterial = new IdentityHashMap<>();
    private PostProcessSettings postProcessSettings = PostProcessSettings.defaults();
    private GraphPreviewRenderer previewRenderer;

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

    /** Configures subsystem-owned optional effects before the pipeline is built. */
    public RenderPipeline postProcessSettings(PostProcessSettings value) {
        if (graph != null) {
            throw new IllegalStateException("post-process settings must be configured before build");
        }
        postProcessSettings = Objects.requireNonNull(value, "postProcessSettings");
        return this;
    }

    public void build() {
        int w = window.width();
        int h = window.height();
        closeGraphResources();

        try {
            validatePostProcessSettings();
            validatePbrVertexLayouts();
            graph = new RenderGraph(w, h);
            cameraUniforms = new CameraUniforms();
            sceneFrameBuilder = new SceneFrameBuilder();
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
            postProcess = PostProcessPassBuilder.create(
                    settings, postProcessSettings, window, w, h);
            boolean hasDirectionalShadow = LightingBinder.shadowDirectionalLight(scene).isPresent();
            boolean hasPointShadow = LightingBinder.shadowPointLight(scene).isPresent();
            boolean hasSpotShadow = LightingBinder.shadowSpotLight(scene).isPresent();
            if (hasDirectionalShadow || hasPointShadow || hasSpotShadow) {
                shadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                        "/shadows/directional_depth.vert", "/shadows/directional_depth.frag");
                if (hasDirectionalShadow && instanced != null && instanced.castShadows()) {
                    instancedShadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                            "/shadows/instanced_directional_depth.vert",
                            "/shadows/directional_depth.frag");
                }
            }

            ForwardPassBuilder.addForwardPasses(graph, settings, scene, postProcessSettings,
                    directionalShadowMap, pointShadowAtlas, spotShadowMap,
                    shadowExecutor(), pointShadowExecutor(), spotShadowExecutor(),
                    geometryExecutor());
            postProcess.addFinalPass(graph);
            finalPassName = postProcess.finalPassName();
            previewRenderer = new GraphPreviewRenderer(previewController, graph, pbrEnvironment,
                    PREVIEW_GENERATIONS.incrementAndGet());
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

    private void validatePostProcessSettings() {
        boolean colorGrading = postProcessSettings.colorGrading().enabled();
        boolean fog = postProcessSettings.fog().enabled();
        if ((colorGrading || fog) && !settings.hdrEnabled()) {
            throw new IllegalStateException("Color grading and fog require HDR tone mapping");
        }
        if (fog && settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            throw new IllegalStateException(
                    "Fog cannot sample multisampled depth until depth resolve is enabled");
        }
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

    /** @return 当前 pipeline 的逻辑预览协调器；不暴露 source native handle */
    public GraphPreviewController previewController() {
        return previewController;
    }

    /**
     * 返回在 UiOverlayPass 内、正式 UI 绘制前使用的受控 recorder。
     * 该入口不会改变 RenderGraph topology。
     */
    public PassExecutor previewOverlayRecorder() {
        GraphPreviewRenderer renderer = previewRenderer;
        if (renderer == null) {
            throw new IllegalStateException("RenderPipeline must be built before preview attachment");
        }
        return renderer.overlayRecorder();
    }

    public Matrix4f lastDirectionalLightSpaceMatrix() {
        return new Matrix4f(lastDirectionalLightSpaceMatrix);
    }

    public int lastShadowCasterDrawCount() {
        return lastShadowCasterDrawCount;
    }

    /** @return 最近一次点光六面 atlas pass 的 caster draw 总数 */
    public int lastPointShadowCasterDrawCount() {
        return lastPointShadowCasterDrawCount;
    }

    /** @return 最近一次聚光 depth pass 的 caster draw 数 */
    public int lastSpotShadowCasterDrawCount() {
        return lastSpotShadowCasterDrawCount;
    }

    /** @return 最近一次成功构建的普通 scene visibility/queue 统计 */
    public VisibilityStatistics lastVisibilityStatistics() {
        return lastVisibilityStatistics;
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
        usedDevices.add(Objects.requireNonNull(device, "device"));
        currentSceneFrame = null;
        lastShadowCasterDrawCount = 0;
        lastPointShadowCasterDrawCount = 0;
        lastSpotShadowCasterDrawCount = 0;
        lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;
        long previewFrameSequence = pipelineFrameIndex;
        activeFrameIndex = instanced == null ? pipelineFrameIndex : instanced.frameIndex();
        pipelineFrameIndex++;
        postProcess.beginFrame(deltaSeconds, scene.camera(), window.width(), window.height(),
                activeFrameIndex);
        if (previewRenderer != null) previewRenderer.prepare(device, previewFrameSequence);
        try {
            graph.execute(device);
            long commandRecordNanos = 0L;
            for (var pass : graph.lastFrameProfile().passes()) {
                commandRecordNanos = Math.addExact(commandRecordNanos, pass.cpuRecordNanos());
            }
            if (currentSceneFrame != null) {
                lastVisibilityStatistics = VisibilityStatistics.from(currentSceneFrame.statistics,
                        commandRecordNanos, graph.lastRecordedCommandCount(),
                        graph.lastRecordedMatrixSnapshots(), graph.lastRecordedObjectPayloads());
            }
            postProcess.frameSucceeded();
            if (previewRenderer != null) previewRenderer.frameSucceeded(previewFrameSequence);
        } catch (RuntimeException | Error failure) {
            postProcess.frameFailed();
            if (previewRenderer != null) previewRenderer.frameFailed(failure);
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
        GraphPreviewRenderer localPreviewRenderer = previewRenderer;
        List<RenderDevice> localDevices = List.copyOf(usedDevices);
        usedDevices.clear();
        frameStateInvalidationByMaterial.clear();
        instancedShadowShader = null;
        shadowShader = null;
        postProcess = null;
        cameraUniforms = null;
        graph = null;
        lightingBinder = null;
        pbrMaterialBinder = null;
        environmentBackground = null;
        previewRenderer = null;
        sceneFrameBuilder = null;
        currentSceneFrame = null;
        lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;
        finalPassName = null;

        RuntimeException failure = null;
        failure = closeCollecting(localPreviewRenderer, failure);
        failure = closeCollecting(localInstancedShadow, failure);
        failure = closeCollecting(localShadow, failure);
        failure = closeCollecting(localPostProcess, failure);
        failure = closeCollecting(localCameraUniforms, failure);
        failure = closeCollecting(localPbrBinder, failure);
        failure = closeCollecting(localBackground, failure);
        failure = closeCollecting(localGraph, failure);
        for (RenderDevice device : localDevices) {
            try {
                device.invalidateState();
            } catch (RuntimeException invalidateFailure) {
                if (failure == null) failure = invalidateFailure;
                else failure.addSuppressed(invalidateFailure);
            }
        }
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
        return (res, cmd) -> renderScene(cmd,
                LightingBinder.shadowDirectionalLight(scene).isPresent()
                        ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0,
                LightingBinder.shadowPointLight(scene).isPresent()
                        ? res.depthAttachment(PointShadowAtlas.TEXTURE_NAME) : 0,
                LightingBinder.shadowSpotLight(scene).isPresent()
                        ? res.depthAttachment(SpotShadowMap.TEXTURE_NAME) : 0);
    }

    private PassExecutor shadowExecutor() {
        return (res, cmd) -> LightingBinder.shadowDirectionalLight(scene).ifPresent(selection -> {
            SceneFrame frame = sceneFrame();
            cmd.bindShader(shadowShader)
                    .enableBlend(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    // 基准场景包含双面平面，因此阴影 pass 显式关闭剔除。
                    .enableCullFace(false)
                    .setUniformMat4(shadowShader, "uLightSpace", lastDirectionalLightSpaceMatrix);
            com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
            for (int queueIndex = 0; queueIndex < frame.shadowCount; queueIndex++) {
                int entry = frame.shadowEntry(queueIndex);
                MeshRenderer renderer = frame.renderer(entry);
                cmd.setUniformMat4(shadowShader, "uModel", frame.model(entry));
                SceneDrawBinding drawBinding = renderer.drawBinding();
                cmd.trySetUniformInt(shadowShader, "uSkinningEnabled",
                        drawBinding.deformsVertices() ? 1 : 0);
                drawBinding.record(cmd, shadowShader, (int) frame.frameIndex,
                        SceneDrawBinding.Pass.SHADOW);
                if (renderer.mesh() != boundMesh) {
                    cmd.bindMesh(renderer.mesh());
                    boundMesh = renderer.mesh();
                }
                cmd.drawMesh(renderer.mesh());
            }
            lastShadowCasterDrawCount = frame.shadowCount;
            if (instanced != null && instanced.castShadows()) {
                cmd.bindShader(instancedShadowShader)
                        .setUniformMat4(instancedShadowShader, "uLightSpace",
                                lastDirectionalLightSpaceMatrix);
                instanced.renderShadow(cmd);
            }
        });
    }

    private PassExecutor pointShadowExecutor() {
        return (res, cmd) -> LightingBinder.shadowPointLight(scene).ifPresent(selection -> {
            SceneFrame frame = sceneFrame();
            cmd.bindShader(shadowShader)
                    .enableBlend(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .enableCullFace(false);
            int draws = 0;
            for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                cmd.viewport(pointShadowAtlas.viewportX(face), pointShadowAtlas.viewportY(face),
                        pointShadowAtlas.settings().resolution(),
                        pointShadowAtlas.settings().resolution())
                        .setUniformMat4(shadowShader, "uLightSpace",
                                lastPointLightSpaceMatrices.get(face));
                draws += recordAllShadowCasters(cmd, frame);
            }
            lastPointShadowCasterDrawCount = draws;
        });
    }

    private PassExecutor spotShadowExecutor() {
        return (res, cmd) -> LightingBinder.shadowSpotLight(scene).ifPresent(selection -> {
            SceneFrame frame = sceneFrame();
            cmd.bindShader(shadowShader)
                    .enableBlend(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .enableCullFace(false)
                    .viewport(0, 0, spotShadowMap.settings().resolution(),
                            spotShadowMap.settings().resolution())
                    .setUniformMat4(shadowShader, "uLightSpace", lastSpotLightSpaceMatrix);
            lastSpotShadowCasterDrawCount = recordAllShadowCasters(cmd, frame);
        });
    }

    private int recordAllShadowCasters(CommandBuffer cmd, SceneFrame frame) {
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        int draws = 0;
        for (int entry = 0; entry < scene.rendererCount(); entry++) {
            MeshRenderer renderer = frame.renderer(entry);
            if (!renderer.castShadows()) continue;
            cmd.setUniformMat4(shadowShader, "uModel", frame.model(entry));
            SceneDrawBinding drawBinding = renderer.drawBinding();
            cmd.trySetUniformInt(shadowShader, "uSkinningEnabled",
                    drawBinding.deformsVertices() ? 1 : 0);
            drawBinding.record(cmd, shadowShader, (int) frame.frameIndex,
                    SceneDrawBinding.Pass.SHADOW);
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
            draws++;
        }
        return draws;
    }

    private void renderScene(CommandBuffer cmd, int shadowTexture,
                             int pointShadowTexture, int spotShadowTexture) {
        SceneFrame frame = sceneFrame();
        cameraUniforms.update(cmd, scene.camera(), window.width(), window.height(),
                settings.antiAliasingMode(), activeFrameIndex);
        if (environmentBackground != null) {
            environmentBackground.render(cmd, scene.camera(), window.width(), window.height());
        }

        ShaderProgram boundShader = null;
        MaterialInstance boundMaterial = null;
        Material boundMaterialTemplate = null;
        boolean boundMaterialHasOverrides = false;
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        boolean frontFaceBound = false;
        boolean boundMirrored = false;
        for (int queueIndex = 0; queueIndex < frame.forwardCount; queueIndex++) {
            int entry = frame.forwardEntry(queueIndex);
            MeshRenderer renderer = frame.renderer(entry);
            Matrix4f model = frame.model(entry);
            MaterialInstance material = renderer.material();
            Material materialTemplate = material.material();
            ShaderProgram shader = materialTemplate.shader();
            boolean materialHasOverrides = material.hasOverrides();
            boolean mirrored = frame.mirrored(entry);
            if (!frontFaceBound || mirrored != boundMirrored) {
                cmd.frontFace(mirrored ? FrontFace.CW : FrontFace.CCW);
                frontFaceBound = true;
                boundMirrored = mirrored;
            }
            boolean materialChanged = material != boundMaterial;
            boolean materialBindingChanged = materialChanged
                    && (boundMaterialTemplate != materialTemplate
                    || boundMaterialHasOverrides || materialHasOverrides);
            if (materialBindingChanged) {
                material.bind(cmd);
            }
            boundMaterial = material;
            boundMaterialTemplate = materialTemplate;
            boundMaterialHasOverrides = materialHasOverrides;
            if (shader != boundShader
                    || materialBindingChanged && invalidatesFrameState(material)) {
                bindFrameState(shader, cmd, shadowTexture, pointShadowTexture, spotShadowTexture);
                if (material.material().model() == MaterialModel.METALLIC_ROUGHNESS) {
                    pbrMaterialBinder.bind(shader, cmd);
                }
                boundShader = shader;
            }
            cmd.setUniformMat4(shader, "uModel", model);
            SceneDrawBinding drawBinding = renderer.drawBinding();
            cmd.trySetUniformInt(shader, "uSkinningEnabled",
                    drawBinding.deformsVertices() ? 1 : 0);
            drawBinding.record(cmd, shader, (int) frame.frameIndex,
                    SceneDrawBinding.Pass.FORWARD);
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }

        if (instanced != null) {
            cmd.bindShader(instanced.shader());
            cmd.enableBlend(false).depthMask(true).enableDepthTest(true);
            bindFrameState(instanced.shader(), cmd, shadowTexture,
                    pointShadowTexture, spotShadowTexture);
            instanced.render(cmd);
        }
    }

    private SceneFrame sceneFrame() {
        if (currentSceneFrame != null) return currentSceneFrame;
        var shadow = LightingBinder.shadowDirectionalLight(scene);
        if (shadow.isPresent()) {
            lastDirectionalLightSpaceMatrix.set(directionalShadowMap.lightSpaceMatrix(
                    shadow.orElseThrow().light(), scene.camera().position()));
        } else {
            lastDirectionalLightSpaceMatrix.identity();
        }
        var pointShadow = LightingBinder.shadowPointLight(scene);
        lastPointLightSpaceMatrices = pointShadow.isPresent()
                ? pointShadowAtlas.faceMatrices(pointShadow.orElseThrow().light()) : List.of();
        var spotShadow = LightingBinder.shadowSpotLight(scene);
        if (spotShadow.isPresent()) {
            lastSpotLightSpaceMatrix.set(
                    spotShadowMap.lightSpaceMatrix(spotShadow.orElseThrow().light()));
        } else {
            lastSpotLightSpaceMatrix.identity();
        }
        SceneFrame built = sceneFrameBuilder.build(scene, Math.max(1, window.width()),
                Math.max(1, window.height()), lastDirectionalLightSpaceMatrix,
                shadow.isPresent(), settings.sceneVisibility(), activeFrameIndex);
        currentSceneFrame = built;
        return built;
    }

    private void bindFrameState(ShaderProgram shader, CommandBuffer cmd, int shadowTexture,
                                int pointShadowTexture, int spotShadowTexture) {
        cameraUniforms.bind(shader);
        lightingBinder.bind(shader, cmd, lastDirectionalLightSpaceMatrix);
        boolean hasShadow = shadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasDirectionalShadow", hasShadow ? 1 : 0)
                .trySetUniformInt(shader, "uShadowMap", SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uShadowBias", directionalShadowMap.settings().bias());
        if (hasShadow) {
            cmd.bindTexture(SHADOW_TEXTURE_UNIT, shadowTexture);
        }
        boolean hasPointShadow = pointShadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasPointShadow", hasPointShadow ? 1 : 0)
                .trySetUniformInt(shader, "uPointShadowMap", POINT_SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uPointShadowBias", pointShadowAtlas.settings().bias());
        if (hasPointShadow) {
            for (int face = 0; face < lastPointLightSpaceMatrices.size(); face++) {
                cmd.trySetUniformMat4(shader, "uPointShadowMatrices[" + face + "]",
                        lastPointLightSpaceMatrices.get(face));
            }
            cmd.bindTexture(POINT_SHADOW_TEXTURE_UNIT, pointShadowTexture);
        }
        boolean hasSpotShadow = spotShadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasSpotShadow", hasSpotShadow ? 1 : 0)
                .trySetUniformInt(shader, "uSpotShadowMap", SPOT_SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uSpotShadowBias", spotShadowMap.settings().bias())
                .trySetUniformMat4(shader, "uSpotShadowMatrix", lastSpotLightSpaceMatrix);
        if (hasSpotShadow) cmd.bindTexture(SPOT_SHADOW_TEXTURE_UNIT, spotShadowTexture);
    }

    private boolean hasPbrMaterials() {
        for (MeshRenderer renderer : scene.forwardDrawOrder()) {
            if (renderer.material().material().model() == MaterialModel.METALLIC_ROUGHNESS) return true;
        }
        return false;
    }

    private void validatePbrVertexLayouts() {
        int rendererIndex = 0;
        for (MeshRenderer renderer : scene.renderers()) {
            if (renderer.material().material().model() == MaterialModel.METALLIC_ROUGHNESS) {
                validatePbrVertexLayout(renderer.mesh().vertexLayout(), rendererIndex);
            }
            rendererIndex++;
        }
    }

    private static void validatePbrVertexLayout(VertexLayout layout, int rendererIndex) {
        Map<VertexSemantic, int[]> contract = Map.of(
                VertexSemantic.POSITION, new int[]{0, 3},
                VertexSemantic.TEXCOORD_0, new int[]{1, 2},
                VertexSemantic.NORMAL, new int[]{2, 3},
                VertexSemantic.TANGENT, new int[]{3, 4});
        for (Map.Entry<VertexSemantic, int[]> required : contract.entrySet()) {
            VertexSemantic semantic = required.getKey();
            VertexAttribute attribute = layout.attribute(semantic).orElseThrow(() ->
                    new IllegalArgumentException("PBR renderer[" + rendererIndex
                            + "] mesh is missing " + semantic + " semantic"));
            int expectedLocation = required.getValue()[0];
            int expectedSize = required.getValue()[1];
            if (attribute.index() != expectedLocation || attribute.type() != GL_FLOAT
                    || attribute.size() != expectedSize || attribute.divisor() != 0
                    || attribute.normalized()) {
                throw new IllegalArgumentException("PBR renderer[" + rendererIndex + "] "
                        + semantic + " must be divisor-0 unnormalized GL_FLOAT vec" + expectedSize
                        + " at location " + expectedLocation);
            }
        }
    }

    /** 不暴露 renderer/queue 引用的每帧可见性值快照。 */
    public record VisibilityStatistics(boolean available, boolean cullingEnabled,
                                       long sceneRevision, int candidateRenderers,
                                       int finiteBoundsRenderers, int unboundedRenderers,
                                       int forwardVisible, int forwardCulled,
                                       int shadowCandidates, int shadowVisible,
                                       int shadowCulled, int staticRenderers,
                                       int dynamicRenderers, int modelCacheHits,
                                       int modelCacheMisses, int boundsCacheHits,
                                       int boundsCacheMisses, boolean forwardQueueReused,
                                       boolean forwardQueueRebuilt, boolean shadowQueueReused,
                                       boolean shadowQueueRebuilt, long modelUpdateNanos,
                                       long boundsTransformNanos, long frustumTestNanos,
                                       long queueSortNanos, long totalQueueBuildNanos,
                                       int opaqueDraws, int additiveDraws, int alphaDraws,
                                       int shaderChanges, int materialChanges, int meshChanges,
                                       int blendChanges, int mirroredChanges,
                                       long commandRecordNanos, int recordedCommands,
                                       int recordedMatrixSnapshots, int recordedObjectPayloads) {
        public static final VisibilityStatistics UNAVAILABLE = new VisibilityStatistics(false,
                false, 0L, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, false, false, false, false,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0);

        private static VisibilityStatistics from(SceneFrame.Statistics source,
                                                  long commandRecordNanos,
                                                  int recordedCommands,
                                                  int recordedMatrixSnapshots,
                                                  int recordedObjectPayloads) {
            return new VisibilityStatistics(true, source.cullingEnabled(), source.sceneRevision(),
                    source.candidateRenderers(), source.finiteBoundsRenderers(),
                    source.unboundedRenderers(), source.forwardVisible(), source.forwardCulled(),
                    source.shadowCandidates(), source.shadowVisible(), source.shadowCulled(),
                    source.staticRenderers(), source.dynamicRenderers(), source.modelCacheHits(),
                    source.modelCacheMisses(), source.boundsCacheHits(), source.boundsCacheMisses(),
                    source.forwardQueueReused(), source.forwardQueueRebuilt(),
                    source.shadowQueueReused(), source.shadowQueueRebuilt(),
                    source.modelUpdateNanos(), source.boundsTransformNanos(),
                    source.frustumTestNanos(), source.queueSortNanos(),
                    source.totalQueueBuildNanos(), source.opaqueDraws(), source.additiveDraws(),
                    source.alphaDraws(), source.shaderChanges(), source.materialChanges(),
                    source.meshChanges(), source.blendChanges(), source.mirroredChanges(),
                    commandRecordNanos, recordedCommands, recordedMatrixSnapshots,
                    recordedObjectPayloads);
        }
    }

    /** 仅在材质确实覆盖引擎逐帧 binding 时，才需要在材质之后重新提交 frame state。 */
    private boolean invalidatesFrameState(MaterialInstance instance) {
        Material material = instance.material();
        boolean templateInvalidates = frameStateInvalidationByMaterial.computeIfAbsent(material,
                candidate -> candidate.defaultUniforms().keySet().stream()
                        .anyMatch(key -> isFrameOwnedUniform(key.name()))
                        || candidate.defaultTextures().stream()
                        .anyMatch(binding -> isFrameOwnedTextureUnit(binding.unit())));
        if (templateInvalidates || !instance.hasOverrides()) return templateInvalidates;
        for (var key : instance.uniformOverrides().keySet()) {
            if (isFrameOwnedUniform(key.name())) return true;
        }
        for (int unit : instance.textureOverrides().keySet()) {
            if (isFrameOwnedTextureUnit(unit)) return true;
        }
        return false;
    }

    static boolean isFrameOwnedUniform(String name) {
        return name.startsWith("uDirectionalLights[")
                || name.startsWith("uPointLights[")
                || name.startsWith("uSpotLights[")
                || name.equals("uDirectionalLightCount")
                || name.equals("uPointLightCount")
                || name.equals("uSpotLightCount")
                || name.equals("uCameraPosition")
                || name.equals("uDirectionalLightSpace")
                || name.equals("uDirectionalShadowLightIndex")
                || name.equals("uPointShadowLightIndex")
                || name.equals("uSpotShadowLightIndex")
                || name.equals("uHasDirectionalShadow")
                || name.equals("uShadowMap")
                || name.equals("uShadowBias")
                || name.equals("uHasPointShadow")
                || name.equals("uPointShadowMap")
                || name.equals("uPointShadowBias")
                || name.startsWith("uPointShadowMatrices[")
                || name.equals("uHasSpotShadow")
                || name.equals("uSpotShadowMap")
                || name.equals("uSpotShadowBias")
                || name.equals("uSpotShadowMatrix")
                || name.equals("uIrradianceMap")
                || name.equals("uPrefilteredMap")
                || name.equals("uBrdfLut")
                || name.equals("uEnvironmentIntensity")
                || name.equals("uEnvironmentRotation")
                || name.equals("uPrefilterMaxLod");
    }

    private static boolean isFrameOwnedTextureUnit(int unit) {
        return unit == SHADOW_TEXTURE_UNIT
                || unit == POINT_SHADOW_TEXTURE_UNIT
                || unit == SPOT_SHADOW_TEXTURE_UNIT
                || unit == PbrMaterialBinder.IRRADIANCE_UNIT
                || unit == PbrMaterialBinder.PREFILTERED_SPECULAR_UNIT
                || unit == PbrMaterialBinder.BRDF_LUT_UNIT;
    }
}
