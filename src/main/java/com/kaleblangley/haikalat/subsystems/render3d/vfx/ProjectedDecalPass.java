package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;

import java.util.Objects;

/**
 * Graph declaration and resource contract for the projected-decal stage.
 *
 * <p>The stage is two passes. {@link #DEPTH_RESOLVE_PASS} copies (and, for an
 * MSAA raster, resolves) the geometry depth into a pass-owned single-sample
 * depth texture; {@link #PASS_NAME} copies the current scene colour into a
 * pass-owned colour target, then records the caller's projected-decal batch
 * against that resolved depth. Both targets are owned by the graph generation
 * and are never shared with another pass: the depth texture is sampled while
 * the colour target is bound, so a shared scene depth would form a feedback
 * loop.</p>
 *
 * <p>The colour target keeps the topology sample count. A single-sample chain
 * gets a texture target so later transparent collectors can borrow it; an MSAA
 * chain gets a multisampled target so the downstream water/glass and HDR
 * resolve consumers keep their existing sample-count contract. The decal
 * batch is recorded before those transparent consumers, so it is part of the
 * scene colour they composite over.</p>
 */
public final class ProjectedDecalPass {
    /** Pass that records the caller's projected-decal batch. */
    public static final String PASS_NAME = "ProjectedDecalPass";
    /** Pass that produces the single-sample depth texture the batch samples. */
    public static final String DEPTH_RESOLVE_PASS = "ProjectedDecalDepthResolve";
    /** Pass-owned colour target that continues the scene colour chain. */
    public static final String COLOR_TEXTURE = "projectedDecal.color";
    /** Pass-owned single-sample depth texture sampled by the decal shader. */
    public static final String DEPTH_TEXTURE = "projectedDecal.depth";

    private ProjectedDecalPass() {
    }

    /**
     * Declares the single-sample depth copy/resolve.
     *
     * @param graph        graph receiving the pass
     * @param geometryPass pass whose depth attachment is copied
     * @return the pass builder for the caller to attach its executor
     */
    public static RenderGraph.PassBuilder addDepthResolve(RenderGraph graph, String geometryPass) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(geometryPass, "geometryPass");
        return graph.addPass(DEPTH_RESOLVE_PASS)
                .createDepthTexture(DEPTH_TEXTURE)
                .noClear()
                .dependsOn(geometryPass);
    }

    /**
     * Declares the pass-owned colour target and the decal draw pass.
     *
     * @param graph           graph receiving the pass
     * @param colorSourcePass current scene-colour producer copied into the target
     * @param format          scene colour format
     * @param sampleCount     topology sample count; {@code >1} keeps an MSAA target
     * @return the pass builder for the caller to attach its executor
     */
    public static RenderGraph.PassBuilder addColorPass(RenderGraph graph, String colorSourcePass,
                                                       RenderFormat format, int sampleCount) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(colorSourcePass, "colorSourcePass");
        Objects.requireNonNull(format, "format");
        if (sampleCount < 1) {
            throw new IllegalArgumentException("projected-decal sample count must be positive");
        }
        RenderGraph.PassBuilder pass = graph.addPass(PASS_NAME)
                .noClear()
                .dependsOn(colorSourcePass)
                .dependsOn(DEPTH_RESOLVE_PASS);
        if (sampleCount > 1) {
            // A multisample texture keeps the topology sample count while
            // remaining borrowable by later MSAA scene stages (planar
            // reflections share the current scene colour storage).
            pass.createColorsMS(java.util.List.of(COLOR_TEXTURE),
                    java.util.List.of(format), sampleCount);
        } else {
            pass.createColor(COLOR_TEXTURE, format);
        }
        return pass;
    }

    /**
     * Copies the geometry depth into the pass-owned single-sample depth texture.
     * A multisampled source is resolved by the blit.
     *
     * @param cmd    target command buffer
     * @param source geometry framebuffer owning the raster depth
     * @param target pass-owned depth-only framebuffer
     */
    public static void recordDepthResolve(CommandBuffer cmd, Framebuffer source,
                                          Framebuffer target) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source == target) {
            throw new IllegalArgumentException(
                    "projected-decal depth resolve must differ from its source");
        }
        if (source.width() != target.width() || source.height() != target.height()) {
            throw new IllegalArgumentException(
                    "projected-decal depth resolve extent must match its source");
        }
        if (target.samples() != 1 || !target.depthAttachmentIsTexture()) {
            throw new IllegalArgumentException(
                    "projected-decal depth resolve must own a single-sample depth texture");
        }
        if (source.depthAttachment() <= 0) {
            throw new IllegalArgumentException(
                    "projected-decal depth resolve requires a source depth attachment");
        }
        cmd.blitDepth(source, target)
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height());
    }
}
