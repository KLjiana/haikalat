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
    LIGHTING_SETTINGS
}
