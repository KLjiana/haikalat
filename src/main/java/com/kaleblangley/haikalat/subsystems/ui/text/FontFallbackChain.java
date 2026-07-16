package com.kaleblangley.haikalat.subsystems.ui.text;

import java.util.List;
import java.util.Objects;

/**
 * 与某一字体 generation 绑定的显式、稳定 fallback 顺序。
 */
public record FontFallbackChain(FontGeneration fontGeneration, List<FontFaceId> faceIds) {
    public FontFallbackChain {
        Objects.requireNonNull(fontGeneration, "fontGeneration");
        faceIds = List.copyOf(Objects.requireNonNull(faceIds, "faceIds"));
        if (faceIds.isEmpty()) {
            throw new IllegalArgumentException("Fallback chain must contain at least one face");
        }
        for (int index = 0; index < faceIds.size(); index++) {
            FontFaceId faceId = Objects.requireNonNull(faceIds.get(index), "faceId");
            if (faceIds.subList(0, index).contains(faceId)) {
                throw new IllegalArgumentException("Fallback chain contains duplicate face " + faceId.value());
            }
        }
    }
}
