package com.kaleblangley.haikalat.gl.command;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class RenderCommandQueue {
    private final ConcurrentLinkedQueue<RenderCommand> queue = new ConcurrentLinkedQueue<>();

    public void submit(RenderCommand command) {
        queue.add(Objects.requireNonNull(command, "command"));
    }

    public int drain() {
        int executed = 0;
        RenderCommand command;
        while ((command = queue.poll()) != null) {
            command.execute();
            executed++;
        }
        return executed;
    }

    public void clear() {
        queue.clear();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}
