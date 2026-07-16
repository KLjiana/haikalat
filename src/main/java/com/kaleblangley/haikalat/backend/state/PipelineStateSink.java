package com.kaleblangley.haikalat.backend.state;

/** Backend-facing target used when a command buffer flushes its final pending pipeline state. */
public interface PipelineStateSink {
    void viewport(int x, int y, int width, int height);

    void enableBlend(boolean enable);

    void blendFunc(int sourceRgb, int destinationRgb);

    void depthMask(boolean write);

    void enableDepthTest(boolean enable);

    void enableCullFace(boolean enable);

    void enableScissor(boolean enable);

    void scissor(int x, int y, int width, int height);

    void enableFramebufferSrgb(boolean enable);

    void clearColor(float red, float green, float blue, float alpha);
}
