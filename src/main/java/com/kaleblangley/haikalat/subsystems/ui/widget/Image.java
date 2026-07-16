package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiImageId;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

import java.util.Objects;

/** 不持有 backend texture 的 retained 图片节点。 */
public final class Image extends UiNode {
    public enum ObjectFit { FILL, CONTAIN, COVER, NONE }

    private UiImageId imageId;
    private ObjectFit objectFit = ObjectFit.CONTAIN;
    private UiColor tint = UiColor.WHITE;

    public Image(UiImageId imageId) {
        this.imageId = Objects.requireNonNull(imageId, "imageId");
        semantics(UiSemanticRole.IMAGE, "", "");
    }

    public UiImageId imageId() { return imageId; }
    public Image imageId(UiImageId value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "imageId");
        if (!imageId.equals(value)) {
            imageId = value;
            markDirty(UiDirtyFlag.PAINT);
        }
        return this;
    }
    public ObjectFit objectFit() { return objectFit; }
    public Image objectFit(ObjectFit value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "objectFit");
        if (objectFit != value) {
            objectFit = value;
            markDirty(UiDirtyFlag.PAINT);
        }
        return this;
    }
    public UiColor tint() { return tint; }
    public Image tint(UiColor value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "tint");
        if (!tint.equals(value)) {
            tint = value;
            markDirty(UiDirtyFlag.PAINT);
        }
        return this;
    }
}
