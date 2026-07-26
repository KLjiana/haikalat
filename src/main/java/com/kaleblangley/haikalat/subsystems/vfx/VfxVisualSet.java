package com.kaleblangley.haikalat.subsystems.vfx;

import java.util.Objects;

/** Materials assigned to each VFX primitive kind. */
public record VfxVisualSet(VfxMaterial particle, VfxMaterial ribbon,
                           VfxMaterial decal, VfxMaterial mesh) {
    private static final VfxVisualSet LEGACY = new VfxVisualSet(
            VfxMaterial.legacyAlpha(), VfxMaterial.legacyAlpha(),
            VfxMaterial.legacyAlpha(), VfxMaterial.legacyAlpha());

    public VfxVisualSet {
        particle = Objects.requireNonNull(particle, "particle");
        ribbon = Objects.requireNonNull(ribbon, "ribbon");
        decal = Objects.requireNonNull(decal, "decal");
        mesh = Objects.requireNonNull(mesh, "mesh");
    }

    public VfxVisualSet(VfxMaterial particle, VfxMaterial ribbon, VfxMaterial decal) {
        this(particle, ribbon, decal, VfxMaterial.legacyAlpha());
    }

    public static VfxVisualSet legacy() {
        return LEGACY;
    }

    public VfxMaterial material(EffectSnapshot.Kind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case PARTICLE -> particle;
            case RIBBON -> ribbon;
            case DECAL -> decal;
            case MESH -> mesh;
        };
    }
}
