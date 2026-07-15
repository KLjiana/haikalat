package com.kaleblangley.haikalat.subsystems.windowing;

import org.lwjgl.glfw.GLFWErrorCallback;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW 窗口封装。渲染器固定面向 OpenGL 4.6 core profile，
 * 因此 context 版本、profile 和 debug hint 不作为 builder 开关暴露。
 */
public final class GlfwWindow implements AutoCloseable, RenderWindow {

    public enum CursorMode { NORMAL, HIDDEN, DISABLED, CAPTURED }

    private final long handle;
    private volatile int width;
    private volatile int height;
    private volatile boolean resized;
    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private double mouseX;
    private double mouseY;
    private double mouseDeltaX;
    private double mouseDeltaY;
    private boolean firstMouse = true;
    private boolean closed;

    private GlfwWindow(Builder b) {
        this.width = b.width;
        this.height = b.height;

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW init failed");
        }
        glfwDefaultWindowHints();

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_CONTEXT_DEBUG, GLFW_TRUE);
        glfwWindowHint(GLFW_SRGB_CAPABLE, GLFW_TRUE);

        glfwWindowHint(GLFW_RESIZABLE, b.resizable ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_VISIBLE, b.visible ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, b.decorated ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_FLOATING, b.floating ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_MAXIMIZED, b.maximized ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, b.focusedOnShow ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, b.transparentFb ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_SCALE_TO_MONITOR, b.scaleToMonitor ? GLFW_TRUE : GLFW_FALSE);
        if (b.posX != Integer.MIN_VALUE) {
            glfwWindowHint(GLFW_POSITION_X, b.posX);
        }
        if (b.posY != Integer.MIN_VALUE) {
            glfwWindowHint(GLFW_POSITION_Y, b.posY);
        }

        handle = glfwCreateWindow(width, height, b.title, NULL, NULL);
        if (handle == NULL) {
            throw new IllegalStateException("Window creation failed");
        }

        glfwSetInputMode(handle, GLFW_STICKY_KEYS, b.stickyKeys ? GLFW_TRUE : GLFW_FALSE);
        glfwSetInputMode(handle, GLFW_STICKY_MOUSE_BUTTONS, b.stickyMouseButtons ? GLFW_TRUE : GLFW_FALSE);
        glfwSetInputMode(handle, GLFW_RAW_MOUSE_MOTION, b.rawMouseMotion ? GLFW_TRUE : GLFW_FALSE);

        int cursorMode = switch (b.cursorMode) {
            case NORMAL -> GLFW_CURSOR_NORMAL;
            case HIDDEN -> GLFW_CURSOR_HIDDEN;
            case DISABLED -> GLFW_CURSOR_DISABLED;
            case CAPTURED -> GLFW_CURSOR_CAPTURED;
        };
        glfwSetInputMode(handle, GLFW_CURSOR, cursorMode);

        glfwSetFramebufferSizeCallback(handle, (h, w, h2) -> {
            this.width = Math.max(1, w);
            this.height = Math.max(1, h2);
            this.resized = true;
        });
        glfwSetKeyCallback(handle, (h, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) {
                keys[key] = action != GLFW_RELEASE;
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
    }

    public long handle() {
        return handle;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    public boolean consumeResize() {
        boolean value = resized;
        resized = false;
        return value;
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

    public void setTitle(String title) {
        glfwSetWindowTitle(handle, title);
    }

    public void requestClose() {
        glfwSetWindowShouldClose(handle, true);
    }

    /** 请求新的窗口尺寸；framebuffer 回调负责发布实际像素尺寸。 */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window dimensions must be positive");
        }
        glfwSetWindowSize(handle, width, height);
    }

    /** 为当前窗口已经绑定的 context 设置 swap interval。 */
    public void setVsync(boolean enabled) {
        glfwSwapInterval(enabled ? 1 : 0);
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void bindContext() {
        glfwMakeContextCurrent(handle);
    }

    public void releaseContext() {
        glfwMakeContextCurrent(0);
    }

    public void pollEvents() {
        resetMouseDelta();
        glfwPollEvents();
    }

    /** 在超时限制内等待事件，适合控制非渲染生产线程的节奏。 */
    public void waitEvents(double timeoutSeconds) {
        resetMouseDelta();
        glfwWaitEventsTimeout(timeoutSeconds);
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    private void resetMouseDelta() {
        mouseDeltaX = 0;
        mouseDeltaY = 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        glfwDestroyWindow(handle);
        glfwTerminate();
        closed = true;
    }

    public static final class Builder {
        private int width;
        private int height;
        private String title;
        private int posX = Integer.MIN_VALUE;
        private int posY = Integer.MIN_VALUE;

        private boolean resizable = true;
        private boolean visible;
        private boolean decorated = true;
        private boolean floating;
        private boolean maximized;
        private boolean focusedOnShow;
        private boolean transparentFb;
        private boolean scaleToMonitor;

        private boolean stickyKeys;
        private boolean stickyMouseButtons;
        private boolean rawMouseMotion;
        private CursorMode cursorMode = CursorMode.DISABLED;

        public Builder dimensions(int w, int h) {
            width = w;
            height = h;
            return this;
        }

        public Builder title(String t) {
            title = t;
            return this;
        }

        public Builder position(int x, int y) {
            posX = x;
            posY = y;
            return this;
        }

        public Builder resizable(boolean r) {
            resizable = r;
            return this;
        }

        public Builder visible(boolean v) {
            visible = v;
            return this;
        }

        public Builder decorated(boolean d) {
            decorated = d;
            return this;
        }

        public Builder floating(boolean f) {
            floating = f;
            return this;
        }

        public Builder maximized(boolean m) {
            maximized = m;
            return this;
        }

        public Builder focusedOnShow() {
            focusedOnShow = true;
            return this;
        }

        public Builder transparentFramebuffer() {
            transparentFb = true;
            return this;
        }

        public Builder scaleToMonitor() {
            scaleToMonitor = true;
            return this;
        }

        public Builder stickyKeys() {
            stickyKeys = true;
            return this;
        }

        public Builder stickyMouseButtons() {
            stickyMouseButtons = true;
            return this;
        }

        public Builder rawMouseMotion() {
            rawMouseMotion = true;
            return this;
        }

        public Builder cursorMode(CursorMode m) {
            cursorMode = m;
            return this;
        }

        public GlfwWindow build() {
            return new GlfwWindow(this);
        }
    }
}
