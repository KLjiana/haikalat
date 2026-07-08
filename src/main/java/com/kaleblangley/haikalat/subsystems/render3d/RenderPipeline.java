package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderFormat;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraph.PassExecutor;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FxaaPostProcessor;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalAccumulationPass;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RenderPipeline {
    private static final String CAMERA_BLOCK_NAME = "CameraBlock";
    private static final int CAMERA_BLOCK_BINDING = 0;
    private static final int CAMERA_BLOCK_SIZE_BYTES = 2 * 16 * Float.BYTES;

    private final RenderWindow window;
    private final Scene scene;
    private final InstancedRenderer instanced;
    private final RenderSettings settings;
    private final Set<Integer> cameraBlockPrograms = new HashSet<>();
    private final DirectionalShadowMap directionalShadowMap = DirectionalShadowMap.defaults();
    private RenderGraph graph;
    private UniformBlock cameraBlock;
    private FxaaPostProcessor fxaa;
    private TemporalAccumulationPass taa;
    private Framebuffer taaHistory;
    private Matrix4f lastDirectionalLightSpaceMatrix = new Matrix4f();
    private boolean taaHistoryValid;

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
        return passNamesFor(mode, false);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, boolean directionalShadow) {
        Objects.requireNonNull(mode, "mode");
        List<String> forwardPasses = switch (mode) {
            case NONE, MSAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.PRESENT_PASS);
            case FXAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.FXAA_PASS);
            case TAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS);
        };
        if (!directionalShadow) {
            return forwardPasses;
        }
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(DirectionalShadowMap.PASS_NAME),
                forwardPasses.stream()).toList();
    }

    public RenderPipelineKind kind() {
        return RenderPipelineKind.FORWARD;
    }

    public void build() {
        int w = window.width();
        int h = window.height();
        closeGraphResources();
        graph = new RenderGraph(w, h);
        cameraBlock = new UniformBlock(CAMERA_BLOCK_SIZE_BYTES);
        if (settings.antiAliasingMode() == AntiAliasingMode.FXAA) {
            fxaa = new FxaaPostProcessor();
        } else if (settings.antiAliasingMode() == AntiAliasingMode.TAA) {
            taa = new TemporalAccumulationPass();
            taaHistory = Framebuffer.singleSampled(w, h);
            taaHistoryValid = false;
        }

        boolean hasDirectionalShadow = scene.hasShadowCastingDirectionalLight();
        if (hasDirectionalShadow) {
            graph.addPass(DirectionalShadowMap.PASS_NAME)
                    .createDepthTexture(DirectionalShadowMap.TEXTURE_NAME)
                    .clearDepthOnly()
                    .execute(shadowExecutor());
        }

        RenderGraph.PassBuilder geometry = graph.addPass(PostProcessTargets.GEOMETRY_PASS);
        if (settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            geometry.createColorMS(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA8,
                    Math.max(2, settings.msaaSamples()));
        } else {
            geometry.createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA8);
        }
        geometry
                .createDepth()
                .clearColor(0.08f, 0.10f, 0.14f, 1.0f);
        if (hasDirectionalShadow) {
            geometry.dependsOn(DirectionalShadowMap.PASS_NAME);
        }
        geometry.execute(geometryExecutor());

        List<String> passNames = passNamesFor(settings.antiAliasingMode());
        String finalPassName = passNames.get(passNames.size() - 1);
        switch (finalPassName) {
            case PostProcessTargets.FXAA_PASS -> graph.addPass(PostProcessTargets.FXAA_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute(fxaaExecutor());
            case PostProcessTargets.TAA_PASS -> graph.addPass(PostProcessTargets.TAA_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute(taaExecutor());
            case PostProcessTargets.PRESENT_PASS -> graph.addPass(PostProcessTargets.PRESENT_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute(presentExecutor());
            default -> throw new IllegalStateException("Unknown final postprocess pass: " + finalPassName);
        }
    }

    private PassExecutor geometryExecutor() {
        return (res, cmd) -> renderScene(res, cmd);
    }

    private PassExecutor shadowExecutor() {
        return (res, cmd) -> scene.firstShadowCastingDirectionalLight().ifPresent(light ->
                lastDirectionalLightSpaceMatrix = directionalShadowMap.lightSpaceMatrix(
                        light, scene.camera().position()));
    }

    private PassExecutor presentExecutor() {
        return (res, cmd) -> {
            Framebuffer geoFb = res.getFramebuffer(PostProcessTargets.GEOMETRY_PASS);
            if (geoFb != null) {
                cmd.blitToDefault(geoFb, geoFb.width(), geoFb.height());
            }
        };
    }

    private PassExecutor fxaaExecutor() {
        return (res, cmd) -> {
            Framebuffer geoFb = res.getFramebuffer(PostProcessTargets.GEOMETRY_PASS);
            if (geoFb != null) {
                fxaa.recordIntoCurrentTarget(cmd, geoFb.colorAttachment(), geoFb.width(), geoFb.height());
            }
        };
    }

    private PassExecutor taaExecutor() {
        return (res, cmd) -> {
            Framebuffer geoFb = res.getFramebuffer(PostProcessTargets.GEOMETRY_PASS);
            if (geoFb != null && taaHistory != null) {
                float historyWeight = taaHistoryValid ? 0.90f : 0.0f;
                taa.recordIntoCurrentTarget(cmd, geoFb.colorAttachment(), taaHistory.colorAttachment(), historyWeight);
                cmd.blitFramebuffer(0, taaHistory.id(), window.width(), window.height(),
                        taaHistory.width(), taaHistory.height());
                taaHistoryValid = true;
            }
        };
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
        if (settings.antiAliasingMode() == AntiAliasingMode.TAA && w > 0 && h > 0) {
            if (taaHistory != null) {
                taaHistory.close();
            }
            taaHistory = Framebuffer.singleSampled(w, h);
            taaHistoryValid = false;
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
        if (cameraBlock != null) {
            cameraBlock.close();
            cameraBlock = null;
        }
        if (fxaa != null) {
            fxaa.close();
            fxaa = null;
        }
        if (taa != null) {
            taa.close();
            taa = null;
        }
        if (taaHistory != null) {
            taaHistory.close();
            taaHistory = null;
        }
        taaHistoryValid = false;
        cameraBlockPrograms.clear();
    }

    private void renderScene(PassResources res, CommandBuffer cmd) {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(45.0),
                window.width() / (float) Math.max(1, window.height()), 0.1f, 100.0f);
        int frameIndex = instanced == null ? 0 : instanced.frameIndex();
        applyTemporalJitter(projection, window.width(), window.height(), frameIndex);
        Matrix4f view = scene.camera().getViewMatrix();
        Matrix4f model = new Matrix4f();
        cameraBlock.setMat4(0, projection)
                .setMat4(16 * Float.BYTES, view);
        cmd.bindUniformBlock(CAMERA_BLOCK_BINDING, cameraBlock);

        for (MeshRenderer renderer : scene.renderers()) {
            renderer.modelMatrix(model, frameIndex);
            MaterialInstance material = renderer.material();
            material.bind(cmd);
            ShaderProgram shader = material.material().shader();
            bindCameraBlock(shader);
            applyLighting(shader, cmd);
            cmd.setUniformMat4(shader, "uModel", model)
                    .bindMesh(renderer.mesh())
                    .drawMesh(renderer.mesh());
        }

        if (instanced != null) {
            bindCameraBlock(instanced.shader());
            applyLighting(instanced.shader(), cmd);
            instanced.render(cmd);
        }
    }

    private void applyLighting(ShaderProgram shader, CommandBuffer cmd) {
        int directionalCount = 0;
        int pointCount = 0;
        int spotCount = 0;
        for (SceneLight light : scene.lights()) {
            switch (light.type()) {
                case DIRECTIONAL -> {
                    String prefix = "uDirectionalLights[" + directionalCount + "].";
                    cmd.trySetUniformVec3(shader, prefix + "direction", light.direction());
                    cmd.trySetUniformVec3(shader, prefix + "color", light.color());
                    cmd.trySetUniformFloat(shader, prefix + "intensity", light.intensity());
                    directionalCount++;
                }
                case POINT -> {
                    String prefix = "uPointLights[" + pointCount + "].";
                    cmd.trySetUniformVec3(shader, prefix + "position", light.position());
                    cmd.trySetUniformVec3(shader, prefix + "color", light.color());
                    cmd.trySetUniformFloat(shader, prefix + "intensity", light.intensity());
                    cmd.trySetUniformFloat(shader, prefix + "range", light.range());
                    pointCount++;
                }
                case SPOT -> {
                    String prefix = "uSpotLights[" + spotCount + "].";
                    cmd.trySetUniformVec3(shader, prefix + "position", light.position());
                    cmd.trySetUniformVec3(shader, prefix + "direction", light.direction());
                    cmd.trySetUniformVec3(shader, prefix + "color", light.color());
                    cmd.trySetUniformFloat(shader, prefix + "intensity", light.intensity());
                    cmd.trySetUniformFloat(shader, prefix + "range", light.range());
                    cmd.trySetUniformFloat(shader, prefix + "innerCone", light.innerConeRadians());
                    cmd.trySetUniformFloat(shader, prefix + "outerCone", light.outerConeRadians());
                    spotCount++;
                }
            }
        }
        cmd.trySetUniformInt(shader, "uDirectionalLightCount", directionalCount);
        cmd.trySetUniformInt(shader, "uPointLightCount", pointCount);
        cmd.trySetUniformInt(shader, "uSpotLightCount", spotCount);
        cmd.trySetUniformVec3(shader, "uCameraPosition", scene.camera().position());
        cmd.trySetUniformMat4(shader, "uDirectionalLightSpace", lastDirectionalLightSpaceMatrix);
    }

    private void bindCameraBlock(ShaderProgram shader) {
        if (cameraBlockPrograms.add(shader.id())) {
            shader.bindUniformBlock(CAMERA_BLOCK_NAME, CAMERA_BLOCK_BINDING);
        }
    }

    private void applyTemporalJitter(Matrix4f projection, int width, int height, int frameIndex) {
        if (settings.antiAliasingMode() != AntiAliasingMode.TAA || width <= 0 || height <= 0) {
            return;
        }
        float[] jitter = jitter(frameIndex);
        projection.m20(projection.m20() + (jitter[0] * 2.0f / width));
        projection.m21(projection.m21() + (jitter[1] * 2.0f / height));
    }

    private static float[] jitter(int frameIndex) {
        return switch (frameIndex & 3) {
            case 0 -> new float[]{-0.25f, -0.25f};
            case 1 -> new float[]{0.25f, -0.25f};
            case 2 -> new float[]{-0.25f, 0.25f};
            default -> new float[]{0.25f, 0.25f};
        };
    }
}
