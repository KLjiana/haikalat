package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.core.BlendMode;

import java.util.List;
import java.util.Objects;

/**
 * Non-GL material definition for asset and scene configuration.
 * Runtime {@code Material} objects are built later from these shader/texture references.
 */
public record MaterialDef(
        String shader,
        List<TextureBinding> textures,
        BlendMode blendMode,
        boolean depthTest
) {
    public MaterialDef {
        shader = Objects.requireNonNull(shader, "shader");
        textures = List.copyOf(Objects.requireNonNull(textures, "textures"));
        blendMode = Objects.requireNonNull(blendMode, "blendMode");
    }

    public static MaterialDef of(String shader) {
        return new MaterialDef(shader, List.of(), BlendMode.OPAQUE, true);
    }

    public record TextureBinding(int unit, String samplerName, String texture, String sampler) {
        public TextureBinding {
            if (unit < 0) {
                throw new IllegalArgumentException("unit must be non-negative");
            }
            samplerName = Objects.requireNonNull(samplerName, "samplerName");
            texture = Objects.requireNonNull(texture, "texture");
        }

        public TextureBinding(int unit, String samplerName, String texture) {
            this(unit, samplerName, texture, null);
        }
    }
}
