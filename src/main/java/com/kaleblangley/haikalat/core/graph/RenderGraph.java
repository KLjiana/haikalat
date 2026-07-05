package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderDevice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;

import static org.lwjgl.opengl.GL30.*;

public final class RenderGraph implements AutoCloseable {
    private final List<Pass> passes = new ArrayList<>();
    private final Map<String, Pass> passByName = new HashMap<>();
    private List<Pass> sortedPasses;
    private final Map<String, Framebuffer> fbos = new HashMap<>();
    private final Map<String, Integer> colorTextures = new HashMap<>();
    private final Map<String, Integer> colorRenderbuffers = new HashMap<>();
    private final Map<String, Integer> depthRenderbuffers = new HashMap<>();
    private final Map<String, Texture2D> importedTextures = new HashMap<>();
    private int width;
    private int height;
    private Framebuffer currentFbo;
    private boolean closed;

    public RenderGraph(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        this.width = width;
        this.height = height;
    }

    /** @return 渲染图宽度 */
    public int width() {
        return width;
    }

    /** @return 渲染图高度 */
    public int height() {
        return height;
    }

    /**
     * 添加一个渲染 Pass，返回 PassBuilder 进行流式配置。
     *
     * @param name Pass 名称
     * @return PassBuilder 实例
     */
    public PassBuilder addPass(String name) {
        return new PassBuilder(this, Objects.requireNonNull(name, "name"));
    }

    /**
     * 导入外部纹理供 Pass 在渲染时使用。
     *
     * @param name    纹理标识名
     * @param texture 纹理对象
     */
    public void importTexture(String name, Texture2D texture) {
        importedTextures.put(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(texture, "texture"));
    }

    Framebuffer getPassFramebuffer(String passName) {
        return fbos.get(passName);
    }

    Texture2D getTexture(String textureName) {
        Texture2D imported = importedTextures.get(textureName);
        return imported;
    }

    Framebuffer currentPassFramebuffer() {
        return currentFbo;
    }

    void addPassInternal(Pass pass) {
        passes.add(pass);
        passByName.put(pass.name, pass);
        sortedPasses = null;
        allocatePassFramebuffer(pass);
    }

