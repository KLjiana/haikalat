package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.graph.RenderGraph;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.*;
import static com.kaleblangley.haikalat.subsystems.render3d.SceneBufferChannel.*;

/** CPU description of the attachments actually produced for one graph. */
record SceneSurfaceLayout(int samples, SceneBufferRequirements requests,
                          List<Attachment> raster, List<Attachment> resolve, Attachment reactive,
                          ReactiveMode reactiveMode, boolean exactDepthOnlyClear) {
    private static final List<SceneBufferChannel> SURFACE_COLORS =
            List.of(NORMAL, VELOCITY, PREVIOUS_SURFACE_DEPTH, VALIDITY);

    enum ReactiveMode { NONE, SCENE, VOLUME_ALIAS }

    record Attachment(SceneBufferChannel channel, String name, String producer,
                      RenderFormat format, int samples, int colorSlot) {
        boolean depthTexture() { return colorSlot == -1; }

        long bytesPerPixel() {
            int bytes = switch (format) {
                case R8 -> 1;
                case RG16F, R32F, DEPTH_COMPONENT24 -> 4;
                default -> throw new IllegalStateException("unexpected surface format " + format);
            };
            return (long) bytes * samples;
        }
    }

    SceneSurfaceLayout {
        raster = List.copyOf(raster);
        resolve = List.copyOf(resolve);
    }

    static SceneSurfaceLayout plan(int samples, SceneBufferRequirements raw,
                                   SceneBufferRequirements single, ReactiveMode reactiveMode) {
        Objects.requireNonNull(reactiveMode, "reactiveMode");
        if (samples < 1 || !raw.isEmpty() && raw.samples() != samples
                || !single.isEmpty() && single.samples() != 1) {
            throw new IllegalArgumentException("surface requests must match their raster or single-sample view");
        }
        if (raw.requires(LINEAR_DEPTH) || single.requires(LINEAR_DEPTH)) {
            throw new IllegalArgumentException("no shared producer for LINEAR_DEPTH");
        }
        if (raw.requires(REACTIVE) || single.requires(REACTIVE) && reactiveMode == ReactiveMode.NONE
                || reactiveMode == ReactiveMode.VOLUME_ALIAS && samples != 1) {
            throw new IllegalArgumentException("reactive requires a late single-sample producer");
        }

        raw = raw.validated();
        single = single.validated();
        var requested = raw.merge(single).withSamples(samples);
        var rasterChannels = EnumSet.noneOf(SceneBufferChannel.class);
        rasterChannels.addAll(requested.channels());
        rasterChannels.remove(REACTIVE);
        var raster = new ArrayList<Attachment>();
        var resolve = new ArrayList<Attachment>();
        if (!rasterChannels.isEmpty()) {
            appendColors(raster, rasterChannels, samples, SCENE_SURFACE_PASS);
            raster.add(new Attachment(DEPTH, samples > 1 ? SCENE_DEPTH_MS : SCENE_DEPTH,
                    SCENE_SURFACE_PASS, RenderFormat.DEPTH_COMPONENT24, samples, -1));
        }
        if (samples > 1 && single.channels().stream().anyMatch(channel -> channel != REACTIVE)) {
            appendColors(resolve, single.channels(), 1, SCENE_SURFACE_RESOLVE_PASS);
            resolve.add(new Attachment(DEPTH, SCENE_DEPTH, SCENE_SURFACE_RESOLVE_PASS,
                    RenderFormat.R32F, 1, resolve.size()));
        }
        Attachment reactive = single.requires(REACTIVE)
                ? new Attachment(REACTIVE, SCENE_REACTIVE, SCENE_REACTIVE_PASS, RenderFormat.R8, 1, 0)
                : null;
        // The depth-only volume guide historically preserves depths all the way to the clear value.
        return new SceneSurfaceLayout(samples, requested, raster, resolve, reactive,
                reactive == null ? ReactiveMode.NONE : reactiveMode,
                resolve.size() == 1 && raw.requires(DEPTH));
    }

    private static void appendColors(List<Attachment> outputs, java.util.Set<SceneBufferChannel> channels,
                                     int samples, String producer) {
        for (var channel : SURFACE_COLORS) {
            if (!channels.contains(channel)) continue;
            String name = switch (channel) {
                case NORMAL -> samples > 1 ? SCENE_NORMAL_MS : SCENE_NORMAL;
                case VELOCITY -> samples > 1 ? SCENE_VELOCITY_MS : SCENE_VELOCITY;
                case PREVIOUS_SURFACE_DEPTH -> samples > 1 ? SCENE_PREVIOUS_DEPTH_MS : SCENE_PREVIOUS_DEPTH;
                case VALIDITY -> samples > 1 ? SCENE_VALIDITY_MS : SCENE_VALIDITY;
                default -> throw new IllegalArgumentException("not a raster color: " + channel);
            };
            RenderFormat format = switch (channel) {
                case NORMAL, VELOCITY -> RenderFormat.RG16F;
                case PREVIOUS_SURFACE_DEPTH -> RenderFormat.R32F;
                case VALIDITY -> RenderFormat.R8;
                default -> throw new IllegalArgumentException("not a raster color: " + channel);
            };
            outputs.add(new Attachment(channel, name, producer, format, samples, outputs.size()));
        }
    }

    Attachment raw(SceneBufferChannel channel) {
        return find(raster, channel);
    }

    Attachment single(SceneBufferChannel channel) {
        if (channel == REACTIVE) return reactive;
        return samples == 1 ? raw(channel) : find(resolve, channel);
    }

    private static Attachment find(List<Attachment> attachments, SceneBufferChannel channel) {
        for (var attachment : attachments) if (attachment.channel() == channel) return attachment;
        return null;
    }

    boolean hasSurface() { return !raster.isEmpty(); }
    boolean hasResolve() { return !resolve.isEmpty(); }

    List<Attachment> rasterColors() {
        return hasSurface() ? raster.subList(0, raster.size() - 1) : List.of();
    }

    void configureRaster(RenderGraph.PassBuilder pass) {
        if (!hasSurface()) throw new IllegalStateException("no raster surface requested");
        var colors = rasterColors();
        if (!colors.isEmpty()) {
            var names = colors.stream().map(Attachment::name).toList();
            var formats = colors.stream().map(Attachment::format).toList();
            if (samples > 1) pass.createColorsMS(names, formats, samples);
            else pass.createColors(names, formats);
        }
        String depth = raw(DEPTH).name();
        if (samples > 1) pass.createDepthTextureMS(depth, samples);
        else pass.createDepthTexture(depth);
        if (colors.isEmpty()) pass.clearDepthOnly();
        else pass.clearColor(0, 0, 0, 0);
    }

    void configureResolve(RenderGraph.PassBuilder pass) {
        if (!hasResolve()) throw new IllegalStateException("no surface resolve requested");
        pass.createColors(resolve.stream().map(Attachment::name).toList(),
                        resolve.stream().map(Attachment::format).toList())
                .noClear().dependsOn(raw(DEPTH).producer());
    }

    record Storage(long rasterBytes, long resolveBytes, long reactiveBytes) {
        static final Storage NONE = new Storage(0, 0, 0);

        long bytes() { return Math.addExact(Math.addExact(rasterBytes, resolveBytes), reactiveBytes); }

        Storage minus(Storage transferred) {
            if (transferred.rasterBytes > rasterBytes || transferred.resolveBytes > resolveBytes
                    || transferred.reactiveBytes > reactiveBytes) {
                throw new IllegalStateException("surface storage transfer exceeds the donor's ownership");
            }
            return new Storage(rasterBytes - transferred.rasterBytes, resolveBytes - transferred.resolveBytes,
                    reactiveBytes - transferred.reactiveBytes);
        }
    }

    Storage storage(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("invalid surface extent");
        long pixels = (long) width * height;
        return new Storage(Math.multiplyExact(pixels, raster.stream().mapToLong(Attachment::bytesPerPixel).sum()),
                Math.multiplyExact(pixels, resolve.stream().mapToLong(Attachment::bytesPerPixel).sum()),
                reactiveMode == ReactiveMode.SCENE ? pixels : 0);
    }

    Storage compatibleStorageFrom(SceneSurfaceLayout donor, Storage previous, Storage requested) {
        // The two full-size surface targets are independently owned by RenderGraph.
        return new Storage(raster.equals(donor.raster) ? previous.rasterBytes() : 0,
                resolve.equals(donor.resolve) ? previous.resolveBytes() : 0,
                previous.reactiveBytes() == requested.reactiveBytes() ? previous.reactiveBytes() : 0);
    }

    /** Omitted outputs are routed beyond the framebuffer's dense color slots. */
    String rasterShaderDefines() {
        StringBuilder defines = new StringBuilder();
        int unused = rasterColors().size();
        for (var channel : SURFACE_COLORS) {
            Attachment output = raw(channel);
            defines.append("#define SCENE_SLOT_").append(channel).append(' ')
                    .append(output == null ? unused++ : output.colorSlot()).append('\n');
        }
        return defines.toString();
    }

    String resolveShaderDefines() {
        StringBuilder defines = new StringBuilder();
        for (var output : resolve) {
            defines.append("#define SCENE_SLOT_").append(output.channel()).append(' ')
                    .append(output.colorSlot()).append('\n');
        }
        if (exactDepthOnlyClear) defines.append("#define SCENE_EXACT_CLEAR\n");
        return defines.toString();
    }
}
