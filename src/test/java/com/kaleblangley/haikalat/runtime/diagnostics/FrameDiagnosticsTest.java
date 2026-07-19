package com.kaleblangley.haikalat.runtime.diagnostics;

import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import com.kaleblangley.haikalat.runtime.RenderStatistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameDiagnosticsTest {
    @TempDir Path temporaryDirectory;

    @Test
    void historyIsBoundedOrderedAndClearStartsNewEpoch() {
        FrameDiagnostics diagnostics = new FrameDiagnostics(DiagnosticsLevel.BASIC, 16);
        RenderStatistics statistics = new RenderStatistics();
        for (int frame = 0; frame < 20; frame++) {
            statistics.beginFrame();
            statistics.endFrame();
            FrameProfile profile = profile(frame);
            diagnostics.publish(statistics.snapshot(), profile, null,
                    new StateCache.Statistics(frame, frame * 2L));
        }

        FrameDiagnostics.History history = diagnostics.history();
        assertEquals(16, history.frames().size());
        assertEquals(4L, history.frames().getFirst().frameSequence());
        assertEquals(19L, diagnostics.latest().frameSequence());
        FrozenDiagnostics frozen = diagnostics.freeze();
        diagnostics.clear();
        assertTrue(diagnostics.history().frames().isEmpty());
        assertEquals(1L, diagnostics.history().epoch());
        assertEquals(16, frozen.history().size());
    }

    @Test
    void unavailableGpuSampleIsNotPresentedAsZeroTime() {
        PassProfile pending = new PassProfile("Geometry", 12L, 0L,
                PassProfile.GpuTimingStatus.PENDING, -1L, 0L, 0L);
        FrameProfile profile = new FrameProfile(20L, List.of(pending), 7L);

        assertFalse(profile.gpuTotalComplete());
        assertEquals(0L, profile.totalGpuNanos());
        assertEquals(PassProfile.GpuTimingStatus.PENDING, profile.passes().getFirst().gpuStatus());
    }

    @Test
    void jsonExportIsDeterministicAndUsesAtomicFinalFile() throws Exception {
        FrameDiagnostics diagnostics = new FrameDiagnostics(DiagnosticsLevel.BASIC, 16);
        RenderStatistics statistics = new RenderStatistics();
        statistics.beginFrame();
        statistics.endFrame();
        diagnostics.publish(statistics.snapshot(), profile(3L), null,
                new StateCache.Statistics(2L, 5L));
        FrozenDiagnostics capture = diagnostics.freeze();
        Path first = temporaryDirectory.resolve("first.json");
        Path second = temporaryDirectory.resolve("second.json");

        DiagnosticsJsonExporter.export(capture, first);
        DiagnosticsJsonExporter.export(capture, second);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        String json = Files.readString(first);
        assertTrue(json.contains("\"schemaVersion\" : 1"));
        assertTrue(json.contains("\"frameSequence\" : 3"));
        assertTrue(Files.list(temporaryDirectory).noneMatch(path -> path.toString().endsWith(".tmp")));
    }

    @Test
    void capacityContractRejectsUnboundedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new FrameDiagnostics(DiagnosticsLevel.BASIC, 15));
        assertThrows(IllegalArgumentException.class,
                () -> new FrameDiagnostics(DiagnosticsLevel.BASIC, 4097));
    }

    @Test
    void duplicateOrRegressingFrameSequenceIsRejected() {
        FrameDiagnostics diagnostics = new FrameDiagnostics(DiagnosticsLevel.BASIC, 16);
        RenderStatistics statistics = new RenderStatistics();
        statistics.beginFrame();
        statistics.endFrame();
        diagnostics.publish(statistics.snapshot(), profile(4), null,
                new StateCache.Statistics(0, 0));

        assertThrows(IllegalStateException.class,
                () -> diagnostics.publish(statistics.snapshot(), profile(4), null,
                        new StateCache.Statistics(0, 0)));
        assertThrows(IllegalStateException.class,
                () -> diagnostics.publish(statistics.snapshot(), profile(3), null,
                        new StateCache.Statistics(0, 0)));
        assertEquals(4L, diagnostics.latest().frameSequence());
    }

    @Test
    void clearDropsPendingSubsystemSummariesAndCloseRejectsReads() {
        FrameDiagnostics diagnostics = new FrameDiagnostics(DiagnosticsLevel.BASIC, 16);
        diagnostics.scene(1, 2, 3, 4);
        diagnostics.ui(1, 2, 3, 4, 5, 6, 7, 8);
        diagnostics.clear();
        RenderStatistics statistics = new RenderStatistics();
        statistics.beginFrame();
        statistics.endFrame();
        diagnostics.publish(statistics.snapshot(), profile(0), null,
                new StateCache.Statistics(0, 0));

        assertTrue(diagnostics.latest().scene().isEmpty());
        assertTrue(diagnostics.latest().ui().isEmpty());
        diagnostics.close();
        assertAll(
                () -> assertThrows(IllegalStateException.class, diagnostics::latest),
                () -> assertThrows(IllegalStateException.class, diagnostics::history),
                () -> assertThrows(IllegalStateException.class, diagnostics::read),
                () -> assertThrows(IllegalStateException.class, diagnostics::freeze),
                () -> assertThrows(IllegalStateException.class, diagnostics::clear));
    }

    @Test
    void exporterRejectsNonFiniteAndInconsistentCaptureBeforeWriting() {
        DiagnosticsSnapshot invalid = new DiagnosticsSnapshot(0L, 0L, 0L,
                DiagnosticsLevel.BASIC, true, Double.NaN, 0L, profile(0),
                new DiagnosticsSnapshot.State(0L, 0L),
                DiagnosticsSnapshot.ResourceSummary.EMPTY,
                DiagnosticsSnapshot.MessageSummary.EMPTY, java.util.Optional.empty(),
                DiagnosticsSnapshot.UploadSummary.EMPTY, java.util.Optional.empty(),
                java.util.Optional.empty());
        FrozenDiagnostics capture = new FrozenDiagnostics(1, 0L, List.of(invalid));
        Path destination = temporaryDirectory.resolve("invalid.json");

        assertThrows(IllegalArgumentException.class,
                () -> DiagnosticsJsonExporter.export(capture, destination));
        assertFalse(Files.exists(destination));

        FrozenDiagnostics.Resource resource = new FrozenDiagnostics.Resource(
                1L, "BUFFER", "test", 7, 0L, 16L, 1L);
        FrozenDiagnostics inconsistentResources = new FrozenDiagnostics(1, 0L, List.of(),
                new FrozenDiagnostics.ResourceTable(List.of(resource), 15L, 1L, 0L, 1L),
                FrozenDiagnostics.MessageTable.EMPTY, FrozenDiagnostics.Metadata.EMPTY);
        Path inconsistentDestination = temporaryDirectory.resolve("inconsistent.json");
        assertThrows(IllegalArgumentException.class,
                () -> DiagnosticsJsonExporter.export(inconsistentResources,
                        inconsistentDestination));
        assertFalse(Files.exists(inconsistentDestination));
    }

    @Test
    void nativeIdsAreOptInAndMetadataIsAlwaysExported() throws Exception {
        FrozenDiagnostics.Resource resource = new FrozenDiagnostics.Resource(
                1L, "BUFFER", "test", 77, 0L, 16L, 1L);
        FrozenDiagnostics capture = new FrozenDiagnostics(1, 0L, List.of(),
                new FrozenDiagnostics.ResourceTable(List.of(resource), 16L, 1L, 0L, 1L),
                FrozenDiagnostics.MessageTable.EMPTY,
                new FrozenDiagnostics.Metadata("0.15-test", "revision",
                        "vendor", "renderer", "4.6"));
        Path safe = temporaryDirectory.resolve("safe.json");
        Path debug = temporaryDirectory.resolve("debug.json");

        DiagnosticsJsonExporter.export(capture, safe);
        DiagnosticsJsonExporter.export(capture, debug,
                DiagnosticsJsonExporter.ExportOptions.WITH_NATIVE_IDS);

        String safeJson = Files.readString(safe);
        assertTrue(safeJson.contains("\"engineVersion\" : \"0.15-test\""));
        assertFalse(safeJson.contains("\"nativeId\""));
        assertTrue(Files.readString(debug).contains("\"nativeId\" : 77"));
    }

    @Test
    void basicVisibilitySummaryKeepsCountsAndTotalButOmitsDetailedStages() throws Exception {
        FrameDiagnostics diagnostics = new FrameDiagnostics(DiagnosticsLevel.BASIC, 16);
        diagnostics.scene(9, 0, 10, 0);
        diagnostics.visibility(new DiagnosticsSnapshot.VisibilitySummary(true, 3,
                10, 9, 1, 7, 3, 6, 4, 2,
                8, 2, 8, 2, 9, 1,
                true, false, false, true,
                11, 12, 13, 14, 60,
                5, 1, 1, 2, 3, 4, 1, 1,
                15, 16, 17, 18));
        RenderStatistics statistics = new RenderStatistics();
        statistics.beginFrame();
        statistics.endFrame();
        diagnostics.publish(statistics.snapshot(), profile(0), null,
                new StateCache.Statistics(0, 0));

        DiagnosticsSnapshot.VisibilitySummary visibility = diagnostics.latest().scene()
                .orElseThrow().visibility().orElseThrow();
        assertEquals(7, visibility.forwardVisible());
        assertEquals(3, visibility.forwardCulled());
        assertEquals(60, visibility.totalQueueBuildNanos());
        assertEquals(0, visibility.modelUpdateNanos());
        assertEquals(0, visibility.shaderChanges());
        assertEquals(8, visibility.staticRenderers());
        assertEquals(8, visibility.modelCacheHits());
        assertEquals(9, visibility.boundsCacheHits());
        assertTrue(visibility.forwardQueueReused());
        assertTrue(visibility.shadowQueueRebuilt());
        assertEquals(15, visibility.commandRecordNanos());
        assertEquals(16, visibility.recordedCommands());
        assertEquals(17, visibility.recordedMatrixSnapshots());
        assertEquals(18, visibility.recordedObjectPayloads());

        Path output = temporaryDirectory.resolve("visibility.json");
        DiagnosticsJsonExporter.export(diagnostics.freeze(), output);
        String json = Files.readString(output);
        assertTrue(json.contains("\"candidateRenderers\" : 10"));
        assertTrue(json.contains("\"forwardVisible\" : 7"));
        assertTrue(json.contains("\"totalQueueBuildNanos\" : 60"));
        assertTrue(json.contains("\"modelCacheHits\" : 8"));
        assertTrue(json.contains("\"forwardQueueReused\" : true"));
        assertTrue(json.contains("\"recordedMatrixSnapshots\" : 17"));
    }

    @Test
    void failedExportLeavesNoTemporaryOrPartialFile() throws Exception {
        Path destination = temporaryDirectory.resolve("occupied.json");
        Files.createDirectory(destination);
        FrozenDiagnostics capture = new FrozenDiagnostics(1, 0L, List.of());

        assertThrows(java.io.IOException.class,
                () -> DiagnosticsJsonExporter.export(capture, destination));
        assertTrue(Files.isDirectory(destination));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static FrameProfile profile(long sequence) {
        return new FrameProfile(10L, List.of(new PassProfile("Present", 2L, 4L,
                PassProfile.GpuTimingStatus.AVAILABLE, sequence, 0L, 0L)), sequence);
    }
}