    /**
     * 拓扑排序并编译渲染图，确定 Pass 执行顺序；检测循环依赖。
     * 编译后可重复调用 execute() 执行。
-     */
    public void compile() {
        Map<String, Integer> inDegree = new HashMap<>();
        for (Pass pass : passes) {
            inDegree.putIfAbsent(pass.name, 0);
            for (String dep : pass.dependencies) {
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
            Pass p = queue.poll();
            sorted.add(p);
            for (Pass other : passes) {
                if (other.dependencies.contains(p.name)) {
                    int deg = inDegree.merge(other.name, -1, Integer::sum);
                    if (deg == 0) {
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

    private void allocatePassFramebuffer(Pass pass) {
        if (pass.useBackbuffer) {
            return;
        }
        int fboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);

        boolean multisampled = pass.samples > 1;
        int colorId = 0;

        if (pass.colorTextureName != null) {
            if (multisampled) {
                colorId = glGenRenderbuffers();
                glBindRenderbuffer(GL_RENDERBUFFER, colorId);
                glRenderbufferStorageMultisample(GL_RENDERBUFFER, pass.samples,
                        pass.colorFormat, width, height);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_RENDERBUFFER, colorId);
                colorRenderbuffers.put(pass.name, colorId);
            } else {
                colorId = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, colorId);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glTexImage2D(GL_TEXTURE_2D, 0, pass.colorFormat, width, height, 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, 0L);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_2D, colorId, 0);
                colorTextures.put(pass.colorTextureName, colorId);
            }
        }

        int depthRbo = 0;
        if (pass.createDepth) {
            depthRbo = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
            if (multisampled) {
                glRenderbufferStorageMultisample(GL_RENDERBUFFER, pass.samples,
                        GL_DEPTH24_STENCIL8, width, height);
            } else {
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
            }
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_RENDERBUFFER, depthRbo);
            depthRenderbuffers.put(pass.name, depthRbo);
        }

        if (multisampled) {
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glReadBuffer(GL_NONE);
        } else {
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
        }
        ensureComplete(fboId);

        Framebuffer fb = new Framebuffer(fboId, colorId, depthRbo,
                width, height, pass.samples, multisampled);
        fbos.put(pass.name, fb);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * 按编译顺序执行所有 Pass。若尚未编译则自动调用 compile()。
     * 每个 Pass 绑定对应 FBO，执行用户定义的渲染逻辑，最后将命令提交至设备。
     *
     * @param device 渲染设备
     */
    public void execute(RenderDevice device) {
        Objects.requireNonNull(device, "device");
        if (sortedPasses == null) {
            compile();
        }
        CommandBuffer cmd = device.createCommandBuffer();
        PassResources resources = new PassResources(this);

        for (Pass pass : sortedPasses) {
            Framebuffer fb = fbos.get(pass.name);
            currentFbo = fb;

            if (pass.useBackbuffer) {
                cmd.enableBlend(false);
                cmd.depthMask(true);
                cmd.bindFramebuffer(GL_FRAMEBUFFER, 0)
                        .viewport(0, 0, width, height);
            } else if (fb != null) {
                cmd.bindFramebuffer(fb)
                        .viewport(0, 0, width, height);
            }

            if (pass.clearColor && !pass.useBackbuffer && fb != null) {
                cmd.clearColor(pass.clearR, pass.clearG, pass.clearB, pass.clearA);
                cmd.clear(true, pass.clearDepth);
            }

            pass.executor.execute(resources, cmd);
        }

        device.execute(cmd);
    }

    /**
     * 用新尺寸重建所有 FBO，释放旧资源。
     *
     * @param newWidth  新宽度
     * @param newHeight 新高度
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            return;
        }
        this.width = newWidth;
        this.height = newHeight;
        disposeResources();
        for (Pass pass : passes) {
            allocatePassFramebuffer(pass);
        }
    }

    private void disposeResources() {
        for (Framebuffer fb : fbos.values()) {
            if (fb != null) {
                glDeleteFramebuffers(fb.id());
            }
        }
        fbos.clear();
        for (int texId : colorTextures.values()) {
            glDeleteTextures(texId);
        }
        colorTextures.clear();
        for (int rbo : colorRenderbuffers.values()) {
            glDeleteRenderbuffers(rbo);
        }
        colorRenderbuffers.clear();
        for (int rbo : depthRenderbuffers.values()) {
            glDeleteRenderbuffers(rbo);
        }
        depthRenderbuffers.clear();
    }

    private static void ensureComplete(int fboId) {
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new GlException("Framebuffer " + fboId + " is incomplete");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        disposeResources();
        closed = true;
    }

    @FunctionalInterface
    public interface PassExecutor {
        void execute(PassResources resources, CommandBuffer cmd);
    }

    private static class Pass {
        final String name;
        final String colorTextureName;
        final int colorFormat;
        final int samples;
        final boolean createDepth;
        final boolean clearColor;
        final boolean clearDepth;
        final float clearR, clearG, clearB, clearA;
        final boolean useBackbuffer;
        final List<String> dependencies;
        final PassExecutor executor;

        Pass(String name, String colorTextureName, int colorFormat, int samples,
             boolean createDepth, boolean clearColor, boolean clearDepth,
             float clearR, float clearG, float clearB, float clearA,
             boolean useBackbuffer, List<String> dependencies, PassExecutor executor) {
            this.name = name;
            this.colorTextureName = colorTextureName;
            this.colorFormat = colorFormat;
            this.samples = samples;
            this.createDepth = createDepth;
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
        private String colorTextureName;
        private int colorFormat = GL_RGBA8;
        private int samples = 1;
        private boolean createDepth;
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

        /**
         * 为此 Pass 创建一个单采样颜色附件纹理。
         *
         * @param textureName 纹理标识名
         * @param format      内部格式
         */
        public PassBuilder createColor(String textureName, int format) {
            this.colorTextureName = Objects.requireNonNull(textureName, "textureName");
            this.colorFormat = format;
            this.samples = 1;
            return this;
        }

        /**
         * 为此 Pass 创建一个单采样颜色附件纹理（默认 RGBA8 格式）。
         *
         * @param textureName 纹理标识名
         */
        public PassBuilder createColor(String textureName) {
            return createColor(textureName, GL_RGBA8);
        }

        /**
         * 为此 Pass 创建一个多重采样颜色附件渲染缓冲区。
         *
         * @param textureName 纹理标识名
         * @param format      内部格式
         * @param samples     采样数
         */
        public PassBuilder createColorMS(String textureName, int format, int samples) {
            this.colorTextureName = Objects.requireNonNull(textureName, "textureName");
            this.colorFormat = format;
            this.samples = Math.max(2, samples);
            return this;
        }

        /**
         * 为此 Pass 创建一个多重采样颜色附件渲染缓冲区（默认 RGBA8 格式）。
         *
         * @param textureName 纹理标识名
         * @param samples     采样数
         */
        public PassBuilder createColorMS(String textureName, int samples) {
            return createColorMS(textureName, GL_RGBA8, samples);
        }

        /** 为此 Pass 创建深度附件。 */
        public PassBuilder createDepth() {
            this.createDepth = true;
            return this;
        }

        /** 令此 Pass 直接渲染到默认帧缓冲（回显缓冲）。 */
        public PassBuilder writeToBackbuffer() {
            this.useBackbuffer = true;
            return this;
        }

        /**
         * 声明此 Pass 依赖于另一个 Pass。
         *
         * @param passName 被依赖的 Pass 名称
         */
        public PassBuilder dependsOn(String passName) {
            dependencies.add(Objects.requireNonNull(passName, "passName"));
            return this;
        }

        /**
         * 设置此 Pass 的清除颜色。
         *
         * @param r 红色分量
         * @param g 绿色分量
         * @param b 蓝色分量
         * @param a 透明分量
         */
        public PassBuilder clearColor(float r, float g, float b, float a) {
            this.clearR = r;
            this.clearG = g;
            this.clearB = b;
            this.clearA = a;
            return this;
        }

        /** 禁用在执行前清除颜色和深度缓冲。 */
        public PassBuilder noClear() {
            this.clearColor = false;
            this.clearDepth = false;
            return this;
        }

        /**
         * 指定此 Pass 的执行逻辑，并将构建好的 Pass 注册到 RenderGraph。
         *
         * @param executor Pass 执行接口
         * @return 所属的 RenderGraph
         */
        public RenderGraph execute(PassExecutor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            Pass pass = new Pass(name, colorTextureName, colorFormat, samples,
                    createDepth, clearColor, clearDepth, clearR, clearG, clearB, clearA,
                    useBackbuffer, List.copyOf(dependencies), executor);
            graph.addPassInternal(pass);
            return graph;
        }
    }
}
