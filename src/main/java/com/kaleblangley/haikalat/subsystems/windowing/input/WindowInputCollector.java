package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;

/**
 * 在窗口事件线程累计回调，并按需发布不可变输入快照。
 *
 * <p>该对象本身不是跨线程可变容器。跨线程边界应只发布 {@link WindowInputSnapshot}。</p>
 */
public final class WindowInputCollector {
    private final EnumSet<Key> keysDown = EnumSet.noneOf(Key.class);
    private final EnumSet<Key> keysPressed = EnumSet.noneOf(Key.class);
    private final EnumSet<Key> keysRepeated = EnumSet.noneOf(Key.class);
    private final EnumSet<Key> keysReleased = EnumSet.noneOf(Key.class);
    private final EnumSet<MouseButton> mouseButtonsDown = EnumSet.noneOf(MouseButton.class);
    private final EnumSet<MouseButton> mouseButtonsPressed = EnumSet.noneOf(MouseButton.class);
    private final EnumSet<MouseButton> mouseButtonsReleased = EnumSet.noneOf(MouseButton.class);
    private int[] committedCodePoints = new int[8];
    private int committedCodePointCount;
    private long sequence;
    private int windowWidth;
    private int windowHeight;
    private int framebufferWidth;
    private int framebufferHeight;
    private float contentScaleX = 1.0f;
    private float contentScaleY = 1.0f;
    private double cursorX;
    private double cursorY;
    private double cursorDeltaX;
    private double cursorDeltaY;
    private double scrollX;
    private double scrollY;
    private boolean cursorBaselineValid;
    private boolean focused;
    private boolean cursorInside;
    private KeyModifiers modifiers = KeyModifiers.NONE;
    private ImeComposition composition;

    /** 更新窗口内容区的逻辑尺寸；0 尺寸不会覆盖最后一个有效布局尺寸。 */
    public void windowSize(int width, int height) {
        requireDimensions(width, height, "window");
        if (width > 0 && height > 0) {
            windowWidth = width;
            windowHeight = height;
        }
    }

    /** 更新 framebuffer 的原始像素尺寸，最小化时允许为 0×0。 */
    public void framebufferSize(int width, int height) {
        requireDimensions(width, height, "framebuffer");
        framebufferWidth = width;
        framebufferHeight = height;
    }

    /** 更新窗口内容相对 framebuffer 的非整数缩放。 */
    public void contentScale(float x, float y) {
        if (!Float.isFinite(x) || x <= 0.0f || !Float.isFinite(y) || y <= 0.0f) {
            throw new IllegalArgumentException("content scale must be finite and positive");
        }
        contentScaleX = x;
        contentScaleY = y;
    }

    /** 记录一次非重复按键按下。 */
    public void pressKey(Key key, KeyModifiers modifiers) {
        key = Objects.requireNonNull(key, "key");
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
        if (keysDown.add(key)) keysPressed.add(key);
    }

    /** 记录操作系统按键重复；repeat 是独立的一次性边沿，不会伪造新的 pressed。 */
    public void repeatKey(Key key, KeyModifiers modifiers) {
        key = Objects.requireNonNull(key, "key");
        keysDown.add(key);
        keysRepeated.add(key);
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
    }

    /** 记录一次按键释放。 */
    public void releaseKey(Key key, KeyModifiers modifiers) {
        key = Objects.requireNonNull(key, "key");
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
        keysDown.remove(key);
        keysReleased.add(key);
    }

    /** 记录一次鼠标按钮按下。 */
    public void pressMouse(MouseButton button, KeyModifiers modifiers) {
        button = Objects.requireNonNull(button, "button");
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
        if (mouseButtonsDown.add(button)) mouseButtonsPressed.add(button);
    }

    /** 记录一次鼠标按钮释放。 */
    public void releaseMouse(MouseButton button, KeyModifiers modifiers) {
        button = Objects.requireNonNull(button, "button");
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
        mouseButtonsDown.remove(button);
        mouseButtonsReleased.add(button);
    }

    /** 更新左上角原点、Y 向下的逻辑光标坐标。 */
    public void cursorPosition(double x, double y) {
        requireFinite(x, "cursor x");
        requireFinite(y, "cursor y");
        if (cursorBaselineValid) {
            cursorDeltaX += x - cursorX;
            cursorDeltaY += y - cursorY;
        }
        cursorX = x;
        cursorY = y;
        cursorBaselineValid = true;
    }

    /** 累加本轮水平和垂直滚轮增量。 */
    public void scroll(double x, double y) {
        requireFinite(x, "scroll x");
        requireFinite(y, "scroll y");
        scrollX += x;
        scrollY += y;
    }

    /** 更新窗口焦点；失焦会为所有仍按下的键和按钮合成 release。 */
    public void focused(boolean focused) {
        this.focused = focused;
        if (!focused) {
            keysReleased.addAll(keysDown);
            keysDown.clear();
            keysRepeated.clear();
            mouseButtonsReleased.addAll(mouseButtonsDown);
            mouseButtonsDown.clear();
            modifiers = KeyModifiers.NONE;
            composition = null;
            cursorBaselineValid = false;
        }
    }

    /** 更新光标是否位于窗口内容区域。 */
    public void cursorInside(boolean inside) {
        cursorInside = inside;
        if (!inside) cursorBaselineValid = false;
    }

    /** 追加一个 GLFW 已提交的 Unicode scalar value。 */
    public void committedCodePoint(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)
                || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
            throw new IllegalArgumentException("invalid Unicode scalar value: " + codePoint);
        }
        if (committedCodePointCount == committedCodePoints.length) {
            committedCodePoints = Arrays.copyOf(committedCodePoints, committedCodePoints.length * 2);
        }
        committedCodePoints[committedCodePointCount++] = codePoint;
    }

    /** 更新当前 IME 预编辑状态。 */
    public void composition(ImeComposition composition) {
        this.composition = Objects.requireNonNull(composition, "composition");
    }

    /** 清除当前 IME 预编辑状态。 */
    public void clearComposition() {
        composition = null;
    }

    /**
     * 拍摄下一份快照，并消费所有一次性输入；持续状态不会清除。
     *
     * @return sequence 严格递增的不可变快照
     */
    public WindowInputSnapshot snapshot() {
        WindowInputSnapshot snapshot = new WindowInputSnapshot(++sequence,
                windowWidth, windowHeight, framebufferWidth, framebufferHeight,
                contentScaleX, contentScaleY, cursorX, cursorY,
                cursorDeltaX, cursorDeltaY, scrollX, scrollY,
                focused, cursorInside, keysDown, keysPressed, keysRepeated, keysReleased,
                mouseButtonsDown, mouseButtonsPressed, mouseButtonsReleased,
                modifiers, Arrays.copyOf(committedCodePoints, committedCodePointCount),
                composition);
        keysPressed.clear();
        keysRepeated.clear();
        keysReleased.clear();
        mouseButtonsPressed.clear();
        mouseButtonsReleased.clear();
        cursorDeltaX = 0.0;
        cursorDeltaY = 0.0;
        scrollX = 0.0;
        scrollY = 0.0;
        committedCodePointCount = 0;
        return snapshot;
    }

    private static void requireDimensions(int width, int height, String kind) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(kind + " dimensions must be non-negative");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
