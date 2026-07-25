package com.kaleblangley.haikalat.subsystems.ui.animation;

/** UI 动画时间轴的确定性累计与当前状态快照。 */
public record UiAnimationDiagnostics(long updates, long started, long completed,
                                     long cancelled, long replaced, int active,
                                     int paused, int activeVisual, int activeLayout,
                                     int peakActive) {
    public UiAnimationDiagnostics {
        if (updates < 0L || started < 0L || completed < 0L || cancelled < 0L
                || replaced < 0L || active < 0 || paused < 0 || activeVisual < 0
                || activeLayout < 0 || peakActive < active
                || paused > active || activeVisual + activeLayout != active
                || replaced > cancelled) {
            throw new IllegalArgumentException("UI animation diagnostics are inconsistent");
        }
    }
}
