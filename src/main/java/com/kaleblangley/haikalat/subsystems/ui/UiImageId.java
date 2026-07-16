package com.kaleblangley.haikalat.subsystems.ui;

/** UI 图片资源的稳定非负标识；不向控件暴露 OpenGL texture id。 */
public record UiImageId(long value) {
    public UiImageId {
        if (value < 0L) throw new IllegalArgumentException("UI image id must be non-negative");
    }
}
