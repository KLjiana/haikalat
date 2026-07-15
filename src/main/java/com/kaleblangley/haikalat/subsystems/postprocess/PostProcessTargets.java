package com.kaleblangley.haikalat.subsystems.postprocess;

public final class PostProcessTargets {
    public static final String GEOMETRY_PASS = "GeometryPass";
    public static final String FXAA_PASS = "FxaaPass";
    public static final String TAA_PASS = "TaaPass";
    public static final String HDR_RESOLVE_PASS = "HdrResolvePass";
    public static final String TONE_MAPPING_PASS = "ToneMappingPass";
    public static final String BLOOM_EXTRACT_PASS = "BloomExtractPass";
    public static final String BLOOM_DOWN_PASS_PREFIX = "BloomDownPass";
    public static final String BLOOM_UP_PASS_PREFIX = "BloomUpPass";
    public static final String AUTO_EXPOSURE_LUMINANCE_PASS = "AutoExposureLuminancePass";
    public static final String AUTO_EXPOSURE_REDUCE_PASS_PREFIX = "AutoExposureReducePass";
    public static final String AUTO_EXPOSURE_ADAPT_PASS = "AutoExposureAdaptPass";
    public static final String PRESENT_PASS = "PresentPass";

    public static final String SCENE_COLOR = "sceneColor";
    public static final String FXAA_COLOR = "fxaaColor";
    public static final String TAA_HISTORY = "taaHistory";
    public static final String TAA_COLOR = "taaColor";
    public static final String HDR_RESOLVED_COLOR = "hdrResolvedColor";
    public static final String TONE_MAPPED_COLOR = "toneMappedColor";
    public static final String BLOOM_DOWN_COLOR_PREFIX = "bloomDownColor";
    public static final String BLOOM_UP_COLOR_PREFIX = "bloomUpColor";
    public static final String AUTO_EXPOSURE_LUMINANCE = "autoExposureLuminance";
    public static final String AUTO_EXPOSURE_REDUCE_COLOR_PREFIX = "autoExposureReduceColor";

    private PostProcessTargets() {
    }

    public static String bloomDownPass(int level) {
        return level == 0 ? BLOOM_EXTRACT_PASS : BLOOM_DOWN_PASS_PREFIX + level;
    }

    public static String bloomDownColor(int level) {
        return BLOOM_DOWN_COLOR_PREFIX + level;
    }

    public static String bloomUpPass(int level) {
        return BLOOM_UP_PASS_PREFIX + level;
    }

    public static String bloomUpColor(int level) {
        return BLOOM_UP_COLOR_PREFIX + level;
    }

    public static String autoExposureReducePass(int level) {
        return AUTO_EXPOSURE_REDUCE_PASS_PREFIX + level;
    }

    public static String autoExposureReduceColor(int level) {
        return AUTO_EXPOSURE_REDUCE_COLOR_PREFIX + level;
    }
}
