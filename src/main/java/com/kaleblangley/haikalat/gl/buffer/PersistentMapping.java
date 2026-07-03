package com.kaleblangley.haikalat.gl.buffer;

import org.lwjgl.opengl.GL;

final class PersistentMapping {
    private static final boolean SUPPORTED;
    private static final int MAP_WRITE = 0x0002;
    private static final int MAP_PERSISTENT = 0x0040;
    private static final int MAP_FLUSH_EXPLICIT = 0x0010;

    static {
        boolean s = false;
        try {
            s = GL.getCapabilities().GL_ARB_buffer_storage;
        } catch (Exception ignored) {
        }
        SUPPORTED = s;
    }

    private PersistentMapping() {
    }

    static boolean isSupported() {
        return SUPPORTED;
    }

    static int mapFlags() {
        return MAP_WRITE | MAP_PERSISTENT | MAP_FLUSH_EXPLICIT;
    }

    static int storageFlags() {
        return MAP_WRITE | MAP_PERSISTENT;
    }
}
