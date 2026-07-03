package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.GlDebug;
import com.kaleblangley.haikalat.gl.RenderSettings;
import com.kaleblangley.haikalat.gl.RenderStatistics;
import com.kaleblangley.haikalat.gl.buffer.UploadQueue;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;
import com.kaleblangley.haikalat.gl.command.RenderCommand;
import com.kaleblangley.haikalat.gl.command.RenderCommandQueue;
import com.kaleblangley.haikalat.gl.command.RenderDevice;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RenderLoop {
    private final RenderCommandQueue commandQueue = new RenderCommandQueue();
    private final RenderStatistics statistics = new RenderStatistics();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final RenderSettings settings;
    private final RenderDevice device;
    private final UploadQueue uploadQueue = new UploadQueue();

    public RenderLoop(RenderSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.device = new RenderDevice();
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

    public UploadQueue uploadQueue() {
        return uploadQueue;
    }

    public void submit(RenderCommand command) {
        commandQueue.submit(command);
    }

    public void submit(CommandBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        executeCommandBuffer(buffer);
    }

    public int drainCommands() {
        return commandQueue.drain();
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

    public void clearCommands() {
        commandQueue.clear();
    }
}
