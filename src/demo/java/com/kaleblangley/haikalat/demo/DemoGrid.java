package com.kaleblangley.haikalat.demo;

import org.joml.Matrix4f;

final class DemoGrid {
    static final int SIDE = 4;
    static final int COUNT = SIDE * SIDE;

    private DemoGrid() {
    }

    static Matrix4f transform(int row, int column, int frame) {
        return new Matrix4f()
                .translation(-1.4f + column * 0.7f, -1.4f + row * 0.7f, 0.0f)
                .rotateZ(frame * 0.04f + (row + column) * 0.3f)
                .scale(0.3f);
    }
}
