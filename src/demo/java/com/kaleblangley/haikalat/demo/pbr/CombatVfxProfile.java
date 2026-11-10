package com.kaleblangley.haikalat.demo.pbr;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Versioned, demo-owned authoring profile for the v0.28 combat VFX showcase.
 *
 * <p>This is deliberately narrower than {@link VisualProfile}: it stores only
 * the material parameters, timing, seed, light definition and logical resource
 * references that the fixed showcase scene supports.  It never stores a GL
 * handle, a full Scene or an arbitrary Java callback.</p>
 */
public record CombatVfxProfile(
        int schemaVersion,
        String profileId,
        boolean rimEnabled, Vector3f rimColor, float rimIntensity, float rimExponent,
        boolean uvFlowEnabled, float uvSpeedU, float uvSpeedV,
        float uvNoiseScale, float uvNoiseAmplitude,
        boolean dissolveEnabled, boolean dissolveExplicit,
        float dissolveEdgeWidth, Vector3f dissolveEdgeColor, float dissolveEdgeIntensity,
        boolean lightEnabled, Vector3f lightColor, float lightIntensity,
        float lightRange, int lightPriority,
        float chargeSeconds, float releaseSeconds, float fadeSeconds, float lightEndSeconds,
        long seed,
        String particleTextureId, String ribbonTextureId, String decalTextureId,
        String meshId) {
    public static final int CURRENT_SCHEMA = 1;

    public CombatVfxProfile {
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("unsupported combat VFX profile schema " + schemaVersion);
        }
        profileId = requireText(profileId, "profileId");
        rimColor = finiteColor(rimColor, "rimColor");
        rimIntensity = finiteNonNegative(rimIntensity, "rimIntensity");
        rimExponent = finitePositive(rimExponent, "rimExponent");
        if (!Float.isFinite(uvSpeedU) || !Float.isFinite(uvSpeedV)) {
            throw new IllegalArgumentException("uv flow speeds must be finite");
        }
        uvNoiseScale = finitePositive(uvNoiseScale, "uvNoiseScale");
        uvNoiseAmplitude = unit(uvNoiseAmplitude, "uvNoiseAmplitude");
        dissolveEdgeWidth = unit(dissolveEdgeWidth, "dissolveEdgeWidth");
        dissolveEdgeColor = finiteColor(dissolveEdgeColor, "dissolveEdgeColor");
        dissolveEdgeIntensity = finiteNonNegative(dissolveEdgeIntensity, "dissolveEdgeIntensity");
        lightColor = finiteColor(lightColor, "lightColor");
        lightIntensity = finiteNonNegative(lightIntensity, "lightIntensity");
        lightRange = finitePositive(lightRange, "lightRange");
        chargeSeconds = finiteNonNegative(chargeSeconds, "chargeSeconds");
        releaseSeconds = finitePositive(releaseSeconds, "releaseSeconds");
        fadeSeconds = finitePositive(fadeSeconds, "fadeSeconds");
        lightEndSeconds = finitePositive(lightEndSeconds, "lightEndSeconds");
        if (!(chargeSeconds < releaseSeconds && releaseSeconds < fadeSeconds
                && fadeSeconds <= lightEndSeconds)) {
            throw new IllegalArgumentException(
                    "timing must satisfy charge < release < fade <= lightEnd");
        }
        particleTextureId = requireText(particleTextureId, "particleTextureId");
        ribbonTextureId = requireText(ribbonTextureId, "ribbonTextureId");
        decalTextureId = requireText(decalTextureId, "decalTextureId");
        meshId = requireText(meshId, "meshId");
    }

    /** Baseline A/B profile: same geometry, timing and resources, no new effect terms. */
    public static CombatVfxProfile baseline() {
        return defaults().toBuilder()
                .profileId("combat-baseline")
                .rimEnabled(false)
                .uvFlowEnabled(false)
                .dissolveEnabled(false)
                .lightEnabled(false)
                .build();
    }

    public static CombatVfxProfile defaults() {
        return new CombatVfxProfile(CURRENT_SCHEMA, "combat-default",
                true, new Vector3f(0.35f, 0.75f, 1.0f), 3.2f, 2.0f,
                true, 0.22f, 0.06f, 5.0f, 0.06f,
                true, true, 0.12f, new Vector3f(0.45f, 0.85f, 1.0f), 5.0f,
                true, new Vector3f(0.35f, 0.7f, 1.0f), 14.0f, 7.0f, 10,
                0.8f, 1.2f, 2.6f, 3.0f,
                0x4841494b414c4154L,
                "particle.fire", "ribbon.streak", "decal.scorch", "mesh.sphere");
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public static final class Builder {
        private int schemaVersion = CURRENT_SCHEMA;
        private String profileId = "combat-draft";
        private boolean rimEnabled;
        private Vector3f rimColor = new Vector3f(1.0f);
        private float rimIntensity = 1.0f;
        private float rimExponent = 2.0f;
        private boolean uvFlowEnabled;
        private float uvSpeedU;
        private float uvSpeedV;
        private float uvNoiseScale = 4.0f;
        private float uvNoiseAmplitude;
        private boolean dissolveEnabled;
        private boolean dissolveExplicit = true;
        private float dissolveEdgeWidth;
        private Vector3f dissolveEdgeColor = new Vector3f(1.0f);
        private float dissolveEdgeIntensity;
        private boolean lightEnabled;
        private Vector3f lightColor = new Vector3f(1.0f);
        private float lightIntensity;
        private float lightRange = 1.0f;
        private int lightPriority;
        private float chargeSeconds;
        private float releaseSeconds = 1.0f;
        private float fadeSeconds = 2.0f;
        private float lightEndSeconds = 2.0f;
        private long seed = 1L;
        private String particleTextureId = "particle.fire";
        private String ribbonTextureId = "ribbon.streak";
        private String decalTextureId = "decal.scorch";
        private String meshId = "mesh.sphere";

        private Builder(CombatVfxProfile source) {
            schemaVersion = source.schemaVersion;
            profileId = source.profileId;
            rimEnabled = source.rimEnabled;
            rimColor = source.rimColor;
            rimIntensity = source.rimIntensity;
            rimExponent = source.rimExponent;
            uvFlowEnabled = source.uvFlowEnabled;
            uvSpeedU = source.uvSpeedU;
            uvSpeedV = source.uvSpeedV;
            uvNoiseScale = source.uvNoiseScale;
            uvNoiseAmplitude = source.uvNoiseAmplitude;
            dissolveEnabled = source.dissolveEnabled;
            dissolveExplicit = source.dissolveExplicit;
            dissolveEdgeWidth = source.dissolveEdgeWidth;
            dissolveEdgeColor = source.dissolveEdgeColor;
            dissolveEdgeIntensity = source.dissolveEdgeIntensity;
            lightEnabled = source.lightEnabled;
            lightColor = source.lightColor;
            lightIntensity = source.lightIntensity;
            lightRange = source.lightRange;
            lightPriority = source.lightPriority;
            chargeSeconds = source.chargeSeconds;
            releaseSeconds = source.releaseSeconds;
            fadeSeconds = source.fadeSeconds;
            lightEndSeconds = source.lightEndSeconds;
            seed = source.seed;
            particleTextureId = source.particleTextureId;
            ribbonTextureId = source.ribbonTextureId;
            decalTextureId = source.decalTextureId;
            meshId = source.meshId;
        }

        public Builder profileId(String value) { profileId = value; return this; }
        public Builder rimEnabled(boolean value) { rimEnabled = value; return this; }
        public Builder rimColor(Vector3f value) { rimColor = value; return this; }
        public Builder rimIntensity(float value) { rimIntensity = value; return this; }
        public Builder rimExponent(float value) { rimExponent = value; return this; }
        public Builder uvFlowEnabled(boolean value) { uvFlowEnabled = value; return this; }
        public Builder uvSpeedU(float value) { uvSpeedU = value; return this; }
        public Builder uvSpeedV(float value) { uvSpeedV = value; return this; }
        public Builder uvNoiseScale(float value) { uvNoiseScale = value; return this; }
        public Builder uvNoiseAmplitude(float value) { uvNoiseAmplitude = value; return this; }
        public Builder dissolveEnabled(boolean value) { dissolveEnabled = value; return this; }
        public Builder dissolveExplicit(boolean value) { dissolveExplicit = value; return this; }
        public Builder dissolveEdgeWidth(float value) { dissolveEdgeWidth = value; return this; }
        public Builder dissolveEdgeColor(Vector3f value) { dissolveEdgeColor = value; return this; }
        public Builder dissolveEdgeIntensity(float value) { dissolveEdgeIntensity = value; return this; }
        public Builder lightEnabled(boolean value) { lightEnabled = value; return this; }
        public Builder lightColor(Vector3f value) { lightColor = value; return this; }
        public Builder lightIntensity(float value) { lightIntensity = value; return this; }
        public Builder lightRange(float value) { lightRange = value; return this; }
        public Builder lightPriority(int value) { lightPriority = value; return this; }
        public Builder chargeSeconds(float value) { chargeSeconds = value; return this; }
        public Builder releaseSeconds(float value) { releaseSeconds = value; return this; }
        public Builder fadeSeconds(float value) { fadeSeconds = value; return this; }
        public Builder lightEndSeconds(float value) { lightEndSeconds = value; return this; }
        public Builder seed(long value) { seed = value; return this; }
        public Builder particleTextureId(String value) { particleTextureId = value; return this; }
        public Builder ribbonTextureId(String value) { ribbonTextureId = value; return this; }
        public Builder decalTextureId(String value) { decalTextureId = value; return this; }
        public Builder meshId(String value) { meshId = value; return this; }

        public CombatVfxProfile build() {
            return new CombatVfxProfile(schemaVersion, profileId,
                    rimEnabled, rimColor, rimIntensity, rimExponent,
                    uvFlowEnabled, uvSpeedU, uvSpeedV, uvNoiseScale, uvNoiseAmplitude,
                    dissolveEnabled, dissolveExplicit, dissolveEdgeWidth,
                    dissolveEdgeColor, dissolveEdgeIntensity,
                    lightEnabled, lightColor, lightIntensity, lightRange, lightPriority,
                    chargeSeconds, releaseSeconds, fadeSeconds, lightEndSeconds, seed,
                    particleTextureId, ribbonTextureId, decalTextureId, meshId);
        }
    }

    @Override
    public Vector3f rimColor() { return new Vector3f(rimColor); }
    @Override
    public Vector3f dissolveEdgeColor() { return new Vector3f(dissolveEdgeColor); }
    @Override
    public Vector3f lightColor() { return new Vector3f(lightColor); }

    private static String requireText(String value, String name) {
        String required = Objects.requireNonNull(value, name).trim();
        if (required.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return required;
    }

    private static Vector3f finiteColor(Vector3f value, String name) {
        Vector3f color = new Vector3f(Objects.requireNonNull(value, name));
        if (!Float.isFinite(color.x) || !Float.isFinite(color.y) || !Float.isFinite(color.z)) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
        if (color.x < 0.0f || color.y < 0.0f || color.z < 0.0f) {
            throw new IllegalArgumentException(name + " components must be non-negative");
        }
        return color;
    }

    private static float finiteNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }

    private static float finitePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return value;
    }

    private static float unit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
        return value;
    }
}
