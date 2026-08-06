package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;

import java.util.List;
import java.util.Objects;

/** Immutable, GL-free representation of a validated {@code haikalat.scene} v1 file. */
public final class SceneDefinition {
    public static final String FORMAT = "haikalat.scene";
    public static final int VERSION = 1;

    private final AssetId source;
    private final CameraDefinition camera;
    private final List<NodeDefinition> nodes;
    private final List<SceneCharacterDefinition> characters;

    public SceneDefinition(AssetId source, CameraDefinition camera, List<NodeDefinition> nodes) {
        this(source, camera, nodes, List.of());
    }

    public SceneDefinition(AssetId source, CameraDefinition camera, List<NodeDefinition> nodes,
                           List<SceneCharacterDefinition> characters) {
        this.source = Objects.requireNonNull(source, "source");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        this.characters = List.copyOf(Objects.requireNonNull(characters, "characters"));
    }

    public AssetId source() {
        return source;
    }

    public CameraDefinition camera() {
        return camera;
    }

    public List<NodeDefinition> nodes() {
        return nodes;
    }

    public List<SceneCharacterDefinition> characters() {
        return characters;
    }

    public record TransformDefinition(
            float translationX, float translationY, float translationZ,
            float rotationX, float rotationY, float rotationZ, float rotationW,
            float scaleX, float scaleY, float scaleZ
    ) {
        public static TransformDefinition identity() {
            return new TransformDefinition(0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 1.0f,
                    1.0f, 1.0f, 1.0f);
        }

        public TransformDefinition {
            requireFinite(translationX, "translationX");
            requireFinite(translationY, "translationY");
            requireFinite(translationZ, "translationZ");
            requireFinite(rotationX, "rotationX");
            requireFinite(rotationY, "rotationY");
            requireFinite(rotationZ, "rotationZ");
            requireFinite(rotationW, "rotationW");
            requireFinite(scaleX, "scaleX");
            requireFinite(scaleY, "scaleY");
            requireFinite(scaleZ, "scaleZ");
            float length = (float) Math.sqrt(rotationX * rotationX + rotationY * rotationY
                    + rotationZ * rotationZ + rotationW * rotationW);
            if (!(length > 1.0e-8f)) {
                throw new IllegalArgumentException("rotation quaternion must be non-zero");
            }
            if (scaleX == 0.0f || scaleY == 0.0f || scaleZ == 0.0f) {
                throw new IllegalArgumentException("scale components must be non-zero");
            }
            rotationX /= length;
            rotationY /= length;
            rotationZ /= length;
            rotationW /= length;
        }
    }

    public record CameraDefinition(String node, ProjectionDefinition projection) {
        public CameraDefinition {
            node = requireText(node, "node");
            projection = Objects.requireNonNull(projection, "projection");
        }
    }

    public record ProjectionDefinition(String type, float fovYDegrees, float nearPlane,
                                       float farPlane) {
        public ProjectionDefinition {
            type = requireText(type, "type");
            if (!type.equals("perspective")) {
                throw new IllegalArgumentException("unsupported camera projection: " + type);
            }
            requireFinite(fovYDegrees, "fovYDegrees");
            requireFinite(nearPlane, "nearPlane");
            requireFinite(farPlane, "farPlane");
            if (!(fovYDegrees > 1.0f && fovYDegrees < 179.0f)) {
                throw new IllegalArgumentException("fovYDegrees must be in (1, 179)");
            }
            if (!(nearPlane > 0.0f) || !(farPlane > nearPlane)) {
                throw new IllegalArgumentException("projection requires 0 < near < far");
            }
        }
    }

    public record RenderableDefinition(
            String type,
            AssetId asset,
            String scene,
            boolean animated,
            String initialAnimation,
            boolean loop,
            boolean castShadows
    ) {
        public RenderableDefinition {
            type = requireText(type, "type");
            if (!type.equals("gltf")) {
                throw new IllegalArgumentException("unsupported renderable type: " + type);
            }
            asset = Objects.requireNonNull(asset, "asset");
            if (!asset.extension().equals("gltf") && !asset.extension().equals("glb")
                    && !isAnimationLibraryAsset(asset)) {
                throw new IllegalArgumentException("renderable asset must be .gltf, .glb, "
                        + "or animation-library.json: " + asset);
            }
            scene = requireText(scene, "scene");
            if (!scene.equals("default") && !scene.startsWith("index:")
                    && !scene.startsWith("name:")) {
                throw new IllegalArgumentException("scene must be default, index:<n> or name:<name>");
            }
            if (!animated && initialAnimation != null) {
                throw new IllegalArgumentException("initialAnimation requires animated=true");
            }
            if (initialAnimation != null) initialAnimation = requireText(initialAnimation, "initialAnimation");
        }
    }

    static boolean isAnimationLibraryAsset(AssetId asset) {
        String path = Objects.requireNonNull(asset, "asset").path();
        return path.equals("animation-library.json")
                || path.endsWith("/animation-library.json");
    }

    public record LightDefinition(
            String type,
            float colorR, float colorG, float colorB,
            float intensity,
            float range,
            float innerConeDegrees,
            float outerConeDegrees,
            boolean castShadows
    ) {
        public LightDefinition {
            type = requireText(type, "type");
            if (!type.equals("directional") && !type.equals("point") && !type.equals("spot")) {
                throw new IllegalArgumentException("unsupported light type: " + type);
            }
            requireFinite(colorR, "colorR");
            requireFinite(colorG, "colorG");
            requireFinite(colorB, "colorB");
            requireFinite(intensity, "intensity");
            requireFinite(range, "range");
            requireFinite(innerConeDegrees, "innerConeDegrees");
            requireFinite(outerConeDegrees, "outerConeDegrees");
            if (colorR < 0.0f || colorG < 0.0f || colorB < 0.0f || intensity < 0.0f) {
                throw new IllegalArgumentException("light color and intensity must be non-negative");
            }
            if ((type.equals("point") || type.equals("spot")) && range <= 0.0f) {
                throw new IllegalArgumentException("point/spot light range must be positive");
            }
            if (type.equals("spot") && !(innerConeDegrees >= 0.0f
                    && outerConeDegrees > innerConeDegrees && outerConeDegrees <= 90.0f)) {
                throw new IllegalArgumentException("spot cones must satisfy 0 <= inner < outer <= 90");
            }
        }
    }

    public record NodeDefinition(
            String id,
            String parent,
            TransformDefinition transform,
            RenderableDefinition renderable,
            LightDefinition light
    ) {
        public NodeDefinition {
            id = requireText(id, "id");
            transform = Objects.requireNonNull(transform, "transform");
            if (renderable != null && light != null) {
                throw new IllegalArgumentException("a node may not contain both renderable and light");
            }
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
