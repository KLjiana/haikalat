package com.kaleblangley.haikalat.subsystems.windowing;

import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import com.kaleblangley.haikalat.subsystems.windowing.input.ClipboardService;
import com.kaleblangley.haikalat.subsystems.windowing.input.GlfwClipboardService;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;

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
    private final WindowInputCollector inputCollector;
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
        glfwSetInputMode(handle, GLFW_LOCK_KEY_MODS, GLFW_TRUE);

        int cursorMode = switch (b.cursorMode) {
            case NORMAL -> GLFW_CURSOR_NORMAL;
            case HIDDEN -> GLFW_CURSOR_HIDDEN;
            case DISABLED -> GLFW_CURSOR_DISABLED;
            case CAPTURED -> GLFW_CURSOR_CAPTURED;
        };
        glfwSetInputMode(handle, GLFW_CURSOR, cursorMode);

        inputCollector = new WindowInputCollector();
        initializeInputState();

        glfwSetWindowSizeCallback(handle, (h, w, h2) -> inputCollector.windowSize(w, h2));
        glfwSetFramebufferSizeCallback(handle, (h, w, h2) -> {
            inputCollector.framebufferSize(w, h2);
            this.width = Math.max(1, w);
            this.height = Math.max(1, h2);
            this.resized = true;
        });
        glfwSetWindowContentScaleCallback(handle,
                (h, xScale, yScale) -> inputCollector.contentScale(xScale, yScale));
        glfwSetKeyCallback(handle, (h, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) {
                keys[key] = action != GLFW_RELEASE;
            }
            var mappedKey = GlfwInputMappings.key(key);
            if (mappedKey != null) {
                var mappedModifiers = GlfwInputMappings.modifiers(mods);
                switch (action) {
                    case GLFW_PRESS -> inputCollector.pressKey(mappedKey, mappedModifiers);
                    case GLFW_REPEAT -> inputCollector.repeatKey(mappedKey, mappedModifiers);
                    case GLFW_RELEASE -> inputCollector.releaseKey(mappedKey, mappedModifiers);
                    default -> {
                        // GLFW 未来新增的 action 不应破坏输入状态。
                    }
                }
            }
        });
        glfwSetMouseButtonCallback(handle, (h, button, action, mods) -> {
            var mappedButton = GlfwInputMappings.mouseButton(button);
            if (mappedButton == null) return;
            var mappedModifiers = GlfwInputMappings.modifiers(mods);
            if (action == GLFW_PRESS) {
                inputCollector.pressMouse(mappedButton, mappedModifiers);
            } else if (action == GLFW_RELEASE) {
                inputCollector.releaseMouse(mappedButton, mappedModifiers);
            }
        });
        glfwSetCursorPosCallback(handle, (h, xpos, ypos) -> {
            inputCollector.cursorPosition(xpos, ypos);
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
        glfwSetScrollCallback(handle, (h, xOffset, yOffset) -> inputCollector.scroll(xOffset, yOffset));
        glfwSetCharCallback(handle, (h, codePoint) -> inputCollector.committedCodePoint(codePoint));
        glfwSetWindowFocusCallback(handle, (h, focused) -> {
            inputCollector.focused(focused);
            if (!focused) {
                Arrays.fill(keys, false);
                resetMouseDelta();
                firstMouse = true;
            }
        });
        glfwSetCursorEnterCallback(handle, (h, entered) -> inputCollector.cursorInside(entered));
    }

    private void initializeInputState() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var windowWidth = stack.mallocInt(1);
            var windowHeight = stack.mallocInt(1);
            glfwGetWindowSize(handle, windowWidth, windowHeight);
            inputCollector.windowSize(windowWidth.get(0), windowHeight.get(0));

            var framebufferWidth = stack.mallocInt(1);
            var framebufferHeight = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);
            int actualFramebufferWidth = framebufferWidth.get(0);
            int actualFramebufferHeight = framebufferHeight.get(0);
            inputCollector.framebufferSize(actualFramebufferWidth, actualFramebufferHeight);
            width = Math.max(1, actualFramebufferWidth);
            height = Math.max(1, actualFramebufferHeight);

            var scaleX = stack.mallocFloat(1);
            var scaleY = stack.mallocFloat(1);
            glfwGetWindowContentScale(handle, scaleX, scaleY);
            inputCollector.contentScale(scaleX.get(0), scaleY.get(0));

            var cursorX = stack.mallocDouble(1);
            var cursorY = stack.mallocDouble(1);
            glfwGetCursorPos(handle, cursorX, cursorY);
            double x = cursorX.get(0);
            double y = cursorY.get(0);
            inputCollector.cursorPosition(x, y);
            inputCollector.cursorInside(x >= 0.0 && y >= 0.0
                    && x < windowWidth.get(0) && y < windowHeight.get(0));
            inputCollector.focused(glfwGetWindowAttrib(handle, GLFW_FOCUSED) == GLFW_TRUE);
        }
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

    /**
     * 拍摄并消费自上一份快照以来的一次性输入。
     *
     * @return subsystem-neutral 的不可变窗口输入快照
     */
    public WindowInputSnapshot inputSnapshot() {
        return inputCollector.snapshot();
    }

    /** @return 绑定到当前窗口 owner 的 Unicode plain-text 剪贴板服务 */
    public ClipboardService clipboardService() {
        return new GlfwClipboardService(handle);
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
