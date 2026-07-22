package com.kaleblangley.haikalat.backend.state;

/** 命令缓冲区提交最终待处理管线状态时使用的后端写入目标。 */
public interface PipelineStateSink {
    void viewport(int x, int y, int width, int height);

    void enableBlend(boolean enable);

    void blendFunc(int sourceRgb, int destinationRgb);

    void depthMask(boolean write);

    void enableDepthTest(boolean enable);

    void enableCullFace(boolean enable);

    void frontFace(int winding);

    void enableScissor(boolean enable);

    void scissor(int x, int y, int width, int height);

    void enableFramebufferSrgb(boolean enable);

    void clearColor(float red, float green, float blue, float alpha);
}
