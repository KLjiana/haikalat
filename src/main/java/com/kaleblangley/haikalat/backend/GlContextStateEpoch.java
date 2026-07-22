package com.kaleblangley.haikalat.backend;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 当前 OpenGL context 中可缓存 native 状态的删除代次。 */
final class GlContextStateEpoch {
    static final long NO_CONTEXT = Long.MIN_VALUE;

    private static final Map<GLCapabilities, Epoch> EPOCHS = new WeakHashMap<>();
    private static final ThreadLocal<CachedEpoch> CURRENT = new ThreadLocal<>();
    private static final AtomicLong NEXT_TOKEN = new AtomicLong();

    private GlContextStateEpoch() {
    }

    static long current() {
        GLCapabilities capabilities = capabilitiesOrNull();
        if (capabilities == null) return NO_CONTEXT;
        return epoch(capabilities).value.get();
    }

    static void deleted() {
        GLCapabilities capabilities = capabilitiesOrNull();
        if (capabilities == null) return;
        epoch(capabilities).value.set(nextToken());
    }

    private static long nextToken() {
        while (true) {
            long previous = NEXT_TOKEN.get();
            if (previous == Long.MAX_VALUE) {
                throw new IllegalStateException("OpenGL context state epoch exhausted");
            }
            long next = previous + 1L;
            if (NEXT_TOKEN.compareAndSet(previous, next)) return next;
        }
    }

    static void releaseCurrent() {
        GLCapabilities capabilities = capabilitiesOrNull();
        if (capabilities == null) return;
        synchronized (EPOCHS) {
            EPOCHS.remove(capabilities);
        }
        CachedEpoch cached = CURRENT.get();
        if (cached != null && cached.capabilities.get() == capabilities) CURRENT.remove();
    }

    private static Epoch epoch(GLCapabilities capabilities) {
        CachedEpoch cached = CURRENT.get();
        if (cached != null && cached.capabilities.get() == capabilities) return cached.epoch;
        Epoch epoch;
        synchronized (EPOCHS) {
            epoch = EPOCHS.computeIfAbsent(capabilities, ignored -> new Epoch());
        }
        CURRENT.set(new CachedEpoch(new WeakReference<>(capabilities), epoch));
        return epoch;
    }

    private static GLCapabilities capabilitiesOrNull() {
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static final class Epoch {
        final AtomicLong value = new AtomicLong(nextToken());
    }

    private record CachedEpoch(WeakReference<GLCapabilities> capabilities, Epoch epoch) {
    }
}
