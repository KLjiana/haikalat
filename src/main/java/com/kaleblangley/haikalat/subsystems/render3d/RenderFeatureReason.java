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
    /** A caller-selected surface set or an explicit empty-set declaration is absent. */
    NO_TRANSPARENT_SET,
    /** The finite water surface required by water caustics is not active. */
    NO_WATER,
    /** The requested planar-reflection configuration has no usable plane. */
    NO_PLANE,
    UNAVAILABLE
}
