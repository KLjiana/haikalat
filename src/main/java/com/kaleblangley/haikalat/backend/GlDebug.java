package com.kaleblangley.haikalat.backend;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.Callback;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.glfwGetCurrentContext;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class GlDebug {
    private static final Logger LOG = Logger.getLogger(GlDebug.class.getName());
    private static final Map<GLCapabilities, Callback> DEBUG_CALLBACKS = new IdentityHashMap<>();

    private GlDebug() {
    }

    /**
     * 为当前 context 启用 OpenGL 4.6 debug output。
     *
     * <p>每个 LWJGL capabilities 对象最多安装一次 callback，并在进程生命周期内持有，
     * 防止原生 OpenGL 调用已经被回收的 callback。
     *
     * @return 当前 context 已安装或已经启用 callback 时返回 {@code true}
     */
    public static synchronized boolean enableDebugCallback() {
        GLCapabilities capabilities = currentCapabilitiesOrNull();
        if (capabilities == null) {
            return false;
        }
        if (DEBUG_CALLBACKS.containsKey(capabilities)) {
            return true;
        }

        glEnable(GL_DEBUG_OUTPUT);
        glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
        GLDebugMessageCallback callback = GLDebugMessageCallback.create(GlDebug::handleDebugMessage);
        glDebugMessageCallback(callback, NULL);
        DEBUG_CALLBACKS.put(capabilities, callback);
        LOG.info("OpenGL debug output enabled");
        return true;
    }

    public static void checkError(String context) {
        if (!hasCurrentContext()) {
            return;
        }
        int error;
        while ((error = glGetError()) != GL_NO_ERROR) {
            LOG.warning(formatError(context, error));
        }
    }

    public static void assertNoError(String context) {
        if (!hasCurrentContext()) {
            return;
        }
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new GlException(formatError(context, error));
        }
    }

    public static void labelObject(int identifier, int object, String label) {
        if (object == 0 || label == null || label.isBlank() || !hasCurrentContext()) {
            return;
        }
        glObjectLabel(identifier, object, label.trim());
    }

    public static boolean hasCurrentContext() {
        return currentCapabilitiesOrNull() != null;
    }

    static String formatError(String context, int error) {
        String safeContext = context == null || context.isBlank() ? "OpenGL" : context;
        return safeContext + ": " + errorName(error) + " (" + error + ")";
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

    static String sourceName(int source) {
        return switch (source) {
            case GL_DEBUG_SOURCE_API -> "API";
            case GL_DEBUG_SOURCE_WINDOW_SYSTEM -> "WINDOW_SYSTEM";
            case GL_DEBUG_SOURCE_SHADER_COMPILER -> "SHADER_COMPILER";
            case GL_DEBUG_SOURCE_THIRD_PARTY -> "THIRD_PARTY";
            case GL_DEBUG_SOURCE_APPLICATION -> "APPLICATION";
            case GL_DEBUG_SOURCE_OTHER -> "OTHER";
            default -> "UNKNOWN_SOURCE(" + source + ")";
        };
    }

    static String typeName(int type) {
        return switch (type) {
            case GL_DEBUG_TYPE_ERROR -> "ERROR";
            case GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR -> "DEPRECATED_BEHAVIOR";
            case GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR -> "UNDEFINED_BEHAVIOR";
            case GL_DEBUG_TYPE_PORTABILITY -> "PORTABILITY";
            case GL_DEBUG_TYPE_PERFORMANCE -> "PERFORMANCE";
            case GL_DEBUG_TYPE_MARKER -> "MARKER";
            case GL_DEBUG_TYPE_PUSH_GROUP -> "PUSH_GROUP";
            case GL_DEBUG_TYPE_POP_GROUP -> "POP_GROUP";
            case GL_DEBUG_TYPE_OTHER -> "OTHER";
            default -> "UNKNOWN_TYPE(" + type + ")";
        };
    }

    static String severityName(int severity) {
        return switch (severity) {
            case GL_DEBUG_SEVERITY_HIGH -> "HIGH";
            case GL_DEBUG_SEVERITY_MEDIUM -> "MEDIUM";
            case GL_DEBUG_SEVERITY_LOW -> "LOW";
            case GL_DEBUG_SEVERITY_NOTIFICATION -> "NOTIFICATION";
            default -> "UNKNOWN_SEVERITY(" + severity + ")";
        };
    }

    static String objectIdentifierName(int identifier) {
        return switch (identifier) {
            case GL_BUFFER -> "BUFFER";
            case GL_SHADER -> "SHADER";
            case GL_PROGRAM -> "PROGRAM";
            case GL_VERTEX_ARRAY -> "VERTEX_ARRAY";
            case GL_QUERY -> "QUERY";
            case GL_SAMPLER -> "SAMPLER";
            case GL_TEXTURE -> "TEXTURE";
            case GL_RENDERBUFFER -> "RENDERBUFFER";
            case GL_FRAMEBUFFER -> "FRAMEBUFFER";
            default -> "OBJECT(" + identifier + ")";
        };
    }

    private static void handleDebugMessage(int source, int type, int id, int severity,
                                           int length, long message, long userParam) {
        String text = GLDebugMessageCallback.getMessage(length, message);
        Level level = severity == GL_DEBUG_SEVERITY_HIGH ? Level.SEVERE
                : severity == GL_DEBUG_SEVERITY_MEDIUM ? Level.WARNING
                : Level.INFO;
        LOG.log(level, () -> "OpenGL " + typeName(type)
                + " [" + severityName(severity) + "]"
                + " source=" + sourceName(source)
                + " id=" + id
                + ": " + text);
    }

    private static GLCapabilities currentCapabilitiesOrNull() {
        if (glfwGetCurrentContext() == NULL) {
            return null;
        }
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }
}
