package com.kaleblangley.haikalat.demo.async;

import org.joml.Matrix4f;

/** 发布后保持不可变的渲染帧快照。 */
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
