package com.kaleblangley.haikalat.core;

import static org.lwjgl.opengl.GL11.GL_CCW;
import static org.lwjgl.opengl.GL11.GL_CW;

/** OpenGL 正面绕序。 */
public enum FrontFace {
    CCW(GL_CCW), CW(GL_CW);
    private final int glValue;
    FrontFace(int glValue) { this.glValue = glValue; }
    public int glValue() { return glValue; }
}
