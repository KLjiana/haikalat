package com.kaleblangley.haikalat.subsystems.windowing.input.win32;

import com.kaleblangley.haikalat.subsystems.windowing.input.ImeComposition;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputClient;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputRect;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Windows IMM 文本输入适配器。
 *
 * <p>构造、activate/deactivate、候选框定位和 close 必须由创建 GLFW 窗口的线程调用。
 * WndProc 只观察 composition 生命周期并始终链式调用原过程。已提交字符仍以 GLFW char
 * callback 为唯一来源；本适配器明确忽略 {@code GCS_RESULTSTR} 内容，避免重复提交。</p>
 */
public final class Win32TextInputAdapter implements TextInputAdapter {
    private static final System.Logger LOG =
            System.getLogger(Win32TextInputAdapter.class.getName());

    private final Thread ownerThread;
    private final Win32NativeBridge nativeBridge;
    private final long nativeWindowHandle;
    private final Win32NativeBridge.WindowHook windowHook;
    private volatile ImeComposition composition;
    private volatile Throwable callbackFailure;
    private volatile boolean closed;
    private TextInputClient activeClient;
    private boolean composing;
    private TextInputRect candidateRect = TextInputRect.EMPTY;

    /**
     * 从 GLFW Win32 native window handle 安装可链式 WndProc hook。
     *
     * @param glfwWindowHandle 有效 GLFWwindow 指针
     * @throws UnsupportedOperationException 当前平台不是 Windows
     */
    public Win32TextInputAdapter(long glfwWindowHandle) {
        this(glfwWindowHandle, createNativeBridge());
    }

    Win32TextInputAdapter(long glfwWindowHandle, Win32NativeBridge nativeBridge) {
        ownerThread = Thread.currentThread();
        this.nativeBridge = Objects.requireNonNull(nativeBridge, "nativeBridge");
        nativeWindowHandle = nativeBridge.nativeWindowHandle(glfwWindowHandle);
        nativeBridge.requireWindowThread(nativeWindowHandle);
        Win32NativeBridge.WindowHook installedHook = null;
        try {
            installedHook = nativeBridge.installWindowHook(nativeWindowHandle, this::observeMessage);
            if (installedHook == null || !installedHook.installed()) {
                throw new IllegalStateException("Win32 WndProc hook was not installed");
            }
        } catch (RuntimeException | Error installFailure) {
            if (installedHook != null) {
                try {
                    installedHook.close();
                } catch (RuntimeException | Error restoreFailure) {
                    installFailure.addSuppressed(restoreFailure);
                }
            }
            throw installFailure;
        }
        windowHook = installedHook;
    }

