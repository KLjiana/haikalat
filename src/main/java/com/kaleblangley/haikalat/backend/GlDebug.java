package com.kaleblangley.haikalat.backend;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;

import java.util.logging.Logger;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL43.glObjectLabel;

public final class GlDebug {
    private static final Logger LOG = Logger.getLogger(GlDebug.class.getName());

    private GlDebug() {
    }

    public static void checkError(String context) {
        int error;
        while ((error = glGetError()) != GL_NO_ERROR) {
            LOG.warning(context + ": " + errorName(error) + " (" + error + ")");
        }
    }

    public static void assertNoError(String context) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new GlException(context + ": " + errorName(error) + " (" + error + ")");
        }
    }

    public static void enableDebugCallback() {
        try {
            if (GL.getCapabilities().GL_KHR_debug) {
                GLUtil.setupDebugMessageCallback(System.err);
                LOG.info("GL debug output enabled");
            }
        } catch (Exception ignored) {
        }
    }

    public static void labelObject(int identifier, int object, String label) {
        if (object == 0 || label == null || label.isBlank()) {
            return;
        }
        try {
            if (GL.getCapabilities().GL_KHR_debug || GL.getCapabilities().OpenGL43) {
                glObjectLabel(identifier, object, label);
            }
        } catch (Exception ignored) {
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
