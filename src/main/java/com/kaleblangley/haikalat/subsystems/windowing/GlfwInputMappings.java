package com.kaleblangley.haikalat.subsystems.windowing;

import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;

import static org.lwjgl.glfw.GLFW.*;

/** GLFW 常量到 subsystem-neutral 输入类型的集中映射。 */
final class GlfwInputMappings {
    private GlfwInputMappings() {
    }

    static Key key(int key) {
        return switch (key) {
            case GLFW_KEY_SPACE -> Key.SPACE;
            case GLFW_KEY_APOSTROPHE -> Key.APOSTROPHE;
            case GLFW_KEY_COMMA -> Key.COMMA;
            case GLFW_KEY_MINUS -> Key.MINUS;
            case GLFW_KEY_PERIOD -> Key.PERIOD;
            case GLFW_KEY_SLASH -> Key.SLASH;
            case GLFW_KEY_0 -> Key.DIGIT_0;
            case GLFW_KEY_1 -> Key.DIGIT_1;
            case GLFW_KEY_2 -> Key.DIGIT_2;
            case GLFW_KEY_3 -> Key.DIGIT_3;
            case GLFW_KEY_4 -> Key.DIGIT_4;
            case GLFW_KEY_5 -> Key.DIGIT_5;
            case GLFW_KEY_6 -> Key.DIGIT_6;
            case GLFW_KEY_7 -> Key.DIGIT_7;
            case GLFW_KEY_8 -> Key.DIGIT_8;
            case GLFW_KEY_9 -> Key.DIGIT_9;
            case GLFW_KEY_SEMICOLON -> Key.SEMICOLON;
            case GLFW_KEY_EQUAL -> Key.EQUAL;
            case GLFW_KEY_A -> Key.A;
            case GLFW_KEY_B -> Key.B;
            case GLFW_KEY_C -> Key.C;
            case GLFW_KEY_D -> Key.D;
            case GLFW_KEY_E -> Key.E;
            case GLFW_KEY_F -> Key.F;
            case GLFW_KEY_G -> Key.G;
            case GLFW_KEY_H -> Key.H;
            case GLFW_KEY_I -> Key.I;
            case GLFW_KEY_J -> Key.J;
            case GLFW_KEY_K -> Key.K;
            case GLFW_KEY_L -> Key.L;
            case GLFW_KEY_M -> Key.M;
            case GLFW_KEY_N -> Key.N;
            case GLFW_KEY_O -> Key.O;
            case GLFW_KEY_P -> Key.P;
            case GLFW_KEY_Q -> Key.Q;
            case GLFW_KEY_R -> Key.R;
            case GLFW_KEY_S -> Key.S;
            case GLFW_KEY_T -> Key.T;
            case GLFW_KEY_U -> Key.U;
            case GLFW_KEY_V -> Key.V;
            case GLFW_KEY_W -> Key.W;
            case GLFW_KEY_X -> Key.X;
            case GLFW_KEY_Y -> Key.Y;
            case GLFW_KEY_Z -> Key.Z;
            case GLFW_KEY_LEFT_BRACKET -> Key.LEFT_BRACKET;
            case GLFW_KEY_BACKSLASH -> Key.BACKSLASH;
            case GLFW_KEY_RIGHT_BRACKET -> Key.RIGHT_BRACKET;
            case GLFW_KEY_GRAVE_ACCENT -> Key.GRAVE_ACCENT;
            case GLFW_KEY_WORLD_1 -> Key.WORLD_1;
            case GLFW_KEY_WORLD_2 -> Key.WORLD_2;
            case GLFW_KEY_ESCAPE -> Key.ESCAPE;
            case GLFW_KEY_ENTER -> Key.ENTER;
            case GLFW_KEY_TAB -> Key.TAB;
            case GLFW_KEY_BACKSPACE -> Key.BACKSPACE;
            case GLFW_KEY_INSERT -> Key.INSERT;
            case GLFW_KEY_DELETE -> Key.DELETE;
            case GLFW_KEY_RIGHT -> Key.RIGHT;
            case GLFW_KEY_LEFT -> Key.LEFT;
            case GLFW_KEY_DOWN -> Key.DOWN;
            case GLFW_KEY_UP -> Key.UP;
            case GLFW_KEY_PAGE_UP -> Key.PAGE_UP;
            case GLFW_KEY_PAGE_DOWN -> Key.PAGE_DOWN;
            case GLFW_KEY_HOME -> Key.HOME;
            case GLFW_KEY_END -> Key.END;
            case GLFW_KEY_CAPS_LOCK -> Key.CAPS_LOCK;
            case GLFW_KEY_SCROLL_LOCK -> Key.SCROLL_LOCK;
            case GLFW_KEY_NUM_LOCK -> Key.NUM_LOCK;
            case GLFW_KEY_PRINT_SCREEN -> Key.PRINT_SCREEN;
            case GLFW_KEY_PAUSE -> Key.PAUSE;
            case GLFW_KEY_F1 -> Key.F1;
            case GLFW_KEY_F2 -> Key.F2;
            case GLFW_KEY_F3 -> Key.F3;
            case GLFW_KEY_F4 -> Key.F4;
            case GLFW_KEY_F5 -> Key.F5;
            case GLFW_KEY_F6 -> Key.F6;
            case GLFW_KEY_F7 -> Key.F7;
            case GLFW_KEY_F8 -> Key.F8;
            case GLFW_KEY_F9 -> Key.F9;
            case GLFW_KEY_F10 -> Key.F10;
            case GLFW_KEY_F11 -> Key.F11;
            case GLFW_KEY_F12 -> Key.F12;
            case GLFW_KEY_F13 -> Key.F13;
            case GLFW_KEY_F14 -> Key.F14;
            case GLFW_KEY_F15 -> Key.F15;
            case GLFW_KEY_F16 -> Key.F16;
            case GLFW_KEY_F17 -> Key.F17;
            case GLFW_KEY_F18 -> Key.F18;
            case GLFW_KEY_F19 -> Key.F19;
            case GLFW_KEY_F20 -> Key.F20;
            case GLFW_KEY_F21 -> Key.F21;
            case GLFW_KEY_F22 -> Key.F22;
            case GLFW_KEY_F23 -> Key.F23;
            case GLFW_KEY_F24 -> Key.F24;
            case GLFW_KEY_F25 -> Key.F25;
            case GLFW_KEY_KP_0 -> Key.KEYPAD_0;
            case GLFW_KEY_KP_1 -> Key.KEYPAD_1;
            case GLFW_KEY_KP_2 -> Key.KEYPAD_2;
            case GLFW_KEY_KP_3 -> Key.KEYPAD_3;
            case GLFW_KEY_KP_4 -> Key.KEYPAD_4;
            case GLFW_KEY_KP_5 -> Key.KEYPAD_5;
            case GLFW_KEY_KP_6 -> Key.KEYPAD_6;
            case GLFW_KEY_KP_7 -> Key.KEYPAD_7;
            case GLFW_KEY_KP_8 -> Key.KEYPAD_8;
            case GLFW_KEY_KP_9 -> Key.KEYPAD_9;
            case GLFW_KEY_KP_DECIMAL -> Key.KEYPAD_DECIMAL;
            case GLFW_KEY_KP_DIVIDE -> Key.KEYPAD_DIVIDE;
            case GLFW_KEY_KP_MULTIPLY -> Key.KEYPAD_MULTIPLY;
            case GLFW_KEY_KP_SUBTRACT -> Key.KEYPAD_SUBTRACT;
            case GLFW_KEY_KP_ADD -> Key.KEYPAD_ADD;
            case GLFW_KEY_KP_ENTER -> Key.KEYPAD_ENTER;
            case GLFW_KEY_KP_EQUAL -> Key.KEYPAD_EQUAL;
            case GLFW_KEY_LEFT_SHIFT -> Key.LEFT_SHIFT;
            case GLFW_KEY_LEFT_CONTROL -> Key.LEFT_CONTROL;
            case GLFW_KEY_LEFT_ALT -> Key.LEFT_ALT;
            case GLFW_KEY_LEFT_SUPER -> Key.LEFT_SUPER;
            case GLFW_KEY_RIGHT_SHIFT -> Key.RIGHT_SHIFT;
            case GLFW_KEY_RIGHT_CONTROL -> Key.RIGHT_CONTROL;
            case GLFW_KEY_RIGHT_ALT -> Key.RIGHT_ALT;
            case GLFW_KEY_RIGHT_SUPER -> Key.RIGHT_SUPER;
            case GLFW_KEY_MENU -> Key.MENU;
            default -> null;
        };
    }

