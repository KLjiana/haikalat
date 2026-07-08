package com.kaleblangley.haikalat.core.upload;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadSystemTest {
    @Test
    void mergesContinuousUploads() {
        UploadSystem uploads = new UploadSystem();
        FakeTarget target = new FakeTarget(1);

        uploads.uploadBuffer(target, 0, bytes(1, 2));
        uploads.uploadBuffer(target, 2, bytes(3, 4));
        uploads.flush();

        assertEquals(1, target.updates.size());
        assertEquals(0, target.updates.get(0).offset);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, target.updates.get(0).data);
        assertEquals(4, uploads.totalBytesUploaded());
        assertEquals(1, uploads.totalGpuUpdates());
    }

    @Test
    void mergesOverlappingUploadsWithLastWriteWins() {
        UploadSystem uploads = new UploadSystem();
        FakeTarget target = new FakeTarget(1);

        uploads.uploadBuffer(target, 0, bytes(1, 2, 3, 4));
        uploads.uploadBuffer(target, 2, bytes(9, 8));
        uploads.flush();

        assertEquals(1, target.updates.size());
        assertArrayEquals(new byte[]{1, 2, 9, 8}, target.updates.get(0).data);
    }

    @Test
    void keepsSeparatedUploadsWhenRangesDoNotTouch() {
        UploadSystem uploads = new UploadSystem();
        FakeTarget target = new FakeTarget(1);

        uploads.uploadBuffer(target, 0, bytes(1));
        uploads.uploadBuffer(target, 4, bytes(2));
        uploads.flush();

        assertEquals(2, target.updates.size());
        assertEquals(0, target.updates.get(0).offset);
        assertEquals(4, target.updates.get(1).offset);
    }

    @Test
    void aggregatesCustomRequestFailures() {
        UploadSystem uploads = new UploadSystem();
        uploads.submit(() -> { throw new IllegalStateException("first"); });
        uploads.submit(() -> { throw new IllegalArgumentException("second"); });

        RuntimeException failure = assertThrows(RuntimeException.class, uploads::flush);
        assertEquals("first", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("second", failure.getSuppressed()[0].getMessage());
    }

    private static ByteBuffer bytes(int... values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length);
        for (int value : values) {
            buffer.put((byte) value);
        }
        buffer.flip();
        return buffer;
    }

    private record Update(long offset, byte[] data) {}

    private static final class FakeTarget implements BufferUploadTarget {
        private final int id;
        private final List<Update> updates = new ArrayList<>();

        private FakeTarget(int id) {
            this.id = id;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public BufferUploadTarget update(long offsetBytes, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            updates.add(new Update(offsetBytes, copy));
            return this;
        }
    }
}
