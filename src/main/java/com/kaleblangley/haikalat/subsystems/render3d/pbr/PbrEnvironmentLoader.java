package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.texture.Texture2D;

import java.util.Objects;

/** 在当前 render thread/context 中解码 HDR 并执行一次 GPU IBL 预计算。 */
public final class PbrEnvironmentLoader {
    private PbrEnvironmentLoader() {
    }

    public static PbrEnvironment load(Class<?> anchor, String hdrResource,
                                      PbrEnvironmentSettings settings) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(hdrResource, "hdrResource");
        Objects.requireNonNull(settings, "settings");
        Texture2D source = Texture2D.fromHdrResource(anchor, hdrResource, false);
        try {
            return EnvironmentPreprocessor.preprocess(source, settings);
        } finally {
            source.close();
        }
    }
}
