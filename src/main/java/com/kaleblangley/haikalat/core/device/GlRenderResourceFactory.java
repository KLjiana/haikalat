package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.RenderTargetManager;
import com.kaleblangley.haikalat.backend.texture.Texture2D;

import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;

public class GlRenderResourceFactory implements RenderResourceFactory {
    @Override
    public GlBuffer createBuffer(BufferDescriptor descriptor) {
        GlBuffer buffer = new GlBuffer(toGlTarget(descriptor.type()), toGlUsage(descriptor.usage()));
        if (descriptor.sizeBytes() > 0) {
            buffer.allocate(descriptor.sizeBytes());
        }
        return buffer;
    }

    @Override
    public Texture2D createTexture(TextureDescriptor descriptor) {
        return Texture2D.fromDescriptor(descriptor);
    }

    @Override
    public Framebuffer createFramebuffer(FramebufferDescriptor descriptor) {
        return Framebuffer.fromDescriptor(descriptor);
    }

    @Override
    public RenderTargetManager createRenderTargetManager() {
        return new RenderTargetManager(this::createFramebuffer);
    }

    private static int toGlTarget(BufferType type) {
        return switch (type) {
            case VERTEX -> GL_ARRAY_BUFFER;
            case INDEX -> GL_ELEMENT_ARRAY_BUFFER;
            case UNIFORM -> GL_UNIFORM_BUFFER;
        };
    }

    private static int toGlUsage(BufferUsage usage) {
        return switch (usage) {
            case STATIC -> GL_STATIC_DRAW;
            case DYNAMIC -> GL_DYNAMIC_DRAW;
            case STREAM -> GL_STREAM_DRAW;
        };
    }
}
