package com.kaleblangley.haikalat.backend.framebuffer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class RenderTargetManager implements AutoCloseable {
    private final Map<String, FramebufferDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, Framebuffer> targets = new LinkedHashMap<>();
    private final Function<FramebufferDescriptor, Framebuffer> factory;
    private boolean closed;

    public RenderTargetManager() {
        this(Framebuffer::fromDescriptor);
    }

    public RenderTargetManager(Function<FramebufferDescriptor, Framebuffer> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public Framebuffer create(String name, FramebufferDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        closeTarget(name);
        descriptors.put(name, descriptor);
        Framebuffer target = factory.apply(descriptor);
        targets.put(name, target);
        return target;
    }

    public Framebuffer get(String name) {
        ensureOpen();
        return targets.get(name);
    }

    public FramebufferDescriptor descriptor(String name) {
        ensureOpen();
        return descriptors.get(name);
    }

    public void resize(int width, int height) {
        ensureOpen();
        closeTargets();
        descriptors.replaceAll((name, descriptor) -> descriptor.resized(width, height));
        for (Map.Entry<String, FramebufferDescriptor> entry : descriptors.entrySet()) {
            targets.put(entry.getKey(), factory.apply(entry.getValue()));
        }
    }

    public void clear() {
        ensureOpen();
        closeTargets();
        descriptors.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closeTargets();
        descriptors.clear();
        closed = true;
    }

    private void closeTarget(String name) {
        Framebuffer old = targets.remove(name);
        if (old != null) {
            old.close();
        }
    }

    private void closeTargets() {
        for (Framebuffer target : targets.values()) {
            target.close();
        }
        targets.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RenderTargetManager is closed");
        }
    }
}
