package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.RenderTargetManager;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.backend.GpuTimer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;

public final class RenderGraph implements AutoCloseable {
    private final List<Pass> passes = new ArrayList<>();
    private final Map<String, Pass> passByName = new HashMap<>();
    private final Map<String, Texture2D> importedTextures = new HashMap<>();
    private final Map<String, Integer> textureAttachmentIds = new HashMap<>();
    private final RenderTargetManager renderTargets;
    private final boolean allocateResources;
    private final PassResources passResources;
    private final CommandBuffer immediateCommands = new CommandBuffer();
    private List<Pass> sortedPasses;
    private int width;
    private int height;
    private Framebuffer currentFbo;
    private long[] cpuRecordNanos = new long[0];
    private FrameProfile lastFrameProfile = FrameProfile.EMPTY;
    private boolean closed;

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
        this.passResources = new PassResources(this);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public PassBuilder addPass(String name) {
        return new PassBuilder(this, Objects.requireNonNull(name, "name"));
    }

    public void importTexture(String name, Texture2D texture) {
        importedTextures.put(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(texture, "texture"));
    }

    Framebuffer getPassFramebuffer(String passName) {
        return renderTargets == null ? null : renderTargets.get(passName);
    }

    Texture2D getTexture(String textureName) {
        return importedTextures.get(textureName);
    }

    int getTextureAttachmentId(String textureName) {
        return textureAttachmentIds.getOrDefault(textureName, 0);
    }

    Framebuffer currentPassFramebuffer() {
        return currentFbo;
    }

    FramebufferDescriptor passFramebufferDescriptor(String passName) {
        Pass pass = passByName.get(passName);
        if (pass == null || pass.useBackbuffer) {
            return null;
        }
        return descriptorFor(pass);
    }

    void addPassInternal(Pass pass) {
        ensureOpen();
        if (passByName.containsKey(pass.name)) {
            throw new GlException("Duplicate RenderGraph pass: " + pass.name);
        }
        passes.add(pass);
        passByName.put(pass.name, pass);
        sortedPasses = null;
        if (allocateResources && !pass.useBackbuffer) {
            allocatePassFramebuffer(pass);
        }
    }

    public void compile() {
        Map<String, Integer> inDegree = new HashMap<>();
        for (Pass pass : passes) {
            inDegree.putIfAbsent(pass.name, 0);
            for (String dep : pass.dependencies) {
                if (!passByName.containsKey(dep)) {
                    throw new GlException("RenderGraph pass " + pass.name + " depends on missing pass " + dep);
                }
                inDegree.merge(pass.name, 1, Integer::sum);
            }
        }
        for (Pass pass : passes) {
            inDegree.putIfAbsent(pass.name, 0);
        }

        List<Pass> sorted = new ArrayList<>();
        Queue<Pass> queue = new ArrayDeque<>();
        for (Pass pass : passes) {
            if (inDegree.getOrDefault(pass.name, 0) == 0) {
                queue.add(pass);
            }
        }
        while (!queue.isEmpty()) {
            Pass pass = queue.poll();
            sorted.add(pass);
            for (Pass other : passes) {
                if (other.dependencies.contains(pass.name)) {
                    int degree = inDegree.merge(other.name, -1, Integer::sum);
                    if (degree == 0) {
                        queue.add(other);
                    }
                }
            }
        }
        if (sorted.size() != passes.size()) {
            throw new GlException("RenderGraph has circular dependency");
        }
        sortedPasses = sorted;
    }

    List<String> passExecutionOrder() {
        if (sortedPasses == null) {
            compile();
        }
        List<String> names = new ArrayList<>(sortedPasses.size());
        for (Pass pass : sortedPasses) {
            names.add(pass.name);
        }
        return names;
    }

    public void execute(RenderDevice device) {
        ensureOpen();
        Objects.requireNonNull(device, "device");
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

        for (int passIndex = 0; passIndex < sortedPasses.size(); passIndex++) {
            Pass pass = sortedPasses.get(passIndex);
            long passCpuStart = System.nanoTime();
            Framebuffer framebuffer = getPassFramebuffer(pass.name);
            currentFbo = framebuffer;
            if (pass.timer == null) pass.timer = new GpuTimer();
            GpuTimer timer = pass.timer;
            cmd.beginGpuTimer(timer);

            if (pass.useBackbuffer) {
                cmd.enableBlend(false);
                cmd.depthMask(true);
                cmd.bindDefaultFramebuffer()
                        .viewport(0, 0, width, height);
            } else if (framebuffer != null) {
                cmd.bindFramebuffer(framebuffer)
                        .viewport(0, 0, framebuffer.width(), framebuffer.height());
            }

            if ((pass.clearColor || pass.clearDepth) && !pass.useBackbuffer && framebuffer != null) {
                cmd.clearColor(pass.clearR, pass.clearG, pass.clearB, pass.clearA);
                cmd.clear(pass.clearColor, pass.clearDepth);
            }

            pass.executor.execute(passResources, cmd);
            cmd.endGpuTimer(timer);
            cpuRecordNanos[passIndex] = System.nanoTime() - passCpuStart;
        }

        device.execute(cmd);
        List<PassProfile> passProfiles = new ArrayList<>(sortedPasses.size());
        for (int passIndex = 0; passIndex < sortedPasses.size(); passIndex++) {
            Pass pass = sortedPasses.get(passIndex);
            GpuTimer timer = pass.timer;
            long gpuNanos = timer == null ? 0L : timer.elapsedNanos();
            passProfiles.add(new PassProfile(pass.name, cpuRecordNanos[passIndex], gpuNanos));
        }
        lastFrameProfile = new FrameProfile(0L, passProfiles);
    }

