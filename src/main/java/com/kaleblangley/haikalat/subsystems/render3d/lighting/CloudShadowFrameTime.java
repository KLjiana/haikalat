package com.kaleblangley.haikalat.subsystems.render3d.lighting;

import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowFramePlan;

/**
 * Monotonic effect time derived from the one-frame shadow plan.
 *
 * <p>The plan carries {@code RenderFrameContext.frameSequence()/deltaSeconds()}.
 * Binding happens multiple times per frame, so time advances at most once per
 * new sequence.  The first observed sequence reports zero; a delta is capped at
 * 0.1 s to keep stalls bounded.  A rebased sequence starts a new clock.  The
 * accumulator is double precision so long sessions do not quantize the float
 * uniform.</p>
 */
final class CloudShadowFrameTime {
    private static final long NO_SEQUENCE = Long.MIN_VALUE;
    private static final float MAX_DELTA_SECONDS = 0.1f;

    private long sequence = NO_SEQUENCE;
    private double seconds;

    /** Returns the accumulated seconds for this plan's sequence. */
    float advance(ShadowFramePlan.FrameTiming timing) {
        long next = timing.frameSequence();
        if (sequence == NO_SEQUENCE) {
            sequence = next;
            return 0.0f;
        }
        if (next < sequence) {
            sequence = next;
            seconds = 0.0;
            return 0.0f;
        }
        if (next == sequence) {
            return (float) seconds;
        }
        sequence = next;
        float delta = timing.deltaSeconds();
        if (Float.isFinite(delta) && delta > 0.0f) {
            seconds += Math.min(delta, MAX_DELTA_SECONDS);
            if (!Double.isFinite(seconds)) seconds = 0.0;
        }
        return (float) seconds;
    }
}
