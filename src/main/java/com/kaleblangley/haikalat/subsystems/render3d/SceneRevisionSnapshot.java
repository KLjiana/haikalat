package com.kaleblangley.haikalat.subsystems.render3d;

/** Immutable six-domain revision snapshot plus the Scene lifecycle generation. */
record SceneRevisionSnapshot(long sceneGeneration,
                             long membershipRevision,
                             long transformModelRevision,
                             long lightingRevision,
                             long materialRenderStateRevision,
                             long cameraRevision,
                             long topologySettingsRevision) {
    private static final long HASH_OFFSET = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    static SceneRevisionSnapshot capture(Scene scene, Camera camera,
                                         long topologySettingsRevision, int frameIndex) {
        return new SceneRevisionSnapshot(scene.generation(), scene.membershipRevision(),
                transformModelRevision(scene, frameIndex), lightingRevision(scene),
                materialRenderStateRevision(scene), camera.visibilityRevision(),
                topologySettingsRevision);
    }

    private static long transformModelRevision(Scene scene, int frameIndex) {
        long revision = mix(HASH_OFFSET, scene.rendererCount());
        for (int index = 0; index < scene.rendererCount(); index++) {
            MeshRenderer renderer = scene.rendererAt(index);
            long rendererRevision = renderer.revisionedModel()
                    ? renderer.modelRevision()
                    : Integer.toUnsignedLong(frameIndex);
            revision = mix(revision, rendererRevision);
        }
        return revision;
    }

    private static long materialRenderStateRevision(Scene scene) {
        long revision = mix(HASH_OFFSET, scene.rendererCount());
        for (int index = 0; index < scene.rendererCount(); index++) {
            revision = mix(revision, scene.rendererAt(index).material().revision());
        }
        return revision;
    }

    private static long lightingRevision(Scene scene) {
        long revision = mix(HASH_OFFSET, scene.lightingRevision());
        for (SceneLight light : scene.lights()) {
            revision = mix(revision, light.type().ordinal());
            revision = mixVector(revision, light.color());
            revision = mix(revision, Float.floatToIntBits(light.intensity()));
            revision = mixVector(revision, light.direction());
            revision = mixVector(revision, light.position());
            revision = mix(revision, Float.floatToIntBits(light.range()));
            revision = mix(revision, Float.floatToIntBits(light.innerConeRadians()));
            revision = mix(revision, Float.floatToIntBits(light.outerConeRadians()));
            revision = mix(revision, light.castShadows() ? 1L : 0L);
        }
        return revision;
    }

    private static long mixVector(long hash, org.joml.Vector3fc value) {
        long mixed = mix(hash, Float.floatToIntBits(value.x()));
        mixed = mix(mixed, Float.floatToIntBits(value.y()));
        return mix(mixed, Float.floatToIntBits(value.z()));
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * HASH_PRIME;
    }
}
