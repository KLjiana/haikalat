package com.kaleblangley.haikalat.subsystems.render3d.preview;

import java.util.List;
import java.util.Objects;

/** 不包含资源 owner 或 native id 的预览源元数据。 */
public record PreviewSourceDescription(PreviewSourceKey key, String displayName,
                                       String producerPass,
                                       List<String> downstreamGraphDependencies,
                                       int width, int height, int mipCount, int sampleCount,
                                       String format, StorageKind storageKind,
                                       PreviewAspect aspect, boolean directlySampled,
                                       boolean requiresResolve, boolean supportsMip,
                                       boolean supportsFace, long estimatedBytes,
                                       Availability availability, String reason) {
    public PreviewSourceDescription {
        key = Objects.requireNonNull(key, "key");
        displayName = requireText(displayName, "displayName");
        producerPass = Objects.requireNonNullElse(producerPass, "");
        downstreamGraphDependencies = List.copyOf(Objects.requireNonNull(
                downstreamGraphDependencies, "downstreamGraphDependencies"));
        if (width < 0 || height < 0 || mipCount <= 0 || sampleCount <= 0 || estimatedBytes < 0L) {
            throw new IllegalArgumentException("preview dimensions/counts/bytes are invalid");
        }
        format = requireText(format, "format");
        storageKind = Objects.requireNonNull(storageKind, "storageKind");
        aspect = Objects.requireNonNull(aspect, "aspect");
        availability = Objects.requireNonNull(availability, "availability");
        reason = Objects.requireNonNullElse(reason, "");
        if (availability != Availability.AVAILABLE && reason.isBlank()) {
            throw new IllegalArgumentException("unavailable preview source requires a reason");
        }
    }

    public boolean previewable() { return availability == Availability.AVAILABLE; }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public enum StorageKind { TEXTURE_2D, RENDERBUFFER, CUBEMAP, BACKBUFFER, EXTERNAL }
    public enum Availability { AVAILABLE, UNSUPPORTED, STALE }
}
