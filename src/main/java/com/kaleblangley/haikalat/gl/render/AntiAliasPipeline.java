package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.AntiAliasingMode;
import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import com.kaleblangley.haikalat.gl.RenderSettings;
import com.kaleblangley.haikalat.gl.fb.Framebuffer;

import java.util.Objects;

public final class AntiAliasPipeline implements GlResource {
    private final RenderSettings settings;
    private final Framebuffer sceneFramebuffer;
    private final Framebuffer historyFramebuffer;
    private final FxaaPostProcessor fxaaPostProcessor;
    private final TemporalAccumulationPass temporalAccumulationPass;
    private boolean closed;

    private AntiAliasPipeline(RenderSettings settings, Framebuffer sceneFramebuffer, Framebuffer historyFramebuffer, FxaaPostProcessor fxaaPostProcessor, TemporalAccumulationPass temporalAccumulationPass) {
        this.settings = settings;
        this.sceneFramebuffer = sceneFramebuffer;
        this.historyFramebuffer = historyFramebuffer;
        this.fxaaPostProcessor = fxaaPostProcessor;
        this.temporalAccumulationPass = temporalAccumulationPass;
    }

    public static AntiAliasPipeline create(RenderSettings settings, int width, int height) {
        Objects.requireNonNull(settings, "settings");
        Framebuffer scene = switch (settings.antiAliasingMode()) {
            case MSAA -> Framebuffer.multiSampled(width, height, Math.max(2, settings.msaaSamples()));
            case FXAA, TAA, NONE -> Framebuffer.singleSampled(width, height);
        };
        Framebuffer history = settings.antiAliasingMode() == AntiAliasingMode.TAA ? Framebuffer.singleSampled(width, height) : null;
        FxaaPostProcessor fxaa = settings.antiAliasingMode() == AntiAliasingMode.FXAA ? new FxaaPostProcessor() : null;
        TemporalAccumulationPass taa = settings.antiAliasingMode() == AntiAliasingMode.TAA ? new TemporalAccumulationPass() : null;
        return new AntiAliasPipeline(settings, scene, history, fxaa, taa);
    }

    public Framebuffer sceneTarget() {
        ensureOpen();
        return sceneFramebuffer;
    }

    public void present(int width, int height) {
        ensureOpen();
        switch (settings.antiAliasingMode()) {
            case NONE -> sceneFramebuffer.blitToDefault(width, height);
            case MSAA -> sceneFramebuffer.blitToDefault(width, height);
            case FXAA -> fxaaPostProcessor.render(sceneFramebuffer, width, height);
            case TAA -> {
                temporalAccumulationPass.render(sceneFramebuffer.colorAttachment(), historyFramebuffer.colorAttachment(), 0.90f, width, height);
                sceneFramebuffer.blitColorTo(historyFramebuffer);
            }
        }
    }

    @Override
    public int id() {
        return sceneFramebuffer.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (fxaaPostProcessor != null) {
            fxaaPostProcessor.close();
        }
        if (temporalAccumulationPass != null) {
            temporalAccumulationPass.close();
        }
        if (historyFramebuffer != null) {
            historyFramebuffer.close();
        }
        sceneFramebuffer.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("AntiAliasPipeline is closed");
        }
    }
}
