package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.nio.IntBuffer;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

/**
 * 窗口输入的一份不可变逐帧快照。
 *
 * <p>按下/重复/释放、滚轮、光标增量和已提交字符只属于当前 sequence；持续按下状态、
 * 尺寸、焦点及 composition 则描述拍摄快照时的稳定状态。</p>
 */
public final class WindowInputSnapshot {
    private final long sequence;
    private final int windowWidth;
    private final int windowHeight;
    private final int framebufferWidth;
    private final int framebufferHeight;
    private final float contentScaleX;
    private final float contentScaleY;
    private final double cursorX;
    private final double cursorY;
    private final double cursorDeltaX;
    private final double cursorDeltaY;
    private final double scrollX;
    private final double scrollY;
    private final boolean focused;
    private final boolean cursorInside;
    private final EnumSet<Key> keysDown;
    private final EnumSet<Key> keysPressed;
    private final EnumSet<Key> keysRepeated;
    private final EnumSet<Key> keysReleased;
    private final EnumSet<MouseButton> mouseButtonsDown;
    private final EnumSet<MouseButton> mouseButtonsPressed;
    private final EnumSet<MouseButton> mouseButtonsReleased;
    private final KeyModifiers modifiers;
    private final int[] committedCodePoints;
    private final ImeComposition composition;

    WindowInputSnapshot(long sequence, int windowWidth, int windowHeight,
                        int framebufferWidth, int framebufferHeight,
                        float contentScaleX, float contentScaleY,
                        double cursorX, double cursorY,
                        double cursorDeltaX, double cursorDeltaY,
                        double scrollX, double scrollY,
                        boolean focused, boolean cursorInside,
                        EnumSet<Key> keysDown, EnumSet<Key> keysPressed,
                        EnumSet<Key> keysRepeated,
                        EnumSet<Key> keysReleased,
                        EnumSet<MouseButton> mouseButtonsDown,
                        EnumSet<MouseButton> mouseButtonsPressed,
                        EnumSet<MouseButton> mouseButtonsReleased,
                        KeyModifiers modifiers, int[] committedCodePoints,
                        ImeComposition composition) {
        this.sequence = sequence;
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
        this.framebufferWidth = framebufferWidth;
        this.framebufferHeight = framebufferHeight;
        this.contentScaleX = contentScaleX;
        this.contentScaleY = contentScaleY;
        this.cursorX = cursorX;
        this.cursorY = cursorY;
        this.cursorDeltaX = cursorDeltaX;
        this.cursorDeltaY = cursorDeltaY;
        this.scrollX = scrollX;
        this.scrollY = scrollY;
        this.focused = focused;
        this.cursorInside = cursorInside;
        this.keysDown = keysDown.clone();
        this.keysPressed = keysPressed.clone();
        this.keysRepeated = keysRepeated.clone();
        this.keysReleased = keysReleased.clone();
        this.mouseButtonsDown = mouseButtonsDown.clone();
        this.mouseButtonsPressed = mouseButtonsPressed.clone();
        this.mouseButtonsReleased = mouseButtonsReleased.clone();
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
        this.committedCodePoints = committedCodePoints.clone();
        this.composition = composition;
    }

    public long sequence() { return sequence; }

    public int windowWidth() { return windowWidth; }

    public int windowHeight() { return windowHeight; }

    public int framebufferWidth() { return framebufferWidth; }

    public int framebufferHeight() { return framebufferHeight; }

    public float contentScaleX() { return contentScaleX; }

    public float contentScaleY() { return contentScaleY; }

    public double cursorX() { return cursorX; }

    public double cursorY() { return cursorY; }

    public double cursorDeltaX() { return cursorDeltaX; }

    public double cursorDeltaY() { return cursorDeltaY; }

    public double scrollX() { return scrollX; }

    public double scrollY() { return scrollY; }

    public boolean focused() { return focused; }

    public boolean cursorInside() { return cursorInside; }

    public boolean keyDown(Key key) {
        return keysDown.contains(Objects.requireNonNull(key, "key"));
    }

    public boolean keyPressed(Key key) {
        return keysPressed.contains(Objects.requireNonNull(key, "key"));
    }

    public boolean keyRepeated(Key key) {
        return keysRepeated.contains(Objects.requireNonNull(key, "key"));
    }

    public boolean keyReleased(Key key) {
        return keysReleased.contains(Objects.requireNonNull(key, "key"));
    }

    public boolean mouseDown(MouseButton button) {
        return mouseButtonsDown.contains(Objects.requireNonNull(button, "button"));
    }

    public boolean mousePressed(MouseButton button) {
        return mouseButtonsPressed.contains(Objects.requireNonNull(button, "button"));
    }

    public boolean mouseReleased(MouseButton button) {
        return mouseButtonsReleased.contains(Objects.requireNonNull(button, "button"));
    }

    public KeyModifiers modifiers() { return modifiers; }

    /**
     * 返回独立 position 的只读视图，调用方不能修改快照内容。
     *
     * @return 从 position 0 开始的 Unicode code point 缓冲区
     */
    public IntBuffer committedCodePoints() {
        return IntBuffer.wrap(committedCodePoints).asReadOnlyBuffer();
    }

    public Optional<ImeComposition> composition() {
        return Optional.ofNullable(composition);
    }
}
