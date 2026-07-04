package com.kaleblangley.haikalat.backend.shader;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FALSE;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform3f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;

public final class ShaderProgram implements GlResource {
    private final int id;
    private final Map<String, Integer> uniformLocations = new ConcurrentHashMap<>();
    private boolean closed;

    private ShaderProgram(int id) {
        this.id = id;
    }

    /**
     * 从 GLSL 源码字符串编译并链接着色器程序。
     *
     * @param vertexSource   顶点着色器源码
     * @param fragmentSource 片段着色器源码
     * @return 编译链接后的 ShaderProgram
     */
    public static ShaderProgram fromSources(String vertexSource, String fragmentSource) {
        Objects.requireNonNull(vertexSource, "vertexSource");
        Objects.requireNonNull(fragmentSource, "fragmentSource");

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);

        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new GlException("Program link failed:\n" + log);
        }

        return new ShaderProgram(program);
    }

    /**
     * 从类路径资源加载着色器文件并编译链接。
     *
     * @param anchor       用于定位资源的类
     * @param vertexPath   顶点着色器资源路径
     * @param fragmentPath 片段着色器资源路径
     * @return 编译链接后的 ShaderProgram
     */
    public static ShaderProgram fromResource(Class<?> anchor, String vertexPath, String fragmentPath) {
        return fromSources(readResource(anchor, vertexPath), readResource(anchor, fragmentPath));
    }

    /** 激活此着色器程序用于渲染。 */
    public ShaderProgram use() {
        ensureOpen();
        glUseProgram(id);
        return this;
    }

    /** 停用当前着色器程序（恢复到固定管线）。 */
    public ShaderProgram stopUsing() {
        glUseProgram(0);
        return this;
    }

    /**
     * 设置 bool 类型 uniform（内部转为 int）。
     *
     * @param name  uniform 名称
     * @param value 布尔值
     * @return 自身，支持链式调用
     */
    public ShaderProgram setBool(String name, boolean value) {
        return setInt(name, value ? 1 : 0);
    }

    /**
     * 设置 int 类型 uniform。
     *
     * @param name  uniform 名称
     * @param value 整数值
     * @return 自身，支持链式调用
     */
    public ShaderProgram setInt(String name, int value) {
        glUniform1i(uniformLocation(name), value);
        return this;
    }

    /**
     * 设置 float 类型 uniform。
     *
     * @param name  uniform 名称
     * @param value 浮点值
     * @return 自身，支持链式调用
     */
    public ShaderProgram setFloat(String name, float value) {
        glUniform1f(uniformLocation(name), value);
        return this;
    }

    /**
     * 设置 vec3 类型 uniform。
     *
     * @param name  uniform 名称
     * @param value 三维向量值
     * @return 自身，支持链式调用
     */
    public ShaderProgram setVec3(String name, Vector3f value) {
        glUniform3f(uniformLocation(name), value.x, value.y, value.z);
        return this;
    }

    /**
     * 设置 mat4 类型 uniform。
     *
     * @param name  uniform 名称
     * @param value 4x4 矩阵值
     * @return 自身，支持链式调用
     */
    public ShaderProgram setMat4(String name, Matrix4f value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            value.get(buffer);
            glUniformMatrix4fv(uniformLocation(name), false, buffer);
        }
        return this;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        glDeleteProgram(id);
        closed = true;
    }

    /**
     * 查询或从缓存获取 uniform 的位置，首次查询时延迟执行。
     *
     * @param name uniform 名称
     * @return uniform 位置
     */
    public int uniformLocation(String name) {
        ensureOpen();
        return uniformLocations.computeIfAbsent(name, key -> {
            int location = glGetUniformLocation(id, key);
            if (location < 0) {
                throw new GlException("Uniform not found: " + key);
            }
            return location;
        });
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Shader program is closed");
        }
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new GlException("Shader compile failed:\n" + log);
        }
        return shader;
    }

    private static String readResource(Class<?> anchor, String path) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(path, "path");
        try (InputStream inputStream = openResource(anchor, path)) {
            if (inputStream == null) {
                throw new GlException("Shader resource not found: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GlException("Failed to read shader resource: " + path, e);
        }
    }

    private static InputStream openResource(Class<?> anchor, String path) {
        if (path.startsWith("/")) {
            return anchor.getResourceAsStream(path);
        }
        return anchor.getResourceAsStream(path);
    }
}
