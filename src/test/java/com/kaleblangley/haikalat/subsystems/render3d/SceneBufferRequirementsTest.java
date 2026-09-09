package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneBufferRequirementsTest {
    @Test
    void velocityImpliesValidationCompanions() {
        SceneBufferRequirements requirements = SceneBufferRequirements
                .of(SceneBufferChannel.VELOCITY).validated();
        assertTrue(requirements.requires(SceneBufferChannel.VELOCITY));
        assertTrue(requirements.requires(SceneBufferChannel.PREVIOUS_SURFACE_DEPTH));
        assertTrue(requirements.requires(SceneBufferChannel.VALIDITY));
        assertFalse(requirements.requires(SceneBufferChannel.REACTIVE));
    }

    @Test
    void mergeIsCommutativeForChannels() {
        SceneBufferRequirements a = SceneBufferRequirements.fog();
        SceneBufferRequirements b = SceneBufferRequirements.gtao(true);
        assertEquals(a.merge(b).channels(), b.merge(a).channels());
        assertTrue(a.merge(b).requires(SceneBufferChannel.DEPTH));
        assertTrue(a.merge(b).requires(SceneBufferChannel.NORMAL));
    }

    @Test
    void noneRequiresNoSurfacePass() {
        SceneBufferRequirements none = SceneBufferRequirements.none();
        assertTrue(none.isEmpty());
        assertFalse(none.requiresSurfacePass());
        assertFalse(SceneBufferRequirements.none().withSamples(4).requiresSurfacePass());
    }

    @Test
    void gtaoAndTaaMergeCoversSharedChannels() {
        SceneBufferRequirements merged = SceneBufferRequirements.gtao(true)
                .merge(SceneBufferRequirements.taa(false));
        for (SceneBufferChannel channel : new SceneBufferChannel[]{
                SceneBufferChannel.DEPTH, SceneBufferChannel.NORMAL,
                SceneBufferChannel.VELOCITY, SceneBufferChannel.PREVIOUS_SURFACE_DEPTH,
                SceneBufferChannel.VALIDITY}) {
            assertTrue(merged.requires(channel), channel.name());
        }
        assertFalse(merged.requires(SceneBufferChannel.REACTIVE));
    }

    @Test
    void invalidSamplesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SceneBufferRequirements.of(SceneBufferChannel.DEPTH).withSamples(0));
    }
}
