package com.kaleblangley.haikalat.gl.buffer;

import com.kaleblangley.haikalat.util.DirectBuffers;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class UploadQueue {
    private final List<UploadRequest> requests = new ArrayList<>();

    public void submit(UploadRequest request) {
        requests.add(Objects.requireNonNull(request, "request"));
    }

    public void submitBuffer(GlBuffer buffer, FloatBuffer data) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(data, "data");
        requests.add(() -> buffer.update(0, data));
    }

    public int pendingCount() {
        return requests.size();
    }

    public void flush() {
        for (UploadRequest request : requests) {
            request.execute();
        }
        requests.clear();
    }

    public void clear() {
        requests.clear();
    }

    @FunctionalInterface
    public interface UploadRequest {
        void execute();
    }
}
