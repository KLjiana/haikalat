package com.kaleblangley.haikalat.backend.buffer;

import java.nio.ByteBuffer;

/** Backend buffer surface accepted by the core upload scheduler. */
public interface BufferUploadTarget {
    int id();

    BufferUploadTarget update(long offsetBytes, ByteBuffer data);
}
