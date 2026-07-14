package com.kaleblangley.haikalat.backend.shader;

import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER;
import static org.lwjgl.opengl.GL40.GL_TESS_CONTROL_SHADER;
import static org.lwjgl.opengl.GL40.GL_TESS_EVALUATION_SHADER;
import static org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER;

/** OpenGL 4.6 core shader stages supported by {@link ShaderProgram}. */
public enum ShaderStage {
    VERTEX(GL_VERTEX_SHADER),
    TESS_CONTROL(GL_TESS_CONTROL_SHADER),
    TESS_EVALUATION(GL_TESS_EVALUATION_SHADER),
    GEOMETRY(GL_GEOMETRY_SHADER),
    FRAGMENT(GL_FRAGMENT_SHADER),
    COMPUTE(GL_COMPUTE_SHADER);

    private final int glType;

    ShaderStage(int glType) {
        this.glType = glType;
    }

    public int glType() {
        return glType;
    }
}
