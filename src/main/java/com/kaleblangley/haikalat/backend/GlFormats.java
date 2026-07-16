package com.kaleblangley.haikalat.backend;


import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.GL_R16F;
import static org.lwjgl.opengl.GL30.GL_RG32F;
import static org.lwjgl.opengl.GL30.GL_RG16F;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;

public final class GlFormats {
    private GlFormats() {
    }

    public static int toGl(RenderFormat format) {
        return switch (format) {
            case RGBA8 -> GL_RGBA8;
            case SRGB8_ALPHA8 -> GL_SRGB8_ALPHA8;
            case RGBA16F -> GL_RGBA16F;
            case R16F -> GL_R16F;
            case RG16F -> GL_RG16F;
            case RG32F -> GL_RG32F;
            case DEPTH_COMPONENT24 -> GL_DEPTH_COMPONENT24;
            case DEPTH24_STENCIL8 -> GL_DEPTH24_STENCIL8;
        };
    }
}
