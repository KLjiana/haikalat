package com.kaleblangley.haikalat.subsystems.render3d;

/** Why a requested feature does or does not currently contribute to the output. */
public enum RenderFeatureReason {
    ACTIVE,
    DISABLED,
    PAUSED_BY_MASTER,
    NO_ELIGIBLE_LIGHT,
    NO_PBR_MATERIAL,
    NO_ENVIRONMENT,
    NO_RECORDER,
    UNAVAILABLE
}
