package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One-frame immutable input captured before RenderGraph command recording begins. */
record RenderFrameContext(ExternalCamera camera,
                          List<SceneLight> lights,
                          SceneRevisionSnapshot revisions,
                          FrameInvalidation invalidation,
                          int width,
                          int height,
                          float deltaSeconds,
                          int frameIndex,
                          long frameSequence) {
    RenderFrameContext {
        Objects.requireNonNull(camera, "camera");
        lights = List.copyOf(Objects.requireNonNull(lights, "lights"));
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(invalidation, "invalidation");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
    }

    static RenderFrameContext capture(Scene scene, Camera camera, int width, int height,
                                      float deltaSeconds, int frameIndex, long frameSequence,
                                      long topologySettingsRevision,
                                      RenderFrameContext previousSuccessfulFrame) {
        Objects.requireNonNull(scene, "scene");
        ExternalCamera frozenCamera = freezeCamera(camera, width, height);
        SceneRevisionSnapshot revisions = SceneRevisionSnapshot.capture(
                scene, frozenCamera, topologySettingsRevision, frameIndex);
        List<SceneLight> frozenLights = freezeLights(scene.lights());
        SceneRevisionSnapshot confirmed = SceneRevisionSnapshot.capture(
                scene, frozenCamera, topologySettingsRevision, frameIndex);
        if (!revisions.equals(confirmed)) {
            throw new IllegalStateException("Scene changed while capturing RenderFrameContext");
        }
        SceneRevisionSnapshot previous = previousSuccessfulFrame == null
                ? null : previousSuccessfulFrame.revisions();
        return new RenderFrameContext(frozenCamera, frozenLights, revisions,
                FrameInvalidation.between(previous, revisions), width, height,
                deltaSeconds, frameIndex, frameSequence);
    }

    void verifySceneStable(Scene scene, long topologySettingsRevision) {
        SceneRevisionSnapshot current = SceneRevisionSnapshot.capture(
                Objects.requireNonNull(scene, "scene"), camera,
                topologySettingsRevision, frameIndex);
        if (!revisions.equals(current)) {
            throw new IllegalStateException(
                    "Scene changed after RenderFrameContext capture; mutation applies next frame");
        }
    }

    private static ExternalCamera freezeCamera(Camera camera, int width, int height) {
        Camera required = Objects.requireNonNull(camera, "camera");
        if (required instanceof ExternalCamera external) return external;

        long before = required.visibilityRevision();
        Matrix4f view = required.getViewMatrix(new Matrix4f());
        Matrix4f projection = CameraProjection.stable(required, width, height, new Matrix4f());
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        var position = required.position();
        long after = required.visibilityRevision();
        if (before != after) {
            throw new IllegalStateException("camera changed while capturing RenderFrameContext");
        }
        return new ExternalCamera(view, projection, viewProjection, position, 0.0f,
                CameraProjection.NEAR_PLANE, CameraProjection.FAR_PLANE, after);
    }

    private static List<SceneLight> freezeLights(List<SceneLight> lights) {
        List<SceneLight> frozen = new ArrayList<>(lights.size());
        for (SceneLight light : lights) {
            frozen.add(new SceneLight(light.type(), light.color(), light.intensity(),
                    light.direction(), light.position(), light.range(),
                    light.innerConeRadians(), light.outerConeRadians(), light.castShadows()));
        }
        return List.copyOf(frozen);
    }
}
