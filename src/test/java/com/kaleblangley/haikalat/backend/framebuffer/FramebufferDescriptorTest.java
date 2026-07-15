package com.kaleblangley.haikalat.backend.framebuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL30.GL_R16F;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_RG32F;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RED;

class FramebufferDescriptorTest {
    @Test
    void srgbRenderFormatUsesSrgbEightBitStorage() {
        FramebufferDescriptor descriptor = FramebufferDescriptor.builder(64, 64)
                .colorTexture(com.kaleblangley.haikalat.backend.RenderFormat.SRGB8_ALPHA8)
                .build();

        FramebufferDescriptor.ColorAttachment color = descriptor.colorAttachments().getFirst();
        assertEquals(GL_SRGB8_ALPHA8, color.internalFormat());
        assertEquals(GL_RGBA, color.externalFormat());
        assertEquals(org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, color.dataType());
    }

    @Test
    void rgba16fTextureUsesFloatUploadMetadata() {
        FramebufferDescriptor descriptor = FramebufferDescriptor.builder(64, 64)
                .colorTexture(com.kaleblangley.haikalat.backend.RenderFormat.RGBA16F)
                .build();
        FramebufferDescriptor.ColorAttachment color = descriptor.colorAttachments().getFirst();

        assertEquals(GL_RGBA16F, color.internalFormat());
        assertEquals(GL_RGBA, color.externalFormat());
        assertEquals(GL_FLOAT, color.dataType());
    }

    @Test
    void r16fTextureUsesSingleChannelFloatMetadata() {
        FramebufferDescriptor descriptor = FramebufferDescriptor.builder(1, 1)
                .colorTexture(com.kaleblangley.haikalat.backend.RenderFormat.R16F)
                .build();
        FramebufferDescriptor.ColorAttachment color = descriptor.colorAttachments().getFirst();

        assertEquals(GL_R16F, color.internalFormat());
        assertEquals(GL_RED, color.externalFormat());
        assertEquals(GL_FLOAT, color.dataType());
    }

    @Test
    void rg32fTextureUsesTwoChannelFloatMetadata() {
        FramebufferDescriptor descriptor = FramebufferDescriptor.builder(1, 1)
                .colorTexture(com.kaleblangley.haikalat.backend.RenderFormat.RG32F)
                .build();
        FramebufferDescriptor.ColorAttachment color = descriptor.colorAttachments().getFirst();

        assertEquals(GL_RG32F, color.internalFormat());
        assertEquals(GL_RG, color.externalFormat());
        assertEquals(GL_FLOAT, color.dataType());
    }

    @Test
    void rgba16fMultisampleRenderbufferKeepsFloatMetadata() {
        FramebufferDescriptor descriptor = FramebufferDescriptor.builder(64, 64)
                .samples(4)
                .colorRenderbuffer(com.kaleblangley.haikalat.backend.RenderFormat.RGBA16F)
                .build();
        FramebufferDescriptor.ColorAttachment color = descriptor.colorAttachments().getFirst();

        assertEquals(GL_RGBA16F, color.internalFormat());
        assertEquals(GL_FLOAT, color.dataType());
        assertEquals(FramebufferDescriptor.AttachmentStorage.RENDERBUFFER, color.storage());
    }

    @Test
    void singleColorDepthRenderbufferDescribesDefaultTarget() {
        FramebufferDescriptor descriptor = FramebufferDescriptor.singleColorDepthRenderbuffer(800, 600);

        assertEquals(800, descriptor.width());
        assertEquals(600, descriptor.height());
        assertEquals(1, descriptor.samples());
        assertFalse(descriptor.multisampled());
        assertEquals(1, descriptor.colorAttachments().size());
        assertEquals(GL_RGBA8, descriptor.colorAttachments().get(0).internalFormat());
        assertEquals(FramebufferDescriptor.AttachmentStorage.TEXTURE_2D,
                descriptor.colorAttachments().get(0).storage());
        assertEquals(GL_DEPTH24_STENCIL8, descriptor.depthAttachment().internalFormat());
        assertEquals(FramebufferDescriptor.AttachmentStorage.RENDERBUFFER,
                descriptor.depthAttachment().storage());
    }

    @Test
    void mrtDescriptorKeepsAllColorFormats() {
        FramebufferDescriptor descriptor = FramebufferDescriptor.mrt(320, 200, GL_RGBA8, GL_RGBA16F);

        assertEquals(2, descriptor.colorAttachments().size());
        assertEquals(GL_RGBA8, descriptor.colorAttachments().get(0).internalFormat());
        assertEquals(GL_RGBA16F, descriptor.colorAttachments().get(1).internalFormat());
    }

    @Test
    void depthTextureDescriptorUsesDepthAttachmentPoint() {
        FramebufferDescriptor descriptor = FramebufferDescriptor.builder(128, 128)
                .colorTexture(GL_RGBA8)
                .depthTexture()
                .build();

        assertEquals(GL_DEPTH_COMPONENT24, descriptor.depthAttachment().internalFormat());
        assertEquals(GL_DEPTH_ATTACHMENT, descriptor.depthAttachment().attachmentPoint());
        assertEquals(FramebufferDescriptor.AttachmentStorage.TEXTURE_2D,
                descriptor.depthAttachment().storage());
    }

    @Test
    void resizePreservesFormatsAndSamples() {
        FramebufferDescriptor resized = FramebufferDescriptor.multisampledColorDepthRenderbuffer(800, 600, 4)
                .resized(1024, 768);

        assertEquals(1024, resized.width());
        assertEquals(768, resized.height());
        assertEquals(4, resized.samples());
        assertTrue(resized.multisampled());
    }

    @Test
    void multisampledTextureAttachmentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FramebufferDescriptor.builder(800, 600)
                .samples(4)
                .colorTexture(GL_RGBA8)
                .build());
        assertThrows(IllegalArgumentException.class, () -> FramebufferDescriptor.builder(800, 600)
                .samples(4)
                .depthTexture()
                .build());
    }
}
