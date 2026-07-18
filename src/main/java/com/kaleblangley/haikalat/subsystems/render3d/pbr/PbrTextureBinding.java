package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;

import java.util.Objects;

/** 把 PBR image texture 与独立 sampler 组合为一个不可变绑定。 */
public record PbrTextureBinding(Texture2D texture, Sampler sampler) {
    public PbrTextureBinding {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(sampler, "sampler");
    }
}
