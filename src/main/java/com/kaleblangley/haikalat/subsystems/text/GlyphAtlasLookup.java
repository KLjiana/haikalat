package com.kaleblangley.haikalat.subsystems.text;

import java.util.Objects;
import java.util.Optional;

/** atlas 查询结果：已就绪、等待上传或暂时受 in-flight generation 阻塞。 */
public final class GlyphAtlasLookup {
    public enum Status {
        READY,
        UPLOAD_REQUIRED,
        CAPACITY_BLOCKED
    }

    private final GlyphKey key;
    private final Status status;
    private final GlyphAtlasGlyph glyph;
    private final GlyphUploadRequest uploadRequest;
    private final String blockedReason;

    private GlyphAtlasLookup(GlyphKey key, Status status, GlyphAtlasGlyph glyph,
                             GlyphUploadRequest uploadRequest, String blockedReason) {
        this.key = Objects.requireNonNull(key, "key");
        this.status = Objects.requireNonNull(status, "status");
        this.glyph = glyph;
        this.uploadRequest = uploadRequest;
        this.blockedReason = blockedReason;
    }

    static GlyphAtlasLookup ready(GlyphAtlasGlyph glyph) {
        return new GlyphAtlasLookup(glyph.key(), Status.READY, glyph, null, null);
    }

    static GlyphAtlasLookup uploadRequired(GlyphUploadRequest request) {
        return new GlyphAtlasLookup(request.key(), Status.UPLOAD_REQUIRED, null, request, null);
    }

    static GlyphAtlasLookup blocked(GlyphKey key, String reason) {
        return new GlyphAtlasLookup(key, Status.CAPACITY_BLOCKED, null, null,
                Objects.requireNonNull(reason, "reason"));
    }

    public GlyphKey key() {
        return key;
    }

    public Status status() {
        return status;
    }

    public boolean ready() {
        return status == Status.READY;
    }

    public Optional<GlyphAtlasGlyph> glyph() {
        return Optional.ofNullable(glyph);
    }

    public Optional<GlyphUploadRequest> uploadRequest() {
        return Optional.ofNullable(uploadRequest);
    }

    public Optional<String> blockedReason() {
        return Optional.ofNullable(blockedReason);
    }
}
