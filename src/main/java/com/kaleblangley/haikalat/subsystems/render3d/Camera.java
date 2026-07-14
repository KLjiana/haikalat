package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {
    public enum Movement {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT
    }

    public static final float YAW = -90.0f;
    public static final float PITCH = 0.0f;
    public static final float SPEED = 25f;
    public static final float SENSITIVITY = 0.1f;
    public static final float ZOOM = 45.0f;

    private final Vector3f position = new Vector3f();
    private final Vector3f front = new Vector3f(0.0f, 0.0f, -1.0f);
    private final Vector3f up = new Vector3f();
    private final Vector3f right = new Vector3f();
    private final Vector3f worldUp = new Vector3f(0.0f, 1.0f, 0.0f);

    private float yaw = YAW;
    private float pitch = PITCH;
    private float movementSpeed = SPEED;
    private float mouseSensitivity = SENSITIVITY;
    private float zoom = ZOOM;

    public Camera() {
        this(new Vector3f(0.0f, 0.0f, 0.0f));
    }

    public Camera(Vector3f position) {
        this(position, new Vector3f(0.0f, 1.0f, 0.0f), YAW, PITCH);
    }

    public Camera(Vector3f position, Vector3f worldUp, float yaw, float pitch) {
        this.position.set(position);
        this.worldUp.set(worldUp);
        this.yaw = yaw;
        this.pitch = pitch;
        updateCameraVectors();
    }

    public Camera(float posX, float posY, float posZ,
                  float upX, float upY, float upZ,
                  float yaw, float pitch) {
        this.position.set(posX, posY, posZ);
        this.worldUp.set(upX, upY, upZ);
        this.yaw = yaw;
        this.pitch = pitch;
        updateCameraVectors();
    }

    /** @return 相机位置 */
    public Vector3f position() { return new Vector3f(position); }
    /** @return 相机前方向量 */
    public Vector3f front() { return new Vector3f(front); }
    /** @return 相机上方向量 */
    public Vector3f up() { return new Vector3f(up); }
    /** @return 相机右方向量 */
    public Vector3f right() { return new Vector3f(right); }
    /** @return 偏航角（度） */
    public float yaw() { return yaw; }
    /** @return 俯仰角（度） */
    public float pitch() { return pitch; }
    /** @return 移动速度 */
    public float movementSpeed() { return movementSpeed; }
    /** @return 缩放/FOV 值 */
    public float zoom() { return zoom; }

    public void setPosition(Vector3f pos) {
        position.set(pos);
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
        updateCameraVectors();
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
        updateCameraVectors();
    }

    public void setMovementSpeed(float speed) {
        this.movementSpeed = speed;
    }

    /** @return 基于当前姿态的 lookAt 视图矩阵 */
    public Matrix4f getViewMatrix() {
        return getViewMatrix(new Matrix4f());
    }

    /**
     * 将视图矩阵写入调用方持有的存储，避免逐帧分配。
     *
     * @param destination 接收结果的矩阵
     * @return {@code destination}
     */
    public Matrix4f getViewMatrix(Matrix4f destination) {
        return destination.identity().lookAt(
                position.x, position.y, position.z,
                position.x + front.x, position.y + front.y, position.z + front.z,
                up.x, up.y, up.z);
    }

    /**
     * 根据移动方向和帧时间更新相机位置。
     *
     * @param direction 移动方向
     * @param deltaTime 帧间隔（秒）
     */
    public void processKeyboard(Movement direction, float deltaTime) {
        float velocity = movementSpeed * deltaTime;
        switch (direction) {
            case FORWARD -> position.fma(velocity, front);
            case BACKWARD -> position.fma(-velocity, front);
            case LEFT -> position.fma(-velocity, right);
            case RIGHT -> position.fma(velocity, right);
        }
    }

    /**
     * 根据鼠标偏移更新偏航和俯仰（默认限制俯仰角）。
     *
     * @param xOffset 鼠标水平偏移
     * @param yOffset 鼠标垂直偏移
     */
    public void processMouseMovement(float xOffset, float yOffset) {
        processMouseMovement(xOffset, yOffset, true);
    }

    /**
     * 根据鼠标偏移更新偏航和俯仰。
     *
     * @param xOffset        鼠标水平偏移
     * @param yOffset        鼠标垂直偏移
     * @param constrainPitch 是否限制俯仰角在 [-89, 89] 范围内
     */
    public void processMouseMovement(float xOffset, float yOffset, boolean constrainPitch) {
        xOffset *= mouseSensitivity;
        yOffset *= mouseSensitivity;

        yaw += xOffset;
        pitch += yOffset;

        if (constrainPitch) {
            if (pitch > 89.0f) pitch = 89.0f;
            if (pitch < -89.0f) pitch = -89.0f;
        }

        updateCameraVectors();
    }

    /**
     * 根据滚轮偏移更新缩放/FOV 值，限制在 [1, 45] 范围内。
     *
     * @param yOffset 滚轮垂直偏移
     */
    public void processMouseScroll(float yOffset) {
        zoom -= yOffset;
        if (zoom < 1.0f) zoom = 1.0f;
        if (zoom > 45.0f) zoom = 45.0f;
    }

    private void updateCameraVectors() {
        front.x = (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        front.y = (float) Math.sin(Math.toRadians(pitch));
        front.z = (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
        front.normalize();
        right.set(front).cross(worldUp).normalize();
        up.set(right).cross(front).normalize();
    }
}
