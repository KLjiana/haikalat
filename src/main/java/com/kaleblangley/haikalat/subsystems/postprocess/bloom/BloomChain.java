package com.kaleblangley.haikalat.subsystems.postprocess.bloom;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;

import java.util.Objects;

/**
 * Owner of the bloom program plus the extract/down/up pass topology.
 *
 * <p>{@link #create} only returns an owner when bloom is enabled, so a disabled
 * effect neither allocates GPU resources nor contributes passes.  Scalars
 * (threshold, soft knee, intensity) update in place; the level count is a
 * topology decision owned by the caller's candidate generation.</p>
 */
public final class BloomChain implements AutoCloseable {
    private final BloomPass pass;
    private BloomSettings settings;

    private BloomChain(BloomSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.pass = new BloomPass();
    }

    /** @return a new owner, or {@code null} when {@code settings} disables bloom */
    public static BloomChain create(BloomSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return settings.enabled() ? new BloomChain(settings) : null;
    }

    public void updateSettings(BloomSettings replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (!replacement.enabled() || replacement.maxLevels() != settings.maxLevels()) {
            throw new IllegalArgumentException("bloom topology differs from the active generation");
        }
        settings = replacement;
    }

    public int levels() {
        return settings.maxLevels();
    }

    public float intensity() {
        return settings.intensity();
    }

    /** Declares the down/up chain and returns the final up-pass colour. */
    public Output addPasses(RenderGraph graph, String sourceTexture, String sourcePass) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(sourceTexture, "sourceTexture");
        Objects.requireNonNull(sourcePass, "sourcePass");
        int levels = settings.maxLevels();
        String previousTexture = sourceTexture;
        String previousPass = sourcePass;
        for (int level = 0; level < levels; level++) {
            final int currentLevel = level;
            final String inputTexture = previousTexture;
            final String inputPass = previousPass;
            String passName = PostProcessTargets.bloomDownPass(level);
            String outputTexture = PostProcessTargets.bloomDownColor(level);
            float scale = 0.5f / (1 << level);
            graph.addPass(passName)
                    .createColor(outputTexture, RenderFormat.RGBA16F)
                    .relativeSize(scale)
                    .noClear()
                    .dependsOn(inputPass)
                    .execute((res, cmd) -> {
                        Framebuffer source = res.framebufferOfPass(inputPass);
                        if (source == null && !PostProcessTargets.TAA_PASS.equals(inputPass)) {
                            throw new IllegalStateException("Bloom source pass has no color target: "
                                    + inputPass);
                        }
                        int sourceWidth = source == null ? graph.width() : source.width();
                        int sourceHeight = source == null ? graph.height() : source.height();
                        int sourceTextureId = res.colorAttachment(inputTexture);
                        if (sourceTextureId == 0) {
                            throw new IllegalStateException("Bloom source texture is unavailable: "
                                    + inputTexture);
                        }
                        if (currentLevel == 0) {
                            pass.recordExtract(cmd, sourceTextureId, sourceWidth, sourceHeight,
                                    settings.threshold(), settings.softKnee());
                        } else {
                            pass.recordDownsample(cmd, sourceTextureId, sourceWidth, sourceHeight);
                        }
                    });
            previousTexture = outputTexture;
            previousPass = passName;
        }

        for (int level = levels - 2; level >= 0; level--) {
            final String highTexture = PostProcessTargets.bloomDownColor(level);
            final String lowTexture = previousTexture;
            final String lowPass = previousPass;
            String passName = PostProcessTargets.bloomUpPass(level);
            String outputTexture = PostProcessTargets.bloomUpColor(level);
            float scale = 0.5f / (1 << level);
            graph.addPass(passName)
                    .createColor(outputTexture, RenderFormat.RGBA16F)
                    .relativeSize(scale)
                    .noClear()
                    .dependsOn(lowPass)
                    .execute((res, cmd) -> {
                        Framebuffer low = res.framebufferOfPass(lowPass);
                        if (low != null) {
                            pass.recordUpsample(cmd, res.colorAttachment(highTexture),
                                    res.colorAttachment(lowTexture), low.width(), low.height());
                        }
                    });
            previousTexture = outputTexture;
            previousPass = passName;
        }
        return new Output(previousTexture, previousPass);
    }

    @Override
    public void close() {
        pass.close();
    }

    /** Final bloom colour texture and its producer pass. */
    public record Output(String textureName, String producerPass) {
        public Output {
            Objects.requireNonNull(textureName, "textureName");
            Objects.requireNonNull(producerPass, "producerPass");
        }
    }
}
