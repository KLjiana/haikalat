package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.fb.Framebuffer;
import com.kaleblangley.haikalat.gl.material.Texture2D;

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

    public Framebuffer getFramebuffer() {
        return graph.currentPassFramebuffer();
    }
}
