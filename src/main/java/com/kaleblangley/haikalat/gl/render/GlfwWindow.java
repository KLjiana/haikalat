package com.kaleblangley.haikalat.gl.render;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class GlfwWindow implements AutoCloseable {
    private final long handle;
    private int width;
    private int height;
    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private double mouseX;
    private double mouseY;
    private double mouseDeltaX;
    private double mouseDeltaY;
    private boolean firstMouse = true;
    private boolean closed;

    public GlfwWindow(int width, int height, String title) {
        this.width = width;
        this.height = height;

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW init failed");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            throw new IllegalStateException("Window creation failed");
        }

        glfwSetFramebufferSizeCallback(handle, (h, w, h2) -> {
            this.width = Math.max(1, w);
            this.height = Math.max(1, h2);
            glViewport(0, 0, this.width, this.height);
        });

        glfwSetKeyCallback(handle, (h, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) {
                keys[key] = (action != GLFW_RELEASE);
            }
        });

        glfwSetCursorPosCallback(handle, (h, xpos, ypos) -> {
            if (firstMouse) {
                mouseX = xpos;
                mouseY = ypos;
                firstMouse = false;
            }
            mouseDeltaX += xpos - mouseX;
            mouseDeltaY += mouseY - ypos;
            mouseX = xpos;
            mouseY = ypos;
        });

        glfwSetInputMode(handle, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
    }

    public long handle() {
        return handle;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean isKeyDown(int key) {
        return key >= 0 && key < keys.length && keys[key];
    }

    public double mouseDeltaX() {
        return mouseDeltaX;
    }

    public double mouseDeltaY() {
        return mouseDeltaY;
    }

    public void show() {
        glfwShowWindow(handle);
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void pollEvents() {
        mouseDeltaX = 0;
        mouseDeltaY = 0;
        glfwPollEvents();
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public void makeContextCurrent() {
        glfwMakeContextCurrent(handle);
    }

    public void releaseContext() {
        glfwMakeContextCurrent(0);
    }

    @Override
    public void close() {
        if (closed) return;
        glfwDestroyWindow(handle);
        glfwTerminate();
        closed = true;
    }
}
