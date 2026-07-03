package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.gl.camera.Camera;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.util.function.BiConsumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class AppWindow {
    private long window;
    private int fbWidth;
    private int fbHeight;
    private float deltaTime;
    private float lastTime;
    private double lastMouseX;
    private double lastMouseY;
    private boolean firstMouse = true;
    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private boolean framebufferResized;
    private Camera camera;

    public AppWindow(int width, int height, String title) {
        this.fbWidth = width;
        this.fbHeight = height;
        this.camera = new Camera(new Vector3f(0.0f, 0.0f, 5.0f));

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW init failed");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(fbWidth, fbHeight, title, NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("Window creation failed");
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);

        glfwSetFramebufferSizeCallback(window, (h, w, h2) -> {
            fbWidth = Math.max(1, w);
            fbHeight = Math.max(1, h2);
            glViewport(0, 0, fbWidth, fbHeight);
            framebufferResized = true;
        });

        glfwSetKeyCallback(window, (h, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) {
                keys[key] = (action == GLFW_PRESS);
            }
        });

        glfwSetCursorPosCallback(window, (h, xpos, ypos) -> {
            if (firstMouse) {
                lastMouseX = xpos;
                lastMouseY = ypos;
                firstMouse = false;
            }
            camera.processMouseMovement((float) (xpos - lastMouseX), (float) (lastMouseY - ypos));
            lastMouseX = xpos;
            lastMouseY = ypos;
        });

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
    }

    public void show() {
        glfwShowWindow(window);
    }

    public Camera camera() {
        return camera;
    }

    public int width() {
        return fbWidth;
    }

    public int height() {
        return fbHeight;
    }

    public boolean consumeResize() {
        boolean r = framebufferResized;
        framebufferResized = false;
        return r;
    }

    private void updateDeltaTime() {
        float currentTime = (float) glfwGetTime();
        deltaTime = currentTime - lastTime;
        lastTime = currentTime;
    }

    private void processInput() {
        if (keys[GLFW_KEY_ESCAPE]) {
            glfwSetWindowShouldClose(window, true);
        }
        float v = 2.5f * deltaTime;
        if (keys[GLFW_KEY_W]) camera.processKeyboard(Camera.Movement.FORWARD, deltaTime);
        if (keys[GLFW_KEY_S]) camera.processKeyboard(Camera.Movement.BACKWARD, deltaTime);
        if (keys[GLFW_KEY_A]) camera.processKeyboard(Camera.Movement.LEFT, deltaTime);
        if (keys[GLFW_KEY_D]) camera.processKeyboard(Camera.Movement.RIGHT, deltaTime);
    }

    public void setTitle(String title) {
        glfwSetWindowTitle(window, title);
    }

    public void run(BiConsumer<AppWindow, Float> frame) {
        while (!glfwWindowShouldClose(window)) {
            updateDeltaTime();
            processInput();
            frame.accept(this, deltaTime);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    public void close() {
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }
}
