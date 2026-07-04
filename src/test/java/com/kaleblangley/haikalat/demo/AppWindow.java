package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.util.function.BiConsumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glEnable;

public final class AppWindow implements RenderWindow {
    private final GlfwWindow window;
    private final Camera camera;
    private float deltaTime;
    private float lastTime;

    public AppWindow(int w, int h, String title, boolean vsync) {
        this.window = new GlfwWindow.Builder()
                .dimensions(w, h).title(title).build();
        this.window.bindContext();
        GL.createCapabilities();
        glfwSwapInterval(vsync ? 1 : 0);
        glEnable(GL_DEPTH_TEST);
        this.camera = new Camera(new Vector3f(0, 0, 5));
    }

    public Camera camera() { return camera; }

    public void setTitle(String t) { glfwSetWindowTitle(window.handle(), t); }

    public boolean consumeResize() { return false; }

    @Override public int width() { return window.width(); }
    @Override public int height() { return window.height(); }

    public void processInput(float dt) {
        if (window.isKeyDown(GLFW_KEY_ESCAPE)) glfwSetWindowShouldClose(window.handle(), true);
        float s = 2.5f * dt;
        if (window.isKeyDown(GLFW_KEY_W)) camera.processKeyboard(Camera.Movement.FORWARD, s);
        if (window.isKeyDown(GLFW_KEY_S)) camera.processKeyboard(Camera.Movement.BACKWARD, s);
        if (window.isKeyDown(GLFW_KEY_A)) camera.processKeyboard(Camera.Movement.LEFT, s);
        if (window.isKeyDown(GLFW_KEY_D)) camera.processKeyboard(Camera.Movement.RIGHT, s);
    }

    public void show() { window.show(); }

    public boolean shouldClose() { return window.shouldClose(); }

    public void run(BiConsumer<AppWindow, Float> frame) {
        while (!window.shouldClose()) {
            float now = (float) glfwGetTime();
            deltaTime = now - lastTime;
            lastTime = now;
            processInput(deltaTime);
            frame.accept(this, deltaTime);
            window.swapBuffers();
            window.pollEvents();
        }
    }

    public void close() { window.close(); }
}
