package com.kaleblangley.haikalat.subsystems.postprocess;

public final class PostProcessTargets {
    public static final String GEOMETRY_PASS = "GeometryPass";
    public static final String FXAA_PASS = "FxaaPass";
    public static final String TAA_PASS = "TaaPass";
    public static final String HDR_RESOLVE_PASS = "HdrResolvePass";
    public static final String TONE_MAPPING_PASS = "ToneMappingPass";
    public static final String PRESENT_PASS = "PresentPass";

    public static final String SCENE_COLOR = "sceneColor";
    public static final String TAA_HISTORY = "taaHistory";
    public static final String TAA_COLOR = "taaColor";
    public static final String HDR_RESOLVED_COLOR = "hdrResolvedColor";
    public static final String TONE_MAPPED_COLOR = "toneMappedColor";

    private PostProcessTargets() {
    }
}
