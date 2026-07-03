package com.kaleblangley.haikalat.gl.command;

import com.kaleblangley.haikalat.gl.fb.Framebuffer;
import com.kaleblangley.haikalat.gl.material.ShaderProgram;
import com.kaleblangley.haikalat.gl.material.Texture2D;
import com.kaleblangley.haikalat.gl.mesh.Mesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform3f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

public final class CommandBuffer {
    private final List<Consumer<StateCache>> commands = new ArrayList<>(64);

    public CommandBuffer useProgram(int program) {
        commands.add(cache -> cache.useProgram(program));
        return this;
    }

    public CommandBuffer bindVertexArray(int vao) {
        commands.add(cache -> cache.bindVertexArray(vao));
        return this;
    }

    public CommandBuffer bindTexture(int unit, int texture) {
        commands.add(cache -> cache.bindTexture2D(unit, texture));
        return this;
    }

    public CommandBuffer bindFramebuffer(int target, int fbo) {
        commands.add(cache -> cache.bindFramebuffer(target, fbo));
        return this;
    }

    public CommandBuffer bindShader(ShaderProgram shader) {
        Objects.requireNonNull(shader, "shader");
        return useProgram(shader.id());
    }

    public CommandBuffer bindMesh(Mesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        return bindVertexArray(mesh.vertexArray().id());
    }

    public CommandBuffer bindTexture(int unit, Texture2D texture) {
        Objects.requireNonNull(texture, "texture");
        return bindTexture(unit, texture.id());
    }

    public CommandBuffer bindFramebuffer(Framebuffer fb) {
        Objects.requireNonNull(fb, "fb");
        return bindFramebuffer(GL_FRAMEBUFFER, fb.id());
    }

    public CommandBuffer drawMesh(Mesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        commands.add(cache -> mesh.drawBound());
        return this;
    }

    public CommandBuffer drawElements(int mode, int count, int type) {
        commands.add(cache -> glDrawElements(mode, count, type, 0L));
        return this;
    }

    public CommandBuffer drawArrays(int mode, int first, int count) {
        commands.add(cache -> glDrawArrays(mode, first, count));
        return this;
    }

    public CommandBuffer drawMeshInstanced(Mesh mesh, int instanceCount) {
        Objects.requireNonNull(mesh, "mesh");
        commands.add(cache -> mesh.drawInstancedBound(instanceCount));
        return this;
    }

    public CommandBuffer viewport(int x, int y, int w, int h) {
        commands.add(cache -> cache.viewport(x, y, w, h));
        return this;
    }

    public CommandBuffer enableBlend(boolean enable) {
        commands.add(cache -> cache.enableBlend(enable));
        return this;
    }

    public CommandBuffer depthMask(boolean write) {
        commands.add(cache -> cache.depthMask(write));
        return this;
    }

    public CommandBuffer enableDepthTest(boolean enable) {
        commands.add(cache -> cache.enableDepthTest(enable));
        return this;
    }

    public CommandBuffer blendFunc(int srcRGB, int dstRGB) {
        commands.add(cache -> cache.blendFunc(srcRGB, dstRGB));
        return this;
    }

    public CommandBuffer clear(boolean color, boolean depth) {
        int mask = (color ? GL_COLOR_BUFFER_BIT : 0) | (depth ? GL_DEPTH_BUFFER_BIT : 0);
        commands.add(cache -> cache.clear(mask));
        return this;
    }

    public CommandBuffer clearColor(float r, float g, float b, float a) {
        commands.add(cache -> cache.clearColor(r, g, b, a));
        return this;
    }

    public CommandBuffer setUniformMat4(ShaderProgram shader, String name, Matrix4f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocation(name);
        Matrix4f copy = new Matrix4f(value);
        commands.add(cache -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buf = stack.mallocFloat(16);
                copy.get(buf);
                glUniformMatrix4fv(location, false, buf);
            }
        });
        return this;
    }

    public CommandBuffer setUniformVec3(ShaderProgram shader, String name, Vector3f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocation(name);
        float x = value.x;
        float y = value.y;
        float z = value.z;
        commands.add(cache -> glUniform3f(location, x, y, z));
        return this;
    }

    public CommandBuffer setUniformInt(ShaderProgram shader, String name, int value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocation(name);
        commands.add(cache -> glUniform1i(location, value));
        return this;
    }

    public CommandBuffer setUniformFloat(ShaderProgram shader, String name, float value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocation(name);
        commands.add(cache -> glUniform1f(location, value));
        return this;
    }

    public CommandBuffer custom(Runnable action) {
        Objects.requireNonNull(action, "action");
        commands.add(cache -> action.run());
        return this;
    }

    public void reset() {
        commands.clear();
    }

    void execute(StateCache cache) {
        for (Consumer<StateCache> cmd : commands) {
            cmd.accept(cache);
        }
    }

    public int commandCount() {
        return commands.size();
    }
}
