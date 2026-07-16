package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;

import java.nio.IntBuffer;

/**
 * 在固定物理 framebuffer 上注入内容缩放，同时保持输入 sequence 和边沿语义。
 */
final class UiDemoInput {
    private final WindowInputCollector scaled = new WindowInputCollector();

    WindowInputSnapshot adapt(WindowInputSnapshot source,
                              UiDemoOptions.ContentScale contentScale) {
        int framebufferWidth = source.framebufferWidth();
        int framebufferHeight = source.framebufferHeight();
        int logicalWidth = logicalSize(framebufferWidth, contentScale.x());
        int logicalHeight = logicalSize(framebufferHeight, contentScale.y());
        scaled.windowSize(logicalWidth, logicalHeight);
        scaled.framebufferSize(framebufferWidth, framebufferHeight);
        scaled.contentScale(contentScale.x(), contentScale.y());
        scaled.focused(source.focused());
        scaled.cursorInside(source.cursorInside());

        double cursorX = rescale(source.cursorX(), source.windowWidth(), logicalWidth);
        double cursorY = rescale(source.cursorY(), source.windowHeight(), logicalHeight);
        scaled.cursorPosition(cursorX, cursorY);
        if (source.scrollX() != 0.0 || source.scrollY() != 0.0) {
            scaled.scroll(source.scrollX(), source.scrollY());
        }
        for (Key key : Key.values()) {
            if (source.keyPressed(key)) scaled.pressKey(key, source.modifiers());
            if (source.keyReleased(key)) scaled.releaseKey(key, source.modifiers());
        }
        for (MouseButton button : MouseButton.values()) {
            if (source.mousePressed(button)) scaled.pressMouse(button, source.modifiers());
            if (source.mouseReleased(button)) scaled.releaseMouse(button, source.modifiers());
        }
        IntBuffer committed = source.committedCodePoints();
        while (committed.hasRemaining()) scaled.committedCodePoint(committed.get());
        source.composition().ifPresentOrElse(scaled::composition, scaled::clearComposition);
        return scaled.snapshot();
    }

    private static int logicalSize(int framebufferSize, float scale) {
        if (framebufferSize == 0) return 0;
        return Math.max(1, Math.round(framebufferSize / scale));
    }

    private static double rescale(double coordinate, int sourceSize, int targetSize) {
        return sourceSize <= 0 ? coordinate : coordinate * targetSize / sourceSize;
    }
}
