package com.kaleblangley.haikalat.gl;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RenderLoop {
    private final RenderCommandQueue commandQueue = new RenderCommandQueue();
    private final RenderStatistics statistics = new RenderStatistics();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final RenderSettings settings;

    public RenderLoop(RenderSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public RenderSettings settings() {
        return settings;
    }

    public RenderStatistics statistics() {
        return statistics;
    }

    public void submit(RenderCommand command) {
        commandQueue.submit(command);
    }

    public int drainCommands() {
        return commandQueue.drain();
    }

    public void beginFrame() {
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
