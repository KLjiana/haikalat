package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS;

/**
 * Finite owner of the static local reflection probes used by the PBR forward
 * shader.
 *
 * <p>The set precomputes every active probe through
 * {@link PbrEnvironmentLoader} on the owning GL thread, stores the immutable
 * settings in declaration order and releases all probe environments plus the
 * shared cube sampler in {@link #close()}. Loading is transactional: if any
 * probe fails, every already-created probe and the sampler are closed and the
 * original failure is rethrown with cleanup failures suppressed. The caller
 * never observes a partially loaded set.</p>
 *
 * <p>Disabled or zero-intensity probe descriptions are retained for settings
 * inspection but load no GPU resources and are ignored by selection. The set
 * is bounded by {@link ReflectionProbeSettings#MAX_PROBE_COUNT}; construction
 * rejects more probes so the fixed shader contract can never be exceeded.</p>
 */
public final class ReflectionProbeSet implements AutoCloseable {
    private final List<ReflectionProbeSettings> settings;
    private final List<ReflectionProbe> probes;
    private final Sampler cubeSampler;
    private boolean closed;

    private ReflectionProbeSet(List<ReflectionProbeSettings> settings,
                               List<ReflectionProbe> probes, Sampler cubeSampler) {
        this.settings = settings;
        this.probes = probes;
        this.cubeSampler = cubeSampler;
    }

    /**
     * Validates and precomputes one set. The device and anchor must belong to
     * the current render thread/context; the anchor resolves every
     * {@code hdrResource} classpath path.
     *
     * @throws IllegalArgumentException for duplicate names, too many probes or
     *                                  invalid settings
     * @throws GlException              when preprocessing fails or the context
     *                                  cannot expose the probe texture units
     */
    public static ReflectionProbeSet load(RenderDevice device, Class<?> anchor,
                                          List<ReflectionProbeSettings> settings) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(anchor, "anchor");
        List<ReflectionProbeSettings> validated = validate(settings);
        int active = activeProbeCount(validated);
        if (active == 0) {
            return new ReflectionProbeSet(validated, List.of(), null);
        }
        int units = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        if (units <= PbrMaterials.REFLECTION_PROBE_UNIT_1) {
            throw new GlException("local reflection probes require at least "
                    + (PbrMaterials.REFLECTION_PROBE_UNIT_1 + 1)
                    + " fragment texture units, but the context exposes " + units);
        }
        Sampler sampler = createCubeSampler();
        List<ReflectionProbe> created = new ArrayList<>(validated.size());
        Throwable primaryFailure = null;
        try {
            for (ReflectionProbeSettings probe : validated) {
                if (!probe.active()) {
                    created.add(null);
                    continue;
                }
                PbrEnvironment environment = PbrEnvironmentLoader.load(device, anchor,
                        probe.hdrResource(), probe.environmentSettings());
                created.add(new ReflectionProbe(probe, environment));
            }
            return new ReflectionProbeSet(validated, List.copyOf(created), sampler);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (primaryFailure != null) {
                RuntimeException cleanupFailure = closeProbes(created);
                try {
                    sampler.close();
                } catch (RuntimeException error) {
                    cleanupFailure = accumulate(cleanupFailure, error);
                }
                if (cleanupFailure != null) primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** Immutable descriptions in declaration order, including disabled entries. */
    public List<ReflectionProbeSettings> settings() {
        ensureOpen();
        return settings;
    }

    /** Total number of descriptions, including disabled entries. */
    public int size() {
        return settings.size();
    }

    /** Number of probes that actually own a precomputed environment. */
    public int activeProbeCount() {
        int count = 0;
        for (ReflectionProbe probe : probes) if (probe != null) count++;
        return count;
    }

    public boolean isEmpty() {
        return activeProbeCount() == 0;
    }

    /**
     * Selects the probes influencing a world point. A point outside every
     * active probe returns {@link ReflectionProbeSelector.Selection#globalOnly()}.
     */
    public ReflectionProbeSelector.Selection select(Vector3fc worldPoint) {
        ensureOpen();
        return ReflectionProbeSelector.select(settings, worldPoint);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = closeProbes(probes);
        if (cubeSampler != null) {
            try {
                cubeSampler.close();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        if (failure != null) throw failure;
    }

    /** Owned probe for a settings index, or {@code null} for a disabled entry. */
    ReflectionProbe probe(int settingsIndex) {
        ensureOpen();
        return probes.get(settingsIndex);
    }

    /** Shared mipmapped cube sampler for every probe texture unit. */
    Sampler cubeSampler() {
        ensureOpen();
        if (cubeSampler == null) throw new IllegalStateException("no active reflection probes bound");
        return cubeSampler;
    }

    static List<ReflectionProbeSettings> validate(List<ReflectionProbeSettings> settings) {
        Objects.requireNonNull(settings, "settings");
        List<ReflectionProbeSettings> result = List.copyOf(settings);
        if (result.size() > ReflectionProbeSettings.MAX_PROBE_COUNT) {
            throw new IllegalArgumentException("at most "
                    + ReflectionProbeSettings.MAX_PROBE_COUNT
                    + " reflection probes are supported, got " + result.size());
        }
        Set<String> names = new HashSet<>();
        int active = 0;
        for (int index = 0; index < result.size(); index++) {
            ReflectionProbeSettings probe = Objects.requireNonNull(result.get(index),
                    "settings[" + index + "]");
            if (!names.add(probe.name())) {
                throw new IllegalArgumentException("duplicate reflection probe name: "
                        + probe.name());
            }
            if (probe.active()) active++;
        }
        if (active > ReflectionProbeSettings.MAX_PROBE_COUNT) {
            throw new IllegalArgumentException("at most "
                    + ReflectionProbeSettings.MAX_PROBE_COUNT
                    + " active reflection probes are supported, got " + active);
        }
        return result;
    }

    static int activeProbeCount(List<ReflectionProbeSettings> settings) {
        int count = 0;
        for (ReflectionProbeSettings probe : settings) if (probe.active()) count++;
        return count;
    }

    private static Sampler createCubeSampler() {
        return Sampler.create(new Sampler.Descriptor(
                GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
    }

    private static RuntimeException closeProbes(List<ReflectionProbe> probes) {
        RuntimeException failure = null;
        for (int index = probes.size() - 1; index >= 0; index--) {
            ReflectionProbe probe = probes.get(index);
            if (probe == null) continue;
            try {
                probe.close();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        return failure;
    }

    private static RuntimeException accumulate(RuntimeException failure, RuntimeException next) {
        if (failure == null) return next;
        failure.addSuppressed(next);
        return failure;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Reflection probe set is closed");
    }
}
