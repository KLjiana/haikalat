package com.kaleblangley.haikalat.subsystems.windowing;

/**
 * 渲染窗口抽象。子系统通过此接口获取窗口尺寸，不依赖具体窗口实现。
 */
public interface RenderWindow {
    /** 帧缓冲宽度（像素） */
    int width();
    /** 帧缓冲高度（像素） */
    int height();
}
