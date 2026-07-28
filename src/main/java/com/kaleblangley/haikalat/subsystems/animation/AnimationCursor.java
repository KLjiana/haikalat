package com.kaleblangley.haikalat.subsystems.animation;

/**
 * Reusable clip cursor result: local time plus the number of loop boundaries crossed.
 * It is intentionally mutable and package-private so state evaluators can reuse it without
 * allocating a result record for every layer and transition.
 */
final class AnimationCursor {
    private float time;
    private long loops;

    AnimationCursor set(float time, long loops) {
        this.time = time;
        this.loops = loops;
        return this;
    }

    float time() {
        return time;
    }

    long loops() {
        return loops;
    }
}
