package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.RenderTargetManager;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.material.ResourceOwnership;
import com.kaleblangley.haikalat.core.presentation.AttachmentRole;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.PresentationResult;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.backend.GpuTimer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RenderGraph implements AutoCloseable {
    private static final GpuTimer.Sample PENDING_GPU_SAMPLE =
            new GpuTimer.Sample(GpuTimer.Status.PENDING, 0L, -1L, 0L, 0L);
    private final List<Pass> passes = new ArrayList<>();
    private final Map<String, Pass> passByName = new HashMap<>();
    private final Map<String, Texture2D> importedTextures = new HashMap<>();
    private final Map<String, ExternalAttachment> importedExternalAttachments = new HashMap<>();
    private final Map<String, PresentationTarget> importedPresentationTargets = new HashMap<>();
    // Replaced as part of a resize candidate commit.  Keeping the lookup as a
    // candidate-owned map means commit does not have to clear/rebuild shared
    // state (which could throw after the framebuffer generation was swapped).
    private Map<String, Integer> textureAttachmentIds = new HashMap<>();
    private RenderTargetManager renderTargets;
    private final RenderTargetManager fixedRenderTargets;
    private final boolean allocateResources;
    private final boolean debugGroupsEnabled = !Boolean.getBoolean(
            "haikalat.render.disableDebugGroups");
    // Benchmark runs still need one non-blocking GPU sample for the whole GTAO
    // chain, but per-pass query rings add a query begin/end and availability
    // poll for every fullscreen pass.  Keep detailed per-pass timings for
    // normal diagnostics and aggregate only the optional GTAO chain when the
    // benchmark requests it.
    private final boolean aggregateGtaoGpuTimer = Boolean.getBoolean(
            "haikalat.render.aggregateGtaoGpuTimer");
    private GpuTimer aggregateGtaoTimer;
    private int firstGtaoPassIndex = -1;
    private int lastGtaoPassIndex = -1;
    private final PassResources passResources;
    private final CommandBuffer immediateCommands = new CommandBuffer();
    private int lastRecordedCommandCount;
    private int lastRecordedMatrixSnapshots;
    private int lastRecordedObjectPayloads;
    private List<Pass> sortedPasses;
    private CompiledRenderGraph compiledGraph;
    private int width;
    private int height;
    private Framebuffer currentFbo;
    private PresentationTarget currentPresentationTarget;
    private PresentationTarget framePresentationTarget;
    private long[] cpuRecordNanos = new long[0];
    private FrameProfile lastFrameProfile = FrameProfile.EMPTY;
    private boolean topologySealed;
    private boolean closed;
    private long frameSequence;
    private long topologyRevision;
    private Description cachedDescription;

    public RenderGraph(int width, int height) {
        this(width, height, true);
    }

    RenderGraph(int width, int height, boolean allocateResources) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        this.width = width;
        this.height = height;
        this.allocateResources = allocateResources;
        this.renderTargets = allocateResources ? new RenderTargetManager() : null;
        this.fixedRenderTargets = allocateResources ? new RenderTargetManager() : null;
        this.passResources = new PassResources(this);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public PassBuilder addPass(String name) {
        ensureOpen();
        ensureTopologyMutable();
        return new PassBuilder(this, Objects.requireNonNull(name, "name"));
    }

    /**
     * 查询渲染图是否已经注册指定 pass。
     *
     * @param passName pass 名称
     * @return 已注册时返回 {@code true}
     */
    public boolean hasPass(String passName) {
        ensureOpen();
        return passByName.containsKey(Objects.requireNonNull(passName, "passName"));
    }

    /**
     * 查询指定 pass 是否直接写入默认 framebuffer。
     *
     * @param passName pass 名称
     * @return pass 存在且声明了 backbuffer target 时返回 {@code true}
     */
    public boolean passWritesToBackbuffer(String passName) {
        ensureOpen();
        Pass pass = passByName.get(Objects.requireNonNull(passName, "passName"));
        return pass != null && pass.useBackbuffer;
    }

    /**
     * 冻结 pass 拓扑。该操作幂等，不影响后续 compile、resize 或 execute。
     */
    public void sealTopology() {
        ensureOpen();
        topologySealed = true;
        cachedDescription = null;
    }

    /** @return pass 拓扑是否已经冻结 */
    public boolean isTopologySealed() {
        return topologySealed;
    }

    public void importTexture(String name, Texture2D texture) {
        ensureOpen();
        importedTextures.put(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(texture, "texture"));
        cachedDescription = null;
    }

    public void importExternalColor(String name, ExternalAttachment attachment) {
        importExternalAttachment(name, attachment, AttachmentRole.COLOR);
    }

    public void importExternalDepth(String name, ExternalAttachment attachment) {
        ExternalAttachment required = Objects.requireNonNull(attachment, "attachment");
        if (!required.hasDepth()) {
            throw new IllegalArgumentException("external depth import requires a depth attachment");
        }
        importExternalAttachment(name, required, required.role());
    }

    public void importExternalStencil(String name, ExternalAttachment attachment) {
        ExternalAttachment required = Objects.requireNonNull(attachment, "attachment");
        if (!required.hasStencil()) {
            throw new IllegalArgumentException(
                    "external stencil import requires a stencil attachment");
        }
        importExternalAttachment(name, required, required.role());
    }

    /**
     * Imports or replaces a host presentation target without changing graph topology.
     * Imported targets are always borrowed and are never closed by the graph.
     */
    public void importPresentationTarget(String name, PresentationTarget target) {
        ensureOpen();
        String requiredName = requireLogicalName(name, "presentation target name");
        PresentationTarget required = Objects.requireNonNull(target, "target");
        requireBorrowed(required.framebufferOwnership(), "presentation framebuffer");
        required.color().ifPresent(attachment ->
                requireBorrowed(attachment.ownership(), "presentation color"));
        required.depth().ifPresent(attachment ->
                requireBorrowed(attachment.ownership(), "presentation depth"));
        required.stencil().ifPresent(attachment ->
                requireBorrowed(attachment.ownership(), "presentation stencil"));
        importedPresentationTargets.put(requiredName, required);
        cachedDescription = null;
    }

    public PresentationTarget importedPresentationTarget(String name) {
        ensureOpen();
        return importedPresentationTargets.get(
                Objects.requireNonNull(name, "presentation target name"));
    }

    /** Removes one borrowed attachment import without touching its native object. */
    public void removeExternalAttachment(String name) {
        ensureOpen();
        importedExternalAttachments.remove(
                Objects.requireNonNull(name, "external attachment name"));
        cachedDescription = null;
    }

    Framebuffer getPassFramebuffer(String passName) {
        if (renderTargets == null) return null;
        Pass pass = passByName.get(passName);
        if (pass != null && isFixedSize(pass)) {
            return fixedRenderTargets.get(passName);
        }
        return renderTargets.get(passName);
    }

    Texture2D getTexture(String textureName) {
        return importedTextures.get(textureName);
    }

    int getTextureAttachmentId(String textureName) {
        ExternalAttachment external = importedExternalAttachments.get(textureName);
        if (external != null) return external.textureId();
        return textureAttachmentIds.getOrDefault(textureName, 0);
    }

    Framebuffer currentPassFramebuffer() {
        return currentFbo;
    }

    PresentationTarget currentPresentationTarget() {
        return currentPresentationTarget;
    }

    PresentationTarget framePresentationTarget() {
        return framePresentationTarget;
    }

    FramebufferDescriptor passFramebufferDescriptor(String passName) {
        Pass pass = passByName.get(passName);
        if (pass == null || pass.useBackbuffer || pass.externalTarget) {
            return null;
        }
        return descriptorFor(pass);
    }

    void addPassInternal(Pass pass) {
        ensureOpen();
        ensureTopologyMutable();
        if (passByName.containsKey(pass.name)) {
            throw new GlException("Duplicate RenderGraph pass: " + pass.name);
        }
        passes.add(pass);
        passByName.put(pass.name, pass);
        sortedPasses = null;
        compiledGraph = null;
        topologyRevision++;
        cachedDescription = null;
        if (allocateResources && !pass.useBackbuffer && !pass.externalTarget) {
            allocatePassFramebuffer(pass);
        }
    }

    public void compile() {
        List<RenderGraphCompiler.PassSpec> specifications = passes.stream()
                .map(pass -> new RenderGraphCompiler.PassSpec(pass.name, pass.dependencies))
                .toList();
        compiledGraph = RenderGraphCompiler.compile(specifications);
        sortedPasses = compiledGraph.passNames().stream().map(passByName::get).toList();
        firstGtaoPassIndex = -1;
        lastGtaoPassIndex = -1;
        boolean gtaoRun = false;
        for (int index = 0; index < sortedPasses.size(); index++) {
            if (isGtaoPass(sortedPasses.get(index))) {
                if (!gtaoRun) {
                    firstGtaoPassIndex = index;
                    gtaoRun = true;
                }
                lastGtaoPassIndex = index;
            } else if (gtaoRun) {
                // Timer queries cannot be nested.  Aggregate only the first
                // contiguous GTAO run; an unusual graph with an interleaved
                // non-GTAO pass falls back to its individual timers there.
                break;
            }
        }
        cachedDescription = null;
    }

    List<String> passExecutionOrder() {
        if (sortedPasses == null) {
            compile();
        }
        return compiledGraph.passNames();
    }

    public void execute(RenderDevice device) {
        execute(device, PresentationTarget.defaultFramebuffer(width, height));
    }

    /**
     * Executes the graph into an explicit host target. No presentation or swap is performed.
     *
     * @return whether commands were executed or skipped because the host extent is zero
     */
    public PresentationResult execute(RenderDevice device, PresentationTarget target) {
        ensureOpen();
        Objects.requireNonNull(device, "device");
        PresentationTarget frameTarget = Objects.requireNonNull(target, "target");
        if (!frameTarget.isRenderable()) {
            currentPresentationTarget = null;
            currentFbo = null;
            return PresentationResult.SKIPPED_ZERO_EXTENT;
        }
        framePresentationTarget = frameTarget;
        if (sortedPasses == null) {
            compile();
        }
        CommandBuffer cmd;
        if (device.executionModel() == com.kaleblangley.haikalat.core.device.ExecutionModel.IMMEDIATE) {
            immediateCommands.reset();
            cmd = immediateCommands;
        } else {
            cmd = device.createCommandBuffer();
        }
        if (cpuRecordNanos.length != sortedPasses.size()) {
            cpuRecordNanos = new long[sortedPasses.size()];
        }

        long currentFrameSequence = frameSequence++;
        Arrays.fill(cpuRecordNanos, 0L);
        GpuTimer.Sample aggregateGtaoSample = null;
        try {
            for (int passIndex = 0; passIndex < sortedPasses.size(); passIndex++) {
                Pass pass = sortedPasses.get(passIndex);
                long passCpuStart = System.nanoTime();
                Framebuffer framebuffer = getPassFramebuffer(pass.name);
                currentFbo = framebuffer;
                PresentationTarget passTarget = targetFor(pass, frameTarget);
                currentPresentationTarget = passTarget;
                boolean aggregateGtaoPass = aggregateGtaoGpuTimer
                        && passIndex >= firstGtaoPassIndex
                        && passIndex <= lastGtaoPassIndex
                        && isGtaoPass(pass);
                GpuTimer beginTimer = null;
                GpuTimer endTimer = null;
                if (!aggregateGtaoPass) {
                    if (pass.timer == null) pass.timer = new GpuTimer();
                    beginTimer = pass.timer;
                    endTimer = pass.timer;
                } else if (passIndex == firstGtaoPassIndex
                        || passIndex == lastGtaoPassIndex) {
                    if (aggregateGtaoTimer == null) aggregateGtaoTimer = new GpuTimer();
                    if (passIndex == firstGtaoPassIndex) beginTimer = aggregateGtaoTimer;
                    if (passIndex == lastGtaoPassIndex) endTimer = aggregateGtaoTimer;
                }
                if (debugGroupsEnabled) cmd.pushDebugGroup(pass.debugGroup);
                cmd.enableScissor(false);
                if (beginTimer != null) cmd.beginGpuTimer(beginTimer, currentFrameSequence);

                if (passTarget != null) {
                    cmd.enableBlend(false);
                    cmd.depthMask(true);
                    cmd.enableFramebufferSrgb(
                            passTarget.colorFormat() == RenderFormat.SRGB8_ALPHA8);
                    cmd.bindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                                    passTarget.drawFramebufferId())
                            .viewport(0, 0, passTarget.width(), passTarget.height());
                } else if (!pass.externalTarget && framebuffer != null) {
                    cmd.enableFramebufferSrgb(pass.colorFormats.contains(RenderFormat.SRGB8_ALPHA8));
                    cmd.bindFramebuffer(framebuffer)
                            .viewport(0, 0, framebuffer.width(), framebuffer.height());
                }

                if ((pass.clearColor || pass.clearDepth) && !pass.useBackbuffer && framebuffer != null) {
                    if (pass.clearDepth) {
                        cmd.depthMask(true);
                    }
                    cmd.clearColor(pass.clearR, pass.clearG, pass.clearB, pass.clearA);
                    cmd.clear(pass.clearColor, pass.clearDepth);
                }

                try {
                    pass.executor.execute(passResources, cmd);
                } finally {
                    if (endTimer != null) cmd.endGpuTimer(endTimer);
                    if (debugGroupsEnabled) cmd.popDebugGroup();
                    cpuRecordNanos[passIndex] = System.nanoTime() - passCpuStart;
                }
            }

            lastRecordedCommandCount = cmd.commandCount();
            lastRecordedMatrixSnapshots = cmd.recordedMatrixSnapshotCount();
            lastRecordedObjectPayloads = cmd.recordedObjectPayloadCount();
            device.execute(cmd);
            List<PassProfile> passProfiles = new ArrayList<>(sortedPasses.size());
            if (aggregateGtaoTimer != null) {
                aggregateGtaoSample = aggregateGtaoTimer.sample(currentFrameSequence);
            }
            for (int passIndex = 0; passIndex < sortedPasses.size(); passIndex++) {
                Pass pass = sortedPasses.get(passIndex);
                boolean aggregateGtaoPass = aggregateGtaoGpuTimer
                        && passIndex >= firstGtaoPassIndex
                        && passIndex <= lastGtaoPassIndex
                        && isGtaoPass(pass);
                GpuTimer.Sample sample;
                if (aggregateGtaoPass) {
                    sample = passIndex == firstGtaoPassIndex && aggregateGtaoSample != null
                            ? aggregateGtaoSample
                            : PENDING_GPU_SAMPLE;
                } else {
                    sample = pass.timer.sample(currentFrameSequence);
                }
                passProfiles.add(new PassProfile(pass.name, cpuRecordNanos[passIndex],
                        sample.elapsedNanos(), mapStatus(sample.status()), sample.resultSequence(),
                        sample.sampleAgeFrames(), sample.skippedSubmissions()));
            }
            lastFrameProfile = new FrameProfile(0L, passProfiles, currentFrameSequence);
            return PresentationResult.RENDERED;
        } catch (RuntimeException | Error failure) {
            lastFrameProfile = failedProfile(currentFrameSequence);
            throw failure;
        } finally {
            currentPresentationTarget = null;
            currentFbo = null;
            framePresentationTarget = null;
        }
    }

    /** @return 最近成功录制帧的 typed command 数量 */
    public int lastRecordedCommandCount() { return lastRecordedCommandCount; }

    /** @return 最近成功录制帧的 primitive mat4 快照数量 */
    public int lastRecordedMatrixSnapshots() { return lastRecordedMatrixSnapshots; }

    /** @return 最近成功录制帧的对象引用 payload 数量 */
    public int lastRecordedObjectPayloads() { return lastRecordedObjectPayloads; }

    private FrameProfile failedProfile(long currentFrameSequence) {
        List<PassProfile> failed = new ArrayList<>(sortedPasses.size());
        for (int index = 0; index < sortedPasses.size(); index++) {
            Pass pass = sortedPasses.get(index);
            boolean aggregateGtaoPass = aggregateGtaoGpuTimer
                    && index >= firstGtaoPassIndex && index <= lastGtaoPassIndex
                    && isGtaoPass(pass);
            long skipped = aggregateGtaoPass
                    ? aggregateGtaoTimer == null ? 0L
                    : aggregateGtaoTimer.sample(currentFrameSequence).skippedSubmissions()
                    : pass.timer == null ? 0L
                    : pass.timer.sample(currentFrameSequence).skippedSubmissions();
            failed.add(new PassProfile(pass.name, cpuRecordNanos[index], 0L,
                    PassProfile.GpuTimingStatus.FAILED, -1L, 0L, skipped));
        }
        return new FrameProfile(0L, failed, currentFrameSequence);
    }

    public FrameProfile lastFrameProfile() {
        return lastFrameProfile;
    }

    /**
     * 返回与实际 compiled plan 一致的不可变 RenderGraph 描述。
     * topology 和尺寸未变化时复用同一实例。
     */
    public Description description() {
        ensureOpen();
        if (sortedPasses == null) compile();
        if (cachedDescription != null) return cachedDescription;
        List<PassDescription> descriptions = new ArrayList<>(sortedPasses.size());
        for (Pass pass : sortedPasses) {
            TargetKind kind = pass.useBackbuffer ? TargetKind.BACKBUFFER
                    : pass.externalTarget ? TargetKind.EXTERNAL : TargetKind.MANAGED;
            PresentationTarget importedTarget = pass.presentationTargetName == null ? null
                    : importedPresentationTargets.get(pass.presentationTargetName);
            int targetWidth = importedTarget != null ? importedTarget.width()
                    : kind == TargetKind.EXTERNAL ? 0
                    : targetDimension(width, pass.fixedWidth, pass.relativeWidthScale,
                    pass.ceilRelativeSize);
            int targetHeight = importedTarget != null ? importedTarget.height()
                    : kind == TargetKind.EXTERNAL ? 0
                    : targetDimension(height, pass.fixedHeight, pass.relativeHeightScale,
                    pass.ceilRelativeSize);
            List<AttachmentDescription> colors = new ArrayList<>(pass.colorFormats.size());
            for (int index = 0; index < pass.colorFormats.size(); index++) {
                String logicalName = index < pass.colorTextureNames.size()
                        ? pass.colorTextureNames.get(index) : "color" + index;
                colors.add(new AttachmentDescription(logicalName,
                        pass.colorFormats.get(index).name(),
                        pass.samples > 1 ? StorageKind.RENDERBUFFER : StorageKind.TEXTURE));
            }
            AttachmentDescription depth = pass.createDepth
                    ? new AttachmentDescription(pass.depthTextureName == null ? "depth" : pass.depthTextureName,
                    pass.depthTextureName == null ? "DEPTH24_STENCIL8" : "DEPTH_COMPONENT",
                    pass.depthTextureName == null ? StorageKind.RENDERBUFFER : StorageKind.TEXTURE)
                    : null;
            descriptions.add(new PassDescription(pass.name, pass.dependencies, kind,
                    targetWidth, targetHeight, pass.samples, colors, depth,
                    pass.clearColor, pass.clearDepth));
        }
        cachedDescription = new Description(width, height, topologyRevision, topologySealed,
                compiledGraph.passNames(), descriptions);
        return cachedDescription;
    }

    /**
     * Allocates a complete resize candidate without changing the active graph.
     * The caller must either commit or close the returned candidate.
     */
    public ResizeCandidate prepareResize(int newWidth, int newHeight) {
        ensureOpen();
        if (newWidth <= 0 || newHeight <= 0
                || newWidth == width && newHeight == height) {
            return ResizeCandidate.noop(this, newWidth, newHeight);
        }
        if (Boolean.getBoolean("haikalat.test.failGraphResizeAllocation")) {
            System.clearProperty("haikalat.test.failGraphResizeAllocation");
            throw new IllegalStateException("injected graph resize allocation failure");
        }
        if (!allocateResources) {
            return new ResizeCandidate(this, newWidth, newHeight, null, null);
        }
        RenderTargetManager candidate = new RenderTargetManager();
        Map<String, Integer> candidateAttachmentIds;
        try {
            for (Pass pass : passes) {
                if (pass.useBackbuffer || pass.externalTarget || isFixedSize(pass)) continue;
                candidate.create(pass.name, descriptorFor(pass, newWidth, newHeight));
            }
            candidateAttachmentIds = buildAttachmentLookup(candidate);
        } catch (RuntimeException | Error failure) {
            try {
                candidate.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return new ResizeCandidate(this, newWidth, newHeight, candidate, candidateAttachmentIds);
    }

    /** Publishes a previously prepared candidate without allocating or retiring resources. */
    public void commitResize(ResizeCandidate candidate) {
        ensureOpen();
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    public void resize(int newWidth, int newHeight) {
        ResizeCandidate candidate = prepareResize(newWidth, newHeight);
        try {
            commitResize(candidate);
        } finally {
            candidate.close();
        }
    }

    /** A graph extent candidate whose old resources are retired only after commit. */
    public static final class ResizeCandidate implements AutoCloseable {
        private final RenderGraph owner;
        private final int width;
        private final int height;
        private RenderTargetManager candidateTargets;
        private Map<String, Integer> candidateAttachmentIds;
        private RenderTargetManager retiredTargets;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(RenderGraph owner, int width, int height,
                                RenderTargetManager candidateTargets,
                                Map<String, Integer> candidateAttachmentIds) {
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.candidateTargets = candidateTargets;
            this.candidateAttachmentIds = candidateAttachmentIds;
        }

        private static ResizeCandidate noop(RenderGraph owner, int width, int height) {
            return new ResizeCandidate(owner, width, height, null, null);
        }

        void validateFor(RenderGraph expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("resize candidate belongs to another graph");
            }
            if (closed) throw new IllegalStateException("resize candidate is closed");
            if (committed) throw new IllegalStateException("resize candidate already committed");
        }

        private void commitInto(RenderGraph expectedOwner) {
            validateFor(expectedOwner);
            if (width <= 0 || height <= 0
                    || owner.width == width && owner.height == height) {
                committed = true;
                return;
            }
            if (owner.allocateResources) {
                retiredTargets = owner.renderTargets;
                owner.renderTargets = candidateTargets;
                candidateTargets = null;
                owner.textureAttachmentIds = candidateAttachmentIds == null
                        ? new HashMap<>() : candidateAttachmentIds;
                candidateAttachmentIds = null;
            }
            owner.width = width;
            owner.height = height;
            owner.cachedDescription = null;
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            if (candidateTargets != null) {
                try {
                    candidateTargets.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                } finally {
                    candidateTargets = null;
                }
            }
            candidateAttachmentIds = null;
            if (retiredTargets != null) {
                try {
                    retiredTargets.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                } finally {
                    retiredTargets = null;
                }
            }
            if (failure != null) throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        if (renderTargets != null) {
            try {
                renderTargets.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
        }
        if (fixedRenderTargets != null) {
            try {
                fixedRenderTargets.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        for (Pass pass : passes) {
            if (pass.timer != null) {
                try {
                    pass.timer.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                } finally {
                    pass.timer = null;
                }
            }
        }
        if (aggregateGtaoTimer != null) {
            try {
                aggregateGtaoTimer.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            } finally {
                aggregateGtaoTimer = null;
            }
        }
        textureAttachmentIds.clear();
        importedExternalAttachments.clear();
        importedPresentationTargets.clear();
        importedTextures.clear();
        closed = true;
        if (failure != null) throw failure;
    }

    private void allocatePassFramebuffer(Pass pass) {
        RenderTargetManager owner = isFixedSize(pass) ? fixedRenderTargets : renderTargets;
        Framebuffer framebuffer = owner.create(pass.name, descriptorFor(pass));
        registerPassAttachments(pass, framebuffer);
    }

    private static boolean isFixedSize(Pass pass) {
        return pass.fixedWidth > 0 && pass.fixedHeight > 0;
    }

    private FramebufferDescriptor descriptorFor(Pass pass) {
        return descriptorFor(pass, width, height);
    }

    private FramebufferDescriptor descriptorFor(Pass pass, int windowWidth, int windowHeight) {
        int targetWidth = targetDimension(windowWidth, pass.fixedWidth, pass.relativeWidthScale,
                pass.ceilRelativeSize);
        int targetHeight = targetDimension(windowHeight, pass.fixedHeight, pass.relativeHeightScale,
                pass.ceilRelativeSize);
        FramebufferDescriptor.Builder builder = FramebufferDescriptor.builder(targetWidth, targetHeight)
                .samples(pass.samples);
        boolean multisampled = pass.samples > 1;
        for (RenderFormat format : pass.colorFormats) {
            if (multisampled) {
                builder.colorRenderbuffer(format);
            } else {
                builder.colorTexture(format);
            }
        }
        if (pass.depthTextureName != null) {
            builder.depthTexture();
        } else if (pass.createDepth) {
            builder.depthStencilRenderbuffer();
        }
        return builder.build();
    }

    private static int targetDimension(int windowDimension, int fixedDimension, float relativeScale,
                                      boolean ceilRelativeSize) {
        if (fixedDimension > 0) {
            return fixedDimension;
        }
        if (relativeScale > 0.0f) {
            return Math.max(1, ceilRelativeSize
                    ? (int) Math.ceil(windowDimension * relativeScale)
                    : Math.round(windowDimension * relativeScale));
        }
        return windowDimension;
    }

    private void refreshAttachmentLookup() {
        textureAttachmentIds.clear();
        for (Pass pass : passes) {
            if (!pass.useBackbuffer && !pass.externalTarget) {
                Framebuffer framebuffer = getPassFramebuffer(pass.name);
                if (framebuffer != null) {
                    registerPassAttachments(pass, framebuffer);
                }
            }
        }
    }

    private void registerPassAttachments(Pass pass, Framebuffer framebuffer) {
        registerPassAttachments(textureAttachmentIds, pass, framebuffer);
    }

    private Map<String, Integer> buildAttachmentLookup(RenderTargetManager dynamicTargets) {
        Map<String, Integer> lookup = new HashMap<>();
        for (Pass pass : passes) {
            if (pass.useBackbuffer || pass.externalTarget) continue;
            Framebuffer framebuffer = isFixedSize(pass)
                    ? fixedRenderTargets.get(pass.name)
                    : dynamicTargets.get(pass.name);
            if (framebuffer != null) {
                registerPassAttachments(lookup, pass, framebuffer);
            }
        }
        return lookup;
    }

    private static void registerPassAttachments(Map<String, Integer> attachmentIds,
                                                Pass pass, Framebuffer framebuffer) {
        for (int i = 0; i < pass.colorTextureNames.size(); i++) {
            if (i < framebuffer.colorAttachmentCount() && framebuffer.colorAttachmentIsTexture(i)) {
                attachmentIds.put(pass.colorTextureNames.get(i), framebuffer.colorAttachment(i));
            }
        }
        if (pass.depthTextureName != null && framebuffer.depthAttachmentIsTexture()) {
            attachmentIds.put(pass.depthTextureName, framebuffer.depthAttachment());
        }
    }

    private static boolean isGtaoPass(Pass pass) {
        return pass.name.startsWith("Gtao");
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("RenderGraph is closed");
        }
    }

    private void ensureTopologyMutable() {
        if (topologySealed) {
            throw new IllegalStateException("RenderGraph pass topology is sealed");
        }
    }

    private void importExternalAttachment(String name, ExternalAttachment attachment,
                                          AttachmentRole requiredRole) {
        ensureOpen();
        String requiredName = requireLogicalName(name, "external attachment name");
        ExternalAttachment required = Objects.requireNonNull(attachment, "attachment");
        if (requiredRole == AttachmentRole.COLOR && required.role() != AttachmentRole.COLOR) {
            throw new IllegalArgumentException("external color import requires COLOR role");
        }
        requireBorrowed(required.ownership(), "external attachment");
        importedExternalAttachments.put(requiredName, required);
        cachedDescription = null;
    }

    private PresentationTarget targetFor(Pass pass, PresentationTarget frameTarget) {
        if (pass.useBackbuffer) return frameTarget;
        if (pass.presentationTargetName == null) return null;
        PresentationTarget target = importedPresentationTargets.get(pass.presentationTargetName);
        if (target == null) {
            throw new IllegalStateException("RenderGraph pass '" + pass.name
                    + "' references missing presentation target '"
                    + pass.presentationTargetName + "'");
        }
        if (!target.isRenderable()) {
            throw new IllegalStateException("RenderGraph pass '" + pass.name
                    + "' references zero-extent presentation target '"
                    + pass.presentationTargetName + "'");
        }
        return target;
    }

    private static String requireLogicalName(String name, String label) {
        String required = Objects.requireNonNull(name, label);
        if (required.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return required;
    }

    private static void requireBorrowed(ResourceOwnership ownership, String label) {
        if (ownership != ResourceOwnership.BORROWED) {
            throw new IllegalArgumentException(label
                    + " imported into RenderGraph must be BORROWED");
        }
    }

    private static PassProfile.GpuTimingStatus mapStatus(GpuTimer.Status status) {
        return PassProfile.GpuTimingStatus.valueOf(status.name());
    }

    /** RenderGraph compiled plan 的只读描述。 */
    public record Description(int width, int height, long topologyRevision, boolean sealed,
                              List<String> executionOrder, List<PassDescription> passes) {
        public Description {
            executionOrder = List.copyOf(executionOrder);
            passes = List.copyOf(passes);
        }
    }

    /** 单个 pass 的只读 target 与依赖描述。 */
    public record PassDescription(String name, List<String> directDependencies,
                                  TargetKind targetKind, int width, int height, int samples,
                                  List<AttachmentDescription> colorAttachments,
                                  AttachmentDescription depthAttachment,
                                  boolean clearColor, boolean clearDepth) {
        public PassDescription {
            directDependencies = List.copyOf(directDependencies);
            colorAttachments = List.copyOf(colorAttachments);
        }
    }

    /**
     * attachment 的逻辑描述，不暴露 native id。
     *
     * @param logicalName 逻辑资源名称
     * @param format 渲染格式名称
     * @param storageKind 底层存储种类
     */
    public record AttachmentDescription(String logicalName, String format, StorageKind storageKind) {
    }

    public enum TargetKind { BACKBUFFER, MANAGED, EXTERNAL }
    public enum StorageKind { TEXTURE, RENDERBUFFER }

    @FunctionalInterface
    public interface PassExecutor {
        void execute(PassResources resources, CommandBuffer cmd);
    }

    private static final class Pass {
        final String name;
        final String debugGroup;
        final List<String> colorTextureNames;
        final List<RenderFormat> colorFormats;
        final int samples;
        final int fixedWidth;
        final int fixedHeight;
        final float relativeWidthScale;
        final float relativeHeightScale;
        final boolean ceilRelativeSize;
        final boolean createDepth;
        final String depthTextureName;
        final boolean clearColor;
        final boolean clearDepth;
        final float clearR;
        final float clearG;
        final float clearB;
        final float clearA;
        final boolean useBackbuffer;
        final boolean externalTarget;
        final String presentationTargetName;
        final List<String> dependencies;
        final PassExecutor executor;
        GpuTimer timer;

        Pass(String name, List<String> colorTextureNames, List<RenderFormat> colorFormats, int samples,
             int fixedWidth, int fixedHeight, float relativeWidthScale, float relativeHeightScale,
             boolean ceilRelativeSize,
             boolean createDepth, String depthTextureName, boolean clearColor, boolean clearDepth,
             float clearR, float clearG, float clearB, float clearA, boolean useBackbuffer,
             boolean externalTarget, String presentationTargetName,
             List<String> dependencies, PassExecutor executor) {
            this.name = name;
            this.debugGroup = "RenderGraph/" + name;
            this.colorTextureNames = colorTextureNames;
            this.colorFormats = colorFormats;
            this.samples = samples;
            this.fixedWidth = fixedWidth;
            this.fixedHeight = fixedHeight;
            this.relativeWidthScale = relativeWidthScale;
            this.relativeHeightScale = relativeHeightScale;
            this.ceilRelativeSize = ceilRelativeSize;
            this.createDepth = createDepth;
            this.depthTextureName = depthTextureName;
            this.clearColor = clearColor;
            this.clearDepth = clearDepth;
            this.clearR = clearR;
            this.clearG = clearG;
            this.clearB = clearB;
            this.clearA = clearA;
            this.useBackbuffer = useBackbuffer;
            this.externalTarget = externalTarget;
            this.presentationTargetName = presentationTargetName;
            this.dependencies = dependencies;
            this.executor = executor;
        }
    }

    public static final class PassBuilder {
        private final RenderGraph graph;
        private final String name;
        private final List<String> colorTextureNames = new ArrayList<>();
        private final List<RenderFormat> colorFormats = new ArrayList<>();
        private int samples = 1;
        private int fixedWidth;
        private int fixedHeight;
        private float relativeWidthScale;
        private float relativeHeightScale;
        private boolean ceilRelativeSize;
        private boolean createDepth;
        private String depthTextureName;
        private boolean clearColor = true;
        private boolean clearDepth = true;
        private float clearR = 0.08f;
        private float clearG = 0.10f;
        private float clearB = 0.14f;
        private float clearA = 1.0f;
        private boolean useBackbuffer;
        private boolean externalTarget;
        private String presentationTargetName;
        private final List<String> dependencies = new ArrayList<>();
        private PassExecutor executor;

        PassBuilder(RenderGraph graph, String name) {
            this.graph = graph;
            this.name = name;
        }

        public PassBuilder createColor(String textureName, RenderFormat format) {
            colorTextureNames.clear();
            colorFormats.clear();
            colorTextureNames.add(Objects.requireNonNull(textureName, "textureName"));
            colorFormats.add(Objects.requireNonNull(format, "format"));
            samples = 1;
            return this;
        }

        public PassBuilder createColor(String textureName, int format) {
            return createColor(textureName, legacyFormat(format));
        }

        public PassBuilder createColor(String textureName) {
            return createColor(textureName, RenderFormat.RGBA8);
        }

        public PassBuilder createColors(String[] textureNames, RenderFormat... formats) {
            Objects.requireNonNull(textureNames, "textureNames");
            return createColors(Arrays.asList(textureNames), Arrays.asList(formats));
        }

        public PassBuilder createColors(String[] textureNames, int... formats) {
            Objects.requireNonNull(textureNames, "textureNames");
            return createColors(Arrays.asList(textureNames), toFormatList(formats));
        }

        public PassBuilder createColors(List<String> textureNames, List<RenderFormat> formats) {
            Objects.requireNonNull(textureNames, "textureNames");
            Objects.requireNonNull(formats, "formats");
            if (textureNames.isEmpty()) {
                throw new IllegalArgumentException("textureNames must not be empty");
            }
            if (textureNames.size() != formats.size()) {
                throw new IllegalArgumentException("textureNames and formats must have the same size");
            }
            colorTextureNames.clear();
            colorFormats.clear();
            colorTextureNames.addAll(textureNames.stream().map(name -> Objects.requireNonNull(name, "textureName")).toList());
            colorFormats.addAll(formats.stream().map(format -> Objects.requireNonNull(format, "format")).toList());
            samples = 1;
            return this;
        }

        public PassBuilder createColorMS(String textureName, RenderFormat format, int samples) {
            colorTextureNames.clear();
            colorFormats.clear();
            colorTextureNames.add(Objects.requireNonNull(textureName, "textureName"));
            colorFormats.add(Objects.requireNonNull(format, "format"));
            this.samples = Math.max(2, samples);
            return this;
        }

        public PassBuilder createColorMS(String textureName, int format, int samples) {
            return createColorMS(textureName, legacyFormat(format), samples);
        }

        public PassBuilder createColorMS(String textureName, int samples) {
            return createColorMS(textureName, RenderFormat.RGBA8, samples);
        }

        public PassBuilder createDepth() {
            createDepth = true;
            depthTextureName = null;
            return this;
        }

        public PassBuilder createDepthTexture(String textureName) {
            if (samples > 1) {
                throw new IllegalStateException("multisampled depth textures are not supported yet");
            }
            createDepth = true;
            depthTextureName = Objects.requireNonNull(textureName, "textureName");
            return this;
        }

        /**
         * 使当前 pass target 独立于窗口尺寸的 graph target。
         * 渲染图 resize 后，固定尺寸 target 仍以原尺寸重建。
         */
        public PassBuilder fixedSize(int width, int height) {
            if (relativeWidthScale > 0.0f || relativeHeightScale > 0.0f) {
                throw new IllegalStateException("fixedSize and relativeSize are mutually exclusive");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("fixed pass dimensions must be positive");
            }
            fixedWidth = width;
            fixedHeight = height;
            return this;
        }

        /**
         * 让当前 target 按窗口尺寸的同一比例分配，并在 resize 时自动重建。
         *
         * @param scale 宽高共同使用的正比例
         * @return 当前 pass builder
         */
        public PassBuilder relativeSize(float scale) {
            return relativeSize(scale, scale);
        }

        /**
         * 让当前 target 分别按窗口宽高比例分配。最终尺寸采用
         * {@code max(1, round(windowSize * scale))}。
         *
         * @param widthScale  宽度正比例
         * @param heightScale 高度正比例
         * @return 当前 pass builder
         */
        public PassBuilder relativeSize(float widthScale, float heightScale) {
            if (fixedWidth > 0 || fixedHeight > 0) {
                throw new IllegalStateException("relativeSize and fixedSize are mutually exclusive");
            }
            if (!Float.isFinite(widthScale) || widthScale <= 0.0f
                    || !Float.isFinite(heightScale) || heightScale <= 0.0f) {
                throw new IllegalArgumentException("relative target scales must be finite and positive");
            }
            relativeWidthScale = widthScale;
            relativeHeightScale = heightScale;
            ceilRelativeSize = false;
            return this;
        }

        /**
         * Uses ceil rather than the legacy round rule for relative target sizes.
         * This is intended for half-resolution effects where odd extents must
         * retain the final row and column.
         */
        public PassBuilder relativeSizeCeil(float widthScale, float heightScale) {
            relativeSize(widthScale, heightScale);
            ceilRelativeSize = true;
            return this;
        }

        public PassBuilder relativeSizeCeil(float scale) {
            return relativeSizeCeil(scale, scale);
        }

        public PassBuilder writeToBackbuffer() {
            if (externalTarget) {
                throw new IllegalStateException("backbuffer and external target are mutually exclusive");
            }
            useBackbuffer = true;
            return this;
        }

        /**
         * 声明该 pass 由 executor 通过正式命令绑定自有的持久目标。
         * 适用于跨帧 history；RenderGraph 不为该 pass 分配 framebuffer。
         */
        public PassBuilder writeToExternalTarget() {
            if (useBackbuffer) {
                throw new IllegalStateException("external target and backbuffer are mutually exclusive");
            }
            externalTarget = true;
            presentationTargetName = null;
            return this;
        }

        /**
         * Writes this pass to a borrowed target imported by logical name.
         * The imported native handles may be replaced between frames.
         */
        public PassBuilder writeToPresentationTarget(String targetName) {
            if (useBackbuffer) {
                throw new IllegalStateException(
                        "presentation target and backbuffer are mutually exclusive");
            }
            externalTarget = true;
            presentationTargetName = requireLogicalName(targetName,
                    "presentation target name");
            return this;
        }

        public PassBuilder dependsOn(String passName) {
            dependencies.add(Objects.requireNonNull(passName, "passName"));
            return this;
        }

        public PassBuilder clearColor(float r, float g, float b, float a) {
            clearR = r;
            clearG = g;
            clearB = b;
            clearA = a;
            return this;
        }

        public PassBuilder noClear() {
            clearColor = false;
            clearDepth = false;
            return this;
        }

        public PassBuilder clearDepthOnly() {
            clearColor = false;
            clearDepth = true;
            return this;
        }

        public RenderGraph execute(PassExecutor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            if (externalTarget && (clearColor || clearDepth)) {
                throw new IllegalStateException("external-target pass must declare noClear()");
            }
            Pass pass = new Pass(name, List.copyOf(colorTextureNames), List.copyOf(colorFormats), samples,
                    fixedWidth, fixedHeight, relativeWidthScale, relativeHeightScale,
                    ceilRelativeSize,
                    createDepth, depthTextureName, clearColor, clearDepth, clearR, clearG, clearB, clearA,
                    useBackbuffer, externalTarget, presentationTargetName,
                    List.copyOf(dependencies), executor);
            graph.addPassInternal(pass);
            return graph;
        }

        private static List<RenderFormat> toFormatList(int[] values) {
            List<RenderFormat> result = new ArrayList<>(values.length);
            for (int value : values) {
                result.add(legacyFormat(value));
            }
            return result;
        }

        private static RenderFormat legacyFormat(int value) {
            return switch (value) {
                case 32856 -> RenderFormat.RGBA8;
                case 35907 -> RenderFormat.SRGB8_ALPHA8;
                case 34842 -> RenderFormat.RGBA16F;
                case 33325 -> RenderFormat.R16F;
                case 33321 -> RenderFormat.R8;
                case 33327 -> RenderFormat.RG16F;
                case 33328 -> RenderFormat.RG32F;
                default -> throw new IllegalArgumentException("Unsupported legacy GL render format: " + value);
            };
        }
    }
}
