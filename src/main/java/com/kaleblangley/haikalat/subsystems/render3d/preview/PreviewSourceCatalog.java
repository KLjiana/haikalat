package com.kaleblangley.haikalat.subsystems.render3d.preview;

import com.kaleblangley.haikalat.core.graph.RenderGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 从 RenderGraph 纯值描述构建、不解析 live GL handle 的预览目录。 */
public final class PreviewSourceCatalog {
    private final long pipelineGeneration;
    private final List<PreviewSourceDescription> sources;
    private final Map<PreviewSourceKey, PreviewSourceDescription> byKey;

    private PreviewSourceCatalog(long pipelineGeneration,
                                 List<PreviewSourceDescription> sources) {
        this.pipelineGeneration = pipelineGeneration;
        this.sources = List.copyOf(sources);
        Map<PreviewSourceKey, PreviewSourceDescription> index = new LinkedHashMap<>();
        for (PreviewSourceDescription source : sources) index.put(source.key(), source);
        byKey = Map.copyOf(index);
    }

    public static PreviewSourceCatalog from(RenderGraph.Description graph,
                                            long pipelineGeneration,
                                            List<RegisteredCube> registeredCubes) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(registeredCubes, "registeredCubes");
        if (pipelineGeneration <= 0L) {
            throw new IllegalArgumentException("pipelineGeneration must be positive");
        }
        List<PreviewSourceDescription> result = new ArrayList<>();
        for (RenderGraph.PassDescription pass : graph.passes()) {
            List<String> downstream = graph.passes().stream()
                    .filter(candidate -> candidate.directDependencies().contains(pass.name()))
                    .map(RenderGraph.PassDescription::name).toList();
            if (pass.targetKind() == RenderGraph.TargetKind.BACKBUFFER) {
                result.add(unavailableGraph(pipelineGeneration, pass, "backbuffer",
                        PreviewAspect.COLOR, PreviewSourceDescription.StorageKind.BACKBUFFER,
                        downstream, "default framebuffer/backbuffer is metadata-only"));
                continue;
            }
            if (pass.targetKind() == RenderGraph.TargetKind.EXTERNAL) {
                result.add(unavailableGraph(pipelineGeneration, pass, "external",
                        PreviewAspect.COLOR, PreviewSourceDescription.StorageKind.EXTERNAL,
                        downstream, "external target is not explicitly registered"));
                continue;
            }
            for (RenderGraph.AttachmentDescription attachment : pass.colorAttachments()) {
                result.add(graphAttachment(pipelineGeneration, pass, attachment,
                        PreviewAspect.COLOR, downstream));
            }
            if (pass.depthAttachment() != null) {
                result.add(graphAttachment(pipelineGeneration, pass, pass.depthAttachment(),
                        PreviewAspect.DEPTH, downstream));
            }
        }
        for (RegisteredCube cube : registeredCubes) result.add(cubeDescription(
                pipelineGeneration, cube));
        return new PreviewSourceCatalog(pipelineGeneration, result);
    }

    public long pipelineGeneration() { return pipelineGeneration; }
    public List<PreviewSourceDescription> sources() { return sources; }

    public Optional<PreviewSourceDescription> find(PreviewSourceKey key) {
        return Optional.ofNullable(byKey.get(Objects.requireNonNull(key, "key")));
    }

    public PreviewSourceDescription.Availability selectionAvailability(PreviewSourceKey key) {
        if (key.pipelineGeneration() != pipelineGeneration) {
            return PreviewSourceDescription.Availability.STALE;
        }
        PreviewSourceDescription source = byKey.get(key);
        return source == null ? PreviewSourceDescription.Availability.STALE
                : source.availability();
    }

    public static long estimateBytes(String format, int width, int height, int samples) {
        if (width < 0 || height < 0 || samples <= 0) {
            throw new IllegalArgumentException("invalid preview storage dimensions");
        }
        int bytesPerPixel = switch (Objects.requireNonNull(format, "format")) {
            case "R16F" -> 2;
            case "RG16F", "RGBA8", "SRGB8_ALPHA8", "DEPTH_COMPONENT",
                 "DEPTH_COMPONENT24", "DEPTH24_STENCIL8" -> 4;
            case "RGBA16F", "RG32F" -> 8;
            default -> 0;
        };
        return Math.multiplyExact(Math.multiplyExact((long) width, height),
                Math.multiplyExact((long) samples, bytesPerPixel));
    }

    private static PreviewSourceDescription graphAttachment(long generation,
                                                              RenderGraph.PassDescription pass,
                                                              RenderGraph.AttachmentDescription attachment,
                                                              PreviewAspect aspect,
                                                              List<String> downstream) {
        boolean supported = supportedFormat(attachment.format(), aspect);
        boolean renderbuffer = attachment.storageKind() == RenderGraph.StorageKind.RENDERBUFFER;
        PreviewSourceDescription.StorageKind storage = renderbuffer
                ? PreviewSourceDescription.StorageKind.RENDERBUFFER
                : PreviewSourceDescription.StorageKind.TEXTURE_2D;
        String reason = supported ? "" : "unsupported attachment format " + attachment.format();
        return new PreviewSourceDescription(
                PreviewSourceKey.graph(generation, pass.name(), attachment.logicalName(), aspect),
                pass.name() + " / " + attachment.logicalName(), pass.name(), downstream,
                pass.width(), pass.height(), 1, pass.samples(), attachment.format(), storage,
                aspect, supported && !renderbuffer, supported && renderbuffer,
                false, false, estimateBytes(attachment.format(), pass.width(), pass.height(),
                pass.samples()), supported ? PreviewSourceDescription.Availability.AVAILABLE
                : PreviewSourceDescription.Availability.UNSUPPORTED, reason);
    }

    private static PreviewSourceDescription unavailableGraph(long generation,
                                                               RenderGraph.PassDescription pass,
                                                               String attachment,
                                                               PreviewAspect aspect,
                                                               PreviewSourceDescription.StorageKind storage,
                                                               List<String> downstream,
                                                               String reason) {
        return new PreviewSourceDescription(
                PreviewSourceKey.graph(generation, pass.name(), attachment, aspect),
                pass.name() + " / " + attachment, pass.name(), downstream,
                pass.width(), pass.height(), 1, Math.max(1, pass.samples()), "UNKNOWN",
                storage, aspect, false, false, false, false, 0L,
                PreviewSourceDescription.Availability.UNSUPPORTED, reason);
    }

    private static PreviewSourceDescription cubeDescription(long generation,
                                                              RegisteredCube cube) {
        boolean supported = cube.format().equals("RGBA16F");
        return new PreviewSourceDescription(
                PreviewSourceKey.cube(generation, cube.name(), cube.ownerGeneration()),
                cube.displayName(), "", List.of(), cube.size(), cube.size(), cube.mipCount(),
                1, cube.format(), PreviewSourceDescription.StorageKind.CUBEMAP,
                PreviewAspect.CUBE, supported, false, cube.mipCount() > 1, true,
                cube.estimatedBytes(), supported ? PreviewSourceDescription.Availability.AVAILABLE
                : PreviewSourceDescription.Availability.UNSUPPORTED,
                supported ? "" : "unsupported cubemap format " + cube.format());
    }

    private static boolean supportedFormat(String format, PreviewAspect aspect) {
        if (aspect == PreviewAspect.DEPTH) {
            return format.equals("DEPTH_COMPONENT") || format.equals("DEPTH_COMPONENT24")
                    || format.equals("DEPTH24_STENCIL8");
        }
        return switch (format) {
            case "RGBA8", "SRGB8_ALPHA8", "RGBA16F", "R16F", "RG16F", "RG32F" -> true;
            default -> false;
        };
    }

    /** cubemap owner 注册时复制的纯值 metadata。 */
    public record RegisteredCube(String name, String displayName, long ownerGeneration,
                                 int size, int mipCount, String format, long estimatedBytes) {
        public RegisteredCube {
            name = requireText(name, "name");
            displayName = requireText(displayName, "displayName");
            format = requireText(format, "format");
            if (ownerGeneration <= 0L || size <= 0 || mipCount <= 0 || estimatedBytes < 0L) {
                throw new IllegalArgumentException("invalid registered cubemap metadata");
            }
        }

        private static String requireText(String value, String name) {
            value = Objects.requireNonNull(value, name).trim();
            if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }
}
