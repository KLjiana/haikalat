package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;

import java.util.Objects;

public final class PassResources {
    private final RenderGraph graph;

    PassResources(RenderGraph graph) {
        this.graph = graph;
    }

    public Framebuffer getFramebuffer(String passName) {
        return graph.getPassFramebuffer(passName);
    }

    public Texture2D getTexture(String textureName) {
        return graph.getTexture(textureName);
    }

    public int getTextureAttachmentId(String textureName) {
        return graph.getTextureAttachmentId(textureName);
    }

    public Framebuffer getFramebuffer() {
        return graph.currentPassFramebuffer();
    }
}
