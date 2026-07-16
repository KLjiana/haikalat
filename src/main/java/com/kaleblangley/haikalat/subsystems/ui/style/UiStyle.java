package com.kaleblangley.haikalat.subsystems.ui.style;

import java.util.Objects;

/** Yoga 细节不可见的不可变布局样式。 */
public record UiStyle(
        UiLength width,
        UiLength height,
        UiLength minWidth,
        UiLength minHeight,
        UiLength maxWidth,
        UiLength maxHeight,
        UiInsets margin,
        UiInsets padding,
        FlexDirection flexDirection,
        JustifyContent justifyContent,
        AlignItems alignItems,
        AlignItems alignSelf,
        PositionType positionType,
        Overflow overflow,
        float flexGrow,
        float flexShrink,
        float gap
) {
    public enum FlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }
    public enum JustifyContent { FLEX_START, CENTER, FLEX_END, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }
    public enum AlignItems { AUTO, FLEX_START, CENTER, FLEX_END, STRETCH, BASELINE, SPACE_BETWEEN, SPACE_AROUND }
    public enum PositionType { RELATIVE, ABSOLUTE }
    public enum Overflow { VISIBLE, HIDDEN, SCROLL }

    public UiStyle {
        if (width == null || height == null || minWidth == null || minHeight == null
                || maxWidth == null || maxHeight == null || margin == null || padding == null
                || flexDirection == null || justifyContent == null || alignItems == null
                || alignSelf == null || positionType == null || overflow == null) {
            throw new NullPointerException("UI style field");
        }
        if (!Float.isFinite(flexGrow) || flexGrow < 0.0f
                || !Float.isFinite(flexShrink) || flexShrink < 0.0f
                || !Float.isFinite(gap) || gap < 0.0f) {
            throw new IllegalArgumentException("flex values and gap must be finite and non-negative");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static UiStyle defaults() {
        return builder().build();
    }

    /** 返回只替换 margin、其余布局字段保持不变的样式。 */
    public UiStyle withMargin(UiInsets value) {
        return new UiStyle(width, height, minWidth, minHeight, maxWidth, maxHeight,
                Objects.requireNonNull(value, "margin"), padding,
                flexDirection, justifyContent, alignItems, alignSelf, positionType,
                overflow, flexGrow, flexShrink, gap);
    }

    public static final class Builder {
        private UiLength width = UiLength.AUTO;
        private UiLength height = UiLength.AUTO;
        private UiLength minWidth = UiLength.AUTO;
        private UiLength minHeight = UiLength.AUTO;
        private UiLength maxWidth = UiLength.AUTO;
        private UiLength maxHeight = UiLength.AUTO;
        private UiInsets margin = UiInsets.ZERO;
        private UiInsets padding = UiInsets.ZERO;
        private FlexDirection flexDirection = FlexDirection.COLUMN;
        private JustifyContent justifyContent = JustifyContent.FLEX_START;
        private AlignItems alignItems = AlignItems.STRETCH;
        private AlignItems alignSelf = AlignItems.AUTO;
        private PositionType positionType = PositionType.RELATIVE;
        private Overflow overflow = Overflow.VISIBLE;
        private float flexGrow;
        private float flexShrink = 1.0f;
        private float gap;

        public Builder width(UiLength value) { width = value; return this; }
        public Builder height(UiLength value) { height = value; return this; }
        public Builder minWidth(UiLength value) { minWidth = value; return this; }
        public Builder minHeight(UiLength value) { minHeight = value; return this; }
        public Builder maxWidth(UiLength value) { maxWidth = value; return this; }
        public Builder maxHeight(UiLength value) { maxHeight = value; return this; }
        public Builder margin(UiInsets value) { margin = value; return this; }
        public Builder padding(UiInsets value) { padding = value; return this; }
        public Builder flexDirection(FlexDirection value) { flexDirection = value; return this; }
        public Builder justifyContent(JustifyContent value) { justifyContent = value; return this; }
        public Builder alignItems(AlignItems value) { alignItems = value; return this; }
        public Builder alignSelf(AlignItems value) { alignSelf = value; return this; }
        public Builder positionType(PositionType value) { positionType = value; return this; }
        public Builder overflow(Overflow value) { overflow = value; return this; }
        public Builder flexGrow(float value) { flexGrow = value; return this; }
        public Builder flexShrink(float value) { flexShrink = value; return this; }
        public Builder gap(float value) { gap = value; return this; }

        public UiStyle build() {
            return new UiStyle(width, height, minWidth, minHeight, maxWidth, maxHeight,
                    margin, padding, flexDirection, justifyContent, alignItems, alignSelf,
                    positionType, overflow, flexGrow, flexShrink, gap);
        }
    }
}
