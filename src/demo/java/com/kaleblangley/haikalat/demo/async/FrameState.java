package com.kaleblangley.haikalat.demo.async;

import org.joml.Matrix4f;

/** Immutable-at-publication render snapshot. */
record FrameState(Matrix4f view, int instanceCount) {
    FrameState {
        view = new Matrix4f(view);
        if (instanceCount < 0) {
            throw new IllegalArgumentException("instanceCount must be non-negative");
        }
    }

    @Override
    public Matrix4f view() {
        return new Matrix4f(view);
    }
}
