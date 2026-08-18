package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DepthResolveDescriptorTest {
    @Test
    void validatesFormatSamplesAndExactExtent() {
        assertDoesNotThrow(() -> new DepthResolveDescriptor("DEPTH24_STENCIL8",
                "DEPTH_COMPONENT", 4, 1, 1280, 720, 1280, 720));
        assertThrows(IllegalArgumentException.class, () -> new DepthResolveDescriptor(
                "DEPTH24_STENCIL8", "DEPTH_COMPONENT", 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DepthResolveDescriptor(
                "DEPTH24_STENCIL8", "DEPTH_COMPONENT", 4, 1, 2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DepthResolveDescriptor(
                "DEPTH_COMPONENT", "DEPTH_COMPONENT", 4, 1, 1, 1, 1, 1));
    }
}
