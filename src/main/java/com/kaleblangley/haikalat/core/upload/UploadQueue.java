package com.kaleblangley.haikalat.core.upload;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;

import java.nio.FloatBuffer;
import java.util.Objects;

@Deprecated
public final class UploadQueue {
    private final UploadSystem system;

    public UploadQueue() { this.system = new UploadSystem(); }

    UploadSystem system() { return system; }

    public void submit(UploadRequest request) {
        Objects.requireNonNull(request, "request");
        system.submit(request::execute);
    }

    public void uploadBuffer(GlBuffer buffer, long offset, FloatBuffer data) {
        system.uploadFloats(buffer, offset, data);
    }

    public int pendingCount() { return system.pendingCount(); }
    public void flush() { system.flush(); }
    public void clear() { system.clear(); }

    @FunctionalInterface
    public interface UploadRequest { void execute(); }
}
