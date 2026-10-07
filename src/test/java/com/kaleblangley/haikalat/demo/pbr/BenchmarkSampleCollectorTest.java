package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.graph.PassProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkSampleCollectorTest {
    private static final String ASSIGN = "ClusteredClusterAssign";
    private static final String GEOMETRY = "GeometryPass";

    @Test
    void latePreviousRoundAndTailSamplesKeepSubmissionIdentity() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-a");
        collector.register(measured(0, 120, 0, 0));
        collector.register(warmup(0, 420, 1));
        collector.register(measured(0, 540, 1, 1));

        collector.acceptAvailable(0, available(ASSIGN, 540, 30));
        collector.acceptAvailable(0, available(ASSIGN, 120, 10));
        assertTrue(collector.terminalFor(Set.of(ASSIGN)));
        assertFalse(collector.terminalFor(Set.of(ASSIGN, GEOMETRY)));
        collector.acceptAvailable(0, available(GEOMETRY, 120, 20));
        collector.acceptAvailable(0, available(GEOMETRY, 540, 40));

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN, GEOMETRY));
        assertTrue(collector.terminalFor(Set.of(ASSIGN, GEOMETRY)));
        assertEquals(List.of(10L, 30L), snapshot.measuredByPass().get(ASSIGN).stream()
                .map(BenchmarkSampleCollector.MeasuredSample::nanos).toList());
        assertEquals(30L, snapshot.paired(Set.of(ASSIGN, GEOMETRY)).get(0).nanos());
        assertEquals(70L, snapshot.paired(Set.of(ASSIGN, GEOMETRY)).get(1).nanos());
    }

    @Test
    void warmupSamplesAreArchivedButExcludedFromMeasuredCoverage() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-b");
        collector.register(warmup(0, 0, 0));
        collector.register(measured(0, 1, 0, 0));
        collector.acceptAvailable(0, available(ASSIGN, 0, 5));
        collector.acceptAvailable(0, available(ASSIGN, 1, 7));

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN));
        assertEquals(2, snapshot.rawSamples().size());
        assertEquals(1, snapshot.measuredByPass().get(ASSIGN).size());
        assertEquals(1.0, snapshot.minimumCoverage(ASSIGN), 1.0e-9);
    }

    @Test
    void standaloneLaterRoundOnlyReportsRoundsThatWereActuallySubmitted() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("standalone-round-2");
        collector.register(warmup(7, 0, 2));
        collector.register(measured(7, 1, 2, 0));
        collector.acceptAvailable(7, available(ASSIGN, 1, 11));
        var snapshot = collector.snapshot(Set.of(ASSIGN));
        assertEquals(1, snapshot.coverage().size());
        assertEquals(2, snapshot.coverage().getFirst().round());
        assertEquals(1.0, snapshot.minimumCoverage(ASSIGN), 1.0e-9);
        assertEquals(2, snapshot.paired(Set.of(ASSIGN)).getFirst().round());
    }

    @Test
    void exactDuplicateIsCountedAndConflictingDuplicateFails() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-c");
        collector.register(measured(0, 2, 0, 0));
        collector.acceptAvailable(0, available(ASSIGN, 2, 9));
        collector.acceptAvailable(0, available(ASSIGN, 2, 9));
        collector.acceptAvailable(0, available(ASSIGN, 2, 10));

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN));
        assertEquals(1, snapshot.duplicateSamples());
        assertEquals(1, snapshot.conflictingSamples());
        assertFalse(snapshot.failures().isEmpty());
        assertEquals(9L, snapshot.measuredByPass().get(ASSIGN).get(0).nanos());
    }

    @Test
    void unknownSequenceIsAFormalFailure() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-d");
        collector.register(measured(0, 0, 0, 0));
        collector.acceptAvailable(0, available(ASSIGN, 99, 3));

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN));
        assertEquals(1, snapshot.unknownSamples());
        assertFalse(snapshot.failures().isEmpty());
        assertEquals(0.0, snapshot.minimumCoverage(ASSIGN), 1.0e-9);
    }

    @Test
    void generationSequenceResetDoesNotCollide() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-e");
        collector.register(measured(0, 0, 0, 0));
        collector.register(measured(1, 0, 0, 1));
        collector.acceptAvailable(1, available(ASSIGN, 0, 12));
        collector.acceptAvailable(0, available(ASSIGN, 0, 11));

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN));
        assertEquals(List.of(11L, 12L), snapshot.measuredByPass().get(ASSIGN).stream()
                .map(BenchmarkSampleCollector.MeasuredSample::nanos).toList());
        assertEquals(1.0, snapshot.minimumCoverage(ASSIGN), 1.0e-9);
    }

    @Test
    void skippedFailedAndNotApplicableAreTerminalButNeverZeroSamples() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-f");
        collector.register(measured(0, 0, 0, 0));
        collector.acceptAttempt(0, 0, new PassProfile(ASSIGN, 0, 0,
                PassProfile.GpuTimingStatus.SKIPPED, -1, 0, 1));
        collector.markNotApplicable(0, 0, GEOMETRY);

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN, GEOMETRY));
        assertTrue(collector.terminalFor(Set.of(ASSIGN, GEOMETRY)));
        assertTrue(snapshot.measuredByPass().isEmpty());
        assertEquals(0.0, snapshot.minimumCoverage(ASSIGN), 1.0e-9);
        assertEquals(0.0, snapshot.minimumCoverage(GEOMETRY), 1.0e-9);
    }

    @Test
    void pairedSetRequiresEveryPassFromTheSameFrame() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-g");
        collector.register(measured(0, 0, 0, 0));
        collector.register(measured(0, 1, 0, 1));
        collector.acceptAvailable(0, available(ASSIGN, 0, 1));
        collector.acceptAvailable(0, available(GEOMETRY, 1, 2));

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN, GEOMETRY));
        assertTrue(snapshot.paired(Set.of(ASSIGN, GEOMETRY)).isEmpty());
    }

    @Test
    void zeroNanosecondResultIsStillAPresentPairedSample() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-zero");
        collector.register(measured(0, 0, 0, 0));
        collector.acceptAvailable(0, available(ASSIGN, 0, 0));
        collector.acceptAvailable(0, available(GEOMETRY, 0, 7));

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN, GEOMETRY));
        assertEquals(1, snapshot.paired(Set.of(ASSIGN, GEOMETRY)).size());
        assertEquals(7L, snapshot.paired(Set.of(ASSIGN, GEOMETRY)).getFirst().nanos());
    }

    @Test
    void coverageIsComputedPerRoundAtTheNinetyFivePercentBoundary() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-h");
        for (int sequence = 0; sequence < 20; sequence++) {
            collector.register(measured(0, sequence, 0, sequence));
            if (sequence < 19) collector.acceptAvailable(0, available(ASSIGN, sequence, 1));
        }

        BenchmarkSampleCollector.Snapshot snapshot = collector.snapshot(Set.of(ASSIGN));
        assertEquals(0.95, snapshot.minimumCoverage(ASSIGN), 1.0e-9);
        assertFalse(collector.terminalFor(Set.of(ASSIGN)),
                "a coverage gate does not make the missing tail query terminal");
    }

    @Test
    void checkpointOnlyWaitsForTicketsThroughItsMeasuredIndex() {
        BenchmarkSampleCollector collector = new BenchmarkSampleCollector("run-checkpoint");
        collector.register(measured(0, 10, 0, 0));
        collector.register(measured(0, 11, 0, 1));
        collector.acceptAvailable(0, available(ASSIGN, 10, 4));

        assertTrue(collector.terminalThroughMeasuredIndex(Set.of(ASSIGN), 0));
        assertFalse(collector.terminalThroughMeasuredIndex(Set.of(ASSIGN), 1));
        assertFalse(collector.terminalFor(Set.of(ASSIGN)));
    }

    private static BenchmarkSampleCollector.FrameTicket measured(long generation, long sequence,
                                                                   int round, int index) {
        return new BenchmarkSampleCollector.FrameTicket(generation, sequence, round,
                BenchmarkSampleCollector.Phase.MEASURED, index);
    }

    private static BenchmarkSampleCollector.FrameTicket warmup(long generation, long sequence,
                                                                 int round) {
        return new BenchmarkSampleCollector.FrameTicket(generation, sequence, round,
                BenchmarkSampleCollector.Phase.WARMUP, -1);
    }

    private static PassProfile available(String pass, long sequence, long nanos) {
        return new PassProfile(pass, 0L, nanos, PassProfile.GpuTimingStatus.AVAILABLE,
                sequence, 0L, 0L);
    }
}
