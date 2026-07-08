package com.kaleblangley.haikalat.subsystems.windowing;

import org.lwjgl.glfw.GLFWErrorCallback;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW 窗口封装，通过 {@link Builder} 创建，实现 {@link RenderWindow}。
 * 创建时不自动绑定上下文、不自动显示。
 */
public final class GlfwWindow implements AutoCloseable, RenderWindow {

    /** 上下文健壮性策略 */
    public enum Robustness { NO_ROBUSTNESS, NO_RESET_NOTIFICATION, LOSE_CONTEXT_ON_RESET }

    /** 上下文释放时的 pipeline flush 行为 */
    public enum ReleaseBehavior { ANY, FLUSH, NONE }

    /** 光标模式 */
    public enum CursorMode { NORMAL, HIDDEN, DISABLED, CAPTURED }

    private final long handle;
    private volatile int width;
    private volatile int height;
    private volatile boolean resized;
    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private double mouseX, mouseY, mouseDeltaX, mouseDeltaY;
    private boolean firstMouse = true;
    private boolean closed;

    private GlfwWindow(Builder b) {
        this.width = b.width;
        this.height = b.height;

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();

        // GL 上下文
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, b.glMajor);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, b.glMinor);
        glfwWindowHint(GLFW_OPENGL_PROFILE, b.coreProfile ? GLFW_OPENGL_CORE_PROFILE : GLFW_OPENGL_COMPAT_PROFILE);
        glfwWindowHint(GLFW_CONTEXT_DEBUG, b.debugContext ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, b.forwardCompat ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_NO_ERROR, b.contextNoError ? GLFW_TRUE : GLFW_FALSE);
        switch (b.robustness) {
            case NO_RESET_NOTIFICATION -> glfwWindowHint(GLFW_CONTEXT_ROBUSTNESS, GLFW_NO_RESET_NOTIFICATION);
            case LOSE_CONTEXT_ON_RESET -> glfwWindowHint(GLFW_CONTEXT_ROBUSTNESS, GLFW_LOSE_CONTEXT_ON_RESET);
        }
        switch (b.releaseBehavior) {
            case FLUSH -> glfwWindowHint(GLFW_CONTEXT_RELEASE_BEHAVIOR, GLFW_RELEASE_BEHAVIOR_FLUSH);
            case NONE  -> glfwWindowHint(GLFW_CONTEXT_RELEASE_BEHAVIOR, GLFW_RELEASE_BEHAVIOR_NONE);
        }

        // 窗口外观
        glfwWindowHint(GLFW_RESIZABLE, b.resizable ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_VISIBLE, b.visible ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, b.decorated ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_FLOATING, b.floating ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_MAXIMIZED, b.maximized ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, b.focusedOnShow ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, b.transparentFb ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_SCALE_TO_MONITOR, b.scaleToMonitor ? GLFW_TRUE : GLFW_FALSE);
        if (b.posX != Integer.MIN_VALUE) glfwWindowHint(GLFW_POSITION_X, b.posX);
        if (b.posY != Integer.MIN_VALUE) glfwWindowHint(GLFW_POSITION_Y, b.posY);

        handle = glfwCreateWindow(width, height, b.title, NULL, NULL);
        if (handle == NULL) throw new IllegalStateException("Window creation failed");

        // 输入 & 光标
        glfwSetInputMode(handle, GLFW_STICKY_KEYS, b.stickyKeys ? GLFW_TRUE : GLFW_FALSE);
        glfwSetInputMode(handle, GLFW_STICKY_MOUSE_BUTTONS, b.stickyMouseButtons ? GLFW_TRUE : GLFW_FALSE);
        glfwSetInputMode(handle, GLFW_RAW_MOUSE_MOTION, b.rawMouseMotion ? GLFW_TRUE : GLFW_FALSE);

        int cursorMode = switch (b.cursorMode) {
            case NORMAL   -> GLFW_CURSOR_NORMAL;
            case HIDDEN   -> GLFW_CURSOR_HIDDEN;
            case DISABLED -> GLFW_CURSOR_DISABLED;
            case CAPTURED -> GLFW_CURSOR_CAPTURED;
        };
        glfwSetInputMode(handle, GLFW_CURSOR, cursorMode);

        // 回调
        glfwSetFramebufferSizeCallback(handle, (h, w, h2) -> {
            this.width = Math.max(1, w);
            this.height = Math.max(1, h2);
            this.resized = true;
        });
        glfwSetKeyCallback(handle, (h, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) keys[key] = (action != GLFW_RELEASE);
        });
        glfwSetCursorPosCallback(handle, (h, xpos, ypos) -> {
            if (firstMouse) { mouseX = xpos; mouseY = ypos; firstMouse = false; }
            mouseDeltaX += xpos - mouseX;
            mouseDeltaY += mouseY - ypos;
            mouseX = xpos; mouseY = ypos;
        });
    }

    /** @return GLFW 原生窗口句柄 */
    public long handle() { return handle; }
    public int width() { return width; }
    public int height() { return height; }
    public boolean consumeResize() {
        boolean value = resized;
        resized = false;
        return value;
    }

    /** @return 指定按键是否处于按下状态 */
    public boolean isKeyDown(int key) { return key >= 0 && key < keys.length && keys[key]; }
    /** @return 自上次 pollEvents() 以来的水平鼠标增量 */
    public double mouseDeltaX() { return mouseDeltaX; }
    /** @return 自上次 pollEvents() 以来的垂直鼠标增量 */
    public double mouseDeltaY() { return mouseDeltaY; }

    /** 显示窗口 */
    public void show() { glfwShowWindow(handle); }
    /** @return 用户是否请求关闭窗口 */
    public boolean shouldClose() { return glfwWindowShouldClose(handle); }

    /** 将 OpenGL 上下文绑定到当前线程 */
    public void bindContext() { glfwMakeContextCurrent(handle); }
    /** 从当前线程解绑 OpenGL 上下文 */
    public void releaseContext() { glfwMakeContextCurrent(0); }

    /** 轮询 GLFW 事件并重置鼠标增量。应每帧调用一次 */
    public void pollEvents() {
        mouseDeltaX = 0;
        mouseDeltaY = 0;
        glfwPollEvents();
    }

    /** 交换前后缓冲区 */
    public void swapBuffers() { glfwSwapBuffers(handle); }

    @Override
    public void close() {
        if (closed) return;
        glfwDestroyWindow(handle);
        glfwTerminate();
        closed = true;
    }

    /**
     * {@link GlfwWindow} 的构建器，按分组组织所有构造参数。
     * <pre>{@code
     * GlfwWindow w = new GlfwWindow.Builder()
     *     .dimensions(800, 600).title("Demo")
     *     .debugContext().noVsync()
     *     .build();
     * w.show();
     * }</pre>
     */
    public static final class Builder {
        // ==== 窗口基础 ====
        private int width, height;
        private String title;
        private int posX = Integer.MIN_VALUE, posY = Integer.MIN_VALUE;

        // ==== GL 上下文 ====
        private int glMajor = 3, glMinor = 3;
        private boolean coreProfile = true;
        private boolean debugContext, forwardCompat, contextNoError;
        private Robustness robustness = Robustness.NO_ROBUSTNESS;
        private ReleaseBehavior releaseBehavior = ReleaseBehavior.ANY;

        // ==== 窗口外观 ====
        private boolean resizable = true, visible, decorated = true;
        private boolean floating, maximized;
        private boolean focusedOnShow, transparentFb, scaleToMonitor;

        // ==== 输入 & 光标 ====
        private boolean stickyKeys, stickyMouseButtons, rawMouseMotion;
        private CursorMode cursorMode = CursorMode.DISABLED;

        // ============ 窗口基础 ============

        /** @param w 宽度（像素），必填 */
        public Builder dimensions(int w, int h) { width = w; height = h; return this; }
        /** @param t 窗口标题，必填 */
        public Builder title(String t) { title = t; return this; }
        /** 指定窗口初始位置 */
        public Builder position(int x, int y) { posX = x; posY = y; return this; }

        // ============ GL 上下文 ============

        /** @param major GL 主版本号，默认 3 */
        public Builder glVersion(int major, int minor) { glMajor = major; glMinor = minor; return this; }
        /** 使用 Compatibility Profile（默认 Core） */
        public Builder compatibilityProfile() { coreProfile = false; return this; }
        /** 启用 GLFW_OPENGL_FORWARD_COMPAT（macOS 核心上下文必须） */
        public Builder forwardCompat() { forwardCompat = true; return this; }
        /** 启用 GLFW_CONTEXT_DEBUG */
        public Builder debugContext() { debugContext = true; return this; }
        /** 启用 GLFW_CONTEXT_NO_ERROR（跳过错误检查，性能优化） */
        public Builder contextNoError() { contextNoError = true; return this; }
        /** 指定上下文健壮性策略 */
        public Builder robustness(Robustness r) { robustness = r; return this; }
        /** 指定上下文释放时的 pipeline flush 行为 */
        public Builder releaseBehavior(ReleaseBehavior b) { releaseBehavior = b; return this; }

        // ============ 窗口外观 ============

        /** 窗口是否可由用户调整大小（默认 true） */
        public Builder resizable(boolean r) { resizable = r; return this; }
        /** 窗口是否创建时即显示（默认 false，手动调用 show()） */
        public Builder visible(boolean v) { visible = v; return this; }
        /** 窗口是否有标题栏和边框（默认 true） */
        public Builder decorated(boolean d) { decorated = d; return this; }
        /** 窗口是否置顶 */
        public Builder floating(boolean f) { floating = f; return this; }
        /** 窗口是否创建时最大化 */
        public Builder maximized(boolean m) { maximized = m; return this; }
        /** 调用 show() 时自动获取焦点 */
        public Builder focusedOnShow() { focusedOnShow = true; return this; }
        /** 帧缓冲透明（用于透明窗口效果） */
        public Builder transparentFramebuffer() { transparentFb = true; return this; }
        /** 按显示器缩放因子调整帧缓冲大小（高 DPI 适配） */
        public Builder scaleToMonitor() { scaleToMonitor = true; return this; }

        // ============ 输入 & 光标 ============

        /** 启用 sticky keys（按键状态在按下/释放间保持） */
        public Builder stickyKeys() { stickyKeys = true; return this; }
        /** 启用 sticky mouse buttons */
        public Builder stickyMouseButtons() { stickyMouseButtons = true; return this; }
        /** 启用原始鼠标运动（无加速） */
        public Builder rawMouseMotion() { rawMouseMotion = true; return this; }
        /** 指定光标模式（默认 DISABLED） */
        public Builder cursorMode(CursorMode m) { cursorMode = m; return this; }

        /** 创建窗口。完成后上下文未绑定、窗口隐藏。 */
        public GlfwWindow build() { return new GlfwWindow(this); }
    }
}
