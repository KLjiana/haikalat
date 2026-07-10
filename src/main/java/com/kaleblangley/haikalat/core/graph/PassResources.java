package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;

import java.util.Objects;

public final class PassResources {
    private final RenderGraph graph;

    PassResources(RenderGraph graph) {
        this.graph = graph;
    }

    /**
     * Returns the OpenGL texture id for a logical color attachment name declared by a pass.
     * Prefer this over pulling a framebuffer only to read its color attachment id.
     */
    public int colorAttachment(String textureName) {
        return graph.getTextureAttachmentId(Objects.requireNonNull(textureName, "textureName"));
    }

    /** Returns the OpenGL texture id for a logical depth texture declared by a pass. */
    public int depthAttachment(String textureName) {
        return graph.getTextureAttachmentId(Objects.requireNonNull(textureName, "textureName"));
    }

    /**
     * Returns the framebuffer owned by a named pass.
     * This is intended for internal OpenGL passes that need backend framebuffer state such as blits or dimensions.
     */
    public Framebuffer framebufferOfPass(String passName) {
        return graph.getPassFramebuffer(Objects.requireNonNull(passName, "passName"));
    }

    /**
     * Returns the framebuffer currently bound for the executing pass.
     * Ordinary passes should usually render through commands and logical resource names instead.
     */
    public Framebuffer currentTarget() {
        return graph.currentPassFramebuffer();
    }

    /**
     * Backend-facing compatibility API. Prefer {@link #framebufferOfPass(String)} for new code.
     */
    @Deprecated(since = "0.6", forRemoval = false)
    Framebuffer getFramebuffer(String passName) {
        return framebufferOfPass(passName);
    }

    /**
     * Backend-facing compatibility API for internal passes that need a concrete texture object.
     * Ordinary passes should use logical resource names and {@link #colorAttachment(String)} when only the id is needed.
     */
    Texture2D getTexture(String textureName) {
        return graph.getTexture(Objects.requireNonNull(textureName, "textureName"));
    }

    /**
     * Backend-facing compatibility API. Prefer {@link #colorAttachment(String)} for new code.
     */
    @Deprecated(since = "0.6", forRemoval = false)
    int getTextureAttachmentId(String textureName) {
        return colorAttachment(textureName);
    }

    /**
     * Backend-facing compatibility API. Prefer {@link #currentTarget()} for new code.
     */
    @Deprecated(since = "0.6", forRemoval = false)
    Framebuffer getFramebuffer() {
        return currentTarget();
    }
}
