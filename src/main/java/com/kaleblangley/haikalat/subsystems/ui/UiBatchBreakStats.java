package com.kaleblangley.haikalat.subsystems.ui;

/** UI 相邻 primitive 无法继续合并时的互斥原因计数。 */
public record UiBatchBreakStats(long orderBarriers, long shaderChanges,
                                long textureChanges, long samplerChanges,
                                long blendChanges, long clipChanges) {
    public static final UiBatchBreakStats EMPTY = new UiBatchBreakStats(0, 0, 0, 0, 0, 0);

    public UiBatchBreakStats {
        if (orderBarriers < 0 || shaderChanges < 0 || textureChanges < 0
                || samplerChanges < 0 || blendChanges < 0 || clipChanges < 0) {
            throw new IllegalArgumentException("UI batch-break statistics cannot be negative");
        }
    }

    /** @return 除首个 batch 外，由全部原因形成的新 batch 数量 */
    public long total() {
        return Math.addExact(Math.addExact(Math.addExact(orderBarriers, shaderChanges),
                        Math.addExact(textureChanges, samplerChanges)),
                Math.addExact(blendChanges, clipChanges));
    }
}
