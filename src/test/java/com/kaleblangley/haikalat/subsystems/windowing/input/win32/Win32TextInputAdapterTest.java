package com.kaleblangley.haikalat.subsystems.windowing.input.win32;

import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputRect;
import com.kaleblangley.haikalat.subsystems.windowing.input.ImeComposition;
import com.kaleblangley.haikalat.subsystems.windowing.input.QueuedTextInputClient;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Win32TextInputAdapterTest {
    @Test
    void publishesPreeditSelectionAndNeverPublishesNativeResultAsCommit() {
        FakeBridge bridge = new FakeBridge();
        bridge.composition = new Win32NativeBridge.NativeComposition(
                "拼音", 1, new byte[]{0, Win32ImeConstants.ATTR_TARGET_CONVERTED});
        Win32TextInputAdapter adapter = new Win32TextInputAdapter(7L, bridge);
        QueuedTextInputClient client = new QueuedTextInputClient();
        try {
            adapter.activate(client);
            adapter.setCandidateRect(new TextInputRect(10.25, 20.75, 3.5, 15.25));
            bridge.fire(Win32ImeConstants.WM_IME_STARTCOMPOSITION, 0L);
            bridge.fire(Win32ImeConstants.WM_IME_COMPOSITION,
                    Win32ImeConstants.GCS_COMPSTR
                            | Win32ImeConstants.GCS_COMPATTR
                            | Win32ImeConstants.GCS_CURSORPOS);

            ImeComposition current = adapter.composition().orElseThrow();
            assertEquals("拼音", current.text());
            assertEquals(1, current.caretIndex());
            assertEquals(1, current.selectionStart());
            assertEquals(2, current.selectionEnd());
            assertEquals(new Win32NativeBridge.NativeRect(10, 20, 14, 36), bridge.positioned);

            bridge.fire(Win32ImeConstants.WM_IME_COMPOSITION,
                    Win32ImeConstants.GCS_RESULTSTR);
            bridge.fire(Win32ImeConstants.WM_IME_ENDCOMPOSITION, 0L);

            List<QueuedTextInputClient.Command> commands = new ArrayList<>();
            client.drain(commands::add);
            assertEquals(3, commands.size());
            assertInstanceOf(QueuedTextInputClient.Command.Started.class, commands.get(0));
            var update = assertInstanceOf(QueuedTextInputClient.Command.Updated.class,
                    commands.get(1));
            assertEquals(current, update.composition());
            assertInstanceOf(QueuedTextInputClient.Command.Cancelled.class, commands.get(2));
            assertTrue(commands.stream().noneMatch(
                    QueuedTextInputClient.Command.Committed.class::isInstance),
                    "GCS_RESULTSTR must not duplicate GLFW committed-char delivery");
            assertTrue(adapter.composition().isEmpty());
            assertTrue(adapter.callbackFailure().isEmpty());
        } finally {
            adapter.close();
        }
    }

    @Test
    void closeDeactivatesClientBeforeRestoringHookAndCanRetryRestoreFailure() {
        List<String> lifecycle = new ArrayList<>();
        FakeBridge bridge = new FakeBridge(lifecycle);
        Win32TextInputAdapter adapter = new Win32TextInputAdapter(7L, bridge);
        adapter.activate(new RecordingClient(lifecycle));
        bridge.fire(Win32ImeConstants.WM_IME_STARTCOMPOSITION, 0L);
        bridge.hook.failClose = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class, adapter::close);

        assertEquals("restore failed", failure.getMessage());
        assertEquals(List.of("started", "cancelled", "hook.close"), lifecycle);
        assertTrue(bridge.hook.installed());

        bridge.hook.failClose = false;
        adapter.close();
        adapter.close();
        assertFalse(bridge.hook.installed());
        assertEquals(List.of("started", "cancelled", "hook.close", "hook.close"), lifecycle);
        assertThrows(IllegalStateException.class, adapter::composition);
    }

    @Test
    void mutableOperationsAreConfinedToCreationThreadButSnapshotReadIsSafe() throws Exception {
        FakeBridge bridge = new FakeBridge();
        Win32TextInputAdapter adapter = new Win32TextInputAdapter(7L, bridge);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> executor.submit(() -> adapter.setCandidateRect(TextInputRect.EMPTY)).get());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(executor.submit(adapter::composition).get().isEmpty());
        } finally {
            adapter.close();
        }
    }

    @Test
    void constructorRestoresAReportedButInactiveHook() {
        FakeBridge bridge = new FakeBridge();
        bridge.hook.installed = false;

        assertThrows(IllegalStateException.class, () -> new Win32TextInputAdapter(7L, bridge));

        assertEquals(1, bridge.hook.closeAttempts);
    }

    private static final class FakeBridge implements Win32NativeBridge {
        private final List<String> lifecycle;
        private final FakeHook hook;
        private MessageObserver observer;
        private NativeComposition composition = new NativeComposition("", 0, new byte[0]);
        private NativeRect positioned;

        private FakeBridge() {
            this(new ArrayList<>());
        }

        private FakeBridge(List<String> lifecycle) {
            this.lifecycle = lifecycle;
            hook = new FakeHook(lifecycle);
        }

        @Override public long nativeWindowHandle(long glfwWindowHandle) {
            return glfwWindowHandle + 100L;
        }

        @Override public void requireWindowThread(long nativeWindowHandle) {
            if (nativeWindowHandle != 107L) {
                throw new IllegalStateException("unexpected HWND");
            }
        }

        @Override public WindowHook installWindowHook(long nativeWindowHandle,
                                                       MessageObserver value) {
            observer = value;
            return hook;
        }

        @Override public NativeComposition readComposition(long nativeWindowHandle) {
            return composition;
        }

        @Override public void positionImeWindows(long nativeWindowHandle, NativeRect rect) {
            positioned = rect;
        }

        private void fire(int message, long flags) {
            observer.onMessage(message, 0L, flags);
        }
    }

    private static final class FakeHook implements Win32NativeBridge.WindowHook {
        private final List<String> lifecycle;
        private boolean installed = true;
        private boolean failClose;
        private int closeAttempts;

        private FakeHook(List<String> lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Override public boolean installed() {
            return installed;
        }

        @Override public void close() {
            closeAttempts++;
            lifecycle.add("hook.close");
            if (failClose) {
                throw new IllegalStateException("restore failed");
            }
            installed = false;
        }
    }

    private static final class RecordingClient implements TextInputClient {
        private final List<String> events;

        private RecordingClient(List<String> events) {
            this.events = events;
        }

        @Override public void compositionStarted() { events.add("started"); }
        @Override public void compositionUpdated(ImeComposition composition) {
            events.add("updated");
        }
        @Override public void compositionCommitted(String text) { events.add("committed"); }
        @Override public void compositionCancelled() { events.add("cancelled"); }
    }
}
