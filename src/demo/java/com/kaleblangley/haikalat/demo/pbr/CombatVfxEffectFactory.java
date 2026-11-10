package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.curve.ColorGradient;
import com.kaleblangley.haikalat.core.curve.FloatTrack;
import com.kaleblangley.haikalat.subsystems.vfx.Decal;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectLight;
import com.kaleblangley.haikalat.subsystems.vfx.MeshVfx;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.RibbonEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.VfxBillboardMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxDissolve;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaskMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.subsystems.vfx.VfxRimLight;
import com.kaleblangley.haikalat.subsystems.vfx.VfxUvFlow;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/** Builds the production {@link EffectAsset} consumed by the showcase VFX renderer. */
final class CombatVfxEffectFactory {
    private static final float DISSOLVE_NOISE_SCALE = 6.0f;

    private CombatVfxEffectFactory() {
    }

    static EffectAsset build(CombatVfxProfile profile, CombatVfxCatalog catalog) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(catalog, "catalog");
        VfxUvFlow uvFlow = profile.uvFlowEnabled()
                ? VfxUvFlow.of(profile.uvSpeedU(), profile.uvSpeedV(),
                profile.uvNoiseScale(), profile.uvNoiseAmplitude(), profile.seed())
                : null;
        VfxDissolve dissolve = profile.dissolveEnabled()
                ? new VfxDissolve(profile.dissolveExplicit()
                        ? VfxDissolve.ProgressSource.EXPLICIT
                        : VfxDissolve.ProgressSource.LIFECYCLE,
                profile.dissolveEdgeWidth(), profile.dissolveEdgeColor(),
                profile.dissolveEdgeIntensity(), DISSOLVE_NOISE_SCALE, profile.seed())
                : null;
        VfxMaterial.Builder particle = VfxMaterial.builder("combat-particle")
                .texture(catalog.texture(profile.particleTextureId()))
                .maskMode(VfxMaskMode.LUMINANCE)
                .blendMode(BlendMode.ADDITIVE)
                .emissiveIntensity(4.5f)
                .billboardMode(VfxBillboardMode.VELOCITY_ALIGNED)
                .velocityStretch(0.35f).maximumStretch(2.5f)
                .softParticleDistance(0.12f);
        VfxMaterial.Builder ribbon = VfxMaterial.builder("combat-ribbon")
                .texture(catalog.texture(profile.ribbonTextureId()))
                .maskMode(VfxMaskMode.ALPHA)
                .blendMode(BlendMode.ADDITIVE)
                .emissiveIntensity(3.5f)
                .softParticleDistance(0.06f);
        VfxMaterial.Builder decal = VfxMaterial.builder("combat-decal")
                .texture(catalog.texture(profile.decalTextureId()))
                .maskMode(VfxMaskMode.LUMINANCE)
                .blendMode(BlendMode.ALPHA)
                .emissiveIntensity(1.0f)
                .softParticleDistance(0.04f);
        VfxMaterial.Builder mesh = VfxMaterial.builder("combat-mesh")
                .texture(catalog.texture("mesh.energy"))
                .maskMode(VfxMaskMode.ALPHA)
                .blendMode(BlendMode.ALPHA)
                .emissiveIntensity(2.4f);
        if (uvFlow != null) {
            particle.uvFlow(uvFlow);
            ribbon.uvFlow(uvFlow);
            decal.uvFlow(uvFlow);
            mesh.uvFlow(uvFlow);
        }
        if (dissolve != null) {
            particle.dissolve(dissolve);
            ribbon.dissolve(dissolve);
            decal.dissolve(dissolve);
            mesh.dissolve(dissolve);
        }
        if (profile.rimEnabled()) {
            mesh.rimLight(new VfxRimLight(profile.rimColor(),
                    profile.rimIntensity(), profile.rimExponent()));
        }
        float meshLifetime = Math.max(profile.fadeSeconds() + 0.2f,
                profile.lightEndSeconds() + 0.2f);
        EffectAsset.Builder asset = EffectAsset.builder("showcase-combat-vfx")
                .particles(new ParticleEmitter(192, 150.0f, 1.6f,
                        new Vector3f(0.0f, 0.85f, 0.0f), 0.55f, 0.4f, 1.5f,
                        new Vector3f(0.0f, -0.6f, 0.0f), 0.1f,
                        0.14f, 0.03f,
                        new Vector4f(1.0f, 0.55f, 0.08f, 0.82f),
                        new Vector4f(0.1f, 0.45f, 1.0f, 0.0f)))
                .ribbon(new RibbonEmitter(48, 1.4f, 0.015f, 0.16f, 0.02f,
                        new Vector4f(0.08f, 0.75f, 1.0f, 0.72f),
                        new Vector4f(0.12f, 0.2f, 0.9f, 0.0f)))
                .decals(new Decal(6, 2.4f,
                        new Vector4f(0.55f, 0.16f, 1.0f, 0.58f),
                        new Vector4f(0.2f, 0.04f, 0.45f, 0.0f)))
                .meshes(new MeshVfx(2, meshLifetime, catalog.mesh(profile.meshId()),
                        0.34f, 0.52f,
                        new Vector4f(0.35f, 0.75f, 1.0f, 0.92f),
                        new Vector4f(0.1f, 0.35f, 0.9f, 0.0f)))
                .particleMaterial(particle.build())
                .ribbonMaterial(ribbon.build())
                .decalMaterial(decal.build())
                .meshMaterial(mesh.build())
                .particleSizeOverLife(new FloatTrack(
                        new FloatTrack.Key(0.0f, 0.14f, FloatTrack.Interpolation.LINEAR),
                        new FloatTrack.Key(0.2f, 0.26f, FloatTrack.Interpolation.LINEAR),
                        new FloatTrack.Key(1.0f, 0.03f, FloatTrack.Interpolation.LINEAR)))
                .particleColorOverLife(new ColorGradient(
                        new ColorGradient.Stop(0.0f, 1.4f, 0.72f, 0.08f, 0.82f),
                        new ColorGradient.Stop(0.4f, 0.7f, 0.18f, 1.1f, 0.55f),
                        new ColorGradient.Stop(1.0f, 0.1f, 0.45f, 1.0f, 0.0f)))
                .ribbonWidthOverLife(new FloatTrack(
                        new FloatTrack.Key(0.0f, 0.16f, FloatTrack.Interpolation.LINEAR),
                        new FloatTrack.Key(1.0f, 0.02f, FloatTrack.Interpolation.LINEAR)))
                .ribbonColorOverLife(new ColorGradient(
                        new ColorGradient.Stop(0.0f, 0.08f, 0.75f, 1.0f, 0.72f),
                        new ColorGradient.Stop(1.0f, 0.12f, 0.2f, 0.9f, 0.0f)))
                .decalScaleOverLife(new FloatTrack(
                        new FloatTrack.Key(0.0f, 0.85f, FloatTrack.Interpolation.LINEAR),
                        new FloatTrack.Key(0.2f, 1.1f, FloatTrack.Interpolation.LINEAR),
                        new FloatTrack.Key(1.0f, 0.72f, FloatTrack.Interpolation.LINEAR)))
                .decalColorOverLife(new ColorGradient(
                        new ColorGradient.Stop(0.0f, 0.55f, 0.16f, 1.0f, 0.58f),
                        new ColorGradient.Stop(1.0f, 0.2f, 0.04f, 0.45f, 0.0f)))
                .meshColorOverLife(new ColorGradient(
                        new ColorGradient.Stop(0.0f, 0.35f, 0.75f, 1.0f, 0.92f),
                        new ColorGradient.Stop(0.7f, 0.2f, 0.6f, 1.0f, 0.72f),
                        new ColorGradient.Stop(1.0f, 0.1f, 0.35f, 0.9f, 0.0f)));
        if (profile.lightEnabled()) {
            asset.light(new EffectLight(profile.releaseSeconds(), profile.lightEndSeconds(),
                    new Vector3f(0.0f, 0.45f, 0.0f), profile.lightColor(),
                    profile.lightIntensity(), profile.lightRange(), profile.lightPriority(),
                    new FloatTrack(
                            new FloatTrack.Key(0.0f, 0.25f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(0.15f, 1.0f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(0.55f, 1.0f, FloatTrack.Interpolation.LINEAR),
                            new FloatTrack.Key(1.0f, 0.0f, FloatTrack.Interpolation.LINEAR)),
                    null));
        }
        return asset.build();
    }
}
