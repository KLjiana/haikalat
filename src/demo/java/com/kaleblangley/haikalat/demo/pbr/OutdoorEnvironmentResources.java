package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import org.joml.Vector3f;
import java.util.LinkedHashMap;

/** Demo-owned, bounded sky cache. Pipelines borrow environments until a successful replacement. */
final class OutdoorEnvironmentResources implements AutoCloseable {
    private final RenderDevice device;
    private final PbrEnvironmentSettings quality;
    private final LinkedHashMap<Key, PbrEnvironment> cache = new LinkedHashMap<>(4, 0.75f, true);
    private PbrEnvironment active;

    OutdoorEnvironmentResources(RenderDevice device, String quality) {
        this.device = device;
        this.quality = PbrEnvironmentSettings.quality(quality);
    }

    PbrEnvironment environmentFor(OutdoorEnvironmentSettings settings) {
        var sky = settings.sky();
        Key key = new Key(sky.zenithColor(), sky.horizonColor(), sky.nadirColor(), sky.environmentIntensity());
        PbrEnvironment result = cache.computeIfAbsent(key, ignored -> PbrEnvironmentLoader.fromSky(device, sky, quality));
        if (active == null) active = result;
        return result;
    }

    void apply(RenderPipeline pipeline, OutdoorEnvironmentSettings settings) {
        PbrEnvironment candidate = environmentFor(settings);
        try {
            pipeline.applyOutdoorEnvironment(settings, candidate);
            active = candidate;
        } finally {
            var entries = cache.entrySet().iterator();
            while (cache.size() > 4 && entries.hasNext()) {
                var entry = entries.next();
                if (entry.getValue() == active) continue;
                entries.remove();
                entry.getValue().close();
            }
        }
    }

    @Override public void close() {
        RuntimeException failure = null;
        for (PbrEnvironment environment : cache.values()) {
            try { environment.close(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        cache.clear();
        if (failure != null) throw failure;
    }

    private record Key(Vector3f zenith, Vector3f horizon, Vector3f nadir, float intensity) { }
}
