package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single process-local source of positive stable light IDs.
 *
 * <p>Scene lights and temporary effect lights draw from the same counter, so a
 * VFX light can never collide with a user light even when both are captured in
 * the same frame.  IDs are never reused.</p>
 */
final class LightIdAllocator {
    private static final AtomicLong NEXT = new AtomicLong(1L);

    private LightIdAllocator() {
    }

    static long next() {
        long value = NEXT.getAndIncrement();
        if (value <= 0L) throw new IllegalStateException("stable light ID space exhausted");
        return value;
    }
}
