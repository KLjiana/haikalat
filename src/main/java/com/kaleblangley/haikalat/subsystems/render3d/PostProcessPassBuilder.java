package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FxaaPostProcessor;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalAccumulationPass;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

final class PostProcessPassBuilder implements AutoCloseable {
    private final RenderSettings settings;
    private final RenderWindow window;
    private final FxaaPostProcessor fxaa;
    private final TemporalAccumulationPass taa;
    private final TaaHistory taaHistory;

    private PostProcessPassBuilder(RenderSettings settings, RenderWindow window,
                                   FxaaPostProcessor fxaa,
                                   TemporalAccumulationPass taa,
                                   TaaHistory taaHistory) {
        this.settings = settings;
        this.window = window;
        this.fxaa = fxaa;
        this.taa = taa;
        this.taaHistory = taaHistory;
    }

    static PostProcessPassBuilder create(RenderSettings settings, RenderWindow window, int width, int height) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(window, "window");
        return switch (settings.antiAliasingMode()) {
            case FXAA -> new PostProcessPassBuilder(settings, window, new FxaaPostProcessor(), null, null);
            case TAA -> new PostProcessPassBuilder(settings, window, null,
                    new TemporalAccumulationPass(), new TaaHistory(width, height));
            case NONE, MSAA -> new PostProcessPassBuilder(settings, window, null, null, null);
        };
    }

    static List<String> passNamesFor(AntiAliasingMode mode) {
        return passNamesFor(mode, false);
    }

    static List<String> passNamesFor(AntiAliasingMode mode, boolean directionalShadow) {
        Objects.requireNonNull(mode, "mode");
        List<String> forwardPasses = switch (mode) {
            case NONE, MSAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.PRESENT_PASS);
            case FXAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.FXAA_PASS);
            case TAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS);
        };
        if (!directionalShadow) {
            return forwardPasses;
        }
        return Stream.concat(Stream.of(DirectionalShadowMap.PASS_NAME), forwardPasses.stream()).toList();
    }

    void addFinalPass(RenderGraph graph) {
        List<String> passNames = passNamesFor(settings.antiAliasingMode());
        String finalPassName = passNames.get(passNames.size() - 1);
        switch (finalPassName) {
            case PostProcessTargets.FXAA_PASS -> graph.addPass(PostProcessTargets.FXAA_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer geoFb = res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                        if (geoFb != null) {
                            fxaa.recordIntoCurrentTarget(cmd, res.colorAttachment(PostProcessTargets.SCENE_COLOR),
                                    geoFb.width(), geoFb.height());
                        }
                    });
            case PostProcessTargets.TAA_PASS -> graph.addPass(PostProcessTargets.TAA_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer geoFb = res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                        if (geoFb != null && taaHistory != null) {
                            Framebuffer history = taaHistory.framebuffer();
                            taa.recordIntoCurrentTarget(cmd, res.colorAttachment(PostProcessTargets.SCENE_COLOR),
                                    history.colorAttachment(), taaHistory.historyWeight());
                            cmd.blitFramebuffer(0, history.id(), window.width(), window.height(),
                                    history.width(), history.height());
                            taaHistory.markValid();
                        }
                    });
            case PostProcessTargets.PRESENT_PASS -> graph.addPass(PostProcessTargets.PRESENT_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer geoFb = res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                        if (geoFb != null) {
                            cmd.blitToDefault(geoFb, geoFb.width(), geoFb.height());
                        }
                    });
            default -> throw new IllegalStateException("Unknown final postprocess pass: " + finalPassName);
        }
    }

    void resize(int width, int height) {
        if (taaHistory != null) {
            taaHistory.resize(width, height);
        }
    }

    @Override
    public void close() {
        if (fxaa != null) {
            fxaa.close();
        }
        if (taa != null) {
            taa.close();
        }
        if (taaHistory != null) {
            taaHistory.close();
        }
    }
}
