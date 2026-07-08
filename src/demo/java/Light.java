import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Light {
    static float[] vertices = {
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,

            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f, 1.0f,
            0.5f, -0.5f,  0.5f,  0.0f,  0.0f, 1.0f,
            0.5f,  0.5f,  0.5f,  0.0f,  0.0f, 1.0f,
            0.5f,  0.5f,  0.5f,  0.0f,  0.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f,  0.0f, 1.0f,
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f, 1.0f,

            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f,  0.5f, -0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f, -0.5f,  0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,

            0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,
            0.5f,  0.5f, -0.5f,  1.0f,  0.0f,  0.0f,
            0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,
            0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,
            0.5f, -0.5f,  0.5f,  1.0f,  0.0f,  0.0f,
            0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,

            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,
            0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,
            0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,
            0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,

            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,
            0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,
            0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,
            0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,
            -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f
    };

    static Logger LOGGER = Logger.getLogger("GL");
    static long window;
    static Shader cubeShader;
    static Shader lightingShader;
    static int lightingVAO;
    static int cubeVAO;
    static int texId1;
    static int texId2;
    static Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f));
    static float lastX = 800 / 2.0f;
    static float lastY = 600 / 2.0f;
    static boolean firstMouse = true;
    static float deltaTime = 0.0f;
    static float lastFrame = 0.0f;

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
        glfwSetCursorPosCallback(window, (window1, xpos, ypos) -> {
            float fxpos = (float) xpos;
            float fypos = (float) ypos;
            if (firstMouse)
            {
                lastX = fxpos;
                lastY = fypos;
                firstMouse = false;
            }

            float xoffset = fxpos - lastX;
            float yoffset = lastY - fypos; // reversed since y-coordinates go from bottom to top

            lastX = fxpos;
            lastY = fypos;
            camera.processMouseMovement(xoffset, yoffset);
        });
        glfwSetScrollCallback(window, (window1, xoffset, yoffset) -> {
            camera.processMouseScroll((float) yoffset);
        });
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        glEnable(GL_DEPTH_TEST);
    }

    public static void main(String[] args) {
        glfwInit();
        initWindow();
        initRenderer();
        while (!glfwWindowShouldClose(window)) {
            loopRender();
            processInput(window);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
        cubeShader.delete();
        glfwTerminate();
    }
    private static void processInput(long window) {
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS)
            glfwSetWindowShouldClose(window, true);

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS)
            camera.processKeyboard(Camera.Movement.FORWARD, deltaTime);
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS)
            camera.processKeyboard(Camera.Movement.BACKWARD, deltaTime);
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS)
            camera.processKeyboard(Camera.Movement.LEFT, deltaTime);
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS)
            camera.processKeyboard(Camera.Movement.RIGHT, deltaTime);
    };

    private static void initRenderer() {
        cubeShader = new Shader("light/cube_pos.vert", "light/cube_color.frag");
        lightingShader = new Shader("light/lighting_pos.vert", "light/lighting_color.frag");
        lightingVAO = glGenVertexArrays();
        glBindVertexArray(lightingVAO);

        int VBO = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, VBO);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * Float.BYTES, NULL);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        cubeVAO = glGenVertexArrays();
        glBindVertexArray(cubeVAO);

        glBindBuffer(GL_ARRAY_BUFFER, VBO);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * Float.BYTES, NULL);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
    }

    private static void loopRender() {
        float currentFrame = (float) glfwGetTime();
        deltaTime = currentFrame - lastFrame;
        lastFrame = currentFrame;

        glClearColor(0.2f, 0.3f, 0.3f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        lightingShader.use();
        Matrix4f model = new Matrix4f();
        Matrix4f view = camera.getViewMatrix();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(camera.zoom), 800f / 600f, 0.1f, 100f);
        Vector3f objectColor = new Vector3f(1.0F, 0.5f, 0.31f);
        Vector3f lightColor = new Vector3f(1.0f, 1.0f, 1.0f);
        lightingShader.setMat4f("model", model)
                .setMat4f("view", view)
                .setMat4f("projection", projection)
                .setVec3("objectColor", objectColor)
                .setVec3("lightColor", lightColor)
                .setVec3("lightPos", new Vector3f(1.2f, 1.0f, 2.0f))
                .setVec3("viewPos", camera.position);;
        glBindVertexArray(lightingVAO);
        glDrawArrays(GL_TRIANGLES, 0, 36);


        cubeShader.use();
        model = new Matrix4f().translate(1.2f, 1.0f, 2.0f).scale(0.2f);
        cubeShader.setMat4f("model", model)
                .setMat4f("view", view)
                .setMat4f("projection", projection);

        glBindVertexArray(cubeVAO);
        glDrawArrays(GL_TRIANGLES, 0, 36);

    }
}
