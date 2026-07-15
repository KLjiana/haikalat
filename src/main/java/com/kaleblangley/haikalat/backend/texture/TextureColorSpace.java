package com.kaleblangley.haikalat.backend.texture;

/**
 * 描述纹理存储值在采样时使用的颜色空间。
 *
 * <p>{@link #LINEAR} 保持存储值不变，适用于法线、深度、遮罩等数据纹理；
 * {@link #SRGB} 让 OpenGL 在采样 RGB 通道时自动解码到线性空间。</p>
 */
public enum TextureColorSpace {
    LINEAR,
    SRGB
}
