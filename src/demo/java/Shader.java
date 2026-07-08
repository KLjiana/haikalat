import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class Shader {
    private final int id;

    public Shader(String vertexPath, String fragmentPath) {
        String vertexSource = readResource(vertexPath);
        String fragmentSource = readResource(fragmentPath);

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);

        id = glCreateProgram();
        glAttachShader(id, vertexShader);
        glAttachShader(id, fragmentShader);
        glLinkProgram(id);

        if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("Program link failed:\n" + glGetProgramInfoLog(id));
        }

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }

    public static String readResource(String path) {
        try (InputStream is = Shader.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException(path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);

        glShaderSource(shader, source);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader compile failed:\n" + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    public void use() {
        glUseProgram(id);
    }

    public int getId() {
        return id;
    }

    public void delete() {
        glDeleteProgram(id);
    }

    public Shader setBool(String name, boolean value) {
        glUniform1i(glGetUniformLocation(id, name), value ? 1 : 0);
        return this;
    }

    public Shader setInt(String name, int value) {
        glUniform1i(glGetUniformLocation(id, name), value);
        return this;
    }

    public Shader setFloat(String name, float value) {
        glUniform1f(glGetUniformLocation(id, name), value);
        return this;
    }

    public Shader setMat4f(String name, Matrix4f value) {
        try (MemoryStack memoryStack = stackPush()) {
            glUniformMatrix4fv(glGetUniformLocation(id, name), false, value.get(memoryStack.mallocFloat(16)));
        }
        return this;
    }

    public Shader setVec3(String name, Vector3f value) {
        glUniform3f(glGetUniformLocation(id, name), value.x, value.y, value.z);
        return this;
    }
}