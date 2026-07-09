package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.device.RenderFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderGraphTest {
    @Test
    void compileSortsPassesByDependencies() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Present").dependsOn("Geometry").execute((res, cmd) -> {});
        graph.addPass("Geometry").execute((res, cmd) -> {});

        assertEquals(List.of("Geometry", "Present"), graph.passExecutionOrder());
    }

    @Test
    void compileDetectsCycles() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("A").dependsOn("B").execute((res, cmd) -> {});
        graph.addPass("B").dependsOn("A").execute((res, cmd) -> {});

        assertThrows(GlException.class, graph::compile);
    }

    @Test
    void compileDetectsMissingDependencies() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Present").dependsOn("Geometry").execute((res, cmd) -> {});

        assertThrows(GlException.class, graph::compile);
    }

    @Test
    void resizeUpdatesPositiveDimensionsAndIgnoresMinimizedSize() {
        RenderGraph graph = new RenderGraph(800, 600, false);

        graph.resize(1024, 768);
        assertEquals(1024, graph.width());
        assertEquals(768, graph.height());

        graph.resize(0, 0);
        assertEquals(1024, graph.width());
        assertEquals(768, graph.height());
    }

    @Test
    void passDescriptorSupportsMrtAndDepthTexture() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("GBuffer")
                .createColors(new String[]{"Albedo", "Normal"}, RenderFormat.RGBA8, RenderFormat.RGBA16F)
                .createDepthTexture("Depth")
                .execute((res, cmd) -> {});

        FramebufferDescriptor descriptor = graph.passFramebufferDescriptor("GBuffer");

        assertEquals(2, descriptor.colorAttachments().size());
        assertEquals(FramebufferDescriptor.AttachmentStorage.TEXTURE_2D,
                descriptor.depthAttachment().storage());
    }

    @Test
    void passDescriptorUsesRenderbuffersForMsaaColorAndDepth() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Geometry")
                .createColorMS("SceneColor", RenderFormat.RGBA8, 4)
                .createDepth()
                .execute((res, cmd) -> {});

        FramebufferDescriptor descriptor = graph.passFramebufferDescriptor("Geometry");

        assertEquals(4, descriptor.samples());
        assertEquals(FramebufferDescriptor.AttachmentStorage.RENDERBUFFER,
                descriptor.colorAttachments().get(0).storage());
        assertEquals(FramebufferDescriptor.AttachmentStorage.RENDERBUFFER,
                descriptor.depthAttachment().storage());
    }

    @Test
    @SuppressWarnings("deprecation")
    void passResourcesExposeNarrowAliasesWithoutOwningLifecycle() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Geometry")
                .createColor("SceneColor", RenderFormat.RGBA8)
                .execute((res, cmd) -> {});
        PassResources resources = new PassResources(graph);

        assertEquals(0, resources.colorAttachment("SceneColor"));
        assertEquals(resources.getTextureAttachmentId("SceneColor"), resources.colorAttachment("SceneColor"));
        assertNull(resources.currentTarget());
        assertNull(resources.framebufferOfPass("Geometry"));
        assertEquals(resources.getFramebuffer(), resources.currentTarget());
        assertEquals(resources.getFramebuffer("Geometry"), resources.framebufferOfPass("Geometry"));
    }
}
