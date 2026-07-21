package com.kaleblangley.haikalat.subsystems.render3d.preview;

import com.kaleblangley.haikalat.backend.GlFormats;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.TextureCube;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.diagnostics.PreviewSummary;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30.GL_COLOR_BUFFER_BIT;

/**
 * RenderGraph 预览专用 GPU owner。
 *
 * <p>对象本身不分配 GL 资源；只有 DETAILED 面板可见、选中有效资源并处于 live
 * 状态时，才在 render thread 惰性创建转换目标。UI 与 diagnostics 只接收逻辑键和
 * {@link PreviewSummary}，不会接触被检查资源的 native handle。</p>
 */
public final class GraphPreviewRenderer implements AutoCloseable {
    static final int MAX_OUTPUT_EDGE = 512;
    static final long GPU_BUDGET_BYTES = 8L * 1024L * 1024L;
    private static final int TEXTURE_2D_UNIT = 12;
    private static final int TEXTURE_CUBE_UNIT = 13;
    private static final String ENVIRONMENT = "environment";
    private static final String IRRADIANCE = "irradiance";
    private static final String PREFILTERED = "prefiltered";

    private final GraphPreviewController controller;
    private final RenderGraph graph;
    private final PbrEnvironment environment;
    private final long pipelineGeneration;
    private final long ownerGeneration;
    private RenderGraph.Description catalogDescription;
    private PreviewSourceCatalog catalog;
    private boolean catalogEnvironmentOpen;
    private ShaderProgram shader;
    private ScreenQuad quad;
    private Sampler linear2d;
    private Sampler nearest2d;
    private Sampler linearCube;
    private Sampler nearestCube;
    private Framebuffer output;
    private Framebuffer resolve;
    private ResourceSignature resourceSignature;
    private Prepared prepared;
    private Prepared submitted;
    private boolean submittedThisFrame;
    private int submittedDraws;
    private int submittedBlits;
    private long lastSuccessfulFrame = -1L;
    private String lastErrorCode = "";
    private String lastErrorMessage = "";
    private long publishedInactiveRevision = Long.MIN_VALUE;
    private boolean closed;

