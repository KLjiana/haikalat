package com.kaleblangley.haikalat.backend.buffer;

import java.nio.ByteBuffer;

/** core 上传调度器可接受的 backend buffer 接口。 */
public interface BufferUploadTarget {
    int id();

    BufferUploadTarget update(long offsetBytes, ByteBuffer data);
}
