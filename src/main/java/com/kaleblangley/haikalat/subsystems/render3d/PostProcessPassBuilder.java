package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.FxaaPostProcessor;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalAccumulationPass;
import com.kaleblangley.haikalat.subsystems.postprocess.ToneMappingPass;
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
    private final ToneMappingPass toneMapping;

    private PostProcessPassBuilder(RenderSettings settings, RenderWindow window,
                                   FxaaPostProcessor fxaa,
                                   TemporalAccumulationPass taa,
                                   TaaHistory taaHistory,
                                   ToneMappingPass toneMapping) {
        this.settings = settings;
        this.window = window;
        this.fxaa = fxaa;
        this.taa = taa;
        this.taaHistory = taaHistory;
        this.toneMapping = toneMapping;
    }

    static PostProcessPassBuilder create(RenderSettings settings, RenderWindow window, int width, int height) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(window, "window");
        boolean hdr = settings.hdrEnabled();
        FxaaPostProcessor fxaa = settings.antiAliasingMode() == AntiAliasingMode.FXAA
                ? new FxaaPostProcessor() : null;
        TemporalAccumulationPass taa = settings.antiAliasingMode() == AntiAliasingMode.TAA
                ? new TemporalAccumulationPass() : null;
        TaaHistory history = settings.antiAliasingMode() == AntiAliasingMode.TAA
                ? new TaaHistory(width, height, taaHistoryFormat(settings))
                : null;
        ToneMappingPass toneMapping = hdr ? new ToneMappingPass() : null;
        return new PostProcessPassBuilder(settings, window, fxaa, taa, history, toneMapping);
    }

    static List<String> passNamesFor(AntiAliasingMode mode) {
        return passNamesFor(mode, ToneMappingMode.NONE, false);
    }

    static List<String> passNamesFor(AntiAliasingMode mode, boolean directionalShadow) {
        return passNamesFor(mode, ToneMappingMode.NONE, directionalShadow);
    }

    static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode) {
        return passNamesFor(mode, toneMappingMode, false);
    }

    static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                     boolean directionalShadow) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(toneMappingMode, "toneMappingMode");
        List<String> forwardPasses = toneMappingMode == ToneMappingMode.NONE
                ? ldrPassNames(mode) : hdrPassNames(mode);
        if (!directionalShadow) {
            return forwardPasses;
        }
        return Stream.concat(Stream.of(DirectionalShadowMap.PASS_NAME), forwardPasses.stream()).toList();
    }

    void addFinalPass(RenderGraph graph) {
        if (settings.hdrEnabled()) {
            addHdrFinalPasses(graph);
            return;
        }
        addLdrFinalPass(graph);
    }

    private void addLdrFinalPass(RenderGraph graph) {
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

    private void addHdrFinalPasses(RenderGraph graph) {
        String hdrTexture = PostProcessTargets.SCENE_COLOR;
        String hdrProducer = PostProcessTargets.GEOMETRY_PASS;

        if (settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            graph.addPass(PostProcessTargets.HDR_RESOLVE_PASS)
                    .createColor(PostProcessTargets.HDR_RESOLVED_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer geometry = res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                        Framebuffer target = res.currentTarget();
                        if (geometry != null && target != null) {
                            cmd.blitColor(geometry, target);
                        }
                    });
            hdrTexture = PostProcessTargets.HDR_RESOLVED_COLOR;
            hdrProducer = PostProcessTargets.HDR_RESOLVE_PASS;
        } else if (settings.antiAliasingMode() == AntiAliasingMode.TAA) {
            graph.addPass(PostProcessTargets.TAA_PASS)
                    .createColor(PostProcessTargets.TAA_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer geometry = res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                        Framebuffer target = res.currentTarget();
                        if (geometry != null && target != null && taaHistory != null) {
                            Framebuffer history = taaHistory.framebuffer();
                            taa.recordIntoCurrentTarget(cmd,
                                    res.colorAttachment(PostProcessTargets.SCENE_COLOR),
                                    history.colorAttachment(), taaHistory.historyWeight());
                            cmd.blitColor(target, history);
                            taaHistory.markValid();
                        }
                    });
            hdrTexture = PostProcessTargets.TAA_COLOR;
            hdrProducer = PostProcessTargets.TAA_PASS;
        }

        final String toneInput = hdrTexture;
        graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                .createColor(PostProcessTargets.TONE_MAPPED_COLOR, RenderFormat.RGBA8)
                .noClear()
                .dependsOn(hdrProducer)
                .execute((res, cmd) -> toneMapping.recordIntoCurrentTarget(cmd,
                        res.colorAttachment(toneInput), settings.exposure()));

        if (settings.antiAliasingMode() == AntiAliasingMode.FXAA) {
            graph.addPass(PostProcessTargets.FXAA_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.TONE_MAPPING_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer source = res.framebufferOfPass(PostProcessTargets.TONE_MAPPING_PASS);
                        if (source != null) {
                            fxaa.recordIntoCurrentTarget(cmd,
                                    res.colorAttachment(PostProcessTargets.TONE_MAPPED_COLOR),
                                    source.width(), source.height());
                        }
                    });
        } else {
            graph.addPass(PostProcessTargets.PRESENT_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.TONE_MAPPING_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer source = res.framebufferOfPass(PostProcessTargets.TONE_MAPPING_PASS);
                        if (source != null) {
                            cmd.blitToDefault(source, window.width(), window.height());
                        }
                    });
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
        if (toneMapping != null) {
            toneMapping.close();
        }
    }

    private static List<String> ldrPassNames(AntiAliasingMode mode) {
        return switch (mode) {
            case NONE, MSAA -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.PRESENT_PASS);
            case FXAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.FXAA_PASS);
            case TAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS);
        };
    }

    private static List<String> hdrPassNames(AntiAliasingMode mode) {
        return switch (mode) {
            case NONE -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.PRESENT_PASS);
            case MSAA -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.HDR_RESOLVE_PASS, PostProcessTargets.TONE_MAPPING_PASS,
                    PostProcessTargets.PRESENT_PASS);
            case FXAA -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.FXAA_PASS);
            case TAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS,
                    PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.PRESENT_PASS);
        };
    }

    static RenderFormat taaHistoryFormat(RenderSettings settings) {
        return settings.hdrEnabled() ? RenderFormat.RGBA16F : RenderFormat.RGBA8;
    }
}
