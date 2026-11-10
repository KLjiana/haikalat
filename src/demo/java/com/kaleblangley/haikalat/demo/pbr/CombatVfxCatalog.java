package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.mesh.MeshData;

import java.util.Map;
import java.util.Objects;

/** Demo-owned logical resource directory for the combat VFX profile. */
record CombatVfxCatalog(Map<String, AssetRef> textures, Map<String, MeshData> meshes) {
    CombatVfxCatalog {
        textures = Map.copyOf(Objects.requireNonNull(textures, "textures"));
        meshes = Map.copyOf(Objects.requireNonNull(meshes, "meshes"));
    }

    static CombatVfxCatalog showcase() {
        MeshData sphere = PbrSphereMesh.create(32, 20);
        return new CombatVfxCatalog(
                Map.of(
                        "particle.fire", AssetRef.of(
                                "/vfx/particles/kenney/particle_pack/fire_01.png"),
                        "particle.spark", AssetRef.of(
                                "/vfx/particles/kenney/particle_pack/spark_04.png"),
                        "ribbon.streak", AssetRef.of(
                                "/vfx/masks/kenney/light_masks/transparent/streaks_composed_a_noise.png"),
                        "decal.scorch", AssetRef.of(
                                "/vfx/decals/kenney/particle_pack/scorch_01.png"),
                        "mesh.energy", AssetRef.of(
                                "/vfx/masks/kenney/light_masks/transparent/ring_a_streaks.png")),
                Map.of("mesh.sphere", sphere));
    }

    AssetRef texture(String id) {
        AssetRef value = textures.get(Objects.requireNonNull(id, "id"));
        if (value == null) {
            throw new IllegalArgumentException("unknown combat VFX texture resource '" + id + "'");
        }
        return value;
    }

    MeshData mesh(String id) {
        MeshData value = meshes.get(Objects.requireNonNull(id, "id"));
        if (value == null) {
            throw new IllegalArgumentException("unknown combat VFX mesh resource '" + id + "'");
        }
        return value;
    }
}
