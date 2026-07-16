package com.kaleblangley.haikalat.subsystems.ui.text;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * UI/update 线程拥有的动态多页 R8 glyph atlas。
 *
 * <p>miss 在 update 线程光栅化并生成不可变 {@link GlyphUploadRequest}；只有 render 上传成功并由
 * update 线程调用 {@link #publishUpload(GlyphUploadRequest)} 后 placement 才对 snapshot 可见。
 * 达到 page 上限时只整页回收没有 pending upload、且没有 in-flight generation 引用的 glyph，
 * 不执行逐帧 repack。</p>
 */
public final class GlyphAtlas implements AutoCloseable {
    private final TextThreadOwner threadOwner = new TextThreadOwner();
    private final GlyphAtlasAllocator allocator;
    private final List<CpuPage> cpuPages = new ArrayList<>();
    private final Map<GlyphKey, Entry> ready = new LinkedHashMap<>();
    private final Map<GlyphKey, Pending> pending = new LinkedHashMap<>();
    private final Object leaseLock = new Object();
    private final TreeMap<Long, Integer> activeGenerations = new TreeMap<>();
    private long generation = 1L;
    private long nextRequestId = 1L;
    private long accessSequence;
    private long hits;
    private long misses;
    private long evictions;
    private long uploadBytes;
    private boolean closed;

    public GlyphAtlas(int pageWidth, int pageHeight, int padding, int maximumPages) {
        Math.multiplyExact(pageWidth, pageHeight);
        allocator = new GlyphAtlasAllocator(pageWidth, pageHeight, padding, maximumPages);
    }

    /** 使用 {@link FontFace#rasterize(GlyphKey)} 处理 atlas miss。 */
    public GlyphAtlasLookup lookup(FontFace face, GlyphKey key) {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(key, "key");
        if (!face.id().equals(key.faceId())) {
            throw new IllegalArgumentException("Glyph key belongs to a different font face");
        }
        return lookup(key, face::rasterize);
    }

    /**
     * 使用可注入 rasterizer 查询 glyph；该入口使 atlas 策略能够在无 native/GL 环境测试。
     */
    public GlyphAtlasLookup lookup(GlyphKey key, GlyphRasterizer rasterizer) {
        checkUsable("GlyphAtlas.lookup");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(rasterizer, "rasterizer");

        Entry cached = ready.get(key);
        if (cached != null) {
            hits = Math.addExact(hits, 1L);
            cached.lastAccess = nextAccessSequence();
            return GlyphAtlasLookup.ready(cached.glyph);
        }
        misses = Math.addExact(misses, 1L);
        Pending alreadyPending = pending.get(key);
        if (alreadyPending != null) {
            return GlyphAtlasLookup.uploadRequired(alreadyPending.request);
        }

        GlyphBitmap bitmap = Objects.requireNonNull(rasterizer.rasterize(key), "rasterizer result");
        if (!key.equals(bitmap.key())) {
            throw new IllegalArgumentException("Rasterizer returned bitmap for a different glyph key");
        }
        if (bitmap.width() == 0 || bitmap.height() == 0) {
            GlyphAtlasGlyph glyph = new GlyphAtlasGlyph(key, generation, Optional.empty(),
                    bitmap.bearingX(), bitmap.bearingY(), bitmap.advanceX(), bitmap.advanceY());
            ready.put(key, new Entry(glyph, nextAccessSequence()));
            return GlyphAtlasLookup.ready(glyph);
        }

        requireFitsPage(bitmap.width(), bitmap.height());
        Optional<GlyphAtlasPlacement> placement = allocator.tryAllocate(bitmap.width(), bitmap.height());
        boolean recycled = false;
        if (placement.isEmpty()) {
            Optional<Integer> recyclablePage = recyclablePage();
            if (recyclablePage.isEmpty()) {
                return GlyphAtlasLookup.blocked(key,
                        "All atlas pages are pending upload or referenced by an in-flight generation");
            }
            recyclePage(recyclablePage.orElseThrow());
            recycled = true;
            placement = allocator.tryAllocate(bitmap.width(), bitmap.height());
            if (placement.isEmpty()) {
                throw new IllegalStateException("Recycled atlas page rejected a prevalidated glyph");
            }
        }
        if (!recycled) {
            bumpGeneration();
        }
        ensureCpuPages();
        GlyphAtlasPlacement allocated = placement.orElseThrow();
        CpuPage page = cpuPages.get(allocated.pageIndex());
        page.write(bitmap, allocated);
        ByteBuffer payload = page.copyRegion(allocated.allocatedX(), allocated.allocatedY(),
                allocated.allocatedWidth(), allocated.allocatedHeight());
        GlyphUploadRequest request = new GlyphUploadRequest(nextRequestId(), bitmap, generation, allocated, payload);
        pending.put(key, new Pending(request));
        return GlyphAtlasLookup.uploadRequired(request);
    }

    /**
     * 标记 render upload 成功并发布 placement；重复或过期 request 会明确失败。
     */
    public GlyphAtlasGlyph publishUpload(GlyphUploadRequest request) {
        checkUsable("GlyphAtlas.publishUpload");
        Pending owned = requirePending(request);
        GlyphAtlasGlyph glyph = new GlyphAtlasGlyph(request.key(), request.generation(),
                Optional.of(request.glyphPlacement()), request.bearingX(), request.bearingY(),
                request.advanceX(), request.advanceY());
        pending.remove(request.key());
        ready.put(request.key(), new Entry(glyph, nextAccessSequence()));
        uploadBytes = Math.addExact(uploadBytes, request.uploadByteCount());
        owned.published = true;
        return glyph;
    }

    /**
     * 标记 upload 失败。request 保持 pending 且 placement 不发布，下一次 lookup 返回同一 request 重试。
     */
    public void uploadFailed(GlyphUploadRequest request) {
        checkUsable("GlyphAtlas.uploadFailed");
        requirePending(request);
    }

    /**
     * 在确认 render thread 不再持有 request 后取消它。shelf 空间会在后续安全整页回收时复用。
     */
    public void cancelUpload(GlyphUploadRequest request) {
        checkUsable("GlyphAtlas.cancelUpload");
        requirePending(request);
        pending.remove(request.key());
    }

    /** 返回当前全部 pending request 的稳定注册顺序快照。 */
    public List<GlyphUploadRequest> pendingUploads() {
        checkUsable("GlyphAtlas.pendingUploads");
        return pending.values().stream().map(value -> value.request).toList();
    }

    /**
     * 为一个即将跨线程发布的 render snapshot 固定当前 generation。
     */
    public GlyphAtlasGenerationLease acquireGeneration() {
        checkUsable("GlyphAtlas.acquireGeneration");
        synchronized (leaseLock) {
            activeGenerations.merge(generation, 1, Math::addExact);
        }
        return new GlyphAtlasGenerationLease(this, generation);
    }

    /** 返回当前 atlas generation。 */
    public long generation() {
        checkUsable("GlyphAtlas.generation");
        return generation;
    }

    /** 返回 CPU 侧完整 R8 page 的防御性只读 direct 快照。 */
    public ByteBuffer pageCoverage(int pageIndex) {
        checkUsable("GlyphAtlas.pageCoverage");
        if (pageIndex < 0 || pageIndex >= cpuPages.size()) {
            throw new IndexOutOfBoundsException("Atlas page index " + pageIndex
                    + " outside [0, " + cpuPages.size() + ")");
        }
        return cpuPages.get(pageIndex).snapshot();
    }

    /** 返回 atlas 累计统计。 */
    public GlyphAtlasStatistics statistics() {
        checkUsable("GlyphAtlas.statistics");
        return new GlyphAtlasStatistics(hits, misses, allocator.pageCount(), evictions, uploadBytes,
                ready.size(), pending.size(), activeLeaseCount());
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * 幂等关闭 CPU atlas。仍有 generation lease 时拒绝关闭，以暴露错误 shutdown 顺序。
     */
    @Override
    public void close() {
        threadOwner.check("GlyphAtlas.close");
        if (closed) {
            return;
        }
        int leases = activeLeaseCount();
        if (leases != 0) {
            throw new IllegalStateException("Cannot close GlyphAtlas while " + leases
                    + " generation lease(s) remain active");
        }
        ready.clear();
        pending.clear();
        cpuPages.clear();
        allocator.clear();
        closed = true;
    }

    void releaseGeneration(long releasedGeneration) {
        synchronized (leaseLock) {
            Integer count = activeGenerations.get(releasedGeneration);
            if (count == null || count <= 0) {
                throw new IllegalStateException("Atlas generation " + releasedGeneration + " is not acquired");
            }
            if (count == 1) {
                activeGenerations.remove(releasedGeneration);
            } else {
                activeGenerations.put(releasedGeneration, count - 1);
            }
        }
    }

    private Pending requirePending(GlyphUploadRequest request) {
        Objects.requireNonNull(request, "request");
        Pending owned = pending.get(request.key());
        if (owned == null || owned.request != request || owned.published) {
            throw new IllegalArgumentException("Upload request is not pending in this atlas: " + request);
        }
        return owned;
    }

    private Optional<Integer> recyclablePage() {
        Integer selectedPage = null;
        long selectedScore = Long.MAX_VALUE;
        for (int pageIndex = 0; pageIndex < allocator.pageCount(); pageIndex++) {
            if (hasPendingOnPage(pageIndex)) {
                continue;
            }
            boolean pinned = false;
            long newestAccess = Long.MIN_VALUE;
            for (Entry entry : ready.values()) {
                Optional<GlyphAtlasPlacement> placement = entry.glyph.placement();
                if (placement.isEmpty() || placement.orElseThrow().pageIndex() != pageIndex) {
                    continue;
                }
                if (isGenerationPinned(entry.glyph.generation())) {
                    pinned = true;
                    break;
                }
                newestAccess = Math.max(newestAccess, entry.lastAccess);
            }
            if (!pinned && (selectedPage == null || newestAccess < selectedScore)) {
                selectedPage = pageIndex;
                selectedScore = newestAccess;
            }
        }
        return Optional.ofNullable(selectedPage);
    }

    private boolean hasPendingOnPage(int pageIndex) {
        for (Pending value : pending.values()) {
            if (value.request.pageIndex() == pageIndex) {
                return true;
            }
        }
        return false;
    }

    private boolean isGenerationPinned(long glyphGeneration) {
        synchronized (leaseLock) {
            Long newestActive = activeGenerations.isEmpty() ? null : activeGenerations.lastKey();
            return newestActive != null && newestActive >= glyphGeneration;
        }
    }

    private void recyclePage(int pageIndex) {
        int removed = 0;
        var iterator = ready.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            Optional<GlyphAtlasPlacement> placement = entry.glyph.placement();
            if (placement.isPresent() && placement.orElseThrow().pageIndex() == pageIndex) {
                iterator.remove();
                removed++;
            }
        }
        evictions = Math.addExact(evictions, removed);
        allocator.clearPage(pageIndex);
        cpuPages.get(pageIndex).clear();
        bumpGeneration();
    }

    private void ensureCpuPages() {
        while (cpuPages.size() < allocator.pageCount()) {
            cpuPages.add(new CpuPage(allocator.pageWidth(), allocator.pageHeight()));
        }
    }

    private void requireFitsPage(int glyphWidth, int glyphHeight) {
        long paddedWidth = (long) glyphWidth + 2L * allocator.padding();
        long paddedHeight = (long) glyphHeight + 2L * allocator.padding();
        if (paddedWidth > allocator.pageWidth() || paddedHeight > allocator.pageHeight()) {
            throw new GlyphAtlasCapacityException("Glyph " + glyphWidth + 'x' + glyphHeight
                    + " with padding " + allocator.padding() + " exceeds atlas page "
                    + allocator.pageWidth() + 'x' + allocator.pageHeight());
        }
    }

    private int activeLeaseCount() {
        synchronized (leaseLock) {
            int total = 0;
            for (int count : activeGenerations.values()) {
                total = Math.addExact(total, count);
            }
            return total;
        }
    }

    private long nextAccessSequence() {
        accessSequence = Math.addExact(accessSequence, 1L);
        return accessSequence;
    }

    private long nextRequestId() {
        long result = nextRequestId;
        if (result <= 0L || result == Long.MAX_VALUE) {
            throw new IllegalStateException("Glyph upload request id space exhausted");
        }
        nextRequestId = result + 1L;
        return result;
    }

    private void bumpGeneration() {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("Glyph atlas generation exhausted");
        }
        generation++;
    }

    private void checkUsable(String operation) {
        threadOwner.check(operation);
        if (closed) {
            throw new IllegalStateException(operation + " cannot use closed GlyphAtlas");
        }
    }

    private static final class Entry {
        private final GlyphAtlasGlyph glyph;
        private long lastAccess;

        private Entry(GlyphAtlasGlyph glyph, long lastAccess) {
            this.glyph = glyph;
            this.lastAccess = lastAccess;
        }
    }

    private static final class Pending {
        private final GlyphUploadRequest request;
        private boolean published;

        private Pending(GlyphUploadRequest request) {
            this.request = request;
        }
    }

    private static final class CpuPage {
        private final int width;
        private final int height;
        private final byte[] coverage;

        private CpuPage(int width, int height) {
            this.width = width;
            this.height = height;
            coverage = new byte[Math.multiplyExact(width, height)];
        }

        private void write(GlyphBitmap bitmap, GlyphAtlasPlacement placement) {
            int regionX = placement.allocatedX();
            int regionY = placement.allocatedY();
            int regionWidth = placement.allocatedWidth();
            int regionHeight = placement.allocatedHeight();
            for (int row = 0; row < regionHeight; row++) {
                int start = (regionY + row) * width + regionX;
                Arrays.fill(coverage, start, start + regionWidth, (byte) 0);
            }
            byte[] source = bitmap.copyCoverage();
            for (int row = 0; row < bitmap.height(); row++) {
                System.arraycopy(source, row * bitmap.width(), coverage,
                        (placement.y() + row) * width + placement.x(), bitmap.width());
            }
        }

        private ByteBuffer copyRegion(int x, int y, int regionWidth, int regionHeight) {
            ByteBuffer result = ByteBuffer.allocate(Math.multiplyExact(regionWidth, regionHeight));
            for (int row = 0; row < regionHeight; row++) {
                result.put(coverage, (y + row) * width + x, regionWidth);
            }
            return result.flip();
        }

        private ByteBuffer snapshot() {
            ByteBuffer result = ByteBuffer.allocateDirect(coverage.length);
            result.put(coverage).flip();
            return result.asReadOnlyBuffer();
        }

        private void clear() {
            Arrays.fill(coverage, (byte) 0);
        }
    }
}
