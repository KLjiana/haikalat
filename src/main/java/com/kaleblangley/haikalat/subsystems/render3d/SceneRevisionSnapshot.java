package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.material.MaterialInstance;

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
                transformModelRevision(scene, frameIndex), scene.lightingRevision(),
                materialRenderStateRevision(scene), camera.visibilityRevision(),
                topologySettingsRevision);
    }

    private static long transformModelRevision(Scene scene, int frameIndex) {
        long revision = mix(mix(HASH_OFFSET, scene.membershipRevision()),
                Transform.mutationEpoch());
        revision = mix(revision, scene.revisionScannedModelEpoch());
        return scene.requiresPerFrameModelRevision()
                ? mix(revision, Integer.toUnsignedLong(frameIndex)) : revision;
    }

    private static long materialRenderStateRevision(Scene scene) {
        return mix(mix(HASH_OFFSET, scene.membershipRevision()),
                MaterialInstance.mutationEpoch());
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * HASH_PRIME;
    }
}
