package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import org.joml.Matrix4f;

public record SceneObject(Mesh mesh, Material material, ModelUpdater updater, boolean castShadows) {

    public SceneObject(Mesh mesh, Material material, ModelUpdater updater) {
        this(mesh, material, updater, true);
    }

    @FunctionalInterface
    public interface ModelUpdater {
        void update(Matrix4f out, int frameIndex);
    }

    public void computeModel(Matrix4f out, int frameIndex) {
        updater.update(out, frameIndex);
    }
}
