package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.RenderTargetManager;
import com.kaleblangley.haikalat.backend.texture.Texture2D;

public interface RenderResourceFactory {
    GlBuffer createBuffer(BufferDescriptor descriptor);

    Texture2D createTexture(TextureDescriptor descriptor);

    Framebuffer createFramebuffer(FramebufferDescriptor descriptor);

    RenderTargetManager createRenderTargetManager();
}
