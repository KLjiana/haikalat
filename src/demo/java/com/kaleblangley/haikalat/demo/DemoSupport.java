package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/** 存放多个正式 Demo 共用的常量和小型辅助逻辑。 */
public final class DemoSupport {
    public static final int DEFAULT_WIDTH = 800;
    public static final int DEFAULT_HEIGHT = 600;
    public static final String U_PROJECTION_VIEW = "uProjView";

    private static final String INSTANCED_VERTEX_SHADER = "/shaders/instancing/instanced-projview.vert";
    private static final String ASYNC_INSTANCED_VERTEX_SHADER = "/shaders/instancing/async-instanced.vert";
    private static final String COLOR_MVP_VERTEX_SHADER = "/shaders/basic/color-mvp.vert";
    private static final String VERTEX_COLOR_FRAGMENT_SHADER = "/shaders/basic/vertex-color-unlit.frag";
    private static final float CAMERA_FOV_RADIANS = (float) Math.toRadians(45.0);
    private static final float CAMERA_NEAR = 0.1f;
    private static final float CAMERA_FAR = 1000.0f;

    private DemoSupport() {
    }

    public static ShaderProgram loadProjectionViewInstancedShader(Class<?> anchor) {
        return ShaderProgram.fromResource(anchor, INSTANCED_VERTEX_SHADER, VERTEX_COLOR_FRAGMENT_SHADER);
    }

    public static ShaderProgram loadAsyncInstancedShader(Class<?> anchor) {
        return ShaderProgram.fromResource(anchor, ASYNC_INSTANCED_VERTEX_SHADER,
                VERTEX_COLOR_FRAGMENT_SHADER);
    }

    public static ShaderProgram loadColorMvpShader(Class<?> anchor) {
        return ShaderProgram.fromResource(anchor, COLOR_MVP_VERTEX_SHADER, VERTEX_COLOR_FRAGMENT_SHADER);
    }

    public static Matrix4f perspective(Matrix4f destination, int width, int height) {
        return destination.identity().perspective(CAMERA_FOV_RADIANS,
                width / (float) Math.max(1, height), CAMERA_NEAR, CAMERA_FAR);
    }

    public static CommandBuffer beginScene(CommandBuffer commands, Framebuffer target) {
        return commands.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .clearColor(0.08f, 0.10f, 0.14f, 1.0f)
                .clear(true, true);
    }

    public static void updateFreeCamera(GlfwWindow window, Camera camera, float deltaTime) {
        camera.processMouseMovement((float) window.mouseDeltaX(), (float) window.mouseDeltaY());
        if (window.isKeyDown(GLFW.GLFW_KEY_W)) camera.processKeyboard(Camera.Movement.FORWARD, deltaTime);
        if (window.isKeyDown(GLFW.GLFW_KEY_S)) camera.processKeyboard(Camera.Movement.BACKWARD, deltaTime);
        if (window.isKeyDown(GLFW.GLFW_KEY_A)) camera.processKeyboard(Camera.Movement.LEFT, deltaTime);
        if (window.isKeyDown(GLFW.GLFW_KEY_D)) camera.processKeyboard(Camera.Movement.RIGHT, deltaTime);
        if (window.isKeyDown(GLFW.GLFW_KEY_ESCAPE)) window.requestClose();
    }
}
