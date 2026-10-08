package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.texture.Texture3D;

import java.util.Objects;

import static org.lwjgl.opengl.GL46.*;

/** Exclusive owner of volume storage; candidates publish all textures/buffers together. */
final class VolumetricResources implements AutoCloseable {
    static final int PARAMETERS_BYTES = 4096;
    static final int VOLUMES_BYTES = 1024;
    static final int LIGHT_HINTS_BYTES = 256 * 16;
    static final int DIRTY_DOMAINS_BYTES = 2048;
    static final int DIAGNOSTICS_BYTES = 4096;
    static final int PARAMETER_BYTES = PARAMETERS_BYTES + VOLUMES_BYTES + LIGHT_HINTS_BYTES
            + DIRTY_DOMAINS_BYTES + DIAGNOSTICS_BYTES;
    private final long memoryBudget;
    private final long transactionBudget;
    private final int lightHintsBytes;
    private Storage storage;
    private long residentBytes;
    private long revision;
    private int readIndex;
    private boolean historyValid;
    private boolean historyStaged;

    VolumetricResources(int nx, int ny, int nz, boolean emission, long memoryBudget, long transactionBudget) {
        this(nx, ny, nz, emission, memoryBudget, transactionBudget, 256);
    }

    VolumetricResources(int nx, int ny, int nz, boolean emission, long memoryBudget, long transactionBudget, int lightCapacity) {
        if (lightCapacity < 1) throw new IllegalArgumentException("volume light capacity must be positive");
        lightHintsBytes = Math.multiplyExact(lightCapacity, LightVolumePacker.RECORD_BYTES);
        if (memoryBudget <= 0 || transactionBudget < memoryBudget) {
            throw new IllegalArgumentException("invalid volumetric allocation budgets");
        }
        this.memoryBudget = memoryBudget;
        this.transactionBudget = transactionBudget;
        requireBudget(nx, ny, nz, emission, 0L);
        storage = new Storage(nx, ny, nz, emission, lightHintsBytes);
        residentBytes = storage.bytes;
    }

    static long estimatedBytes(int nx, int ny, int nz, boolean emission) {
        return estimatedBytes(nx, ny, nz, emission, LIGHT_HINTS_BYTES);
    }

    private static long estimatedBytes(int nx, int ny, int nz, boolean emission, int hintsBytes) {
        long rgba = Texture3D.estimatedByteSize(nx, ny, nz, RenderFormat.RGBA16F);
        // Boundaries, depth guide, three byte-encoded masks and sixteen exact light indices.
        long prefix = Texture3D.estimatedByteSize(nx, ny, Math.addExact(nz, 9), RenderFormat.RGBA16F);
        long reactivePrefix=Texture3D.estimatedByteSize(nx,ny,Math.addExact(nz,1),RenderFormat.R8);
        return Math.addExact(reactivePrefix,Math.addExact(PARAMETER_BYTES-LIGHT_HINTS_BYTES+hintsBytes, Math.addExact(prefix,
                Math.addExact(Math.multiplyExact(rgba, emission ? 6L : 5L),
                        Math.multiplyExact(Texture3D.estimatedByteSize(nx, ny, nz, RenderFormat.R8),2)))));
    }

    Texture3D medium() { ensureOpen(); return storage.textures[0]; }
    Texture3D emission() { ensureOpen(); return storage.textures[1]; }
    Texture3D source() { ensureOpen(); return storage.textures[2]; }
    Texture3D historyRead() { ensureOpen(); return storage.textures[3 + readIndex]; }
    Texture3D historyWrite() { ensureOpen(); return storage.textures[4 - readIndex]; }
    Texture3D prefix() { ensureOpen(); return storage.textures[5]; }
    Texture3D reject() { ensureOpen(); return storage.textures[6]; }
    Texture3D reactivePrefix() { ensureOpen(); return storage.textures[7]; }
    Texture3D lightOffReactive() { ensureOpen(); return storage.textures[8]; }
    GlBuffer parameters() { ensureOpen(); return storage.buffers[0]; }
    GlBuffer volumes() { ensureOpen(); return storage.buffers[1]; }
    GlBuffer lightHints() { ensureOpen(); return storage.buffers[2]; }
    int lightHintsBytes() { return lightHintsBytes; }
    GlBuffer dirtyDomains() { ensureOpen(); return storage.buffers[3]; }
    GlBuffer diagnostics() { ensureOpen(); return storage.buffers[4]; }
    long bytes() { ensureOpen(); return storage.bytes; }
    long residentBytes() { ensureOpen(); return residentBytes; }
    long revision() { ensureOpen(); return revision; }
    boolean historyValid() { ensureOpen(); return historyValid; }

    /** Called only after the temporal output dispatch has completed recording/execution. */
    void stageHistoryWrite() { ensureOpen(); historyStaged = true; }

    /** The owning successful-frame transaction is the only caller allowed to publish history. */
    void commitHistory() {
        ensureOpen();
        if (!historyStaged) return;
        readIndex = 1 - readIndex;
        historyValid = true;
        historyStaged = false;
    }

    void discardHistory() { ensureOpen(); historyStaged = false; historyValid = false; }

