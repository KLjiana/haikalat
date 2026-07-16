package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.sync.GpuFence;
import com.kaleblangley.haikalat.backend.sync.GpuFenceTarget;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphUploadRequest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * render thread 拥有的多页 R8 glyph atlas GPU 存储和上传队列。
 *
 * <p>CPU atlas 只向本类提交不可变 {@link GlyphUploadRequest}。纹理创建发生在 render thread，
 * 页面初始化和 glyph region 写入只通过 {@link CommandBuffer#uploadTextureRegion} 录制；本类不会使用
 * {@code custom()}。每批上传末尾插入 typed GPU fence，只有 fence 确认 GPU 已完成整批写入后才向
 * update 线程发布一个不可分割的成功结果。</p>
 *
 * <p>如果承载上传的命令流在 fence 前失败或被放弃，render thread 必须调用
 * {@link UploadSubmission#executionFailed(Throwable)}。失败结果不会暴露 texture id，同一组 request
 * 可以在下一帧原样重试。</p>
 */
public final class UiGlyphAtlasGpu implements AutoCloseable {
    private static final long CLOSE_FENCE_TIMEOUT_NANOS = 1_000_000_000L;

    /** 一次上传 submission 的可观察状态。 */
    public enum SubmissionStatus {
        RECORDED,
        GPU_PENDING,
        SUCCEEDED,
        FAILED
    }

    private final int pageWidth;
    private final int pageHeight;
    private final int pageByteCount;
    private final TexturePage[] pages;
    private final List<Completion> submissions = new ArrayList<>();
    private final Set<Long> inFlightRequestIds = new HashSet<>();
    private final ConcurrentLinkedQueue<UiGlyphUploadResult> completedResults =
            new ConcurrentLinkedQueue<>();
    private long nextSubmissionId = 1L;
    private Thread renderThread;
    private boolean closed;

    /**
     * 创建延迟分配 GL page 的 GPU atlas。
     *
     * @param pageWidth atlas page 宽度
     * @param pageHeight atlas page 高度
     * @param maximumPages 最大 page 数
     */
    public UiGlyphAtlasGpu(int pageWidth, int pageHeight, int maximumPages) {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("glyph atlas page dimensions must be positive");
        }
        if (maximumPages <= 0) {
            throw new IllegalArgumentException("glyph atlas maximumPages must be positive");
        }
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        try {
            pageByteCount = Math.multiplyExact(pageWidth, pageHeight);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("glyph atlas page byte count overflows int", overflow);
        }
        pages = new TexturePage[maximumPages];
    }

    /**
     * 在 render thread 创建所需 R8 page，并把整批 region upload 录制到命令流。
     *
     * <p>首次使用的 page 先通过同一个 typed region-upload 命令清零，保证未写区域具有确定值。
     * 本方法完成后 request 仍不可发布；update 线程必须等待 {@link #drainCompletedResults()} 返回成功。
     * 如果本方法自身抛出运行时异常，它也会先发布整批失败结果，调用方必须放弃当前命令缓冲。</p>
     *
     * @param requests 本批稳定上传请求，不能为空
     * @param commands 当前 render pass 的命令缓冲
     * @return 用于报告命令流执行失败的 submission 句柄
     */
    public UploadSubmission recordUploads(Iterable<GlyphUploadRequest> requests,
                                          CommandBuffer commands) {
        ensureOpen();
        Objects.requireNonNull(commands, "commands");
        List<GlyphUploadRequest> stableRequests = validateAndCopy(requests);
        claimOrAssertRenderThread();
        pollGpuCompletions();
        ensureRequestsAvailable(stableRequests);

        long submissionId = nextSubmissionId;
        nextSubmissionId = Math.addExact(nextSubmissionId, 1L);
        Completion completion = new Completion(submissionId, stableRequests);
        submissions.add(completion);
        for (GlyphUploadRequest request : stableRequests) {
            inFlightRequestIds.add(request.requestId());
        }

        List<CreatedPage> createdPages = new ArrayList<>();
        try {
            createMissingPages(stableRequests, createdPages);
            installCreatedPages(createdPages);
            reservePageInitialization(completion);
            recordPageInitialization(completion, commands);
            for (GlyphUploadRequest request : stableRequests) {
                TexturePage page = pages[request.pageIndex()];
                commands.uploadTextureRegion(page.texture, request.x(), request.y(),
                        request.width(), request.height(), request.payload());
            }
            commands.insertGpuFence(completion);
            return new UploadSubmission(completion);
        } catch (RuntimeException failure) {
            RuntimeException reported = rollbackRecording(completion, createdPages, failure);
            failCompletion(completion, reported);
            throw reported;
        } catch (Error failure) {
            rollbackRecording(completion, createdPages, failure);
            removeAbortedCompletion(completion);
            throw failure;
        }
    }

    /**
     * 在 render thread 非阻塞检查 GPU fence，并发布已经完整结束的批次。
     *
     * @return 本次新发布的成功或失败批次数
     */
    public int pollGpuCompletions() {
        ensureOpen();
        claimOrAssertRenderThread();
        int completed = 0;
        for (Completion completion : submissions) {
            if (completion.status != SubmissionStatus.GPU_PENDING) {
                continue;
            }
            try {
                if (!completion.fence.isSignaled()) {
                    continue;
                }
                completion.fence.close();
                completion.fence = null;
                succeedCompletion(completion);
            } catch (RuntimeException failure) {
                failCompletion(completion, failure);
            }
            completed++;
        }
        pruneTerminalSubmissions();
        return completed;
    }

    /**
     * 返回并移除一个完成结果。该入口只访问并发队列，可由 UI/update 线程调用。
     */
    public Optional<UiGlyphUploadResult> pollCompletedResult() {
        return Optional.ofNullable(completedResults.poll());
    }

    /**
     * 返回并移除当前全部完成结果。该入口只访问并发队列，可由 UI/update 线程调用。
     */
    public List<UiGlyphUploadResult> drainCompletedResults() {
        ArrayList<UiGlyphUploadResult> drained = new ArrayList<>();
        UiGlyphUploadResult result;
        while ((result = completedResults.poll()) != null) {
            drained.add(result);
        }
        return List.copyOf(drained);
    }

    public int pageWidth() {
        return pageWidth;
    }

    public int pageHeight() {
        return pageHeight;
    }

    public int maximumPages() {
        return pages.length;
    }

    /** 返回当前已经创建的 R8 page 数量。 */
    public int createdPageCount() {
        int count = 0;
        for (TexturePage page : pages) {
            if (page != null) count++;
        }
        return count;
    }

    /**
     * 把 display list 中的逻辑 atlas page index 解析为实际 R8 texture id。
     *
     * <p>该入口只允许 render thread 调用；GL texture id 不得写回 CPU glyph、display list 或
     * 跨线程 snapshot。{@link UiRenderer} 在提交 GLYPH batch 时使用它完成最终映射。</p>
     *
     * @param pageIndex display list 保存的逻辑 atlas page index
     * @return 已完成上传的 R8 texture id
     */
    public int renderTextureId(int pageIndex) {
        ensureOpen();
        assertRenderThread();
        if (pageIndex < 0 || pageIndex >= pages.length) {
            throw new IllegalArgumentException("glyph atlas page index outside GPU cache: " + pageIndex);
        }
        TexturePage page = pages[pageIndex];
        if (page == null || !page.initialized) {
            throw new IllegalStateException(
                    "glyph atlas page " + pageIndex + " is not ready for rendering");
        }
        return page.texture.id();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * 等待已插入的上传 fence 收尾并逆序关闭所有 page；重复调用无副作用。
     * 清理异常以第一个异常为主，其余通过 suppressed 保留。
     */
    @Override
    public void close() {
        if (closed) return;
        if (renderThread != null) assertRenderThread();
        closed = true;
        RuntimeException cleanupFailure = null;
        IllegalStateException closedFailure =
                new IllegalStateException("glyph atlas GPU storage closed before upload completion");

        for (int index = submissions.size() - 1; index >= 0; index--) {
            Completion completion = submissions.get(index);
            if (completion.status == SubmissionStatus.GPU_PENDING && completion.fence != null) {
                try {
                    if (!completion.fence.waitFor(CLOSE_FENCE_TIMEOUT_NANOS)) {
                        cleanupFailure = append(cleanupFailure, new IllegalStateException(
                                "timed out waiting for glyph upload submission " + completion.id));
                    }
                } catch (RuntimeException failure) {
                    cleanupFailure = append(cleanupFailure, failure);
                }
            }
            if (!terminal(completion.status)) {
                RuntimeException fenceFailure = failCompletion(completion, closedFailure);
                cleanupFailure = append(cleanupFailure, fenceFailure);
            }
        }
        submissions.clear();
        inFlightRequestIds.clear();

        for (int pageIndex = pages.length - 1; pageIndex >= 0; pageIndex--) {
            TexturePage page = pages[pageIndex];
            if (page == null) continue;
            try {
                page.texture.close();
            } catch (RuntimeException failure) {
                cleanupFailure = append(cleanupFailure, failure);
            }
            pages[pageIndex] = null;
        }
        if (cleanupFailure != null) throw cleanupFailure;
    }

    /**
     * render thread 用于标记一批命令尚未越过 typed fence 就已经失败或被放弃的句柄。
     */
    public final class UploadSubmission {
        private final Completion completion;

        private UploadSubmission(Completion completion) {
            this.completion = completion;
        }

        public long submissionId() {
            return completion.id;
        }

        public List<GlyphUploadRequest> requests() {
            return completion.requests;
        }

        /** 该状态为 volatile，可由 update 线程用于诊断，但结果仍应通过完成队列消费。 */
        public SubmissionStatus status() {
            return completion.status;
        }

        /**
         * 报告命令流执行失败或被放弃。
         *
         * <p>仅当 executor 尚未到达本批 typed fence 时转换为 FAILED；如果 fence 已经插入，
         * 说明本批上传命令完整执行，后续命令的失败不会回滚本批。</p>
         *
         * @param failure 命令流失败原因
         * @return 本次调用是否把 submission 转换为失败终态
         */
        public boolean executionFailed(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            assertRenderThread();
            if (completion.status != SubmissionStatus.RECORDED) {
                return false;
            }
            failCompletion(completion, failure);
            pruneTerminalSubmissions();
            return true;
        }
    }

    private List<GlyphUploadRequest> validateAndCopy(Iterable<GlyphUploadRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        ArrayList<GlyphUploadRequest> copied = new ArrayList<>();
        HashSet<Long> ids = new HashSet<>();
        for (GlyphUploadRequest request : requests) {
            Objects.requireNonNull(request, "request");
            if (request.pageIndex() < 0 || request.pageIndex() >= pages.length) {
                throw new IllegalArgumentException("glyph upload page " + request.pageIndex()
                        + " exceeds GPU atlas maximum " + pages.length);
            }
            if (request.glyphPlacement().pageWidth() != pageWidth
                    || request.glyphPlacement().pageHeight() != pageHeight) {
                throw new IllegalArgumentException("glyph upload page dimensions "
                        + request.glyphPlacement().pageWidth() + 'x'
                        + request.glyphPlacement().pageHeight() + " do not match GPU atlas "
                        + pageWidth + 'x' + pageHeight);
            }
            int required = Math.multiplyExact(request.width(), request.height());
            if (request.uploadByteCount() != required) {
                throw new IllegalArgumentException("glyph upload payload size does not match region");
            }
            if (!ids.add(request.requestId())) {
                throw new IllegalArgumentException(
                        "duplicate glyph upload request " + request.requestId());
            }
            copied.add(request);
        }
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("glyph upload batch must not be empty");
        }
        return List.copyOf(copied);
    }

    private void ensureRequestsAvailable(List<GlyphUploadRequest> requests) {
        for (GlyphUploadRequest request : requests) {
            if (inFlightRequestIds.contains(request.requestId())) {
                throw new IllegalStateException(
                        "glyph upload request is already in flight: " + request.requestId());
            }
            TexturePage page = pages[request.pageIndex()];
            if (page != null && !page.initialized && page.initializingSubmission != 0L) {
                throw new IllegalStateException("glyph atlas page " + request.pageIndex()
                        + " is awaiting initialization by submission "
                        + page.initializingSubmission);
            }
        }
    }

    private void createMissingPages(List<GlyphUploadRequest> requests,
                                    List<CreatedPage> createdPages) {
        HashSet<Integer> planned = new HashSet<>();
        for (GlyphUploadRequest request : requests) {
            int pageIndex = request.pageIndex();
            if (pages[pageIndex] == null && planned.add(pageIndex)) {
                createdPages.add(new CreatedPage(pageIndex,
                        new TexturePage(Texture2D.createR8(pageWidth, pageHeight))));
            }
        }
    }

    private void installCreatedPages(List<CreatedPage> createdPages) {
        for (CreatedPage created : createdPages) {
            if (pages[created.index] != null) {
                throw new IllegalStateException("glyph atlas page was created concurrently");
            }
            pages[created.index] = created.page;
            created.installed = true;
        }
    }

    private void reservePageInitialization(Completion completion) {
        for (GlyphUploadRequest request : completion.requests) {
            TexturePage page = pages[request.pageIndex()];
            if (!page.initialized && page.initializingSubmission == 0L) {
                page.initializingSubmission = completion.id;
                completion.pagesToInitialize.add(request.pageIndex());
            } else if (!page.initialized && page.initializingSubmission != completion.id) {
                throw new IllegalStateException("glyph atlas page " + request.pageIndex()
                        + " is already being initialized");
            }
        }
    }

    private void recordPageInitialization(Completion completion, CommandBuffer commands) {
        if (completion.pagesToInitialize.isEmpty()) return;
        ByteBuffer zeroPage = ByteBuffer.allocateDirect(pageByteCount).asReadOnlyBuffer();
        for (int pageIndex : completion.pagesToInitialize) {
            commands.uploadTextureRegion(pages[pageIndex].texture,
                    0, 0, pageWidth, pageHeight, zeroPage);
        }
    }

    private RuntimeException rollbackRecording(Completion completion,
                                               List<CreatedPage> createdPages,
                                               RuntimeException failure) {
        releaseInitializationReservations(completion, false);
        for (int index = createdPages.size() - 1; index >= 0; index--) {
            CreatedPage created = createdPages.get(index);
            if (created.installed && pages[created.index] == created.page) {
                pages[created.index] = null;
            }
            try {
                created.page.texture.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        return failure;
    }

    private void rollbackRecording(Completion completion,
                                   List<CreatedPage> createdPages,
                                   Error failure) {
        releaseInitializationReservations(completion, false);
        for (int index = createdPages.size() - 1; index >= 0; index--) {
            CreatedPage created = createdPages.get(index);
            if (created.installed && pages[created.index] == created.page) {
                pages[created.index] = null;
            }
            try {
                created.page.texture.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    private void succeedCompletion(Completion completion) {
        if (completion.status != SubmissionStatus.GPU_PENDING) {
            throw new IllegalStateException("glyph upload cannot succeed from " + completion.status);
        }
        releaseInitializationReservations(completion, true);
        releaseRequestIds(completion);
        completion.status = SubmissionStatus.SUCCEEDED;
        LinkedHashMap<Integer, Integer> pageTextures = new LinkedHashMap<>();
        for (GlyphUploadRequest request : completion.requests) {
            pageTextures.putIfAbsent(request.pageIndex(),
                    pages[request.pageIndex()].texture.id());
        }
        completedResults.add(UiGlyphUploadResult.succeeded(
                completion.id, completion.requests, pageTextures));
    }

    /** 返回 fence 清理异常；没有异常时返回 null。 */
    private RuntimeException failCompletion(Completion completion, Throwable failure) {
        if (terminal(completion.status)) return null;
        RuntimeException reported = new IllegalStateException(
                "glyph upload submission " + completion.id + " failed", failure);
        RuntimeException cleanupFailure = null;
        if (completion.fence != null) {
            try {
                completion.fence.close();
            } catch (RuntimeException cleanup) {
                reported.addSuppressed(cleanup);
                cleanupFailure = cleanup;
            }
            completion.fence = null;
        }
        releaseInitializationReservations(completion, false);
        releaseRequestIds(completion);
        completion.status = SubmissionStatus.FAILED;
        completedResults.add(UiGlyphUploadResult.failed(
                completion.id, completion.requests, reported));
        return cleanupFailure;
    }

    private void releaseInitializationReservations(Completion completion, boolean initialized) {
        for (int pageIndex : completion.pagesToInitialize) {
            TexturePage page = pages[pageIndex];
            if (page == null || page.initializingSubmission != completion.id) continue;
            if (initialized) page.initialized = true;
            page.initializingSubmission = 0L;
        }
    }

    private void releaseRequestIds(Completion completion) {
        for (GlyphUploadRequest request : completion.requests) {
            inFlightRequestIds.remove(request.requestId());
        }
    }

    private void removeAbortedCompletion(Completion completion) {
        releaseInitializationReservations(completion, false);
        releaseRequestIds(completion);
        completion.status = SubmissionStatus.FAILED;
        submissions.remove(completion);
    }

    private void pruneTerminalSubmissions() {
        Iterator<Completion> iterator = submissions.iterator();
        while (iterator.hasNext()) {
            if (terminal(iterator.next().status)) iterator.remove();
        }
    }

    private void claimOrAssertRenderThread() {
        if (renderThread == null) {
            renderThread = Thread.currentThread();
        } else {
            assertRenderThread();
        }
    }

    private void assertRenderThread() {
        if (renderThread != Thread.currentThread()) {
            String owner = renderThread == null ? "<unclaimed>" : renderThread.getName();
            throw new IllegalStateException("UiGlyphAtlasGpu is owned by render thread "
                    + owner + " but accessed from " + Thread.currentThread().getName());
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UiGlyphAtlasGpu is closed");
    }

    private static boolean terminal(SubmissionStatus status) {
        return status == SubmissionStatus.SUCCEEDED || status == SubmissionStatus.FAILED;
    }

    private static RuntimeException append(RuntimeException current, RuntimeException next) {
        if (next == null) return current;
        if (current == null) return next;
        if (current != next) current.addSuppressed(next);
        return current;
    }

    private final class Completion implements GpuFenceTarget {
        private final long id;
        private final List<GlyphUploadRequest> requests;
        private final List<Integer> pagesToInitialize = new ArrayList<>();
        private volatile SubmissionStatus status = SubmissionStatus.RECORDED;
        private GpuFence fence;

        private Completion(long id, List<GlyphUploadRequest> requests) {
            this.id = id;
            this.requests = requests;
        }

        /** 由 CommandExecutor 在本批所有 upload 命令执行后调用。 */
        @Override
        public void insertGpuFence() {
            assertRenderThread();
            if (status == SubmissionStatus.FAILED) {
                return;
            }
            if (status != SubmissionStatus.RECORDED) {
                throw new IllegalStateException("glyph upload fence inserted from " + status);
            }
            try {
                fence = GpuFence.insert();
                status = SubmissionStatus.GPU_PENDING;
            } catch (RuntimeException failure) {
                failCompletion(this, failure);
                throw failure;
            }
        }

        @Override
        public void executionFailed(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            assertRenderThread();
            if (status == SubmissionStatus.RECORDED) {
                failCompletion(this, failure);
                pruneTerminalSubmissions();
            }
        }
    }

    private static final class TexturePage {
        private final Texture2D texture;
        private boolean initialized;
        private long initializingSubmission;

        private TexturePage(Texture2D texture) {
            this.texture = Objects.requireNonNull(texture, "texture");
        }
    }

    private static final class CreatedPage {
        private final int index;
        private final TexturePage page;
        private boolean installed;

        private CreatedPage(int index, TexturePage page) {
            this.index = index;
            this.page = page;
        }
    }
}
