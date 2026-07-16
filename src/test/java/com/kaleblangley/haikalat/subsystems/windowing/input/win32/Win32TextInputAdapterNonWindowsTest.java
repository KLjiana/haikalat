package com.kaleblangley.haikalat.subsystems.windowing.input.win32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisabledOnOs(OS.WINDOWS)
class Win32TextInputAdapterNonWindowsTest {
    @Test
    void classInitializationDoesNotAttemptToLoadWin32Libraries() {
        assertDoesNotThrow(() -> Class.forName(Win32TextInputAdapter.class.getName(),
                true, Win32TextInputAdapter.class.getClassLoader()));
        assertFalse(Win32TextInputAdapter.isSupported());
        assertThrows(UnsupportedOperationException.class,
                () -> new Win32TextInputAdapter(1L));
    }
}
