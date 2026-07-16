package com.kaleblangley.haikalat.subsystems.windowing.input.win32;

import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.QueuedTextInputClient;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputRect;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 反复验证真实 GLFW 窗口与 Win32 WndProc hook 的完整所有权周期。 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "haikalat.uiNativeSoak", matches = "true")
class Win32TextInputAdapterNativeSoakTest {
    private static volatile int gcPressureChecksum;

    @Test
    void callbackSurvivesGcAcrossRepeatedWindowLifecycles() {
        int rounds = Integer.getInteger("haikalat.uiNativeSoakRounds", 50);
        assertTrue(rounds >= 50, "release soak requires at least 50 lifecycle rounds");

        for (int round = 0; round < rounds; round++) {
            runLifecycle(round);
        }
    }

    private static void runLifecycle(int round) {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(96 + round % 5, 48 + round % 3)
                .title("Win32 IME Native Soak " + round)
                .visible(false)
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build()) {
            window.pollEvents();
            QueuedTextInputClient client = new QueuedTextInputClient();
            Win32TextInputAdapter adapter = new Win32TextInputAdapter(window.handle());
            try (adapter) {
                adapter.activate(client);
                adapter.setCandidateRect(new TextInputRect(
                        3.25 + round, 4.5, 1.0, 16.0));
                WinDef.HWND hwnd = new WinDef.HWND(new Pointer(adapter.nativeWindowHandle()));

                forceGcPressure(round);
                window.pollEvents();
                send(hwnd, Win32ImeConstants.WM_IME_STARTCOMPOSITION);
                send(hwnd, Win32ImeConstants.WM_IME_ENDCOMPOSITION);

                List<QueuedTextInputClient.Command> commands = new ArrayList<>();
                client.drain(commands::add);
                assertEquals(2, commands.size(), "round " + round);
                assertInstanceOf(QueuedTextInputClient.Command.Started.class, commands.get(0));
                assertInstanceOf(QueuedTextInputClient.Command.Cancelled.class, commands.get(1));
                assertTrue(adapter.callbackFailure().isEmpty(), "round " + round);

                adapter.close();
                adapter.close();
                send(hwnd, Win32ImeConstants.WM_IME_STARTCOMPOSITION);
                assertEquals(0, client.drain(command -> { }),
                        "restored hook must stay detached in round " + round);
            }
            window.pollEvents();
        }
    }

    private static void forceGcPressure(int round) {
        byte[][] garbage = new byte[8][];
        int checksum = 0;
        for (int index = 0; index < garbage.length; index++) {
            garbage[index] = new byte[128 * 1024];
            garbage[index][index] = (byte) (round + index);
            checksum += garbage[index][index];
        }
        gcPressureChecksum = checksum;
        garbage = null;
        System.gc();
    }

    private static void send(WinDef.HWND hwnd, int message) {
        User32.INSTANCE.SendMessage(hwnd, message, new WinDef.WPARAM(), new WinDef.LPARAM());
    }
}
