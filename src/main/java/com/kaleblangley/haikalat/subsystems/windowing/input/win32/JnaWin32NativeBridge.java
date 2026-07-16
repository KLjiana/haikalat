package com.kaleblangley.haikalat.subsystems.windowing.input.win32;

import com.sun.jna.CallbackReference;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 只由 Windows 构造路径加载的 JNA/IMM 实现。 */
final class JnaWin32NativeBridge implements Win32NativeBridge {
    private static final System.Logger LOG =
            System.getLogger(JnaWin32NativeBridge.class.getName());

    private final User32 user32;
    private final Kernel32 kernel32;
    private final Imm32Library imm32;

    JnaWin32NativeBridge() {
        user32 = User32.INSTANCE;
        kernel32 = Kernel32.INSTANCE;
        imm32 = Native.load("imm32", Imm32Library.class, W32APIOptions.DEFAULT_OPTIONS);
    }

    @Override
    public long nativeWindowHandle(long glfwWindowHandle) {
        if (glfwWindowHandle == 0L) {
            throw new IllegalArgumentException("GLFW window handle must be non-zero");
        }
        long nativeHandle = GLFWNativeWin32.glfwGetWin32Window(glfwWindowHandle);
        if (nativeHandle == 0L) {
            throw new IllegalStateException("GLFW did not return a Win32 HWND");
        }
        return nativeHandle;
    }

    @Override
    public void requireWindowThread(long nativeWindowHandle) {
        WinDef.HWND hwnd = hwnd(nativeWindowHandle);
        int windowThread = user32.GetWindowThreadProcessId(hwnd, null);
        int currentThread = kernel32.GetCurrentThreadId();
        if (windowThread == 0) {
            throw win32Failure("GetWindowThreadProcessId");
        }
        if (windowThread != currentThread) {
            throw new IllegalStateException("Win32 IME hook must be installed on the HWND owner thread"
                    + " (window=" + windowThread + ", current=" + currentThread + ")");
        }
    }

    @Override
    public WindowHook installWindowHook(long nativeWindowHandle, MessageObserver observer) {
        return new JnaWindowHook(hwnd(nativeWindowHandle), Objects.requireNonNull(observer, "observer"));
    }

    @Override
    public NativeComposition readComposition(long nativeWindowHandle) {
        WinDef.HWND hwnd = hwnd(nativeWindowHandle);
        Pointer context = imm32.ImmGetContext(hwnd);
        if (context == null) {
            return new NativeComposition("", 0, new byte[0]);
        }
        try {
            String text = readUtf16(context, Win32ImeConstants.GCS_COMPSTR);
            int caret = imm32.ImmGetCompositionStringW(context,
                    Win32ImeConstants.GCS_CURSORPOS, null, 0);
            if (caret < 0) {
                caret = text.length();
            }
            return new NativeComposition(text, caret,
                    readBytes(context, Win32ImeConstants.GCS_COMPATTR));
        } finally {
            if (!imm32.ImmReleaseContext(hwnd, context).booleanValue()) {
                LOG.log(System.Logger.Level.WARNING, "ImmReleaseContext failed");
            }
        }
    }

    @Override
    public void positionImeWindows(long nativeWindowHandle, NativeRect rect) {
        Objects.requireNonNull(rect, "rect");
        WinDef.HWND hwnd = hwnd(nativeWindowHandle);
        Pointer context = imm32.ImmGetContext(hwnd);
        if (context == null) {
            return;
        }
        try {
            CompositionForm composition = new CompositionForm();
            composition.dwStyle = Win32ImeConstants.CFS_POINT;
            composition.ptCurrentPos = new WinDef.POINT(rect.left(), rect.top());
            composition.rcArea = rectangle(rect);
            composition.write();
            if (!imm32.ImmSetCompositionWindow(context, composition).booleanValue()) {
                LOG.log(System.Logger.Level.DEBUG, "ImmSetCompositionWindow returned FALSE");
            }

            CandidateForm candidate = new CandidateForm();
            candidate.dwIndex = 0;
            candidate.dwStyle = Win32ImeConstants.CFS_CANDIDATEPOS;
            candidate.ptCurrentPos = new WinDef.POINT(rect.left(), rect.bottom());
            candidate.rcArea = rectangle(rect);
            candidate.write();
            if (!imm32.ImmSetCandidateWindow(context, candidate).booleanValue()) {
                LOG.log(System.Logger.Level.DEBUG, "ImmSetCandidateWindow returned FALSE");
            }
        } finally {
            if (!imm32.ImmReleaseContext(hwnd, context).booleanValue()) {
                LOG.log(System.Logger.Level.WARNING, "ImmReleaseContext failed");
            }
        }
    }

    private String readUtf16(Pointer context, int index) {
        int byteCount = imm32.ImmGetCompositionStringW(context, index, null, 0);
        if (byteCount <= 0) {
            return "";
        }
        Memory memory = new Memory(byteCount);
        int actual = imm32.ImmGetCompositionStringW(context, index, memory, byteCount);
        if (actual <= 0) {
            return "";
        }
        int evenLength = Math.min(actual, byteCount) & ~1;
        return new String(memory.getByteArray(0L, evenLength), StandardCharsets.UTF_16LE);
    }

