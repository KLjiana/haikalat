package com.kaleblangley.haikalat.subsystems.render3d.geometry;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the per-object displacement binding: default-off no-op,
 * bounded visibility padding/revision, committed previous time and delegation
 * to an existing skin/morph binding.
 */
class VertexDisplacementDrawBindingTest {
    @Test
    void disabledBindingIsANoOpForBoundsAndDeformation() {
        VertexDisplacementDrawBinding binding = new VertexDisplacementDrawBinding(
                VertexDisplacementSettings.disabled());
        assertFalse(binding.deformsVertices());
        assertEquals(0.0f, binding.boundsPadding());
        assertEquals(0L, binding.boundsRevision());
        assertFalse(binding.previousDeformationAvailable());
        assertFalse(binding.hasPreviousTime());
        assertFalse(binding.skinningEnabled());
        assertEquals(0, binding.morphTargetCount());
    }

    @Test
    void activeBindingPadsBoundsAndRejectsNesting() {
        VertexDisplacementSettings settings = VertexDisplacementSettings.localSinusoidal(
                0.12f, 1.0f, 0.5f, 0.2f);
        VertexDisplacementDrawBinding binding = new VertexDisplacementDrawBinding(settings);
        assertTrue(binding.deformsVertices());
        assertEquals(0.12f, binding.boundsPadding(), 0.0f);
        assertEquals(0L, binding.boundsRevision());

        binding.update(VertexDisplacementSettings.localSinusoidal(0.2f, 1.0f, 0.5f, 0.2f),
                1.0f);
        assertEquals(0.2f, binding.boundsPadding(), 0.0f);
        assertEquals(1L, binding.boundsRevision());
        assertThrows(IllegalArgumentException.class,
                () -> new VertexDisplacementDrawBinding(settings, binding));
    }

    @Test
    void committedPreviousTimeTracksOnlySuccessfulFrames() {
        VertexDisplacementSettings settings = VertexDisplacementSettings.localSinusoidal(
                0.1f, 1.0f, 1.0f, 0.0f);
        VertexDisplacementDrawBinding binding = new VertexDisplacementDrawBinding(settings);
        binding.update(settings, 1.0f);
        binding.prepareTemporalFrame();
        assertFalse(binding.hasPreviousTime(), "first frame has no committed history");
        binding.commitTemporalFrame();
        assertTrue(binding.hasPreviousTime());
        assertEquals(1.0f, binding.previousTimeSeconds(), 0.0f);

        binding.update(settings, 2.0f);
        binding.prepareTemporalFrame();
        assertEquals(1.0f, binding.previousTimeSeconds(), 0.0f,
                "previous time must not advance before the frame commits");
        binding.discardTemporalFrame();
        assertEquals(1.0f, binding.previousTimeSeconds(), 0.0f,
                "a discarded frame must keep the committed previous time");
        binding.prepareTemporalFrame();
        binding.commitTemporalFrame();
        assertEquals(2.0f, binding.previousTimeSeconds(), 0.0f);
    }

    @Test
    void modelEditInvalidatesPreviousDeformationButTimeAdvanceDoesNot() {
        VertexDisplacementSettings settings = VertexDisplacementSettings.localSinusoidal(
                0.1f, 1.0f, 1.0f, 0.0f);
        VertexDisplacementDrawBinding binding = new VertexDisplacementDrawBinding(settings);
        binding.update(settings, 0.0f);
        binding.prepareTemporalFrame();
        binding.commitTemporalFrame();
        assertTrue(binding.previousDeformationAvailable());

        binding.update(settings, 0.5f);
        assertTrue(binding.previousDeformationAvailable(),
                "same model, later time keeps the committed deformation valid");

        VertexDisplacementSettings edited = VertexDisplacementSettings.localSinusoidal(
                0.2f, 1.0f, 1.0f, 0.0f);
        binding.update(edited, 1.0f);
        assertFalse(binding.hasPreviousTime(),
                "changing amplitude must not mix two deformation models");
        assertFalse(binding.previousDeformationAvailable());
        assertEquals(1L, binding.boundsRevision());
    }

    @Test
    void delegatesSkinMorphAndTemporalToBaseBinding() {
        FakeBinding base = new FakeBinding();
        VertexDisplacementDrawBinding binding = new VertexDisplacementDrawBinding(
                VertexDisplacementSettings.localSinusoidal(0.1f, 1.0f, 0.5f, 0.0f), base);
        assertTrue(binding.skinningEnabled());
        assertEquals(2, binding.morphTargetCount());
        assertEquals(31L * 7L, binding.boundsRevision());
        assertEquals(0.25f, binding.boundsPadding(), 0.0f);

        binding.prepareTemporalFrame();
        binding.prepareTemporalCommit();
        binding.commitTemporalFrame();
        assertEquals(1, base.prepareCount);
        assertEquals(1, base.prepareCommitCount);
        assertEquals(1, base.commitCount);
        assertTrue(binding.previousDeformationAvailable());

        base.previousAvailable = false;
        assertFalse(binding.previousDeformationAvailable(),
                "both displacement and base skin/morph history must be valid");
        binding.discardTemporalFrame();
        binding.invalidatePrevious();
        assertEquals(1, base.discardCount);
        assertEquals(1, base.invalidateCount);
        assertFalse(binding.hasPreviousTime());
    }

    private static final class FakeBinding implements SceneDrawBinding, TemporalDrawBinding {
        private long revision = 7L;
        private boolean previousAvailable = true;
        private int prepareCount;
        private int prepareCommitCount;
        private int commitCount;
        private int discardCount;
        private int invalidateCount;

        @Override
        public void record(CommandBuffer commands, ShaderProgram shader, int frameIndex, Pass pass) {
        }

        @Override
        public boolean skinningEnabled() {
            return true;
        }

        @Override
        public int morphTargetCount() {
            return 2;
        }

        @Override
        public long boundsRevision() {
            return revision;
        }

        @Override
        public float boundsPadding() {
            return 0.25f;
        }

        @Override
        public TemporalDrawBinding temporalBinding() {
            return this;
        }

        @Override
        public boolean previousDeformationAvailable() {
            return previousAvailable;
        }

        @Override
        public void prepareTemporalFrame() {
            prepareCount++;
        }

        @Override
        public void prepareTemporalCommit() {
            prepareCommitCount++;
        }

        @Override
        public void recordPrevious(CommandBuffer commands, ShaderProgram shader) {
        }

        @Override
        public void commitTemporalFrame() {
            commitCount++;
        }

        @Override
        public void discardTemporalFrame() {
            discardCount++;
        }

        @Override
        public void invalidatePrevious() {
            invalidateCount++;
        }
    }
}
