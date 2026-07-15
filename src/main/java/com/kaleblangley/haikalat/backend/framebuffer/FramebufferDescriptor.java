package com.kaleblangley.haikalat.backend.framebuffer;

import com.kaleblangley.haikalat.backend.GlFormats;
import com.kaleblangley.haikalat.backend.RenderFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.GL_R16F;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_RG32F;

public record FramebufferDescriptor(
        int width,
        int height,
        int samples,
        List<ColorAttachment> colorAttachments,
        DepthAttachment depthAttachment
) {
    public FramebufferDescriptor {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        if (samples <= 0) {
            throw new IllegalArgumentException("samples must be positive");
        }
        colorAttachments = List.copyOf(Objects.requireNonNull(colorAttachments, "colorAttachments"));
        depthAttachment = Objects.requireNonNull(depthAttachment, "depthAttachment");
        if (samples > 1) {
            for (ColorAttachment attachment : colorAttachments) {
                if (attachment.storage() == AttachmentStorage.TEXTURE_2D) {
                    throw new IllegalArgumentException("multisampled color textures are not supported yet");
                }
            }
            if (depthAttachment.storage() == AttachmentStorage.TEXTURE_2D) {
                throw new IllegalArgumentException("multisampled depth textures are not supported yet");
            }
        }
    }

    public static FramebufferDescriptor singleColorDepthRenderbuffer(int width, int height) {
        return builder(width, height)
                .colorTexture(GL_RGBA8)
                .depthStencilRenderbuffer()
                .build();
    }

    public static FramebufferDescriptor multisampledColorDepthRenderbuffer(int width, int height, int samples) {
        return builder(width, height)
                .samples(Math.max(2, samples))
                .colorRenderbuffer(GL_RGBA8)
                .depthStencilRenderbuffer()
                .build();
    }

    public static FramebufferDescriptor colorOnly(int width, int height, int internalFormat) {
        return builder(width, height)
                .colorTexture(internalFormat)
                .build();
    }

    public static FramebufferDescriptor mrt(int width, int height, int... internalFormats) {
        Builder builder = builder(width, height);
        for (int format : internalFormats) {
            builder.colorTexture(format);
        }
        return builder.depthStencilRenderbuffer().build();
    }

    public static Builder builder(int width, int height) {
        return new Builder(width, height);
    }

    public FramebufferDescriptor resized(int newWidth, int newHeight) {
        return new FramebufferDescriptor(newWidth, newHeight, samples, colorAttachments, depthAttachment);
    }

    public boolean multisampled() {
        return samples > 1;
    }

    public enum AttachmentStorage {
        TEXTURE_2D,
        RENDERBUFFER,
        NONE
    }

    public record ColorAttachment(
            int internalFormat,
            int externalFormat,
            int dataType,
            AttachmentStorage storage
    ) {
        public ColorAttachment {
            Objects.requireNonNull(storage, "storage");
            if (storage == AttachmentStorage.NONE) {
                throw new IllegalArgumentException("color attachment storage must not be NONE");
            }
        }

        public static ColorAttachment texture(int internalFormat) {
            return new ColorAttachment(internalFormat, colorExternalFormat(internalFormat), colorDataType(internalFormat),
                    AttachmentStorage.TEXTURE_2D);
        }

        public static ColorAttachment renderbuffer(int internalFormat) {
            return new ColorAttachment(internalFormat, colorExternalFormat(internalFormat), colorDataType(internalFormat),
                    AttachmentStorage.RENDERBUFFER);
        }

        private static int colorDataType(int internalFormat) {
            return internalFormat == GL_RGBA16F || internalFormat == GL_R16F || internalFormat == GL_RG32F
                    ? GL_FLOAT : GL_UNSIGNED_BYTE;
        }

        private static int colorExternalFormat(int internalFormat) {
            if (internalFormat == GL_R16F) {
                return GL_RED;
            }
            return internalFormat == GL_RG32F ? GL_RG : GL_RGBA;
        }
    }

    public record DepthAttachment(
            int internalFormat,
            int externalFormat,
            int dataType,
            int attachmentPoint,
            AttachmentStorage storage
    ) {
        public static DepthAttachment none() {
            return new DepthAttachment(0, 0, 0, 0, AttachmentStorage.NONE);
        }

        public static DepthAttachment texture() {
            return new DepthAttachment(GL_DEPTH_COMPONENT24, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT,
                    org.lwjgl.opengl.GL11.GL_FLOAT, GL_DEPTH_ATTACHMENT, AttachmentStorage.TEXTURE_2D);
        }

        public static DepthAttachment stencilRenderbuffer() {
            return new DepthAttachment(GL_DEPTH24_STENCIL8, 0, 0, GL_DEPTH_STENCIL_ATTACHMENT,
                    AttachmentStorage.RENDERBUFFER);
        }
    }

    public static final class Builder {
        private final int width;
        private final int height;
        private int samples = 1;
        private final List<ColorAttachment> colorAttachments = new ArrayList<>();
        private DepthAttachment depthAttachment = DepthAttachment.none();

        private Builder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public Builder samples(int samples) {
            this.samples = samples;
            return this;
        }

        public Builder colorTexture(int internalFormat) {
            colorAttachments.add(ColorAttachment.texture(internalFormat));
            return this;
        }

        public Builder colorTexture(RenderFormat format) {
            return colorTexture(GlFormats.toGl(format));
        }

        public Builder colorRenderbuffer(int internalFormat) {
            colorAttachments.add(ColorAttachment.renderbuffer(internalFormat));
            return this;
        }

        public Builder colorRenderbuffer(RenderFormat format) {
            return colorRenderbuffer(GlFormats.toGl(format));
        }

        public Builder depthTexture() {
            depthAttachment = DepthAttachment.texture();
            return this;
        }

        public Builder depthStencilRenderbuffer() {
            depthAttachment = DepthAttachment.stencilRenderbuffer();
            return this;
        }

        public FramebufferDescriptor build() {
            return new FramebufferDescriptor(width, height, samples, colorAttachments, depthAttachment);
        }
    }
}
