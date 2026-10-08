package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.VisualSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LightVolumeHints;

import java.util.Objects;
import java.util.List;
import java.util.HashSet;

/** Versioned authoring metadata plus one immutable renderer visual snapshot. */
public record VisualProfile(int schemaVersion, String profileId, String description,
                            String author, VisualSettings settings,
                            List<LightVolumeOverride> lightVolumes) {
    public static final int CURRENT_SCHEMA = 2;

    /** Index into the deterministic scene factory order, never a native or transient GPU index. */
    public record LightVolumeOverride(int sceneLightIndex, LightVolumeHints hints) {
        public LightVolumeOverride {
            if (sceneLightIndex < 0 || sceneLightIndex > 4096)
                throw new IllegalArgumentException("sceneLightIndex must be in [0,4096]");
            hints = Objects.requireNonNull(hints, "hints");
        }
    }

    public VisualProfile {
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("unsupported visual profile schema " + schemaVersion);
        }
        profileId = requireText(profileId, "profileId");
        description = Objects.requireNonNull(description, "description");
        author = Objects.requireNonNull(author, "author");
        settings = Objects.requireNonNull(settings, "settings");
        lightVolumes = List.copyOf(Objects.requireNonNull(lightVolumes, "lightVolumes"));
        if (lightVolumes.size() > 4097) throw new IllegalArgumentException("too many light overrides");
        HashSet<Integer> indices = new HashSet<>();
        for (LightVolumeOverride entry : lightVolumes) {
            if (!indices.add(entry.sceneLightIndex()))
                throw new IllegalArgumentException("duplicate scene light override " + entry.sceneLightIndex());
        }
    }

    public VisualProfile(int schemaVersion, String profileId, String description,
                         String author, VisualSettings settings) {
        this(schemaVersion, profileId, description, author, settings, List.of());
    }

    static VisualProfile outdoorCompatibility(OutdoorEnvironmentSettings outdoor) {
        OutdoorEnvironmentSettings required = Objects.requireNonNull(outdoor, "outdoor");
        return new VisualProfile(CURRENT_SCHEMA, required.preset(),
                "Sky profile; physical fog requires explicit schema 2 coefficients", "Haikalat",
                new VisualSettings(1.05f, required.sky().environmentIntensity(), 0.0f,
                        AntiAliasingMode.TAA, BloomSettings.builder().enabled(true).build(),
                        required));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
