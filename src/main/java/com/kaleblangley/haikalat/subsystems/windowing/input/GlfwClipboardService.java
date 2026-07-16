package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.util.Objects;
import java.util.Optional;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;
import static org.lwjgl.system.MemoryUtil.NULL;

/** 使用指定 GLFW window 作为剪贴板 owner 的实现。 */
public final class GlfwClipboardService implements ClipboardService {
    private final long windowHandle;

    public GlfwClipboardService(long windowHandle) {
        if (windowHandle == NULL) throw new IllegalArgumentException("windowHandle must not be NULL");
        this.windowHandle = windowHandle;
    }

    @Override
    public Optional<String> readText() {
        return Optional.ofNullable(glfwGetClipboardString(windowHandle));
    }

    @Override
    public void writeText(String text) {
        glfwSetClipboardString(windowHandle, Objects.requireNonNull(text, "text"));
    }
}
