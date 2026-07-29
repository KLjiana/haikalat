package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;

import java.util.Objects;

public final class PassResources {
    private final RenderGraph graph;

    PassResources(RenderGraph graph) {
        this.graph = graph;
    }

    /**
     * 返回 pass 声明的逻辑颜色附件所对应的 OpenGL texture id。
     * 仅需附件 id 时，应优先使用该方法，而不是先取得 framebuffer。
     */
    public int colorAttachment(String textureName) {
        return graph.getTextureAttachmentId(Objects.requireNonNull(textureName, "textureName"));
    }

    /** @return pass 声明的逻辑深度纹理所对应的 OpenGL texture id */
    public int depthAttachment(String textureName) {
        return graph.getTextureAttachmentId(Objects.requireNonNull(textureName, "textureName"));
    }

    /**
     * 返回指定 pass 持有的 framebuffer。
     * 该方法供需要 blit、尺寸等 backend framebuffer 状态的内部 OpenGL pass 使用。
     */
    public Framebuffer framebufferOfPass(String passName) {
        return graph.getPassFramebuffer(Objects.requireNonNull(passName, "passName"));
    }

    /**
     * 返回当前执行 pass 绑定的 framebuffer。
     * 普通 pass 通常应通过命令和逻辑资源名完成渲染。
     */
    public Framebuffer currentTarget() {
        return graph.currentPassFramebuffer();
    }

    /**
     * Returns the explicit presentation target bound for the current pass, or
     * {@code null} for a managed offscreen pass.
     */
    public PresentationTarget presentationTarget() {
        return graph.currentPresentationTarget();
    }

    /**
     * Returns the host target supplied for this graph execution, including
     * during managed offscreen passes.
     */
    public PresentationTarget framePresentationTarget() {
        return graph.framePresentationTarget();
    }

    /**
     * 面向 backend 的兼容 API；新代码优先使用 {@link #framebufferOfPass(String)}。
     */
    @Deprecated(since = "0.6", forRemoval = false)
    Framebuffer getFramebuffer(String passName) {
        return framebufferOfPass(passName);
    }

    /**
     * 面向需要具体 texture 对象的内部 pass 的 backend 兼容 API。
     * 仅需要 id 的普通 pass 应使用逻辑资源名和 {@link #colorAttachment(String)}。
     */
    Texture2D getTexture(String textureName) {
        return graph.getTexture(Objects.requireNonNull(textureName, "textureName"));
    }

    /**
     * 面向 backend 的兼容 API；新代码优先使用 {@link #colorAttachment(String)}。
     */
    @Deprecated(since = "0.6", forRemoval = false)
    int getTextureAttachmentId(String textureName) {
        return colorAttachment(textureName);
    }

    /**
     * 面向 backend 的兼容 API；新代码优先使用 {@link #currentTarget()}。
     */
    @Deprecated(since = "0.6", forRemoval = false)
    Framebuffer getFramebuffer() {
        return currentTarget();
    }
}
