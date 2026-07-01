import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {

    public enum Movement {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT
    }

    // 默认参数
    public static final float YAW = -90.0f;
    public static final float PITCH = 0.0f;
    public static final float SPEED = 2.5f;
    public static final float SENSITIVITY = 0.1f;
    public static final float ZOOM = 45.0f;

    // Camera Attributes
    public final Vector3f position = new Vector3f();
    public final Vector3f front = new Vector3f(0.0f, 0.0f, -1.0f);
    public final Vector3f up = new Vector3f();
    public final Vector3f right = new Vector3f();
    public final Vector3f worldUp = new Vector3f();

    // Euler Angles
    public float yaw;
    public float pitch;

    // Camera options
    public float movementSpeed = SPEED;
    public float mouseSensitivity = SENSITIVITY;
    public float zoom = ZOOM;

    /**
     * 默认构造
     */
    public Camera() {
        this(new Vector3f(0, 0, 0));
    }

    /**
     * 只指定位置
     */
    public Camera(Vector3f position) {
        this(position, new Vector3f(0, 1, 0), YAW, PITCH);
    }

    /**
     * 完整构造
     */
    public Camera(Vector3f position, Vector3f worldUp, float yaw, float pitch) {
        this.position.set(position);
        this.worldUp.set(worldUp);
        this.yaw = yaw;
        this.pitch = pitch;
        updateCameraVectors();
    }

    /**
     * 与 C++ 第二个构造一致
     */
    public Camera(
            float posX, float posY, float posZ,
            float upX, float upY, float upZ,
            float yaw, float pitch
    ) {
        this.position.set(posX, posY, posZ);
        this.worldUp.set(upX, upY, upZ);
        this.yaw = yaw;
        this.pitch = pitch;
        updateCameraVectors();
    }

    /**
     * glm::lookAt
     */
    public Matrix4f getViewMatrix() {
        return new Matrix4f().lookAt(
                position,
                new Vector3f(position).add(front),
                up
        );
    }

    /**
     * 键盘移动
     */
    public void processKeyboard(Movement direction, float deltaTime) {

        float velocity = movementSpeed * deltaTime;

        switch (direction) {
            case FORWARD ->
                    position.fma(velocity, front);

            case BACKWARD ->
                    position.fma(-velocity, front);

            case LEFT ->
                    position.fma(-velocity, right);

            case RIGHT ->
                    position.fma(velocity, right);
        }
    }

    /**
     * 鼠标移动
     */
    public void processMouseMovement(float xOffset, float yOffset) {
        processMouseMovement(xOffset, yOffset, true);
    }

    public void processMouseMovement(float xOffset, float yOffset, boolean constrainPitch) {

        xOffset *= mouseSensitivity;
        yOffset *= mouseSensitivity;

        yaw += xOffset;
        pitch += yOffset;

        if (constrainPitch) {
            if (pitch > 89.0f)
                pitch = 89.0f;

            if (pitch < -89.0f)
                pitch = -89.0f;
        }

        updateCameraVectors();
    }

    /**
     * 滚轮缩放
     */
    public void processMouseScroll(float yOffset) {

        zoom -= yOffset;

        if (zoom < 1.0f)
            zoom = 1.0f;

        if (zoom > 45.0f)
            zoom = 45.0f;
    }

    /**
     * 根据欧拉角重新计算 Front Right Up
     */
    private void updateCameraVectors() {

        Vector3f front = new Vector3f();

        front.x = (float) (
                Math.cos(Math.toRadians(yaw))
                        * Math.cos(Math.toRadians(pitch))
        );

        front.y = (float) Math.sin(Math.toRadians(pitch));

        front.z = (float) (
                Math.sin(Math.toRadians(yaw))
                        * Math.cos(Math.toRadians(pitch))
        );

        this.front.set(front.normalize());

        this.right.set(this.front)
                .cross(worldUp)
                .normalize();

        this.up.set(this.right)
                .cross(this.front)
                .normalize();
    }
}