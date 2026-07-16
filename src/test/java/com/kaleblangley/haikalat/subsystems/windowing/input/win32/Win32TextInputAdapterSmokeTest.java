package com.kaleblangley.haikalat.subsystems.windowing.input.win32;

import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputRect;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.QueuedTextInputClient;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Windows 本机 WndProc 安装、链式转发和恢复 smoke，不要求激活真实输入法。 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class Win32TextInputAdapterSmokeTest {
    @Test
    void installsObservesAndRestoresRealWindowProcedure() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(64, 32)
                .title("Win32 IME Hook Smoke")
                .visible(false)
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build()) {
            QueuedTextInputClient client = new QueuedTextInputClient();
            Win32TextInputAdapter adapter = new Win32TextInputAdapter(window.handle());
            try (adapter) {
                adapter.activate(client);
                adapter.setCandidateRect(new TextInputRect(4.0, 5.0, 1.0, 14.0));
                WinDef.HWND hwnd = new WinDef.HWND(new Pointer(adapter.nativeWindowHandle()));

                send(hwnd, Win32ImeConstants.WM_IME_STARTCOMPOSITION);
                send(hwnd, Win32ImeConstants.WM_IME_ENDCOMPOSITION);

                List<QueuedTextInputClient.Command> commands = new ArrayList<>();
                client.drain(commands::add);
                assertEquals(2, commands.size());
                assertInstanceOf(QueuedTextInputClient.Command.Started.class, commands.get(0));
                assertInstanceOf(QueuedTextInputClient.Command.Cancelled.class, commands.get(1));

                adapter.close();
                adapter.close();
                send(hwnd, Win32ImeConstants.WM_IME_STARTCOMPOSITION);
                assertEquals(0, client.drain(command -> { }),
                        "restored WndProc must no longer invoke the Java hook");
            }
        }
    }

    private static void send(WinDef.HWND hwnd, int message) {
        User32.INSTANCE.SendMessage(hwnd, message, new WinDef.WPARAM(), new WinDef.LPARAM());
    }
}
