package com.kaleblangley.haikalat.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UniformBlockTest {
    @Test
    void alignSizeRoundsUpToAlignment() {
        assertEquals(256, UniformBlock.alignSize(1, 256));
        assertEquals(256, UniformBlock.alignSize(256, 256));
        assertEquals(512, UniformBlock.alignSize(257, 256));
        assertEquals(192, UniformBlock.alignSize(129, 64));
    }
}
