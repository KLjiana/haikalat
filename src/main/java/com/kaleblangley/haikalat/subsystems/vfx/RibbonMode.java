package com.kaleblangley.haikalat.subsystems.vfx;

/** Ribbon 控制点的来源；Beam 复用 Ribbon 的排序、宽度曲线和渲染路径。 */
public enum RibbonMode {
    /** 按效果 origin 的运动历史持续采样控制点。 */
    TRAIL,
    /** 由调用方显式提供起点、终点和可选中间控制点。 */
    BEAM
}
