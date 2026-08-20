package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.core.presentation.PresentationResult;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.ExecutionModel;
import com.kaleblangley.haikalat.core.device.RenderBackendKind;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.device.ResourceBarrier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    void fixedSizePassDescriptorDoesNotUseGraphDimensions() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Shadow")
                .createDepthTexture("ShadowDepth")
                .fixedSize(2048, 2048)
                .execute((res, cmd) -> {});

        FramebufferDescriptor descriptor = graph.passFramebufferDescriptor("Shadow");

        assertEquals(2048, descriptor.width());
        assertEquals(2048, descriptor.height());
    }

    @Test
    void relativeSizeRoundsOddDimensionsAndTracksResize() {
        RenderGraph graph = new RenderGraph(5, 3, false);
        graph.addPass("Half")
                .createColor("HalfColor")
                .relativeSize(0.5f)
                .execute((res, cmd) -> {});

        FramebufferDescriptor initial = graph.passFramebufferDescriptor("Half");
        assertEquals(3, initial.width());
        assertEquals(2, initial.height());

        graph.resize(1, 1);
        FramebufferDescriptor minimum = graph.passFramebufferDescriptor("Half");
        assertEquals(1, minimum.width());
        assertEquals(1, minimum.height());

        graph.resize(0, 0);
        assertEquals(1, graph.passFramebufferDescriptor("Half").width());
    }

    @Test
    void relativeSizeCeilPreservesOddHalfResolutionExtent() {
        RenderGraph graph = new RenderGraph(5, 3, false);
        graph.addPass("HalfCeil")
                .createColor("HalfColor")
                .relativeSizeCeil(0.5f)
                .execute((res, cmd) -> {});

        FramebufferDescriptor initial = graph.passFramebufferDescriptor("HalfCeil");
        assertEquals(3, initial.width());
        assertEquals(2, initial.height());

        graph.resize(7, 5);
        FramebufferDescriptor resized = graph.passFramebufferDescriptor("HalfCeil");
        assertEquals(4, resized.width());
        assertEquals(3, resized.height());
    }

    @Test
    void fixedAndRelativeSizesAreMutuallyExclusive() {
        RenderGraph graph = new RenderGraph(800, 600, false);

        assertThrows(IllegalStateException.class, () -> graph.addPass("A")
                .fixedSize(64, 64)
                .relativeSize(0.5f));
        assertThrows(IllegalStateException.class, () -> graph.addPass("B")
                .relativeSize(0.5f)
                .fixedSize(64, 64));
        assertThrows(IllegalArgumentException.class, () -> graph.addPass("C").relativeSize(0.0f));
    }

    @Test
    void externalTargetPassKeepsOrderingWithoutAllocatingGraphFramebuffer() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Luminance").execute((res, cmd) -> {});
        graph.addPass("Adaptation")
                .writeToExternalTarget()
                .noClear()
                .dependsOn("Luminance")
                .execute((res, cmd) -> {});

        assertEquals(List.of("Luminance", "Adaptation"), graph.passExecutionOrder());
        assertNull(graph.passFramebufferDescriptor("Adaptation"));
        assertThrows(IllegalStateException.class, () -> graph.addPass("Invalid")
                .writeToExternalTarget()
                .execute((res, cmd) -> {}));
    }

    @Test
    void borrowedExternalImportsCanBeReplacedWithoutChangingTopology() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Present")
                .writeToPresentationTarget("host")
                .noClear()
                .execute((res, cmd) -> {});
        long revision = graph.description().topologyRevision();

        ExternalAttachment firstColor = ExternalAttachment.borrowedColor(
                11, RenderFormat.RGBA8, 800, 600);
        graph.importExternalColor("hostColor", firstColor);
        graph.importPresentationTarget("host", PresentationTarget.borrowed(
                21, firstColor, null, 800, 600, 1));
        assertEquals(11, new PassResources(graph).colorAttachment("hostColor"));
        assertEquals(800, graph.description().passes().get(0).width());

        ExternalAttachment secondColor = ExternalAttachment.borrowedColor(
                12, RenderFormat.RGBA8, 1024, 768);
        graph.importExternalColor("hostColor", secondColor);
        graph.importPresentationTarget("host", PresentationTarget.borrowed(
                22, secondColor, null, 1024, 768, 2));

        assertEquals(12, new PassResources(graph).colorAttachment("hostColor"));
        assertEquals(1024, graph.description().passes().get(0).width());
        assertEquals(revision, graph.description().topologyRevision());
        graph.close();
        assertTrue(firstColor.ownership()
                == com.kaleblangley.haikalat.core.material.ResourceOwnership.BORROWED);
    }

    @Test
    void zeroExtentSkipsBeforeAllocatingTimersOrSubmittingCommands() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Present").writeToBackbuffer().noClear()
                .execute((resources, commands) -> fail("zero extent must not record passes"));
        CountingDevice device = new CountingDevice();

        assertEquals(PresentationResult.SKIPPED_ZERO_EXTENT,
                graph.execute(device, PresentationTarget.defaultFramebuffer(0, 0)));
        assertEquals(0, device.executions);
        assertEquals(0, graph.lastRecordedCommandCount());
    }

    private static final class CountingDevice implements RenderDevice {
        int executions;

        @Override public RenderBackendKind backendKind() { return RenderBackendKind.OPENGL; }
        @Override public ExecutionModel executionModel() { return ExecutionModel.EXPLICIT; }
        @Override public CommandBuffer createCommandBuffer() { return new CommandBuffer(); }
        @Override public void execute(CommandBuffer buffer) { executions++; }
        @Override public void invalidateState() {}
        @Override public void transition(ResourceBarrier... barriers) {}
    }

    @Test
    void passQueriesDistinguishBackbufferOffscreenAndMissingPasses() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        graph.addPass("Geometry").createColor("SceneColor").execute((res, cmd) -> {});
        graph.addPass("Present")
                .writeToBackbuffer()
                .noClear()
                .dependsOn("Geometry")
                .execute((res, cmd) -> {});

        assertTrue(graph.hasPass("Geometry"));
        assertTrue(graph.hasPass("Present"));
        assertFalse(graph.hasPass("Missing"));
        assertFalse(graph.passWritesToBackbuffer("Geometry"));
        assertTrue(graph.passWritesToBackbuffer("Present"));
        assertFalse(graph.passWritesToBackbuffer("Missing"));
    }

    @Test
    void sealedTopologyRejectsNewAndPreviouslyStartedPassBuilders() {
        RenderGraph graph = new RenderGraph(800, 600, false);
        RenderGraph.PassBuilder pending = graph.addPass("Pending");
        graph.addPass("Present").writeToBackbuffer().noClear().execute((res, cmd) -> {});

        graph.sealTopology();
        graph.sealTopology();

        assertTrue(graph.isTopologySealed());
        assertThrows(IllegalStateException.class, () -> graph.addPass("Late"));
        assertThrows(IllegalStateException.class, () -> pending.execute((res, cmd) -> {}));
        assertEquals(List.of("Present"), graph.passExecutionOrder(),
                "Sealing must still allow graph compilation");

        graph.resize(1024, 768);
        assertEquals(1024, graph.width());
        assertEquals(768, graph.height());
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