    /** 返回运行平台是否为 Windows；该检查不会初始化 JNA Win32 binding。 */
    public static boolean isSupported() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.startsWith("windows");
    }

    @Override
    public void activate(TextInputClient client) {
        requireOwnerThread();
        ensureOpen();
        Objects.requireNonNull(client, "client");
        if (activeClient != null && activeClient != client) {
            throw new IllegalStateException("another text input client is already active");
        }
        activeClient = client;
        nativeBridge.positionImeWindows(nativeWindowHandle, toNativeRect(candidateRect));
    }

    @Override
    public void deactivate(TextInputClient client) {
        requireOwnerThread();
        ensureOpen();
        Objects.requireNonNull(client, "client");
        if (activeClient == client) {
            deactivateCurrentClient();
        }
    }

    @Override
    public void setCandidateRect(TextInputRect rect) {
        requireOwnerThread();
        ensureOpen();
        candidateRect = Objects.requireNonNull(rect, "rect");
        nativeBridge.positionImeWindows(nativeWindowHandle, toNativeRect(rect));
    }

    @Override
    public Optional<ImeComposition> composition() {
        ensureOpen();
        return Optional.ofNullable(composition);
    }

    @Override
    public boolean compositionAvailable() {
        return true;
    }

    /**
     * 先停用 text client，再恢复原 WndProc。恢复失败时保留 hook 强引用并允许调用方重试 close。
     */
    @Override
    public void close() {
        requireOwnerThread();
        if (closed) {
            return;
        }
        Throwable failure = null;
        try {
            deactivateCurrentClient();
        } catch (RuntimeException | Error clientFailure) {
            failure = clientFailure;
        }
        try {
            windowHook.close();
            closed = true;
        } catch (RuntimeException | Error hookFailure) {
            if (failure == null) {
                failure = hookFailure;
            } else {
                failure.addSuppressed(hookFailure);
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    long nativeWindowHandle() {
        return nativeWindowHandle;
    }

    Optional<Throwable> callbackFailure() {
        return Optional.ofNullable(callbackFailure);
    }

    private void observeMessage(int message, long wParam, long lParam) {
        try {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("WndProc callback arrived on a non-owner thread");
            }
            switch (message) {
                case Win32ImeConstants.WM_IME_STARTCOMPOSITION -> startComposition();
                case Win32ImeConstants.WM_IME_COMPOSITION -> updateComposition(lParam);
                case Win32ImeConstants.WM_IME_ENDCOMPOSITION -> finishComposition();
                default -> {
                    // Bridge 会把所有消息（包括观察过的 IME 消息）继续交给原 WndProc。
                }
            }
        } catch (Throwable failure) {
            callbackFailure = failure;
            LOG.log(System.Logger.Level.ERROR, "Win32 IME callback failed", failure);
        }
    }

    private void startComposition() {
        if (composing) {
            return;
        }
        composing = true;
        composition = new ImeComposition("", 0, 0, 0);
        if (activeClient != null) {
            activeClient.compositionStarted();
        }
    }

    private void updateComposition(long flags) {
        long preeditMask = Win32ImeConstants.GCS_COMPSTR
                | Win32ImeConstants.GCS_COMPATTR
                | Win32ImeConstants.GCS_CURSORPOS;
        if ((flags & preeditMask) != 0L) {
            if (!composing) {
                startComposition();
            }
            ImeComposition updated = toComposition(
                    nativeBridge.readComposition(nativeWindowHandle));
            composition = updated;
            if (activeClient != null) {
                activeClient.compositionUpdated(updated);
            }
        }
        if ((flags & Win32ImeConstants.GCS_RESULTSTR) != 0L) {
            // Result text is deliberately not read or published. GLFW char is the sole commit source.
            finishComposition();
        }
    }

    private void finishComposition() {
        boolean hadComposition = composing || composition != null;
        composing = false;
        composition = null;
        if (hadComposition && activeClient != null) {
            activeClient.compositionCancelled();
        }
    }

    private void deactivateCurrentClient() {
        TextInputClient client = activeClient;
        boolean hadComposition = composing || composition != null;
        activeClient = null;
        composing = false;
        composition = null;
        if (client != null && hadComposition) {
            client.compositionCancelled();
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Win32TextInputAdapter must be mutated on its window thread");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Win32TextInputAdapter is closed");
        }
    }

    private static Win32NativeBridge createNativeBridge() {
        if (!isSupported()) {
            throw new UnsupportedOperationException(
                    "Win32TextInputAdapter is only available on Windows");
        }
        return new JnaWin32NativeBridge();
    }

    private static ImeComposition toComposition(Win32NativeBridge.NativeComposition nativeValue) {
        String text = nativeValue.text();
        int caret = Math.clamp(nativeValue.caretIndex(), 0, text.length());
        int selectionStart = -1;
        int selectionEnd = -1;
        byte[] attributes = nativeValue.attributes();
        int attributeCount = Math.min(attributes.length, text.length());
        for (int index = 0; index < attributeCount; index++) {
            int attribute = Byte.toUnsignedInt(attributes[index]);
            if (attribute == Win32ImeConstants.ATTR_TARGET_CONVERTED
                    || attribute == Win32ImeConstants.ATTR_TARGET_NOTCONVERTED) {
                if (selectionStart < 0) {
                    selectionStart = index;
                }
                selectionEnd = index + 1;
            }
        }
        if (selectionStart < 0) {
            selectionStart = caret;
            selectionEnd = caret;
        }
        return new ImeComposition(text, selectionStart, selectionEnd, caret);
    }

    private static Win32NativeBridge.NativeRect toNativeRect(TextInputRect source) {
        int left = floorToInt(source.x());
        int top = floorToInt(source.y());
        int right = ceilToInt(source.x() + source.width());
        int bottom = ceilToInt(source.y() + source.height());
        return new Win32NativeBridge.NativeRect(left, top,
                Math.max(left, right), Math.max(top, bottom));
    }

    private static int floorToInt(double value) {
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(value);
    }

    private static int ceilToInt(double value) {
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(value);
    }
}