    ResizeCandidate prepareResize(int nx, int ny, int nz, boolean emission) {
        ensureOpen();
        if (storage.nx == nx && storage.ny == ny && storage.nz == nz && storage.emission == emission) {
            return new ResizeCandidate(this, storage, null);
        }
        requireBudget(nx, ny, nz, emission, residentBytes);
        Storage candidate = new Storage(nx, ny, nz, emission, lightHintsBytes);
        residentBytes += candidate.bytes;
        return new ResizeCandidate(this, storage, candidate);
    }

    void commitResize(ResizeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    private void requireBudget(int nx, int ny, int nz, boolean emission, long activeBytes) {
        long requested = estimatedBytes(nx, ny, nz, emission, lightHintsBytes);
        if (requested > memoryBudget || Math.addExact(activeBytes, requested) > transactionBudget) {
            throw new IllegalArgumentException("volumetric resources exceed allocation/transaction budget: "
                    + requested + " + active " + activeBytes);
        }
    }

    private void ensureOpen() { if (storage == null) throw new IllegalStateException("volumetric resources are closed"); }

    @Override public void close() {
        if (storage == null) return;
        Storage retired = storage;
        storage = null;
        historyValid = false; historyStaged = false;
        try { retired.close(); }
        finally { residentBytes -= retired.bytes; }
    }

    static final class ResizeCandidate implements AutoCloseable {
        private final VolumetricResources owner;
        private final Storage expected;
        private Storage candidate;
        private Storage retired;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(VolumetricResources owner, Storage expected, Storage candidate) {
            this.owner = owner; this.expected = expected; this.candidate = candidate;
        }

        private void commitInto(VolumetricResources target) {
            if (target != owner) throw new IllegalArgumentException("volumetric resize candidate owner mismatch");
            owner.ensureOpen();
            if (closed || committed) throw new IllegalStateException("volumetric resize candidate already consumed");
            if (owner.storage != expected) throw new IllegalStateException("volumetric resize candidate is stale");
            if (candidate != null) {
                retired = owner.storage;
                owner.storage = candidate;
                candidate = null;
                owner.readIndex = 0; owner.historyValid = false; owner.historyStaged = false;
                owner.revision++;
            }
            committed = true;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            long released = (candidate == null ? 0L : candidate.bytes) + (retired == null ? 0L : retired.bytes);
            Throwable failure = closeCollecting(candidate, null);
            failure = closeCollecting(retired, failure);
            candidate = null; retired = null;
            owner.residentBytes -= released;
            rethrow(failure);
        }
    }

    private static final class Storage implements AutoCloseable {
        final int nx, ny, nz;
        final boolean emission;
        final long bytes;
        final Texture3D[] textures = new Texture3D[9];
        final GlBuffer[] buffers = new GlBuffer[5];
        boolean closed;

        Storage(int nx, int ny, int nz, boolean emission, int hintsBytes) {
            this.nx = nx; this.ny = ny; this.nz = nz; this.emission = emission;
            bytes = estimatedBytes(nx, ny, nz, emission, hintsBytes);
            try {
                for (int index = 0; index < textures.length; index++) {
                    if (index == 1 && !emission) continue;
                    injectAllocationFailure(index);
                    textures[index] = Texture3D.create(nx, ny, index == 5 ? Math.addExact(nz, 9) : index == 7 ? Math.addExact(nz, 1) : nz,
                            index >= 6 ? RenderFormat.R8 : index == 2 ? RenderFormat.RGBA32F : RenderFormat.RGBA16F);
                }
                int[] sizes = {PARAMETERS_BYTES, VOLUMES_BYTES, hintsBytes, DIRTY_DOMAINS_BYTES, DIAGNOSTICS_BYTES};
                for (int index = 0; index < buffers.length; index++) {
                    injectAllocationFailure(textures.length + index);
                    buffers[index] = new GlBuffer(index == 0 ? GL_UNIFORM_BUFFER : GL_SHADER_STORAGE_BUFFER, GL_DYNAMIC_DRAW);
                    buffers[index].allocateStorage(sizes[index], GL_DYNAMIC_STORAGE_BIT);
                    GlDebug.assertNoError("volumetric parameter allocation");
                    if (glGetNamedBufferParameteri(buffers[index].id(), GL_BUFFER_SIZE) != sizes[index]) {
                        throw new IllegalStateException("volumetric parameter storage allocation was not completed");
                    }
                }
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            for (int index = buffers.length - 1; index >= 0; index--) failure = closeCollecting(buffers[index], failure);
            for (int index = textures.length - 1; index >= 0; index--) failure = closeCollecting(textures[index], failure);
            rethrow(failure);
        }
    }

    private static void injectAllocationFailure(int index) {
        if (Integer.toString(index).equals(System.getProperty("haikalat.test.failVolumeAllocation"))) {
            System.clearProperty("haikalat.test.failVolumeAllocation");
            throw new IllegalStateException("injected volume allocation failure at " + index);
        }
    }

    private static Throwable closeCollecting(AutoCloseable resource, Throwable failure) {
        if (resource == null) return failure;
        try { resource.close(); }
        catch (Exception | Error cleanup) {
            if (failure == null) return cleanup;
            failure.addSuppressed(cleanup);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new IllegalStateException("volumetric resource cleanup failed", failure);
    }
}
