package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;

/**
 * Small helpers that keep station presets consistent: every complete feature
 * request carries the session's HDR/AA render settings and the default
 * post-process chain unless the station deliberately changes one component.
 */
final class ExhibitionSettings {
    private ExhibitionSettings() {
    }

    static RenderFeatureSettings base(RenderSettings renderSettings) {
        return RenderFeatureSettings.defaults().toBuilder()
                .render(renderSettings)
                .postProcess(PostProcessSettings.defaults())
                .build();
    }

    static RenderFeatureSettings withPostProcess(RenderSettings renderSettings,
                                                 PostProcessSettings postProcess) {
        return base(renderSettings).toBuilder().postProcess(postProcess).build();
    }

    /** Returns a validated copy of the session settings with only the AA mode replaced. */
    static RenderSettings withAntiAliasing(RenderSettings base, AntiAliasingMode mode) {
        return RenderSettings.builder()
                .vsync(base.vsync())
                .antiAliasingMode(mode)
                .msaaSamples(base.msaaSamples())
                .toneMappingMode(base.toneMappingMode())
                .exposure(base.exposure())
                .exposureMode(base.exposureMode())
                .autoExposureSettings(base.autoExposureSettings())
                .bloomSettings(base.bloomSettings())
                .sceneVisibility(base.sceneVisibility())
                .renderScale(base.renderScale())
                .build();
    }

    /** Returns a validated copy of the session settings with only the render scale replaced. */
    static RenderSettings withRenderScale(RenderSettings base, float renderScale) {
        return base.withRenderScale(renderScale);
    }
}
