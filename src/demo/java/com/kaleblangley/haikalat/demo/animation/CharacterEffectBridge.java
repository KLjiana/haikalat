package com.kaleblangley.haikalat.demo.animation;

import com.kaleblangley.haikalat.subsystems.animation.AnimationSignal;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import org.joml.Vector2f;
import org.joml.Vector3fc;

import java.util.List;
import java.util.Objects;

/**
 * Demo/runtime orchestration proof from animation signals to VFX commands.
 * Neither the animation nor VFX subsystem depends on the other.
 */
public final class CharacterEffectBridge {
    private final long seed;
    private long lastSequence = -1L;
    private long consumed;

    public CharacterEffectBridge(long seed) {
        this.seed = seed;
    }

    public int consume(List<AnimationSignal> signals, EffectInstance effect,
                       Vector3fc socketPosition, Vector3fc surfaceNormal) {
        Objects.requireNonNull(signals, "signals");
        EffectInstance target = Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(socketPosition, "socketPosition");
        Objects.requireNonNull(surfaceNormal, "surfaceNormal");
        int spawned = 0;
        for (AnimationSignal signal : signals) {
            Objects.requireNonNull(signal, "signals element");
            if (signal.sequence() <= lastSequence) continue;
            lastSequence = signal.sequence();
            if ((signal.type() == AnimationSignal.Type.MARKER
                    || signal.type() == AnimationSignal.Type.EVENT)
                    && ("attack-hit".equals(signal.name())
                    || "impact".equals(signal.payload()))) {
                target.spawnDecal(socketPosition, surfaceNormal,
                        new Vector2f(0.72f, 0.24f), rotation(signal.sequence()));
                spawned++;
                consumed++;
            }
        }
        return spawned;
    }

    public long consumedSignalCount() {
        return consumed;
    }

    private float rotation(long sequence) {
        long mixed = sequence ^ seed;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (float) ((mixed & 0xffffL) / 65536.0 * Math.PI * 2.0);
    }
}
