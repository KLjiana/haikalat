package com.kaleblangley.haikalat.core.upload;

import java.nio.ByteBuffer;

public interface BufferUploadTarget {
    int id();

    BufferUploadTarget update(long offsetBytes, ByteBuffer data);
}
