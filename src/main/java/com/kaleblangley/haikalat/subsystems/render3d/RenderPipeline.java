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
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

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
    private SceneFrameBuilder sceneFrameBuilder;
    private SceneFrame currentSceneFrame;
    private int activeFrameIndex;
    private int pipelineFrameIndex;
    private VisibilityStatistics lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;

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
        currentSceneFrame = null;
        lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;
        activeFrameIndex = instanced == null ? pipelineFrameIndex : instanced.frameIndex();
        pipelineFrameIndex++;
        postProcess.beginFrame(deltaSeconds);
        try {
            graph.execute(device);
            long commandRecordNanos = 0L;
            for (var pass : graph.lastFrameProfile().passes()) {
                commandRecordNanos = Math.addExact(commandRecordNanos, pass.cpuRecordNanos());
            }
            lastVisibilityStatistics = lastVisibilityStatistics.withCommandEncoding(
                    commandRecordNanos, graph.lastRecordedCommandCount(),
                    graph.lastRecordedMatrixSnapshots(), graph.lastRecordedObjectPayloads());
            postProcess.frameSucceeded();
        } catch (RuntimeException | Error failure) {
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
        sceneFrameBuilder = null;
        currentSceneFrame = null;
        lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;
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
                MeshRenderer renderer = frame.shadowRenderer(queueIndex);
                cmd.setUniformMat4(shadowShader, "uModel", frame.shadowModel(queueIndex));
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

    private void renderScene(CommandBuffer cmd, int shadowTexture) {
        SceneFrame frame = sceneFrame();
        cameraUniforms.update(cmd, scene.camera(), window.width(), window.height(),
                settings.antiAliasingMode(), activeFrameIndex);
        if (environmentBackground != null) {
            environmentBackground.render(cmd, scene.camera(), window.width(), window.height());
        }

        ShaderProgram boundShader = null;
        MaterialInstance boundMaterial = null;
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        for (int queueIndex = 0; queueIndex < frame.forwardCount; queueIndex++) {
            MeshRenderer renderer = frame.forwardRenderer(queueIndex);
            Matrix4f model = frame.forwardModel(queueIndex);
            MaterialInstance material = renderer.material();
            ShaderProgram shader = material.material().shader();
            cmd.frontFace(frame.forwardMirrored(queueIndex) ? FrontFace.CW : FrontFace.CCW);
            boolean materialChanged = material != boundMaterial;
            boolean materialBindingChanged = materialChanged
                    && !sharesUnmodifiedMaterialBinding(boundMaterial, material);
            if (materialBindingChanged) {
                material.bind(cmd);
            }
            boundMaterial = material;
            if (shader != boundShader || materialBindingChanged) {
                bindFrameState(shader, cmd, shadowTexture);
                if (material.material().model() == MaterialModel.METALLIC_ROUGHNESS) {
                    pbrMaterialBinder.bind(shader, cmd);
                }
                boundShader = shader;
            }
            cmd.setUniformMat4(shader, "uModel", model);
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }

        if (instanced != null) {
            cmd.bindShader(instanced.shader());
            cmd.enableBlend(false).depthMask(true).enableDepthTest(true);
            bindFrameState(instanced.shader(), cmd, shadowTexture);
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
        SceneFrame built = sceneFrameBuilder.build(scene, Math.max(1, window.width()),
                Math.max(1, window.height()), lastDirectionalLightSpaceMatrix,
                shadow.isPresent(), settings.sceneVisibility(), activeFrameIndex);
        currentSceneFrame = built;
        lastVisibilityStatistics = VisibilityStatistics.from(built.statistics);
        return built;
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

        private static VisibilityStatistics from(SceneFrame.Statistics source) {
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
                    0L, 0, 0, 0);
        }

        private VisibilityStatistics withCommandEncoding(long nanos, int commands,
                                                         int matrices, int objects) {
            if (!available) return this;
            return new VisibilityStatistics(available, cullingEnabled, sceneRevision,
                    candidateRenderers, finiteBoundsRenderers, unboundedRenderers,
                    forwardVisible, forwardCulled, shadowCandidates, shadowVisible,
                    shadowCulled, staticRenderers, dynamicRenderers, modelCacheHits,
                    modelCacheMisses, boundsCacheHits, boundsCacheMisses,
                    forwardQueueReused, forwardQueueRebuilt, shadowQueueReused,
                    shadowQueueRebuilt, modelUpdateNanos, boundsTransformNanos,
                    frustumTestNanos, queueSortNanos, totalQueueBuildNanos, opaqueDraws,
                    additiveDraws, alphaDraws, shaderChanges, materialChanges, meshChanges,
                    blendChanges, mirroredChanges, nanos, commands, matrices, objects);
        }
    }

    /** 仅无逐对象覆盖的实例可以按同一 Material 模板安全折叠 binding。 */
    private static boolean sharesUnmodifiedMaterialBinding(MaterialInstance previous,
                                                            MaterialInstance current) {
        return previous != null
                && previous.material() == current.material()
                && previous.uniformOverrides().isEmpty()
                && previous.textureOverrides().isEmpty()
                && current.uniformOverrides().isEmpty()
                && current.textureOverrides().isEmpty();
    }
}
