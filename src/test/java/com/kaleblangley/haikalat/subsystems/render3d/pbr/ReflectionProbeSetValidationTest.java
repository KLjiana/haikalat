package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the finite construction rules that must hold before any GL work starts. */
class ReflectionProbeSetValidationTest {
    @Test
    void emptyAndBoundarySizedSetsAreValid() {
        assertTrue(ReflectionProbeSet.validate(List.of()).isEmpty());
        List<ReflectionProbeSettings> two = List.of(probe("a"), probe("b"));
        assertEquals(2, ReflectionProbeSet.validate(two).size());
        assertEquals(2, ReflectionProbeSet.activeProbeCount(two));
    }

    @Test
    void moreThanTheFixedProbeCountIsRejected() {
        List<ReflectionProbeSettings> three = List.of(probe("a"), probe("b"), probe("c"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReflectionProbeSet.validate(three));
        assertTrue(failure.getMessage().contains("at most"));
    }

    @Test
    void duplicateNamesAreRejectedEvenWhenOneProbeIsDisabled() {
        assertThrows(IllegalArgumentException.class,
                () -> ReflectionProbeSet.validate(List.of(probe("same"), probe("same"))));
        assertThrows(IllegalArgumentException.class,
                () -> ReflectionProbeSet.validate(List.of(probe("same"),
                        probe("same").disabledCopy())));
    }

    @Test
    void nullEntriesAndNullListsAreRejected() {
        assertThrows(NullPointerException.class, () -> ReflectionProbeSet.validate(null));
        assertThrows(NullPointerException.class,
                () -> ReflectionProbeSet.validate(java.util.Arrays.asList(probe("a"), null)));
    }

    private static ReflectionProbeSettings probe(String name) {
        return ReflectionProbeSettings.box(name, new Vector3f(),
                new Vector3f(1.0f), 0.5f, ReflectionProbeSettings.Quality.TEST,
                1.0f, 0.0f, "/environments/pbr/probe-warm.hdr");
    }
}
