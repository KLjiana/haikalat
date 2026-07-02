package com.kaleblangley.haikalat.gl;

import static org.lwjgl.opengl.GL33.*;

public final class GlDebug {
    private GlDebug() {
    }

    public static void checkError(String context) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new GlException(context + ": " + errorName(error) + " (" + error + ")");
        }
    }

    public static String errorName(int error) {
        return switch (error) {
            case GL_INVALID_ENUM -> "GL_INVALID_ENUM";
            case GL_INVALID_VALUE -> "GL_INVALID_VALUE";
            case GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
            default -> "GL_ERROR_UNKNOWN";
        };
    }
}
