package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** 仅供 scene snapshot 识别的保守 revisioned model source。 */
interface RevisionedModelSource extends SceneObject.ModelUpdater {
    long revision();

    final class TransformSource implements RevisionedModelSource {
        private final Transform transform;

        TransformSource(Transform transform) {
            this.transform = transform;
        }

        @Override public long revision() { return transform.revision(); }
        @Override public void update(Matrix4f out, int frameIndex) { transform.matrix(out); }
    }

    final class FixedSource implements RevisionedModelSource {
        private final Matrix4f matrix;

        FixedSource(Matrix4fc matrix) {
            this.matrix = new Matrix4f(matrix);
            BoundsTransforms.requireFiniteAffine(this.matrix);
        }

        @Override public long revision() { return 0L; }
        @Override public void update(Matrix4f out, int frameIndex) { out.set(matrix); }
    }
}
