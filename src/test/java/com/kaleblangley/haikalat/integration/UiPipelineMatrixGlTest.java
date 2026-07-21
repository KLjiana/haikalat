package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.UnavailableTextInputAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 UI overlay 能在每一种合法后处理组合上保持最终合成语义。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class UiPipelineMatrixGlTest {
    private static final int EXPECTED_PIPELINE_RUNS = 40;
    private static final int EXPECTED_UI_RUNS = 20;

    @Test
    void uiOverlayExecutesAcrossEveryLegalPipelineCombination() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            GlRenderDevice device = new GlRenderDevice();
            int pipelineRuns = 0;
            int uiRuns = 0;
            for (RenderSettings settings : legalSettings()) {
                for (boolean uiEnabled : List.of(false, true)) {
                    renderCombination(window, device, settings, uiEnabled);
                    pipelineRuns++;
                    if (uiEnabled) uiRuns++;
                }
            }
            assertEquals(EXPECTED_PIPELINE_RUNS, pipelineRuns);
            assertEquals(EXPECTED_UI_RUNS, uiRuns);
        }
    }

    private static void renderCombination(GlfwWindow window, GlRenderDevice device,
                                          RenderSettings settings, boolean uiEnabled) {
        RenderPipeline pipeline = new RenderPipeline(
                window, new Scene(new Camera()), null, settings);
        try {
            pipeline.build();
            assertFalse(pipeline.graph().hasPass(UiSystem.OVERLAY_PASS_NAME));
            if (uiEnabled) {
                try (UiSystem ui = UiSystem.create(window, UiConfig.defaults(),
                        new UnavailableTextInputAdapter("pipeline matrix test"))) {
                    addVisibleMarker(ui);
                    ui.attachTo(pipeline.graph(), pipeline.finalPassName());
                    ui.update(window.inputSnapshot(), 1.0f / 60.0f);
                    pipeline.execute(device);
                    assertTrue(ui.statistics().drawCalls() > 0,
                            () -> "UI 未产生 draw: " + describe(settings));
                    assertTrue(pipeline.graph().hasPass(UiSystem.OVERLAY_PASS_NAME));
                }
            } else {
                pipeline.execute(device);
            }
            GlDebug.assertNoError(describe(settings) + ", ui=" + uiEnabled);
        } finally {
            pipeline.close();
        }
    }

    private static void addVisibleMarker(UiSystem ui) {
        Panel marker = new Panel();
        marker.debugName("PipelineMatrixMarker");
        marker.style(UiStyle.builder()
                .width(UiLength.points(16.0f))
                .height(UiLength.points(16.0f))
                .build());
        ui.document().root().add(marker);
    }

    private static List<RenderSettings> legalSettings() {
        List<RenderSettings> settings = new ArrayList<>();
        for (AntiAliasingMode aa : AntiAliasingMode.values()) {
            settings.add(settings(aa, ToneMappingMode.NONE, false, ExposureMode.MANUAL));
            for (boolean bloom : List.of(false, true)) {
                for (ExposureMode exposure : ExposureMode.values()) {
                    settings.add(settings(aa, ToneMappingMode.ACES, bloom, exposure));
                }
            }
        }
        return List.copyOf(settings);
    }

    private static RenderSettings settings(AntiAliasingMode aa, ToneMappingMode toneMapping,
                                           boolean bloom, ExposureMode exposure) {
        return RenderSettings.builder()
                .vsync(false)
                .antiAliasingMode(aa)
                .toneMappingMode(toneMapping)
                .exposureMode(exposure)
                .bloomSettings(BloomSettings.builder().enabled(bloom).build())
                .build();
    }

    private static String describe(RenderSettings settings) {
        return settings.toneMappingMode() + "/" + settings.antiAliasingMode()
                + "/bloom=" + settings.bloomSettings().enabled()
                + "/exposure=" + settings.exposureMode();
    }
}
