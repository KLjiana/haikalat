import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Main {
    static float[] vertices = {
            -0.5f, -0.5f, -0.5f, 0.0f, 0.0f,
            0.5f, -0.5f, -0.5f, 1.0f, 0.0f,
            0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
            0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
            -0.5f, 0.5f, -0.5f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f, 0.0f, 0.0f,

            -0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
            0.5f, -0.5f, 0.5f, 1.0f, 0.0f,
            0.5f, 0.5f, 0.5f, 1.0f, 1.0f,
            0.5f, 0.5f, 0.5f, 1.0f, 1.0f,
            -0.5f, 0.5f, 0.5f, 0.0f, 1.0f,
            -0.5f, -0.5f, 0.5f, 0.0f, 0.0f,

            -0.5f, 0.5f, 0.5f, 1.0f, 0.0f,
            -0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
            -0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
            -0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
            -0.5f, 0.5f, 0.5f, 1.0f, 0.0f,

            0.5f, 0.5f, 0.5f, 1.0f, 0.0f,
            0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
            0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
            0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
            0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
            0.5f, 0.5f, 0.5f, 1.0f, 0.0f,

            -0.5f, -0.5f, -0.5f, 0.0f, 1.0f,
            0.5f, -0.5f, -0.5f, 1.0f, 1.0f,
            0.5f, -0.5f, 0.5f, 1.0f, 0.0f,
            0.5f, -0.5f, 0.5f, 1.0f, 0.0f,
            -0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
            -0.5f, -0.5f, -0.5f, 0.0f, 1.0f,

            -0.5f, 0.5f, -0.5f, 0.0f, 1.0f,
            0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
            0.5f, 0.5f, 0.5f, 1.0f, 0.0f,
            0.5f, 0.5f, 0.5f, 1.0f, 0.0f,
            -0.5f, 0.5f, 0.5f, 0.0f, 0.0f,
            -0.5f, 0.5f, -0.5f, 0.0f, 1.0f
    };
    static int[] indices = {
            // 注意索引从0开始!
            // 此例的索引(0,1,2,3)就是顶点数组vertices的下标，
            // 这样可以由下标代表顶点组合成矩形

            0, 1, 3, // 第一个三角形
            1, 2, 3  // 第二个三角形
    };

    static Vector3f[] cubePositions = {
            new Vector3f(0.0f, 0.0f, 0.0f),
            new Vector3f(2.0f, 5.0f, -15.0f),
            new Vector3f(-1.5f, -2.2f, -2.5f),
            new Vector3f(-3.8f, -2.0f, -12.3f),
            new Vector3f(2.4f, -0.4f, -3.5f),
            new Vector3f(-1.7f, 3.0f, -7.5f),
            new Vector3f(1.3f, -2.0f, -2.5f),
            new Vector3f(1.5f, 2.0f, -2.5f),
            new Vector3f(1.5f, 0.2f, -1.5f),
            new Vector3f(-1.3f, 1.0f, -1.5f)
    };
    static Logger LOGGER = Logger.getLogger("GL");
    static long window;
    static Shader shader;
    static int VAO;
    static int texId1;
    static int texId2;

    private static void initWindow() {
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(800, 600, "LearnOpenGL", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            throw new RuntimeException("Failed to create the GLFW window");
        }
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        glfwSetFramebufferSizeCallback(window, (window1, width, height) -> {
            glViewport(0, 0, width, height);
        });
    }

    public static void main(String[] args) {
        glfwInit();
        initWindow();
        initRenderer();
        initTexture();
        while (!glfwWindowShouldClose(window)) {
            loopRender();
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
        shader.delete();
        glfwTerminate();
    }


    private static void initRenderer() {
        glEnable(GL_DEPTH_TEST);
        shader = new Shader("pos.vert", "color.frag");
        VAO = glGenVertexArrays();
        glBindVertexArray(VAO);

        int VBO = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, VBO);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

//        int EBO = glGenBuffers();
//        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, EBO);
//        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, NULL);
        glEnableVertexAttribArray(0);
//        glVertexAttribPointer(1, 3, GL_FLOAT, false, 8 * Float.BYTES, 3 * Float.BYTES);
//        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private static void initTexture() {
        texId1 = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        try (MemoryStack memoryStack = stackPush()) {
            IntBuffer height = memoryStack.mallocInt(1);
            IntBuffer weight = memoryStack.mallocInt(1);
            IntBuffer channels = memoryStack.mallocInt(1);
            try (InputStream wallStream = Main.class.getResourceAsStream("wall.png")) {
                byte[] bytes = wallStream.readAllBytes();
                ByteBuffer byteBuffer = BufferUtils.createByteBuffer(bytes.length);
                byteBuffer.put(bytes);
                byteBuffer.flip();
                STBImage.stbi_set_flip_vertically_on_load(true);
                ByteBuffer texture = STBImage.stbi_load_from_memory(byteBuffer, weight, height, channels, 0);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 512, 512, 0, GL_RGB, GL_UNSIGNED_BYTE, texture);
                glGenerateMipmap(GL_TEXTURE_2D);
                STBImage.stbi_image_free(texture);
            }
        } catch (Exception e) {
            throw new RuntimeException("Load Texture", e);
        }

        texId2 = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId2);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        try (MemoryStack memoryStack = stackPush()) {
            IntBuffer height = memoryStack.mallocInt(1);
            IntBuffer weight = memoryStack.mallocInt(1);
            IntBuffer channels = memoryStack.mallocInt(1);
            try (InputStream awesomeface = Main.class.getResourceAsStream("awesomeface.png")) {
                byte[] bytes = awesomeface.readAllBytes();
                ByteBuffer byteBuffer = BufferUtils.createByteBuffer(bytes.length);
                byteBuffer.put(bytes);
                byteBuffer.flip();
                ByteBuffer texture = STBImage.stbi_load_from_memory(byteBuffer, weight, height, channels, 0);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 512, 512, 0, GL_RGBA, GL_UNSIGNED_BYTE, texture);
                glGenerateMipmap(GL_TEXTURE_2D);
                STBImage.stbi_image_free(texture);
            }
        } catch (Exception e) {
            throw new RuntimeException("Load Texture", e);
        }
        shader.use();
        shader.setInt("Tex1", 0).setInt("Tex2", 1);
    }

    private static void loopRender() {
        glClearColor(0.2f, 0.3f, 0.3f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        shader.use();
        //===Cube===
        Matrix4f model = new Matrix4f().rotate((float) (glfwGetTime() * Math.toRadians(50.0f)), 0.5f, 1.0f, 0.0f);
        Matrix4f view = new Matrix4f().translate(0.0f, 0.0f, -3.0f);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(120f), 800f / 600f, 0.1f, 100f);
        MemoryStack memoryStack = stackPush();
        FloatBuffer modelBuffer = memoryStack.mallocFloat(16);
        model.get(modelBuffer);
        FloatBuffer viewBuffer = memoryStack.mallocFloat(16);
        view.get(viewBuffer);
        FloatBuffer projectionBuffer = memoryStack.mallocFloat(16);
        projection.get(projectionBuffer);
        glUniformMatrix4fv(glGetUniformLocation(shader.getId(), "model"), false, modelBuffer);
        glUniformMatrix4fv(glGetUniformLocation(shader.getId(), "view"), false, viewBuffer);
        glUniformMatrix4fv(glGetUniformLocation(shader.getId(), "projection"), false, projectionBuffer);
        memoryStack.pop();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texId1);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, texId2);
        glBindVertexArray(VAO);
        glDrawArrays(GL_TRIANGLES, 0, 36);
//        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
//        glBindVertexArray(0);
//        shader.delete();
    }
}