    public FrameProfile lastFrameProfile() {
        return lastFrameProfile;
    }

    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            return;
        }
        width = newWidth;
        height = newHeight;
        if (!allocateResources) {
            return;
        }
        for (Pass pass : passes) {
            if (!pass.useBackbuffer && pass.fixedWidth == 0) {
                allocatePassFramebuffer(pass);
            }
        }
        refreshAttachmentLookup();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (renderTargets != null) {
            renderTargets.close();
        }
        for (Pass pass : passes) {
            if (pass.timer != null) pass.timer.close();
        }
        textureAttachmentIds.clear();
        closed = true;
    }

    private void allocatePassFramebuffer(Pass pass) {
        Framebuffer framebuffer = renderTargets.create(pass.name, descriptorFor(pass));
        registerPassAttachments(pass, framebuffer);
    }

    private FramebufferDescriptor descriptorFor(Pass pass) {
        int targetWidth = pass.fixedWidth > 0 ? pass.fixedWidth : width;
        int targetHeight = pass.fixedHeight > 0 ? pass.fixedHeight : height;
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

    private void refreshAttachmentLookup() {
        textureAttachmentIds.clear();
        for (Pass pass : passes) {
            if (!pass.useBackbuffer) {
                Framebuffer framebuffer = getPassFramebuffer(pass.name);
                if (framebuffer != null) {
                    registerPassAttachments(pass, framebuffer);
                }
            }
        }
    }

    private void registerPassAttachments(Pass pass, Framebuffer framebuffer) {
        for (int i = 0; i < pass.colorTextureNames.size(); i++) {
            if (i < framebuffer.colorAttachmentCount() && framebuffer.colorAttachmentIsTexture(i)) {
                textureAttachmentIds.put(pass.colorTextureNames.get(i), framebuffer.colorAttachment(i));
            }
        }
        if (pass.depthTextureName != null && framebuffer.depthAttachmentIsTexture()) {
            textureAttachmentIds.put(pass.depthTextureName, framebuffer.depthAttachment());
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("RenderGraph is closed");
        }
    }

    @FunctionalInterface
    public interface PassExecutor {
        void execute(PassResources resources, CommandBuffer cmd);
    }

    private static final class Pass {
        final String name;
        final List<String> colorTextureNames;
        final List<RenderFormat> colorFormats;
        final int samples;
        final int fixedWidth;
        final int fixedHeight;
        final boolean createDepth;
        final String depthTextureName;
        final boolean clearColor;
        final boolean clearDepth;
        final float clearR;
        final float clearG;
        final float clearB;
        final float clearA;
        final boolean useBackbuffer;
        final List<String> dependencies;
        final PassExecutor executor;
        GpuTimer timer;

        Pass(String name, List<String> colorTextureNames, List<RenderFormat> colorFormats, int samples,
             int fixedWidth, int fixedHeight,
             boolean createDepth, String depthTextureName, boolean clearColor, boolean clearDepth,
             float clearR, float clearG, float clearB, float clearA, boolean useBackbuffer,
             List<String> dependencies, PassExecutor executor) {
            this.name = name;
            this.colorTextureNames = colorTextureNames;
            this.colorFormats = colorFormats;
            this.samples = samples;
            this.fixedWidth = fixedWidth;
            this.fixedHeight = fixedHeight;
            this.createDepth = createDepth;
            this.depthTextureName = depthTextureName;
            this.clearColor = clearColor;
            this.clearDepth = clearDepth;
            this.clearR = clearR;
            this.clearG = clearG;
            this.clearB = clearB;
            this.clearA = clearA;
            this.useBackbuffer = useBackbuffer;
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
        private boolean createDepth;
        private String depthTextureName;
        private boolean clearColor = true;
        private boolean clearDepth = true;
        private float clearR = 0.08f;
        private float clearG = 0.10f;
        private float clearB = 0.14f;
        private float clearA = 1.0f;
        private boolean useBackbuffer;
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
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("fixed pass dimensions must be positive");
            }
            fixedWidth = width;
            fixedHeight = height;
            return this;
        }

        public PassBuilder writeToBackbuffer() {
            useBackbuffer = true;
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
            Pass pass = new Pass(name, List.copyOf(colorTextureNames), List.copyOf(colorFormats), samples,
                    fixedWidth, fixedHeight,
                    createDepth, depthTextureName, clearColor, clearDepth, clearR, clearG, clearB, clearA,
                    useBackbuffer, List.copyOf(dependencies), executor);
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
                case 34842 -> RenderFormat.RGBA16F;
                default -> throw new IllegalArgumentException("Unsupported legacy GL render format: " + value);
            };
        }
    }
}
