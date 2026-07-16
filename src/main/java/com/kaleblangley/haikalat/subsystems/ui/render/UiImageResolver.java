package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.UiImageId;

import java.util.Objects;
import java.util.Optional;

/** 把不含 backend 对象的 UI 图片标识解析为快照可记录的纹理区域。 */
@FunctionalInterface
public interface UiImageResolver {
    /** 返回图片当前可用的纹理区域；资源尚未就绪时返回空。 */
    Optional<UiImageRegion> resolve(UiImageId imageId);

    /** 返回始终报告资源尚未就绪的解析器。 */
    static UiImageResolver empty() {
        return imageId -> {
            Objects.requireNonNull(imageId, "imageId");
            return Optional.empty();
        };
    }
}
