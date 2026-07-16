package com.kaleblangley.haikalat.backend.texture;

import static org.lwjgl.opengl.GL15.GL_READ_ONLY;
import static org.lwjgl.opengl.GL15.GL_READ_WRITE;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;

/** 类型化 image load/store 访问模式。 */
public enum ImageAccess {
    READ_ONLY(GL_READ_ONLY),
    WRITE_ONLY(GL_WRITE_ONLY),
    READ_WRITE(GL_READ_WRITE);

    private final int glValue;

    ImageAccess(int glValue) {
        this.glValue = glValue;
    }

    public int glValue() {
        return glValue;
    }
}
