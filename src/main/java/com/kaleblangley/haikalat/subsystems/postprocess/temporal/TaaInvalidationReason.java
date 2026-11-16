package com.kaleblangley.haikalat.subsystems.postprocess.temporal;

/** Why the temporal resolve stopped trusting its previous successful history. */
public enum TaaInvalidationReason {
    NONE,
    FIRST_FRAME,
    VOLUMETRIC_SETTINGS,
    VOLUMETRIC_TOPOLOGY,
    RESIZE,
    EXPLICIT_RESET,
    GTAO_SETTINGS,
    GTAO_TOPOLOGY,
    SHADOW_TOPOLOGY,
    LIGHTING_SETTINGS,
    VOLUMETRIC_PARTICLE_SETTINGS,
    STOCHASTIC_TRANSPARENCY_SETTINGS,
    WEIGHTED_OIT_SETTINGS,
    PLANAR_REFLECTION_SETTINGS,
    CAUSTICS_SETTINGS,
    HAIR_STRANDS_SETTINGS,
    HAIR_STRAND_MODEL
}