    static MouseButton mouseButton(int button) {
        return switch (button) {
            case GLFW_MOUSE_BUTTON_1 -> MouseButton.LEFT;
            case GLFW_MOUSE_BUTTON_2 -> MouseButton.RIGHT;
            case GLFW_MOUSE_BUTTON_3 -> MouseButton.MIDDLE;
            case GLFW_MOUSE_BUTTON_4 -> MouseButton.BUTTON_4;
            case GLFW_MOUSE_BUTTON_5 -> MouseButton.BUTTON_5;
            case GLFW_MOUSE_BUTTON_6 -> MouseButton.BUTTON_6;
            case GLFW_MOUSE_BUTTON_7 -> MouseButton.BUTTON_7;
            case GLFW_MOUSE_BUTTON_8 -> MouseButton.BUTTON_8;
            default -> null;
        };
    }

    static KeyModifiers modifiers(int modifiers) {
        return new KeyModifiers((modifiers & GLFW_MOD_SHIFT) != 0,
                (modifiers & GLFW_MOD_CONTROL) != 0,
                (modifiers & GLFW_MOD_ALT) != 0,
                (modifiers & GLFW_MOD_SUPER) != 0,
                (modifiers & GLFW_MOD_CAPS_LOCK) != 0,
                (modifiers & GLFW_MOD_NUM_LOCK) != 0);
    }
}
