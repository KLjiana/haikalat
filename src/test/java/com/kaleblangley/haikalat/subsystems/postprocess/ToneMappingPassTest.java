package com.kaleblangley.haikalat.subsystems.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 ACES CPU 参考曲线的数值稳定性和单调性。 */
class ToneMappingPassTest {
    @Test
    void acesReferenceIsFiniteMonotonicAndDoesNotHardClipHdrInput() {
        float previous = -1.0f;
        for (float input : new float[]{0.0f, 0.25f, 1.0f, 4.0f, 16.0f}) {
            float mapped = ToneMappingPass.acesChannel(input, 1.0f);
            assertTrue(Float.isFinite(mapped));
            assertTrue(mapped >= previous);
            assertTrue(mapped >= 0.0f && mapped <= 1.0f);
            previous = mapped;
        }
        assertNotEquals(ToneMappingPass.acesChannel(1.0f, 1.0f),
                ToneMappingPass.acesChannel(4.0f, 1.0f));
        assertTrue(Float.isFinite(ToneMappingPass.acesChannel(Float.MAX_VALUE, 1.0f)));
    }

    @Test
    void acesReferenceRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> ToneMappingPass.acesChannel(-1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> ToneMappingPass.acesChannel(Float.NaN, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> ToneMappingPass.acesChannel(1.0f, 0.0f));
    }
}