    private byte[] readBytes(Pointer context, int index) {
        int byteCount = imm32.ImmGetCompositionStringW(context, index, null, 0);
        if (byteCount <= 0) {
            return new byte[0];
        }
        Memory memory = new Memory(byteCount);
        int actual = imm32.ImmGetCompositionStringW(context, index, memory, byteCount);
        return actual <= 0 ? new byte[0]
                : memory.getByteArray(0L, Math.min(actual, byteCount));
    }

    private static WinDef.RECT rectangle(NativeRect source) {
        WinDef.RECT rectangle = new WinDef.RECT();
        rectangle.left = source.left();
        rectangle.top = source.top();
        rectangle.right = source.right();
        rectangle.bottom = source.bottom();
        return rectangle;
    }

    private static WinDef.HWND hwnd(long value) {
        return new WinDef.HWND(new Pointer(value));
    }

    private IllegalStateException win32Failure(String operation) {
        return new IllegalStateException(operation + " failed with Win32 error "
                + kernel32.GetLastError());
    }

    private final class JnaWindowHook implements WindowHook {
        private final WinDef.HWND hwnd;
        private final WinUser.WindowProc callback;
        private final Pointer callbackPointer;
        private Pointer originalProcedure;
        private boolean installed;

        private JnaWindowHook(WinDef.HWND hwnd, MessageObserver observer) {
            this.hwnd = hwnd;
            kernel32.SetLastError(0);
            BaseTSD.LONG_PTR original = user32.GetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC);
            originalProcedure = original == null ? null : original.toPointer();
            if (originalProcedure == null) {
                throw win32Failure("GetWindowLongPtr(GWL_WNDPROC)");
            }
            callback = (window, message, wParam, lParam) -> {
                try {
                    observer.onMessage(message, wParam.longValue(), lParam.longValue());
                } catch (Throwable failure) {
                    LOG.log(System.Logger.Level.ERROR,
                            "Win32 IME observer failed; forwarding message to original WndProc", failure);
                }
                return user32.CallWindowProc(originalProcedure, window, message, wParam, lParam);
            };
            callbackPointer = CallbackReference.getFunctionPointer(callback);

            kernel32.SetLastError(0);
            Pointer previous = user32.SetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC, callbackPointer);
            int error = kernel32.GetLastError();
            if (previous == null && error != 0) {
                IllegalStateException failure = new IllegalStateException(
                        "SetWindowLongPtr(GWL_WNDPROC) failed with Win32 error " + error);
                restoreIfPartiallyInstalled(failure);
                throw failure;
            }
            if (previous != null) {
                originalProcedure = previous;
            }
            installed = true;
        }

        private void restoreIfPartiallyInstalled(RuntimeException installFailure) {
            BaseTSD.LONG_PTR currentValue = user32.GetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC);
            Pointer current = currentValue == null ? null : currentValue.toPointer();
            if (!Objects.equals(callbackPointer, current)) {
                return;
            }
            kernel32.SetLastError(0);
            user32.SetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC, originalProcedure);
            int restoreError = kernel32.GetLastError();
            if (restoreError != 0) {
                installFailure.addSuppressed(new IllegalStateException(
                        "restoring partially installed WndProc failed with Win32 error "
                                + restoreError));
            }
        }

        @Override
        public boolean installed() {
            return installed;
        }

        @Override
        public void close() {
            if (!installed) {
                return;
            }
            BaseTSD.LONG_PTR currentValue = user32.GetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC);
            Pointer current = currentValue == null ? null : currentValue.toPointer();
            if (!Objects.equals(callbackPointer, current)) {
                throw new IllegalStateException("cannot restore WndProc while a newer hook is chained above IME");
            }
            kernel32.SetLastError(0);
            Pointer previous = user32.SetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC, originalProcedure);
            int error = kernel32.GetLastError();
            if (previous == null && error != 0) {
                throw new IllegalStateException("restoring WndProc failed with Win32 error " + error);
            }
            installed = false;
        }
    }

    /** 仅声明 IMM32 所需的最小 ABI，避免把 native 类型泄漏到 windowing 公共接口。 */
    private interface Imm32Library extends StdCallLibrary {
        Pointer ImmGetContext(WinDef.HWND hwnd);

        WinDef.BOOL ImmReleaseContext(WinDef.HWND hwnd, Pointer context);

        int ImmGetCompositionStringW(Pointer context, int index, Pointer buffer, int byteCount);

        WinDef.BOOL ImmSetCompositionWindow(Pointer context, CompositionForm form);

        WinDef.BOOL ImmSetCandidateWindow(Pointer context, CandidateForm form);
    }

    @Structure.FieldOrder({"dwStyle", "ptCurrentPos", "rcArea"})
    public static final class CompositionForm extends Structure {
        public int dwStyle;
        public WinDef.POINT ptCurrentPos;
        public WinDef.RECT rcArea;
    }

    @Structure.FieldOrder({"dwIndex", "dwStyle", "ptCurrentPos", "rcArea"})
    public static final class CandidateForm extends Structure {
        public int dwIndex;
        public int dwStyle;
        public WinDef.POINT ptCurrentPos;
        public WinDef.RECT rcArea;
    }
}