    public GraphPreviewRenderer(GraphPreviewController controller, RenderGraph graph,
                                PbrEnvironment environment, long pipelineGeneration) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.environment = environment;
        if (pipelineGeneration <= 0L) {
            throw new IllegalArgumentException("pipelineGeneration must be positive");
        }
        this.pipelineGeneration = pipelineGeneration;
        ownerGeneration = pipelineGeneration;
    }

    /** 在 graph 录制前刷新纯值目录并完成惰性资源生命周期变更。 */
    public void prepare(RenderDevice device, long frameSequence) {
        ensureOpen();
        Objects.requireNonNull(device, "device");
        prepared = null;
        submitted = null;
        submittedThisFrame = false;
        submittedDraws = 0;
        submittedBlits = 0;

        GraphPreviewController.Request request = controller.request();
        if (!request.detailed() || !request.panelVisible()) {
            releaseGpuResources(device);
            if (publishedInactiveRevision != request.revision()) {
                controller.publishSummary(request.revision(),
                        summary(request, "IDLE", 0, 0, "", 0L));
                publishedInactiveRevision = request.revision();
            }
            return;
        }
        publishedInactiveRevision = Long.MIN_VALUE;
        refreshCatalog();
        if (request.key() == null) {
            releaseGpuResources(device);
            controller.publishSummary(request.revision(),
                    summary(request, "IDLE", 0, 0, "", 0L));
            return;
        }

        PreviewSourceDescription source = catalog.find(request.key()).orElse(null);
        if (source == null || request.key().pipelineGeneration() != pipelineGeneration) {
            releaseGpuResources(device);
            fail(request, "STALE", "STALE_SELECTION",
                    "selected resource belongs to an old pipeline generation", 0L);
            return;
        }
        if (!source.previewable()) {
            releaseGpuResources(device);
            fail(request, source.availability().name(), "UNSUPPORTED_SOURCE", source.reason(), 0L);
            return;
        }
        if (request.options().mipLevel() >= source.mipCount()) {
            releaseGpuResources(device);
            fail(request, "UNSUPPORTED", "INVALID_MIP",
                    "mip " + request.options().mipLevel() + " is outside [0, "
                            + (source.mipCount() - 1) + "]", 0L);
            return;
        }
        if (source.aspect() == PreviewAspect.CUBE && resolveCube(request.key()) == null) {
            releaseGpuResources(device);
            fail(request, "STALE", "CUBE_OWNER_CLOSED", "cubemap owner is no longer available", 0L);
            return;
        }

        ResourceSignature signature;
        try {
            signature = signature(source, request.options());
        } catch (RuntimeException failure) {
            releaseGpuResources(device);
            fail(request, "UNSUPPORTED", "PREVIEW_BUDGET", failure.getMessage(), 0L);
            return;
        }

        if (request.frozen() || request.paused()) {
            controller.publishSummary(request.revision(), summary(request,
                    request.frozen() ? "FROZEN_METADATA" : "PAUSED",
                    output == null ? 0 : output.width(), output == null ? 0 : output.height(),
                    resourceSignature == null ? "" : resourceSignature.path(),
                    resourceSignature == null ? 0L : resourceSignature.estimatedBytes()));
            return;
        }

        try {
            ensureGpuResources(device, signature);
        } catch (RuntimeException | Error failure) {
            releaseGpuResources(device);
            fail(request, "FAILED", "RESOURCE_CREATE_FAILED", safeMessage(failure), 0L);
            return;
        }
        if (Math.floorMod(frameSequence, request.options().updateInterval()) != 0L) {
            controller.publishSummary(request.revision(), summary(request, "THROTTLED",
                    output.width(), output.height(), signature.path(), signature.estimatedBytes()));
            return;
        }
        prepared = new Prepared(request, source, signature);
        controller.publishSummary(request.revision(), summary(request, "READY",
                output.width(), output.height(), signature.path(), signature.estimatedBytes()));
    }

    /** 在 UiOverlayPass 内、UI 绘制前记录最多一次 resolve 和一次 conversion draw。 */
    public void record(PassResources resources, CommandBuffer commands) {
        Prepared value = prepared;
        if (value == null) return;
        if (controller.request().revision() != value.request().revision()) {
            prepared = null;
            return;
        }
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(commands, "commands");

        ResolvedSource source;
        try {
            source = resolveLiveSource(resources, value.source());
            validateNoFeedback(source);
        } catch (RuntimeException failure) {
            fail(value.request(), "FAILED", "SOURCE_RESOLVE_FAILED", safeMessage(failure),
                    value.signature().estimatedBytes());
            prepared = null;
            controller.clearOutput();
            return;
        }

        int sampled2d = source.texture2d();
        int blits = 0;
        if (value.signature().resolve()) {
            if (value.source().aspect() == PreviewAspect.DEPTH) {
                commands.blitDepth(source.framebuffer(), resolve);
                sampled2d = resolve.depthAttachment();
            } else {
                commands.blitFramebuffer(source.framebuffer().id(), resolve.id(),
                        source.framebuffer().width(), source.framebuffer().height(),
                        resolve.width(), resolve.height(), GL_COLOR_BUFFER_BIT, GL_NEAREST,
                        source.colorAttachment());
                sampled2d = resolve.colorAttachment();
            }
            blits = 1;
        }

        PreviewOptions options = value.request().options();
        Sampler sourceSampler = options.filtering() == PreviewOptions.Filtering.NEAREST
                ? nearest2d : linear2d;
        commands.bindFramebuffer(output)
                .viewport(0, 0, output.width(), output.height())
                .enableScissor(false)
                .enableBlend(false)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(true)
                .bindShader(shader)
                .setUniformInt(shader, "uTexture2D", TEXTURE_2D_UNIT)
                .setUniformInt(shader, "uTextureCube", TEXTURE_CUBE_UNIT)
                .setUniformInt(shader, "uSourceKind", source.cube() == null ? 0 : 1)
                .setUniformInt(shader, "uMode", modeCode(value.source(), options))
                .setUniformInt(shader, "uChannel", options.channel().ordinal())
                .setUniformFloat(shader, "uExposureEv", options.exposureEv())
                .setUniformVec2(shader, "uRange", options.rangeMin(), options.rangeMax())
                .setUniformInt(shader, "uFalseColor", options.falseColor() ? 1 : 0)
                .setUniformInt(shader, "uDepthInterpretation", options.depthInterpretation().ordinal())
                .setUniformVec2(shader, "uNearFar", options.nearPlane(), options.farPlane())
                .setUniformInt(shader, "uInvertDepth", options.invertDepth() ? 1 : 0)
                .setUniformInt(shader, "uCubeFace", options.cubeFace().ordinal())
                .setUniformInt(shader, "uMipLevel", options.mipLevel())
                .setUniformInt(shader, "uCheckerboard", options.checkerboard() ? 1 : 0);
        if (source.cube() == null) {
            commands.bindTexture(TEXTURE_2D_UNIT, sampled2d).bindSampler(TEXTURE_2D_UNIT, sourceSampler);
        } else {
            Sampler cubeSampler = options.filtering() == PreviewOptions.Filtering.NEAREST
                    ? nearestCube : linearCube;
            commands.bindTextureCube(TEXTURE_CUBE_UNIT, source.cube(), cubeSampler);
        }
        commands.bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .bindDefaultFramebuffer()
                .viewport(0, 0, graph.width(), graph.height())
                .enableScissor(false)
                .enableBlend(false)
                .enableDepthTest(false)
                .depthMask(true)
                .enableCullFace(false)
                .enableFramebufferSrgb(false);
        submittedThisFrame = true;
        submitted = value;
        submittedDraws = 1;
        submittedBlits = blits;
    }

    /** 仅在整张 graph 成功提交后发布 preview output，避免把旧像素冒充失败帧。 */
    public void frameSucceeded(long frameSequence) {
        Prepared value = submitted;
        if (!submittedThisFrame || value == null || output == null || resourceSignature == null) return;
        lastSuccessfulFrame = frameSequence;
        lastErrorCode = "";
        lastErrorMessage = "";
        PreviewOptions options = value.request().options();
        Sampler outputSampler = options.filtering() == PreviewOptions.Filtering.NEAREST
                ? nearest2d : linear2d;
        controller.publishOutput(value.request().revision(), output.colorAttachment(), outputSampler.id(),
                output.width(), output.height());
        controller.publishSummary(value.request().revision(), summary(value.request(), "LIVE",
                output.width(), output.height(), resourceSignature.path(),
                resourceSignature.estimatedBytes()));
    }

    /** graph 任一 pass/command 失败时立即撤销 UI 映射。 */
    public void frameFailed(Throwable failure) {
        controller.clearOutput();
        lastErrorCode = "GRAPH_FRAME_FAILED";
        lastErrorMessage = safeMessage(failure);
        controller.reportError(lastErrorCode, lastErrorMessage);
        GraphPreviewController.Request request = controller.request();
        controller.publishSummary(request.revision(), summary(request, "FAILED",
                output == null ? 0 : output.width(), output == null ? 0 : output.height(),
                resourceSignature == null ? "" : resourceSignature.path(),
                resourceSignature == null ? 0L : resourceSignature.estimatedBytes()));
    }

    public RenderGraph.PassExecutor overlayRecorder() {
        return this::record;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        controller.clearOutput();
        closeGpuResources();
        prepared = null;
        catalog = null;
        catalogDescription = null;
        GraphPreviewController.Request request = controller.request();
        controller.publishSummary(request.revision(), PreviewSummary.idle());
    }

    private void refreshCatalog() {
        RenderGraph.Description description = graph.description();
        boolean environmentOpen = environment != null && !environment.isClosed();
        if (description == catalogDescription && environmentOpen == catalogEnvironmentOpen
                && catalog != null) return;
        List<PreviewSourceCatalog.RegisteredCube> cubes = environmentOpen
                ? registeredCubes() : List.of();
        catalog = PreviewSourceCatalog.from(description, pipelineGeneration, cubes);
        catalogDescription = description;
        catalogEnvironmentOpen = environmentOpen;
        controller.publishCatalog(catalog);
    }

    private List<PreviewSourceCatalog.RegisteredCube> registeredCubes() {
        ArrayList<PreviewSourceCatalog.RegisteredCube> result = new ArrayList<>(3);
        addCube(result, ENVIRONMENT, "PBR environment", environment.radiance());
        addCube(result, IRRADIANCE, "PBR irradiance", environment.irradiance());
        addCube(result, PREFILTERED, "PBR prefiltered specular", environment.prefilteredSpecular());
        return List.copyOf(result);
    }

    private void addCube(List<PreviewSourceCatalog.RegisteredCube> target, String name,
                         String displayName, TextureCube cube) {
        target.add(new PreviewSourceCatalog.RegisteredCube(name, displayName, ownerGeneration,
                cube.size(), cube.mipLevels(), cube.format().name(), estimateCubeBytes(cube)));
    }

    private ResourceSignature signature(PreviewSourceDescription source, PreviewOptions options) {
        int sourceWidth = source.aspect() == PreviewAspect.CUBE
                ? Math.max(1, source.width() >> options.mipLevel()) : source.width();
        int sourceHeight = source.aspect() == PreviewAspect.CUBE
                ? sourceWidth : source.height();
        boolean managedResolve = usesManagedResolve(source);
        long resolveBytes = source.requiresResolve() && !managedResolve
                ? PreviewSourceCatalog.estimateBytes(source.format(), source.width(), source.height(), 1)
                : 0L;
        if (resolveBytes >= GPU_BUDGET_BYTES) {
            throw new IllegalStateException("single-sample resolve target exceeds 8 MiB preview budget");
        }
        int[] outputSize = outputSize(sourceWidth, sourceHeight, GPU_BUDGET_BYTES - resolveBytes);
        long outputBytes = Math.multiplyExact((long) outputSize[0] * outputSize[1], 4L);
        long estimated = Math.addExact(resolveBytes, outputBytes);
        String path = managedResolve ? "MANAGED_COLOR_RESOLVE+CONVERT" : source.requiresResolve()
                ? (source.aspect() == PreviewAspect.DEPTH ? "DEPTH_RESOLVE+CONVERT"
                : "COLOR_RESOLVE+CONVERT")
                : source.aspect() == PreviewAspect.CUBE ? "CUBE_CONVERT" : "DIRECT_CONVERT";
        return new ResourceSignature(source.key(), outputSize[0], outputSize[1],
                source.requiresResolve() && !managedResolve,
                source.width(), source.height(), source.format(),
                source.aspect(), path, estimated);
    }

    private static int[] outputSize(int width, int height, long availableBytes) {
        if (width <= 0 || height <= 0 || availableBytes < 4L) {
            throw new IllegalStateException("preview output has no available storage budget");
        }
        double scale = Math.min(1.0, MAX_OUTPUT_EDGE / (double) Math.max(width, height));
        long maximumPixels = availableBytes / 4L;
        long scaledPixels = Math.max(1L, Math.round(width * scale) * Math.round(height * scale));
        if (scaledPixels > maximumPixels) {
            scale = Math.sqrt(maximumPixels / ((double) width * height));
        }
        int outputWidth = Math.max(1, (int) Math.floor(width * scale));
        int outputHeight = Math.max(1, (int) Math.floor(height * scale));
        while ((long) outputWidth * outputHeight > maximumPixels) {
            if (outputWidth >= outputHeight && outputWidth > 1) outputWidth--;
            else if (outputHeight > 1) outputHeight--;
            else throw new IllegalStateException("preview output exceeds storage budget");
        }
        return new int[]{outputWidth, outputHeight};
    }

    private void ensureGpuResources(RenderDevice device, ResourceSignature signature) {
        if (signature.equals(resourceSignature) && output != null) return;
        closeGpuResources();
        try {
            shader = ShaderProgram.fromResource(GraphPreviewRenderer.class,
                    "/render3d/preview/preview.vert", "/render3d/preview/preview.frag");
            quad = new ScreenQuad();
            linear2d = Sampler.create(new Sampler.Descriptor(GL_LINEAR, GL_LINEAR,
                    GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            nearest2d = Sampler.create(new Sampler.Descriptor(GL_NEAREST, GL_NEAREST,
                    GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            linearCube = Sampler.create(new Sampler.Descriptor(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                    GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            nearestCube = Sampler.create(new Sampler.Descriptor(GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST,
                    GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            output = Framebuffer.fromDescriptor(FramebufferDescriptor.colorOnly(
                    signature.outputWidth(), signature.outputHeight(), GL_SRGB8_ALPHA8));
            if (signature.resolve()) {
                resolve = signature.aspect() == PreviewAspect.DEPTH
                        ? depthResolveTarget(signature)
                        : Framebuffer.fromDescriptor(FramebufferDescriptor.colorOnly(
                        signature.resolveWidth(), signature.resolveHeight(),
                        GlFormats.toGl(RenderFormat.valueOf(signature.format()))));
            }
            resourceSignature = signature;
            device.invalidateState();
        } catch (RuntimeException | Error failure) {
            closeGpuResources();
            device.invalidateState();
            throw failure;
        }
    }

    private ResolvedSource resolveLiveSource(PassResources resources,
                                             PreviewSourceDescription description) {
        if (description.aspect() == PreviewAspect.CUBE) {
            TextureCube cube = resolveCube(description.key());
            if (cube == null) throw new IllegalStateException("cubemap owner is closed or replaced");
            return new ResolvedSource(0, -1, null, cube);
        }
        if (usesManagedResolve(description)) {
            Framebuffer managed = resources.framebufferOfPass("HdrResolvePass");
            if (managed == null || managed.isClosed() || !managed.colorAttachmentIsTexture(0)) {
                throw new IllegalStateException("managed HDR resolve target is unavailable");
            }
            return new ResolvedSource(managed.colorAttachment(0), 0, managed, null);
        }
        Framebuffer framebuffer = resources.framebufferOfPass(description.producerPass());
        if (framebuffer == null || framebuffer.isClosed()) {
            throw new IllegalStateException("producer framebuffer is unavailable");
        }
        int colorAttachment = description.aspect() == PreviewAspect.COLOR
                ? colorAttachmentIndex(description) : -1;
        if (description.requiresResolve()) {
            return new ResolvedSource(0, colorAttachment, framebuffer, null);
        }
        if (description.aspect() == PreviewAspect.DEPTH) {
            if (!framebuffer.depthAttachmentIsTexture()) {
                throw new IllegalStateException("depth attachment is not sampleable");
            }
            return new ResolvedSource(framebuffer.depthAttachment(), -1, framebuffer, null);
        }
        int index = colorAttachment;
        if (index < 0 || !framebuffer.colorAttachmentIsTexture(index)) {
            throw new IllegalStateException("color attachment is not sampleable");
        }
        return new ResolvedSource(framebuffer.colorAttachment(index), index, framebuffer, null);
    }

    private boolean usesManagedResolve(PreviewSourceDescription source) {
        if (!source.requiresResolve() || source.aspect() != PreviewAspect.COLOR
                || !source.producerPass().equals("GeometryPass")
                || !source.key().attachmentName().equals("sceneColor")
                || !source.format().equals("RGBA16F")) return false;
        return graph.description().passes().stream().anyMatch(pass ->
                pass.name().equals("HdrResolvePass")
                        && pass.colorAttachments().stream().anyMatch(attachment ->
                        attachment.logicalName().equals("hdrResolvedColor")
                                && attachment.format().equals("RGBA16F")));
    }

    private int colorAttachmentIndex(PreviewSourceDescription description) {
        RenderGraph.PassDescription pass = graph.description().passes().stream()
                .filter(value -> value.name().equals(description.producerPass()))
                .findFirst().orElseThrow(() -> new IllegalStateException("producer pass disappeared"));
        int index = -1;
        for (int candidate = 0; candidate < pass.colorAttachments().size(); candidate++) {
            if (pass.colorAttachments().get(candidate).logicalName()
                    .equals(description.key().attachmentName())) {
                index = candidate;
                break;
            }
        }
        if (index < 0) throw new IllegalStateException("color attachment disappeared");
        return index;
    }

    private static Framebuffer depthResolveTarget(ResourceSignature signature) {
        FramebufferDescriptor.Builder builder = FramebufferDescriptor.builder(
                signature.resolveWidth(), signature.resolveHeight());
        if (signature.format().equals("DEPTH24_STENCIL8")) builder.depthStencilTexture();
        else builder.depthTexture();
        return Framebuffer.fromDescriptor(builder.build());
    }

    private TextureCube resolveCube(PreviewSourceKey key) {
        if (environment == null || environment.isClosed()
                || key.ownerGeneration() != ownerGeneration) return null;
        return switch (key.registeredName()) {
            case ENVIRONMENT -> environment.radiance();
            case IRRADIANCE -> environment.irradiance();
            case PREFILTERED -> environment.prefilteredSpecular();
            default -> null;
        };
    }

    private void validateNoFeedback(ResolvedSource source) {
        if (output == null) throw new IllegalStateException("preview output is unavailable");
        if (source.texture2d() != 0 && source.texture2d() == output.colorAttachment()) {
            throw new IllegalStateException("source texture aliases preview output");
        }
        if (source.framebuffer() != null && source.framebuffer().id() == output.id()) {
            throw new IllegalStateException("source framebuffer aliases preview output");
        }
        if (resolve != null && source.framebuffer() != null
                && source.framebuffer().id() == resolve.id()) {
            throw new IllegalStateException("resolve source and destination framebuffer alias");
        }
    }

    private int modeCode(PreviewSourceDescription source, PreviewOptions options) {
        if (source.aspect() == PreviewAspect.DEPTH || options.mode() == PreviewOptions.Mode.DEPTH) return 2;
        if (options.mode() == PreviewOptions.Mode.HDR) return 1;
        if (options.mode() == PreviewOptions.Mode.COLOR) return 0;
        if (source.format().equals("RGBA16F") || source.format().equals("R16F")
                || source.format().equals("RG16F") || source.format().equals("RG32F")) return 1;
        return 0;
    }

    private PreviewSummary summary(GraphPreviewController.Request request, String status,
                                   int width, int height, String path, long bytes) {
        PreviewOptions options = request.options();
        return new PreviewSummary(request.key() == null ? "" : request.key().logicalName(),
                options.mode().name(), options.channel().name(), options.exposureEv(),
                options.rangeMin(), options.rangeMax(), options.falseColor(),
                options.depthInterpretation().name(), options.nearPlane(), options.farPlane(),
                options.cubeFace().name(), options.mipLevel(), options.filtering().name(),
                options.updateInterval(), request.paused(), request.frozen(), status,
                width, height, lastSuccessfulFrame, lastErrorCode, lastErrorMessage,
                path, submittedDraws, submittedBlits, bytes);
    }

    private void fail(GraphPreviewController.Request request, String status,
                      String code, String message, long bytes) {
        lastErrorCode = code;
        lastErrorMessage = Objects.requireNonNullElse(message, "preview failure");
        controller.reportError(code, lastErrorMessage);
        controller.clearOutput();
        controller.publishSummary(request.revision(), summary(request, status, 0, 0, "", bytes));
    }

    private void releaseGpuResources(RenderDevice device) {
        if (!hasGpuResources()) return;
        controller.clearOutput();
        closeGpuResources();
        device.invalidateState();
    }

    private boolean hasGpuResources() {
        return shader != null || quad != null || output != null || resolve != null
                || linear2d != null || nearest2d != null || linearCube != null || nearestCube != null;
    }

    private void closeGpuResources() {
        controller.clearOutput();
        closeQuietly(resolve); resolve = null;
        closeQuietly(output); output = null;
        closeQuietly(nearestCube); nearestCube = null;
        closeQuietly(linearCube); linearCube = null;
        closeQuietly(nearest2d); nearest2d = null;
        closeQuietly(linear2d); linear2d = null;
        closeQuietly(quad); quad = null;
        closeQuietly(shader); shader = null;
        resourceSignature = null;
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception failure) {
            System.getLogger(GraphPreviewRenderer.class.getName()).log(
                    System.Logger.Level.WARNING, "preview cleanup failed", failure);
        }
    }

    private static long estimateCubeBytes(TextureCube cube) {
        long pixels = 0L;
        int size = cube.size();
        for (int mip = 0; mip < cube.mipLevels(); mip++) {
            pixels = Math.addExact(pixels, Math.multiplyExact((long) size * size, 6L));
            size = Math.max(1, size / 2);
        }
        return Math.multiplyExact(pixels, 8L);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank()
                ? (failure == null ? "preview failure" : failure.getClass().getSimpleName()) : message;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("GraphPreviewRenderer is closed");
    }

    private record ResourceSignature(PreviewSourceKey key, int outputWidth, int outputHeight,
                                     boolean resolve, int resolveWidth, int resolveHeight,
                                     String format, PreviewAspect aspect, String path,
                                     long estimatedBytes) { }
    private record Prepared(GraphPreviewController.Request request,
                            PreviewSourceDescription source,
                            ResourceSignature signature) { }
    private record ResolvedSource(int texture2d, int colorAttachment,
                                  Framebuffer framebuffer, TextureCube cube) { }
}
