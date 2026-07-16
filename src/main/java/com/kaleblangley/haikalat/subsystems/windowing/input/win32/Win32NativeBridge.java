package com.kaleblangley.haikalat.subsystems.windowing.input.win32;

import java.util.Objects;

/** Win32TextInputAdapter 的可替换系统边界，使生命周期逻辑可在纯 JVM 中验证。 */
interface Win32NativeBridge {
    long nativeWindowHandle(long glfwWindowHandle);

    void requireWindowThread(long nativeWindowHandle);

    WindowHook installWindowHook(long nativeWindowHandle, MessageObserver observer);

    NativeComposition readComposition(long nativeWindowHandle);

    void positionImeWindows(long nativeWindowHandle, NativeRect rect);

    @FunctionalInterface
    interface MessageObserver {
        void onMessage(int message, long wParam, long lParam);
    }

    interface WindowHook extends AutoCloseable {
        boolean installed();

        @Override
        void close();
    }

    /** IMM 返回的 UTF-16 preedit、caret 和逐 code-unit attribute。 */
    record NativeComposition(String text, int caretIndex, byte[] attributes) {
        public NativeComposition {
            Objects.requireNonNull(text, "text");
            attributes = Objects.requireNonNull(attributes, "attributes").clone();
        }

        @Override
        public byte[] attributes() {
            return attributes.clone();
        }
    }

    /** Win32 client 坐标中的排他矩形。 */
    record NativeRect(int left, int top, int right, int bottom) {
        public NativeRect {
            if (right < left || bottom < top) {
                throw new IllegalArgumentException("native IME rectangle has negative dimensions");
            }
        }
    }
}
