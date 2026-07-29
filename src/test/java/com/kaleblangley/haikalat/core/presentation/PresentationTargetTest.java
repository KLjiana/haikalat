package com.kaleblangley.haikalat.core.presentation;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.material.ResourceOwnership;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresentationTargetTest {
    @Test
    void borrowedTargetPreservesRawHandlesAndOwnership() {
        ExternalAttachment color = ExternalAttachment.borrowedColor(
                17, RenderFormat.RGBA8, 1280, 720);
        ExternalAttachment depth = ExternalAttachment.borrowedDepthStencil(18, 1280, 720);

        PresentationTarget target = PresentationTarget.borrowed(
                41, color, depth, 1280, 720, 9);

        assertEquals(41, target.drawFramebufferId());
        assertEquals(41, target.readFramebufferId());
        assertEquals(17, target.color().orElseThrow().textureId());
        assertEquals(18, target.depth().orElseThrow().textureId());
        assertEquals(ResourceOwnership.BORROWED, target.framebufferOwnership());
        assertTrue(target.hasDepth());
        assertTrue(target.hasStencil());
        assertTrue(target.isRenderable());
        assertEquals(9, target.generation());
    }

    @Test
    void zeroExtentIsAValidNonRenderableHostState() {
        PresentationTarget target = PresentationTarget.builder(0, 0)
                .framebuffer(52)
                .generation(4)
                .build();

        assertFalse(target.isRenderable());
        assertEquals(52, target.drawFramebufferId());
    }

    @Test
    void attachmentExtentAndSamplesMustMatchRenderableTarget() {
        ExternalAttachment color = ExternalAttachment.borrowedColor(
                7, RenderFormat.RGBA8, 640, 480);
        assertThrows(IllegalArgumentException.class, () -> PresentationTarget.builder(800, 600)
                .framebuffer(3).color(color).build());
        assertThrows(IllegalArgumentException.class, () -> PresentationTarget.builder(640, 480)
                .framebuffer(3).samples(4).color(color).build());
    }

    @Test
    void roleAndFormatValidationRejectsInvalidDepthAndColor() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalAttachment(
                1, AttachmentRole.COLOR, RenderFormat.DEPTH_COMPONENT24,
                64, 64, 1, ResourceOwnership.BORROWED));
        assertThrows(IllegalArgumentException.class, () -> new ExternalAttachment(
                1, AttachmentRole.DEPTH, RenderFormat.RGBA8,
                64, 64, 1, ResourceOwnership.BORROWED));
    }

    @Test
    void attachmentOwnershipMustMatchFramebufferOwnership() {
        ExternalAttachment borrowed = ExternalAttachment.borrowedColor(
                7, RenderFormat.RGBA8, 64, 64);
        assertThrows(IllegalArgumentException.class, () -> PresentationTarget.builder(64, 64)
                .framebuffer(3)
                .framebufferOwnership(ResourceOwnership.OWNED)
                .color(borrowed)
                .build());
    }

    @Test
    void defaultFramebufferIsBorrowedAndHasNoNativeAttachments() {
        PresentationTarget target = PresentationTarget.defaultFramebuffer(320, 180);
        assertEquals(0, target.drawFramebufferId());
        assertEquals(0, target.readFramebufferId());
        assertTrue(target.color().isEmpty());
        assertEquals(ResourceOwnership.BORROWED, target.framebufferOwnership());
    }
}
