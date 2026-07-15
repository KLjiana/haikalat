package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.PassProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/** 按 RenderGraph pass 名称累计正式测量区间的 CPU record 与 GPU query 时间。 */
public final class PassBenchmarkAccumulator {
    private final int expectedFrames;
    private final Map<String, FrameTimingAccumulator> timings = new LinkedHashMap<>();

    public PassBenchmarkAccumulator(int expectedFrames) {
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be non-negative");
        }
        this.expectedFrames = expectedFrames;
    }

    public void add(FrameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        for (PassProfile pass : profile.passes()) {
            timings.computeIfAbsent(pass.passName(), ignored -> new FrameTimingAccumulator(expectedFrames))
                    .add(pass.cpuRecordNanos(), pass.gpuNanos());
        }
    }

    public Map<String, FrameTimingAccumulator.Summary> snapshot() {
        Map<String, FrameTimingAccumulator.Summary> result = new LinkedHashMap<>();
        timings.forEach((name, values) -> result.put(name, values.summary()));
        return Collections.unmodifiableMap(result);
    }

    public void reset() {
        timings.clear();
    }
}
