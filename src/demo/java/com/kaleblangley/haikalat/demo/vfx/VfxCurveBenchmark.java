package com.kaleblangley.haikalat.demo.vfx;

import com.kaleblangley.haikalat.core.curve.ColorGradient;
import com.kaleblangley.haikalat.core.curve.FloatTrack;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Reproducible no-GL A/B measurement for 10,000-particle snapshot curve sampling. */
public final class VfxCurveBenchmark {
    private static final int PARTICLES = 10_000;
    private static final int WARMUP_SAMPLES = 12;
    private static final int MEASURED_SAMPLES = 40;
    private static volatile int blackhole;

    private VfxCurveBenchmark() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 0) throw new IllegalArgumentException("benchmark takes no arguments");
        AllocationCounter allocations = AllocationCounter.create();
        try (EffectAsset linearAsset = asset(false); EffectAsset curvedAsset = asset(true);
             EffectInstance linear = linearAsset.instantiate(0x181L);
             EffectInstance curved = curvedAsset.instantiate(0x181L)) {
            Vector3f origin = new Vector3f();
            Vector3f camera = new Vector3f(0.0f, 0.0f, 8.0f);
            for (int frame = 0; frame < 60; frame++) {
                linear.update(1.0f / 60.0f, origin);
                curved.update(1.0f / 60.0f, origin);
            }
            if (linear.statistics().activeParticles() != PARTICLES
                    || curved.statistics().activeParticles() != PARTICLES) {
                throw new IllegalStateException("benchmark did not populate exactly 10,000 particles");
            }
            for (int index = 0; index < WARMUP_SAMPLES; index++) {
                consume(linear.snapshot(camera));
                consume(curved.snapshot(camera));
            }

            Measurement linearMeasurement = new Measurement();
            Measurement curvedMeasurement = new Measurement();
            for (int index = 0; index < MEASURED_SAMPLES; index++) {
                if ((index & 1) == 0) {
                    measure(linear, camera, allocations, linearMeasurement);
                    measure(curved, camera, allocations, curvedMeasurement);
                } else {
                    measure(curved, camera, allocations, curvedMeasurement);
                    measure(linear, camera, allocations, linearMeasurement);
                }
            }
            double linearMillis = linearMeasurement.medianNanos() / 1_000_000.0;
            double curvedMillis = curvedMeasurement.medianNanos() / 1_000_000.0;
            double regression = (curvedMillis / linearMillis - 1.0) * 100.0;
            System.out.printf(Locale.ROOT,
                    "VFX_CURVE_BENCHMARK particles=%d warmup=%d samples=%d "
                            + "linearMedianMs=%.4f curvedMedianMs=%.4f regressionPercent=%.2f "
                            + "linearBytes=%d curvedBytes=%d curveSamples=%d%n",
                    PARTICLES, WARMUP_SAMPLES, MEASURED_SAMPLES, linearMillis, curvedMillis,
                    regression, linearMeasurement.medianBytes(), curvedMeasurement.medianBytes(),
                    curved.curveSamples());
        }
    }

    private static void measure(EffectInstance instance, Vector3f camera,
                                AllocationCounter allocations, Measurement destination) {
        long bytesBefore = allocations.currentThreadBytes();
        long start = System.nanoTime();
        EffectSnapshot snapshot = instance.snapshot(camera);
        long nanos = System.nanoTime() - start;
        long bytes = allocations.currentThreadBytes() - bytesBefore;
        consume(snapshot);
        destination.add(nanos, Math.max(0L, bytes));
    }

    private static void consume(EffectSnapshot snapshot) {
        blackhole ^= snapshot.primitiveCount();
        if (!snapshot.particles().isEmpty()) {
            blackhole ^= Float.floatToIntBits(snapshot.particles().get(0).size());
        }
    }

    private static EffectAsset asset(boolean curved) {
        ParticleEmitter emitter = new ParticleEmitter(PARTICLES, PARTICLES, 100.0f,
                new Vector3f(0.0f, 1.0f, 0.0f), 0.0f, 0.0f, 0.0f,
                new Vector3f(), 0.0f, 0.16f, 0.02f,
                new Vector4f(1.0f, 0.6f, 0.1f, 0.9f),
                new Vector4f(0.8f, 0.05f, 0.02f, 0.0f));
        EffectAsset.Builder builder = EffectAsset.builder(curved ? "curved" : "linear")
                .particles(emitter);
        if (curved) {
            builder.particleSizeOverLife(new FloatTrack(
                            new FloatTrack.Key(0.0f, 0.16f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(0.2f, 0.28f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(1.0f, 0.02f, FloatTrack.Interpolation.LINEAR)))
                    .particleColorOverLife(new ColorGradient(
                            new ColorGradient.Stop(0.0f, 1.0f, 0.6f, 0.1f, 0.9f),
                            new ColorGradient.Stop(0.4f, 1.2f, 0.15f, 0.04f, 0.6f),
                            new ColorGradient.Stop(1.0f, 0.8f, 0.05f, 0.02f, 0.0f)));
        }
        return builder.build();
    }

    private static final class Measurement {
        private final long[] nanos = new long[MEASURED_SAMPLES];
        private final long[] bytes = new long[MEASURED_SAMPLES];
        private int size;

        private void add(long elapsedNanos, long allocatedBytes) {
            nanos[size] = elapsedNanos;
            bytes[size] = allocatedBytes;
            size++;
        }

        private double medianNanos() {
            return median(nanos);
        }

        private long medianBytes() {
            return (long) median(bytes);
        }

        private double median(long[] source) {
            long[] values = Arrays.copyOf(source, size);
            Arrays.sort(values);
            int middle = values.length / 2;
            return (values[middle - 1] + (double) values[middle]) * 0.5;
        }
    }

    private interface AllocationCounter {
        long currentThreadBytes();

        static AllocationCounter create() {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean extended
                    && extended.isThreadAllocatedMemorySupported()) {
                if (!extended.isThreadAllocatedMemoryEnabled()) {
                    extended.setThreadAllocatedMemoryEnabled(true);
                }
                long threadId = Thread.currentThread().threadId();
                return () -> extended.getThreadAllocatedBytes(threadId);
            }
            return () -> 0L;
        }
    }
}
