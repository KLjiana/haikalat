package com.kaleblangley.haikalat.subsystems.ui;

/** 控制仍走正常 display list 的 UI 诊断绘制。 */
public record UiDebugOptions(boolean layoutBounds, boolean clipRectangles,
                             boolean hitTestPath, boolean focusAndCapture,
                             boolean dirtyReasons, boolean atlasOccupancy,
                             boolean batchBoundaries) {
    public static final UiDebugOptions NONE = new UiDebugOptions(false, false, false,
            false, false, false, false);
}
