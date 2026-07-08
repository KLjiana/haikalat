package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.core.BlendMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void bufferDescriptorRejectsInvalidSizes() {
        BufferDescriptor descriptor = BufferDescriptor.uniform(256, BufferUsage.DYNAMIC);

        assertEquals(BufferType.UNIFORM, descriptor.type());
        assertEquals(BufferUsage.DYNAMIC, descriptor.usage());
        assertEquals(256, descriptor.sizeBytes());
        assertThrows(IllegalArgumentException.class,
                () -> BufferDescriptor.vertex(-1, BufferUsage.STATIC));
    }

    @Test
    void textureDescriptorRequiresPositiveDimensions() {
        TextureDescriptor descriptor = TextureDescriptor.color(640, 480, RenderFormat.RGBA8);

        assertEquals(640, descriptor.width());
        assertEquals(480, descriptor.height());
        assertEquals(RenderFormat.RGBA8, descriptor.format());
        assertFalse(descriptor.mipmapped());
        assertThrows(IllegalArgumentException.class,
                () -> TextureDescriptor.color(0, 480, RenderFormat.RGBA8));
    }

    @Test
    void pipelineStatePresetsDescribeCommonStates() {
        PipelineStateDescriptor opaque = PipelineStateDescriptor.opaque();
        PipelineStateDescriptor transparent = PipelineStateDescriptor.transparent();

        assertTrue(opaque.depthTest());
        assertTrue(opaque.depthWrite());
        assertEquals(BlendMode.OPAQUE, opaque.blendMode());
        assertEquals(CullMode.BACK, opaque.cullMode());
        assertTrue(transparent.depthTest());
        assertFalse(transparent.depthWrite());
        assertEquals(BlendMode.ALPHA, transparent.blendMode());
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
