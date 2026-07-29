package com.kaleblangley.haikalat.core.presentation;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.material.ResourceOwnership;

import java.util.Objects;

/**
 * Immutable description of a texture attachment used by a presentation target.
 *
 * <p>The descriptor never allocates, deletes, resizes, or changes texture
 * parameters. Lifetime remains governed by {@link #ownership()}.</p>
 *
 * @param textureId positive OpenGL texture name
 * @param role attachment role within the presentation target
 * @param format storage format promised by the owner
 * @param width texture width, or zero only as part of a {@code 0x0} extent
 * @param height texture height, or zero only as part of a {@code 0x0} extent
 * @param samples sample count
 * @param ownership native-resource lifetime contract
 */
public record ExternalAttachment(
        int textureId,
        AttachmentRole role,
        RenderFormat format,
        int width,
        int height,
        int samples,
        ResourceOwnership ownership
) {
    public ExternalAttachment {
        if (textureId <= 0) {
            throw new IllegalArgumentException("external texture id must be positive");
        }
        role = Objects.requireNonNull(role, "role");
        format = Objects.requireNonNull(format, "format");
        ownership = Objects.requireNonNull(ownership, "ownership");
        validateExtent(width, height);
        if (samples <= 0) {
            throw new IllegalArgumentException("external attachment samples must be positive");
        }
        validateFormat(role, format);
    }

    public static ExternalAttachment borrowedColor(int textureId, RenderFormat format,
                                                   int width, int height) {
        return new ExternalAttachment(textureId, AttachmentRole.COLOR, format,
                width, height, 1, ResourceOwnership.BORROWED);
    }

    public static ExternalAttachment borrowedDepth(int textureId, RenderFormat format,
                                                   int width, int height) {
        return new ExternalAttachment(textureId, AttachmentRole.DEPTH, format,
                width, height, 1, ResourceOwnership.BORROWED);
    }

    public static ExternalAttachment borrowedDepthStencil(int textureId, int width, int height) {
        return new ExternalAttachment(textureId, AttachmentRole.DEPTH_STENCIL,
                RenderFormat.DEPTH24_STENCIL8, width, height, 1,
                ResourceOwnership.BORROWED);
    }

    public boolean hasDepth() {
        return role == AttachmentRole.DEPTH || role == AttachmentRole.DEPTH_STENCIL;
    }

    public boolean hasStencil() {
        return role == AttachmentRole.STENCIL || role == AttachmentRole.DEPTH_STENCIL;
    }

    private static void validateExtent(int width, int height) {
        if (width < 0 || height < 0 || (width == 0) != (height == 0)) {
            throw new IllegalArgumentException(
                    "external attachment extent must be positive or exactly 0x0");
        }
    }

    private static void validateFormat(AttachmentRole role, RenderFormat format) {
        boolean depthFormat = format == RenderFormat.DEPTH_COMPONENT24;
        boolean depthStencilFormat = format == RenderFormat.DEPTH24_STENCIL8;
        switch (role) {
            case COLOR -> {
                if (depthFormat || depthStencilFormat) {
                    throw new IllegalArgumentException("color attachment requires a color format");
                }
            }
            case DEPTH -> {
                if (!depthFormat && !depthStencilFormat) {
                    throw new IllegalArgumentException("depth attachment requires a depth format");
                }
            }
            case STENCIL, DEPTH_STENCIL -> {
                if (!depthStencilFormat) {
                    throw new IllegalArgumentException(
                            role + " attachment requires DEPTH24_STENCIL8");
                }
            }
        }
    }
}
