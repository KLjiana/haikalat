package com.kaleblangley.haikalat.backend;


import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.GL_RGBA8;

public final class GlFormats {
    private GlFormats() {
    }

    public static int toGl(RenderFormat format) {
        return switch (format) {
            case RGBA8 -> GL_RGBA8;
            case RGBA16F -> GL_RGBA16F;
            case DEPTH_COMPONENT24 -> GL_DEPTH_COMPONENT24;
            case DEPTH24_STENCIL8 -> GL_DEPTH24_STENCIL8;
        };
    }
}
