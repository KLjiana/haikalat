package com.kaleblangley.haikalat.subsystems.render3d.scene;

import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLightEntry;

import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.scene.FrameInvalidation;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneRevisionSnapshot;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One-frame immutable input captured before RenderGraph command recording begins. */
public record RenderFrameContext(ExternalCamera camera,
                          List<SceneLight> lights,
                          List<SceneLightEntry> lightEntries,
                          SceneRevisionSnapshot revisions,
                          FrameInvalidation invalidation,
                          int width,
                          int height,
                          float deltaSeconds,
                          int frameIndex,
                          long frameSequence) {
    public RenderFrameContext {
        Objects.requireNonNull(camera, "camera");
        lights = List.copyOf(Objects.requireNonNull(lights, "lights"));
        lightEntries = List.copyOf(Objects.requireNonNull(lightEntries, "lightEntries"));
        if (lights.size() != lightEntries.size()) {
            throw new IllegalArgumentException("lights and lightEntries must have equal size");
        }
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(invalidation, "invalidation");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
    }

    public static RenderFrameContext capture(Scene scene, Camera camera, int width, int height,
                                      float deltaSeconds, int frameIndex, long frameSequence,
                                      long topologySettingsRevision,
                                      RenderFrameContext previousSuccessfulFrame) {
        return capture(scene, camera, width, height, deltaSeconds, frameIndex, frameSequence,
                topologySettingsRevision, previousSuccessfulFrame, List.of());
    }

    /**
     * Captures the frame with additional frozen light entries (for example
     * temporary effect lights) merged before any pass records commands.
     *
     * <p>Additional entries must carry stable IDs that do not collide with the
     * Scene.  The Scene revision check stays untouched: effect-light changes
     * are already frozen into {@code additionalLights} and do not make the
     * Scene itself unstable.</p>
     */
    public static RenderFrameContext capture(Scene scene, Camera camera, int width, int height,
                                      float deltaSeconds, int frameIndex, long frameSequence,
                                      long topologySettingsRevision,
                                      RenderFrameContext previousSuccessfulFrame,
                                      List<SceneLightEntry> additionalLights) {
        Objects.requireNonNull(scene, "scene");
        List<SceneLightEntry> extras = List.copyOf(
                Objects.requireNonNull(additionalLights, "additionalLights"));
        ExternalCamera frozenCamera = freezeCamera(camera, width, height);
        SceneRevisionSnapshot revisions = SceneRevisionSnapshot.capture(
                scene, frozenCamera, topologySettingsRevision, frameIndex);
        List<SceneLightEntry> frozenEntries = freezeLightEntries(scene.lightEntries(), extras);
        List<SceneLight> frozenLights = frozenEntries.stream().map(SceneLightEntry::light).toList();
        SceneRevisionSnapshot confirmed = SceneRevisionSnapshot.capture(
                scene, frozenCamera, topologySettingsRevision, frameIndex);
        if (!revisions.equals(confirmed)) {
            throw new IllegalStateException("Scene changed while capturing RenderFrameContext");
        }
        SceneRevisionSnapshot previous = previousSuccessfulFrame == null
                ? null : previousSuccessfulFrame.revisions();
        return new RenderFrameContext(frozenCamera, frozenLights, frozenEntries, revisions,
                FrameInvalidation.between(previous, revisions), width, height,
                deltaSeconds, frameIndex, frameSequence);
    }

    public void verifySceneStable(Scene scene, long topologySettingsRevision) {
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

    private static List<SceneLightEntry> freezeLightEntries(List<SceneLightEntry> entries,
                                                            List<SceneLightEntry> additional) {
        List<SceneLightEntry> frozen = new ArrayList<>(entries.size() + additional.size());
        java.util.Set<Long> stableIds = new java.util.HashSet<>();
        for (SceneLightEntry entry : entries) {
            if (!stableIds.add(entry.stableId())) {
                throw new IllegalStateException("duplicate scene light stable id " + entry.stableId());
            }
            frozen.add(entry.frozen());
        }
        for (SceneLightEntry entry : additional) {
            if (!stableIds.add(entry.stableId())) {
                throw new IllegalStateException(
                        "effect light stable id collides with a scene light: " + entry.stableId()
                                + " scene=" + entries.stream().map(SceneLightEntry::stableId).toList()
                                + " additional=" + additional.stream()
                                .map(SceneLightEntry::stableId).toList());
            }
            frozen.add(entry.frozen());
        }
        return List.copyOf(frozen);
    }
}
