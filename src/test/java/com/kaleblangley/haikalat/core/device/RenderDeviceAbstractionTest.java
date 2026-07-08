package com.kaleblangley.haikalat.core.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderDeviceAbstractionTest {
    @Test
    void glDeviceAdvertisesImmediateOpenGlBackend() {
        GlRenderDevice device = new GlRenderDevice();

        assertEquals(RenderBackendKind.OPENGL, device.backendKind());
        assertEquals(ExecutionModel.IMMEDIATE, device.executionModel());
        device.transition(ResourceBarrier.texture("SceneColor",
                ResourceLayout.COLOR_ATTACHMENT, ResourceLayout.SHADER_READ));
    }

    @Test
    void resourceBarrierNamesLayoutTransitionsForExplicitBackends() {
        ResourceBarrier barrier = ResourceBarrier.framebuffer("GBuffer",
                ResourceLayout.COLOR_ATTACHMENT, ResourceLayout.SHADER_READ);

        assertEquals(ResourceKind.FRAMEBUFFER, barrier.resourceKind());
        assertEquals("GBuffer", barrier.resourceName());
        assertEquals(ResourceLayout.COLOR_ATTACHMENT, barrier.before());
        assertEquals(ResourceLayout.SHADER_READ, barrier.after());
    }
}
