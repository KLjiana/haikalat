package com.kaleblangley.haikalat.subsystems.render3d.preview;

import com.kaleblangley.haikalat.core.graph.RenderGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreviewContractsTest {
    @Test
    void logicalKeysIncludePipelineAndOwnerGenerations() {
        PreviewSourceKey first = PreviewSourceKey.graph(3L, "Geometry", "sceneColor",
                PreviewAspect.COLOR);
        PreviewSourceKey same = PreviewSourceKey.graph(3L, "Geometry", "sceneColor",
                PreviewAspect.COLOR);
        PreviewSourceKey rebuilt = PreviewSourceKey.graph(4L, "Geometry", "sceneColor",
                PreviewAspect.COLOR);
        PreviewSourceKey cube = PreviewSourceKey.cube(3L, "environment", 7L);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, rebuilt);
        assertEquals("cube:environment@7", cube.logicalName());
        assertThrows(IllegalArgumentException.class,
                () -> PreviewSourceKey.cube(3L, "environment", 0L));
    }

    @Test
    void optionsClampExposureAndRejectNonFiniteOrAmbiguousRanges() {
        PreviewOptions defaults = PreviewOptions.defaults();

        assertEquals(16.0f, defaults.exposureEv(99.0f).exposureEv());
        assertEquals(-16.0f, defaults.exposureEv(-99.0f).exposureEv());
        assertEquals(0, defaults.cube(PreviewOptions.CubeFace.NEGATIVE_Z, -4).mipLevel());
        assertThrows(IllegalArgumentException.class, () -> defaults.exposureEv(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> defaults.range(1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> defaults.depth(PreviewOptions.DepthInterpretation.PERSPECTIVE_LINEAR,
                        1.0f, 0.5f, false));
        assertThrows(IllegalArgumentException.class, () -> defaults.updateInterval(3));
    }

    @Test
    void catalogDescribesGraphStorageDependenciesAndStaleSelection() {
        RenderGraph.PassDescription geometry = new RenderGraph.PassDescription(
                "Geometry", List.of(), RenderGraph.TargetKind.MANAGED,
                1280, 720, 4,
                List.of(new RenderGraph.AttachmentDescription("sceneColor", "RGBA16F",
                        RenderGraph.StorageKind.RENDERBUFFER)),
                new RenderGraph.AttachmentDescription("depth", "DEPTH24_STENCIL8",
                        RenderGraph.StorageKind.RENDERBUFFER), true, true);
        RenderGraph.PassDescription resolve = new RenderGraph.PassDescription(
                "Resolve", List.of("Geometry"), RenderGraph.TargetKind.MANAGED,
                1280, 720, 1,
                List.of(new RenderGraph.AttachmentDescription("resolved", "RGBA16F",
                        RenderGraph.StorageKind.TEXTURE)), null, false, false);
        RenderGraph.PassDescription present = new RenderGraph.PassDescription(
                "Present", List.of("Resolve"), RenderGraph.TargetKind.BACKBUFFER,
                1280, 720, 1, List.of(), null, false, false);
        RenderGraph.Description graph = new RenderGraph.Description(1280, 720, 9L, true,
                List.of("Geometry", "Resolve", "Present"),
                List.of(geometry, resolve, present));
        PreviewSourceCatalog catalog = PreviewSourceCatalog.from(graph, 5L,
                List.of(new PreviewSourceCatalog.RegisteredCube("environment", "Environment",
                        2L, 64, 7, "RGBA16F", 262_128L)));

        PreviewSourceDescription msaa = catalog.find(PreviewSourceKey.graph(
                5L, "Geometry", "sceneColor", PreviewAspect.COLOR)).orElseThrow();
        assertAll(
                () -> assertTrue(msaa.previewable()),
                () -> assertTrue(msaa.requiresResolve()),
                () -> assertFalse(msaa.directlySampled()),
                () -> assertEquals(List.of("Resolve"), msaa.downstreamGraphDependencies()),
                () -> assertEquals(1280L * 720L * 4L * 8L, msaa.estimatedBytes()));

        PreviewSourceDescription backbuffer = catalog.find(PreviewSourceKey.graph(
                5L, "Present", "backbuffer", PreviewAspect.COLOR)).orElseThrow();
        assertEquals(PreviewSourceDescription.Availability.UNSUPPORTED,
                backbuffer.availability());
        assertTrue(backbuffer.reason().contains("backbuffer"));

        PreviewSourceDescription cube = catalog.find(
                PreviewSourceKey.cube(5L, "environment", 2L)).orElseThrow();
        assertTrue(cube.supportsFace());
        assertTrue(cube.supportsMip());
        assertEquals(PreviewSourceDescription.Availability.STALE,
                catalog.selectionAvailability(PreviewSourceKey.cube(4L, "environment", 2L)));
    }

    @Test
    void memoryEstimatesCoverScalarVectorColorDepthAndMsaa() {
        assertEquals(200L, PreviewSourceCatalog.estimateBytes("R16F", 10, 10, 1));
        assertEquals(800L, PreviewSourceCatalog.estimateBytes("RG32F", 10, 10, 1));
        assertEquals(1_600L, PreviewSourceCatalog.estimateBytes("RGBA8", 10, 10, 4));
        assertEquals(400L,
                PreviewSourceCatalog.estimateBytes("DEPTH24_STENCIL8", 10, 10, 1));
        assertEquals(0L, PreviewSourceCatalog.estimateBytes("INTEGER_UNKNOWN", 10, 10, 1));
    }

    @Test
    void requestRevisionPreventsLateGpuOutputFromPairingWithNewOptions() {
        GraphPreviewController controller = new GraphPreviewController();
        PreviewSourceKey key = PreviewSourceKey.graph(1L, "Geometry", "sceneColor",
                PreviewAspect.COLOR);
        controller.detailedDiagnostics(true);
        controller.panelVisible(true);
        controller.select(key);
        long recordedRevision = controller.request().revision();

        controller.options(controller.options().channel(PreviewOptions.Channel.R));
        controller.publishOutput(recordedRevision, 17, 3, 64, 64);

        assertTrue(controller.output().isEmpty());
        long currentRevision = controller.request().revision();
        controller.publishOutput(currentRevision, 18, 4, 64, 64);
        assertEquals(18, controller.output().orElseThrow().textureId());
    }

    @Test
    void previewErrorsAreBoundedAndAdjacentDuplicatesAreCollapsed() {
        GraphPreviewController controller = new GraphPreviewController();
        controller.reportError("A", "same");
        controller.reportError("A", "same");
        for (int index = 0; index < 20; index++) {
            controller.reportError("E" + index, "message");
        }

        assertEquals(16, controller.recentErrors().size());
        assertEquals("E4:message", controller.recentErrors().getFirst());
    }
}
