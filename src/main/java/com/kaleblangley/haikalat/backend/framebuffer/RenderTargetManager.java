package com.kaleblangley.haikalat.backend.framebuffer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owns named framebuffer generations.
 *
 * <p>Replacement and resize allocate a complete candidate map before retiring the active
 * targets. A failed candidate therefore cannot leave callers with a partially rebuilt set.</p>
 */
public final class RenderTargetManager implements AutoCloseable {
    private final Map<String, FramebufferDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, Framebuffer> targets = new LinkedHashMap<>();
    private boolean closed;

    public Framebuffer create(String name, FramebufferDescriptor descriptor) {
        ensureOpen();
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        // Allocate the replacement before touching the active target. A failed allocation
        // leaves the previous framebuffer usable.
        Framebuffer candidate = Framebuffer.fromDescriptor(descriptor);
        Framebuffer previous = targets.put(name, candidate);
        descriptors.put(name, descriptor);
        if (previous != null) previous.close();
        return candidate;
    }

    /**
     * Creates a framebuffer that owns its color attachments but borrows the
     * depth texture of {@code depthSource}.  The source must belong to this
     * manager generation and is never closed by the returned target.
     */
    public Framebuffer createShared(String name, FramebufferDescriptor descriptor,
                                    Framebuffer depthSource) {
        return createShared(name, descriptor, null, depthSource);
    }

    public Framebuffer createShared(String name, FramebufferDescriptor descriptor,
                                    Framebuffer colorSource, Framebuffer depthSource) {
        return createShared(name, descriptor, colorSource, depthSource, null);
    }

    public Framebuffer createShared(String name, FramebufferDescriptor descriptor,
                                    Framebuffer colorSource, Framebuffer depthSource,
                                    java.util.List<Integer> colorIndices) {
        ensureOpen();
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Framebuffer candidate = Framebuffer.fromDescriptorSharingAttachments(descriptor, colorSource, depthSource, colorIndices);
        Framebuffer previous = targets.put(name, candidate);
        descriptors.put(name, descriptor);
        if (previous != null) previous.close();
        return candidate;
    }

    public Framebuffer get(String name) {
        ensureOpen();
        return targets.get(name);
    }

    /** Replaces a named subset atomically; unrelated targets keep their storage and identity. */
    public void replaceSelected(Map<String, FramebufferDescriptor> replacements) {
        ensureOpen();
        Map<String, Framebuffer> candidates = new LinkedHashMap<>();
        for (String name : replacements.keySet()) {
            if (!targets.containsKey(name)) throw new IllegalArgumentException("unknown target: " + name);
        }
        try {
            for (var entry : replacements.entrySet())
                candidates.put(entry.getKey(), Framebuffer.fromDescriptor(entry.getValue()));
        } catch (RuntimeException | Error failure) {
            closeCandidateTargets(candidates, failure);
            throw failure;
        }
        Map<String, Framebuffer> retired = new LinkedHashMap<>();
        candidates.forEach((name, target) -> retired.put(name, targets.put(name, target)));
        descriptors.putAll(replacements);
        RuntimeException failure = closeTargets(retired);
        if (failure != null) throw failure;
    }

    public FramebufferDescriptor descriptor(String name) {
        ensureOpen();
        return descriptors.get(name);
    }

    public void resize(int width, int height) {
        ensureOpen();
        Map<String, FramebufferDescriptor> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, FramebufferDescriptor> entry : descriptors.entrySet()) {
            candidates.put(entry.getKey(), entry.getValue().resized(width, height));
        }
        replaceAll(candidates);
    }

    public void clear() {
        ensureOpen();
        RuntimeException failure = closeTargets();
        descriptors.clear();
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        RuntimeException failure = closeTargets();
        descriptors.clear();
        closed = true;
        if (failure != null) throw failure;
    }

    private void replaceAll(Map<String, FramebufferDescriptor> nextDescriptors) {
        Map<String, Framebuffer> candidates = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, FramebufferDescriptor> entry : nextDescriptors.entrySet()) {
                candidates.put(entry.getKey(), Framebuffer.fromDescriptor(entry.getValue()));
            }
        } catch (RuntimeException | Error failure) {
            closeCandidateTargets(candidates, failure);
            throw failure;
        }
        Map<String, Framebuffer> previous = new LinkedHashMap<>(targets);
        targets.clear();
        targets.putAll(candidates);
        descriptors.clear();
        descriptors.putAll(nextDescriptors);
        RuntimeException failure = closeTargets(previous);
        if (failure != null) throw failure;
    }

    private RuntimeException closeTargets() {
        Map<String, Framebuffer> previous = new LinkedHashMap<>(targets);
        targets.clear();
        return closeTargets(previous);
    }

    private static RuntimeException closeTargets(Map<String, Framebuffer> values) {
        RuntimeException failure = null;
        for (Framebuffer target : values.values()) {
            try {
                target.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }

    private static void closeCandidateTargets(Map<String, Framebuffer> values, Throwable failure) {
        for (Framebuffer target : values.values()) {
            try {
                target.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RenderTargetManager is closed");
        }
    }
}
