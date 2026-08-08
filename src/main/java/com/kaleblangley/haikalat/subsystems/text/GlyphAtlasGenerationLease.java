package com.kaleblangley.haikalat.subsystems.text;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * render snapshot 对 atlas generation 的跨线程引用；关闭后允许安全 page 淘汰。
 */
public final class GlyphAtlasGenerationLease implements AutoCloseable {
    private final GlyphAtlas owner;
    private final long generation;
    private final AtomicBoolean released = new AtomicBoolean();

    GlyphAtlasGenerationLease(GlyphAtlas owner, long generation) {
        this.owner = owner;
        this.generation = generation;
    }

    public long generation() {
        return generation;
    }

    public boolean released() {
        return released.get();
    }

    /** 可由 render thread 幂等释放。 */
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            owner.releaseGeneration(generation);
        }
    }
}
