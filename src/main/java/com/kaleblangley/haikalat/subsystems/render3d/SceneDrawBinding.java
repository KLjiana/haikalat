package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import java.util.Objects;

/** Records per-object GPU resource bindings immediately before a scene draw. */
public interface SceneDrawBinding {
    SceneDrawBinding NONE = new SceneDrawBinding() {
        @Override
        public void record(CommandBuffer commands, ShaderProgram shader,
                           int frameIndex, Pass pass) {
            Objects.requireNonNull(commands, "commands");
            Objects.requireNonNull(shader, "shader");
            Objects.requireNonNull(pass, "pass");
        }
    };

    void record(CommandBuffer commands, ShaderProgram shader, int frameIndex, Pass pass);

    default boolean deformsVertices() {
        return false;
    }

    enum Pass {
        FORWARD,
        SHADOW
    }
}
