package com.kaleblangley.haikalat.core.upload;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void neverMergesDifferentTargetsThatShareAnId() {
        UploadSystem uploads = new UploadSystem();
        FakeTarget first = new FakeTarget(1);
        FakeTarget second = new FakeTarget(1);

        uploads.uploadBuffer(first, 0, bytes(1));
        uploads.uploadBuffer(second, 1, bytes(2));
        uploads.flush();

        assertEquals(1, first.updates.size());
        assertEquals(1, second.updates.size());
        assertArrayEquals(new byte[]{1}, first.updates.get(0).data);
        assertArrayEquals(new byte[]{2}, second.updates.get(0).data);
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

    @Test
    void publishesMetadataOnlyAfterItsUploadCompletes() {
        UploadSystem uploads = new UploadSystem();
        FakeTarget target = new FakeTarget(1);
        List<String> events = new ArrayList<>();

        uploads.uploadBuffer(target, 0, bytes(7, 8), () -> {
            assertEquals(1, target.updates.size());
            events.add("published");
        });
        uploads.flush();

        assertEquals(List.of("published"), events);
    }

    @Test
    void skipsMetadataPublicationWhenGpuUploadFails() {
        UploadSystem uploads = new UploadSystem();
        List<String> events = new ArrayList<>();
        BufferUploadTarget failing = new BufferUploadTarget() {
            @Override public int id() { return 9; }
            @Override public BufferUploadTarget update(long offsetBytes, ByteBuffer data) {
                throw new IllegalStateException("upload failed");
            }
        };

        uploads.uploadBuffer(failing, 0, bytes(1), () -> events.add("published"));

        assertThrows(IllegalStateException.class, uploads::flush);
        assertTrue(events.isEmpty());
    }

    @Test
    void discardingUploadAlsoCancelsItsPublicationCallback() {
        UploadSystem uploads = new UploadSystem();
        FakeTarget target = new FakeTarget(1);
        List<String> events = new ArrayList<>();
        uploads.uploadBuffer(target, 0, bytes(1), () -> events.add("published"));

        assertEquals(1, uploads.discardUploadsFor(target));
        uploads.flush();

        assertTrue(events.isEmpty());
        assertTrue(target.updates.isEmpty());
    }

    @Test
    void sealRejectsNewWorkButAllowsAcceptedWorkToDrain() {
        UploadSystem uploads = new UploadSystem();
        FakeTarget target = new FakeTarget(1);
        List<String> events = new ArrayList<>();
        uploads.uploadBuffer(target, 0, bytes(4), () -> events.add("published"));

        uploads.seal();

        assertTrue(uploads.isSealed());
        assertThrows(IllegalStateException.class,
                () -> uploads.uploadBuffer(target, 1, bytes(5)));
        uploads.flush();
        assertEquals(1, target.updates.size());
        assertEquals(List.of("published"), events);
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
