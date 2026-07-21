package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import org.joml.Matrix4f;

import java.util.Objects;

public record MeshRenderer(
        Mesh mesh,
        MaterialInstance material,
        Transform transform,
        SceneObject.ModelUpdater updater,
        boolean castShadows
) {
    public MeshRenderer {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(updater, "updater");
    }

    public static MeshRenderer of(Mesh mesh, Material material, Transform transform) {
        return of(mesh, material.createInstance(), transform);
    }

    public static MeshRenderer of(Mesh mesh, MaterialInstance material, Transform transform) {
        Objects.requireNonNull(transform, "transform");
        return new MeshRenderer(mesh, material, transform,
                new RevisionedModelSource.TransformSource(transform), true);
    }

    public static MeshRenderer animated(Mesh mesh, Material material, SceneObject.ModelUpdater updater) {
        return animated(mesh, material.createInstance(), updater);
    }

    public static MeshRenderer animated(Mesh mesh, MaterialInstance material, SceneObject.ModelUpdater updater) {
        return new MeshRenderer(mesh, material, Transform.identity(), updater, true);
    }

    public MeshRenderer withoutShadows() {
        return new MeshRenderer(mesh, material, transform, updater, false);
    }

    public Matrix4f modelMatrix(Matrix4f out, int frameIndex) {
        updater.update(out, frameIndex);
        return out;
    }

    boolean revisionedModel() {
        return updater instanceof RevisionedModelSource;
    }

    boolean immutableModel() {
        return updater instanceof RevisionedModelSource source && source.immutable();
    }

    long modelRevision() {
        return ((RevisionedModelSource) updater).revision();
    }
}
