package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.GlDebug;
import com.kaleblangley.haikalat.gl.RenderSettings;
import com.kaleblangley.haikalat.gl.RenderStatistics;
import com.kaleblangley.haikalat.gl.buffer.UploadSystem;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;
import com.kaleblangley.haikalat.gl.command.GlRenderDevice;
import com.kaleblangley.haikalat.gl.command.RenderDevice;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RenderLoop {
    private final RenderStatistics statistics = new RenderStatistics();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final RenderSettings settings;
    private final RenderDevice device;
    private final UploadSystem uploadQueue = new UploadSystem();

    public RenderLoop(RenderSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.device = new GlRenderDevice();
    }

    public RenderSettings settings() {
        return settings;
    }

    public RenderStatistics statistics() {
        return statistics;
    }

    public RenderDevice device() {
        return device;
    }

    public UploadSystem uploadQueue() {
        return uploadQueue;
    }

    public void submit(CommandBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        executeCommandBuffer(buffer);
    }

    public void executeCommandBuffer(CommandBuffer buffer) {
        device.execute(buffer);
    }

    public void beginFrame() {
        uploadQueue.flush();
        statistics.beginFrame();
    }

    public void endFrame() {
        statistics.endFrame();
        if (settings.debugErrors()) {
            GlDebug.checkError("RenderLoop.endFrame");
        }
    }

    public void requestStop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }
}
