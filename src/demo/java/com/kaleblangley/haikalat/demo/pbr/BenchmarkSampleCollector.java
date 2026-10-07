package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.graph.PassProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Attributes asynchronous GPU query results to the frame that submitted them.
 *
 * <p>The collector deliberately has no OpenGL dependency.  A benchmark first
 * registers each submitted frame and can then deliver samples in any order,
 * including after a later round has begun.  A sample is never classified from
 * the CPU loop that happened to drain it.</p>
 */
final class BenchmarkSampleCollector {
    enum Phase { WARMUP, MEASURED }

    private final String runId;
    private final Map<FrameKey, FrameTicket> tickets = new LinkedHashMap<>();
    private final Map<SampleKey, MutableSample> samples = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private int duplicateSamples;
    private int conflictingSamples;
    private int unknownSamples;

    BenchmarkSampleCollector(String runId) {
        this.runId = requireText(runId, "runId");
    }

    void register(FrameTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        FrameKey key = new FrameKey(ticket.generationId(), ticket.sequence());
        FrameTicket previous = tickets.putIfAbsent(key, ticket);
        if (previous != null && !previous.equals(ticket)) {
            failures.add("conflicting frame ticket " + key + ": " + previous + " vs " + ticket);
        }
    }

    void acceptAvailable(long generationId, PassProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile.gpuStatus() != PassProfile.GpuTimingStatus.AVAILABLE) {
            throw new IllegalArgumentException("available sample required");
        }
        accept(generationId, profile.sampleFrameSequence(), profile.passName(),
                profile.gpuStatus(), profile.gpuNanos(), profile.skippedSubmissions());
    }

    void acceptAttempt(long generationId, long frameSequence, PassProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile.gpuStatus() != PassProfile.GpuTimingStatus.SKIPPED
                && profile.gpuStatus() != PassProfile.GpuTimingStatus.FAILED
                && profile.gpuStatus() != PassProfile.GpuTimingStatus.UNSUPPORTED) {
            return;
        }
        accept(generationId, frameSequence, profile.passName(), profile.gpuStatus(), 0L,
                profile.skippedSubmissions());
    }

    void markNotApplicable(long generationId, long frameSequence, String passName) {
        accept(generationId, frameSequence, passName,
                PassProfile.GpuTimingStatus.UNSUPPORTED, 0L, 0L);
    }

    private void accept(long generationId, long frameSequence, String passName,
                        PassProfile.GpuTimingStatus status, long nanos,
                        long skippedSubmissions) {
        FrameKey frameKey = new FrameKey(generationId, frameSequence);
        FrameTicket ticket = tickets.get(frameKey);
        if (ticket == null) {
            unknownSamples++;
            failures.add("sample has no frame ticket: generation=" + generationId
                    + " sequence=" + frameSequence + " pass=" + passName
                    + " status=" + status);
            return;
        }
        SampleKey key = new SampleKey(runId, generationId, requireText(passName, "passName"),
                frameSequence);
        MutableSample candidate = new MutableSample(key, ticket, status, nanos,
                skippedSubmissions);
        MutableSample previous = samples.putIfAbsent(key, candidate);
        if (previous == null) return;
        if (previous.sameValue(candidate)) {
            previous.duplicateCount++;
            duplicateSamples++;
            return;
        }
        conflictingSamples++;
        failures.add("conflicting GPU sample " + key + ": " + previous.status + "/"
                + previous.nanos + " vs " + status + "/" + nanos);
    }

    boolean terminalFor(Collection<String> requiredPasses) {
        return terminalThroughMeasuredIndex(requiredPasses, Integer.MAX_VALUE);
    }

    boolean terminalThroughMeasuredIndex(Collection<String> requiredPasses,
                                         int measuredIndexInclusive) {
        if (measuredIndexInclusive < 0) {
            throw new IllegalArgumentException("measuredIndexInclusive must be non-negative");
        }
        Set<String> required = normalizedPasses(requiredPasses);
        for (Map.Entry<FrameKey, FrameTicket> entry : tickets.entrySet()) {
            if (entry.getValue().phase() != Phase.MEASURED
                    || entry.getValue().measuredIndex() > measuredIndexInclusive) continue;
            for (String pass : required) {
                if (!samples.containsKey(new SampleKey(runId, entry.getKey().generationId(), pass,
                        entry.getKey().sequence()))) {
                    return false;
                }
            }
        }
        return true;
    }

    Snapshot snapshot(Collection<String> requiredPasses) {
        Set<String> required = normalizedPasses(requiredPasses);
        List<RawSample> raw = samples.values().stream()
                .sorted(Comparator.comparingLong((MutableSample value) -> value.ticket.generationId())
                        .thenComparingLong(value -> value.ticket.sequence())
                        .thenComparing(value -> value.key.passName()))
                .map(MutableSample::freeze)
                .toList();

        Map<String, List<MeasuredSample>> measured = new TreeMap<>();
        for (MutableSample sample : samples.values()) {
            if (sample.ticket.phase() != Phase.MEASURED
                    || sample.status != PassProfile.GpuTimingStatus.AVAILABLE) {
                continue;
            }
            measured.computeIfAbsent(sample.key.passName(), ignored -> new ArrayList<>())
                    .add(new MeasuredSample(sample.ticket.generationId(), sample.ticket.sequence(),
                            sample.ticket.round(), sample.ticket.measuredIndex(), sample.nanos));
        }
        measured.replaceAll((pass, values) -> values.stream()
                .sorted(Comparator.comparingInt(MeasuredSample::measuredIndex))
                .toList());

        List<Integer> rounds = tickets.values().stream().map(FrameTicket::round).distinct().sorted().toList();
        List<Coverage> coverage = new ArrayList<>();
        for (String pass : required) {
            for (int round : rounds) {
                int currentRound = round;
                int expected = (int) tickets.values().stream()
                        .filter(ticket -> ticket.phase() == Phase.MEASURED
                                && ticket.round() == currentRound)
                        .count();
                int available = (int) measured.getOrDefault(pass, List.of()).stream()
                        .filter(sample -> sample.round() == currentRound)
                        .count();
                coverage.add(new Coverage(pass, round, available, expected,
                        expected == 0 ? Double.NaN : available / (double) expected));
            }
        }
        return new Snapshot(runId, raw, Map.copyOf(measured), List.copyOf(coverage),
                List.copyOf(failures), duplicateSamples, conflictingSamples, unknownSamples,
                measuredTicketCount());
    }

    private int measuredTicketCount() {
        return (int) tickets.values().stream()
                .filter(ticket -> ticket.phase() == Phase.MEASURED).count();
    }

    private static Set<String> normalizedPasses(Collection<String> passes) {
        Objects.requireNonNull(passes, "requiredPasses");
        Set<String> result = new LinkedHashSet<>();
        for (String pass : passes) result.add(requireText(pass, "passName"));
        if (result.isEmpty()) throw new IllegalArgumentException("requiredPasses must not be empty");
        return Set.copyOf(result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    record FrameTicket(long generationId, long sequence, int round, Phase phase,
                       int measuredIndex) {
        FrameTicket {
            if (generationId < 0L || sequence < 0L || round < 0 || measuredIndex < -1) {
                throw new IllegalArgumentException("invalid frame ticket");
            }
            Objects.requireNonNull(phase, "phase");
            if ((phase == Phase.MEASURED) != (measuredIndex >= 0)) {
                throw new IllegalArgumentException("only measured tickets have measuredIndex");
            }
        }
    }

    record FrameKey(long generationId, long sequence) { }

    record SampleKey(String runId, long generationId, String passName, long sequence) { }

    record RawSample(SampleKey key, int round, Phase phase, int measuredIndex,
                     PassProfile.GpuTimingStatus status, long nanos,
                     long skippedSubmissions, int duplicateCount) { }

    record MeasuredSample(long generationId, long sequence, int round, int measuredIndex,
                          long nanos) { }

    record Coverage(String passName, int round, int available, int expected, double ratio) { }

    record PairedSample(long generationId, long sequence, int round, int measuredIndex,
                        long nanos) { }

    record Snapshot(String runId, List<RawSample> rawSamples,
                    Map<String, List<MeasuredSample>> measuredByPass,
                    List<Coverage> coverage, List<String> failures,
                    int duplicateSamples, int conflictingSamples, int unknownSamples,
                    int measuredTickets) {
        double minimumCoverage(String passName) {
            return coverage.stream().filter(value -> value.passName().equals(passName))
                    .mapToDouble(Coverage::ratio).min().orElse(Double.NaN);
        }

        List<PairedSample> paired(Collection<String> passNames) {
            Set<String> required = normalizedPasses(passNames);
            Map<FrameKey, long[]> sums = new LinkedHashMap<>();
            Map<FrameKey, boolean[]> present = new LinkedHashMap<>();
            Map<FrameKey, MeasuredSample> identities = new LinkedHashMap<>();
            int index = 0;
            Map<String, Integer> positions = new LinkedHashMap<>();
            for (String pass : required) positions.put(pass, index++);
            for (Map.Entry<String, Integer> pass : positions.entrySet()) {
                for (MeasuredSample sample : measuredByPass.getOrDefault(pass.getKey(), List.of())) {
                    FrameKey key = new FrameKey(sample.generationId(), sample.sequence());
                    long[] values = sums.computeIfAbsent(key, ignored -> new long[required.size()]);
                    boolean[] availability = present.computeIfAbsent(key,
                            ignored -> new boolean[required.size()]);
                    if (!availability[pass.getValue()]) {
                        values[pass.getValue()] = sample.nanos();
                        availability[pass.getValue()] = true;
                        identities.putIfAbsent(key, sample);
                    }
                }
            }
            List<PairedSample> paired = new ArrayList<>();
            for (Map.Entry<FrameKey, long[]> entry : sums.entrySet()) {
                boolean[] availability = present.get(entry.getKey());
                boolean complete = true;
                for (boolean value : availability) complete &= value;
                if (!complete) continue;
                long total = 0L;
                for (long value : entry.getValue()) total = Math.addExact(total, value);
                MeasuredSample identity = identities.get(entry.getKey());
                paired.add(new PairedSample(identity.generationId(), identity.sequence(),
                        identity.round(), identity.measuredIndex(), total));
            }
            paired.sort(Comparator.comparingInt(PairedSample::measuredIndex));
            return List.copyOf(paired);
        }
    }

    private static final class MutableSample {
        private final SampleKey key;
        private final FrameTicket ticket;
        private final PassProfile.GpuTimingStatus status;
        private final long nanos;
        private final long skippedSubmissions;
        private int duplicateCount;

        private MutableSample(SampleKey key, FrameTicket ticket,
                              PassProfile.GpuTimingStatus status, long nanos,
                              long skippedSubmissions) {
            this.key = key;
            this.ticket = ticket;
            this.status = Objects.requireNonNull(status, "status");
            this.nanos = nanos;
            this.skippedSubmissions = skippedSubmissions;
            if (nanos < 0L || skippedSubmissions < 0L) {
                throw new IllegalArgumentException("sample counters must be non-negative");
            }
        }

        private boolean sameValue(MutableSample other) {
            return status == other.status && nanos == other.nanos
                    && skippedSubmissions == other.skippedSubmissions;
        }

        private RawSample freeze() {
            return new RawSample(key, ticket.round(), ticket.phase(), ticket.measuredIndex(),
                    status, nanos, skippedSubmissions, duplicateCount);
        }
    }
}
