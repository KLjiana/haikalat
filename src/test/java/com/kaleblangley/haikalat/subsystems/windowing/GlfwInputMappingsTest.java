package com.kaleblangley.haikalat.subsystems.windowing;

import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class GlfwInputMappingsTest {
    @Test
    void mapsKeysButtonsAndModifiersWithoutPublishingGlfwIntegers() {
        assertEquals(Key.A, GlfwInputMappings.key(GLFW_KEY_A));
        assertEquals(Key.KEYPAD_ENTER, GlfwInputMappings.key(GLFW_KEY_KP_ENTER));
        assertEquals(MouseButton.LEFT, GlfwInputMappings.mouseButton(GLFW_MOUSE_BUTTON_LEFT));
        assertEquals(MouseButton.BUTTON_8, GlfwInputMappings.mouseButton(GLFW_MOUSE_BUTTON_8));

        var modifiers = GlfwInputMappings.modifiers(GLFW_MOD_SHIFT | GLFW_MOD_ALT
                | GLFW_MOD_CAPS_LOCK);
        assertTrue(modifiers.shift());
        assertFalse(modifiers.control());
        assertTrue(modifiers.alt());
        assertTrue(modifiers.capsLock());
        assertFalse(modifiers.numLock());
    }

    @Test
    void unknownCodesAreSafelyIgnored() {
        assertNull(GlfwInputMappings.key(GLFW_KEY_UNKNOWN));
        assertNull(GlfwInputMappings.key(Integer.MAX_VALUE));
        assertNull(GlfwInputMappings.mouseButton(-1));
        assertNull(GlfwInputMappings.mouseButton(Integer.MAX_VALUE));
    }
}
