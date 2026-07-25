package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;

public record SceneObject(Mesh mesh, Material material, ModelUpdater updater, boolean castShadows,
                          SceneDrawBinding drawBinding) {

    public SceneObject {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(updater, "updater");
        Objects.requireNonNull(drawBinding, "drawBinding");
    }

    public SceneObject(Mesh mesh, Material material, ModelUpdater updater) {
        this(mesh, material, updater, true, SceneDrawBinding.NONE);
    }

    public SceneObject(Mesh mesh, Material material, ModelUpdater updater,
                       boolean castShadows) {
        this(mesh, material, updater, castShadows, SceneDrawBinding.NONE);
    }

    /** 创建防御性复制矩阵、可由 scene cache 明确认定为静态的对象。 */
    public static SceneObject fixed(Mesh mesh, Material material, Matrix4fc matrix,
                                    boolean castShadows) {
        Objects.requireNonNull(matrix, "matrix");
        return new SceneObject(mesh, material, new RevisionedModelSource.FixedSource(matrix),
                castShadows, SceneDrawBinding.NONE);
    }

    public static SceneObject fixed(Mesh mesh, Material material, Matrix4fc matrix,
                                    boolean castShadows, SceneDrawBinding drawBinding) {
        Objects.requireNonNull(matrix, "matrix");
        return new SceneObject(mesh, material, new RevisionedModelSource.FixedSource(matrix),
                castShadows, drawBinding);
    }

    /** 创建默认投射阴影的固定矩阵对象。 */
    public static SceneObject fixed(Mesh mesh, Material material, Matrix4fc matrix) {
        return fixed(mesh, material, matrix, true);
    }

    @FunctionalInterface
    public interface ModelUpdater {
        void update(Matrix4f out, int frameIndex);
    }

    public void computeModel(Matrix4f out, int frameIndex) {
        updater.update(out, frameIndex);
    }
}
