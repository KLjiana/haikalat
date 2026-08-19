package com.kaleblangley.haikalat.subsystems.render3d;

/** Explicit immutable depth-content identity for one cascade, point block or spot tile. */
record ShadowCacheKey(long sceneGeneration,
                      long lightId,
                      long lightRevision,
                      long casterMembershipRevision,
                      long transformModelRevision,
                      long materialRevision,
                      long deformationRevision,
                      long cameraCascadeKey,
                      long settingsKey) {
    ShadowFramePlan.MissReason difference(ShadowCacheKey previous) {
        if (previous == null) return ShadowFramePlan.MissReason.NEW_ALLOCATION;
        if (sceneGeneration != previous.sceneGeneration) return ShadowFramePlan.MissReason.GENERATION;
        if (lightId != previous.lightId || lightRevision != previous.lightRevision) {
            return ShadowFramePlan.MissReason.LIGHT;
        }
        if (casterMembershipRevision != previous.casterMembershipRevision) {
            return ShadowFramePlan.MissReason.CASTER_MEMBERSHIP;
        }
        if (transformModelRevision != previous.transformModelRevision) {
            return ShadowFramePlan.MissReason.TRANSFORM_MODEL;
        }
        if (materialRevision != previous.materialRevision) {
            return ShadowFramePlan.MissReason.MATERIAL;
        }
        if (deformationRevision != previous.deformationRevision) {
            return ShadowFramePlan.MissReason.DEFORMATION;
        }
        if (cameraCascadeKey != previous.cameraCascadeKey) {
            return ShadowFramePlan.MissReason.CAMERA_CASCADE;
        }
        if (settingsKey != previous.settingsKey) return ShadowFramePlan.MissReason.SETTINGS;
        return ShadowFramePlan.MissReason.NONE;
    }
}
