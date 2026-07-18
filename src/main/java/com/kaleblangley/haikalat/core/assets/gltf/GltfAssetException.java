package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.util.Objects;

/** glTF 读取、解析、解码和上传阶段的结构化异常。 */
public final class GltfAssetException extends RuntimeException {
    private final AssetRef source;
    private final Phase phase;
    private final String location;
    private final String dependentUri;

    public GltfAssetException(AssetRef source, Phase phase, String location, String message) {
        this(source, phase, location, null, message, null);
    }

    public GltfAssetException(AssetRef source, Phase phase, String location,
                              String dependentUri, String message, Throwable cause) {
        super(format(source, phase, location, dependentUri, message), cause);
        this.source = Objects.requireNonNull(source, "source");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.location = location == null ? "" : location;
        this.dependentUri = dependentUri;
    }

    public AssetRef source() { return source; }
    public Phase phase() { return phase; }
    public String location() { return location; }
    public String dependentUri() { return dependentUri; }

    private static String format(AssetRef source, Phase phase, String location,
                                 String dependentUri, String message) {
        StringBuilder result = new StringBuilder("Failed to ")
                .append(phase.name().toLowerCase(java.util.Locale.ROOT))
                .append(" glTF ").append(source.path());
        if (location != null && !location.isBlank()) result.append(" at ").append(location);
        if (dependentUri != null) result.append(" (URI ").append(dependentUri).append(')');
        return result.append(": ").append(message).toString();
    }

    public enum Phase { READ, PARSE, RESOLVE, DECODE, UPLOAD, INSTANTIATE }
}
