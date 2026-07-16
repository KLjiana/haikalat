package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.text.GlyphUploadRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一批 glyph atlas 上传在 render thread 完成后的不可变结果。
 *
 * <p>结果通过并发队列发布，可以由 UI/update 线程安全消费。成功结果覆盖整批 request，
 * update 线程只能在收到成功结果后调用 CPU atlas 的 publish；失败结果同样覆盖整批，
 * 因而不会暴露半有效的 atlas generation。</p>
 */
public final class UiGlyphUploadResult {
    /** 上传批次的终态。 */
    public enum Status {
        SUCCEEDED,
        FAILED
    }

    private final long submissionId;
    private final List<GlyphUploadRequest> requests;
    private final Status status;
    private final Map<Integer, Integer> pageTextureIds;
    private final Throwable failure;

    private UiGlyphUploadResult(long submissionId,
                                List<GlyphUploadRequest> requests,
                                Status status,
                                Map<Integer, Integer> pageTextureIds,
                                Throwable failure) {
        if (submissionId <= 0L) {
            throw new IllegalArgumentException("submissionId must be positive");
        }
        this.submissionId = submissionId;
        this.requests = List.copyOf(Objects.requireNonNull(requests, "requests"));
        if (this.requests.isEmpty()) {
            throw new IllegalArgumentException("glyph upload result must contain requests");
        }
        this.status = Objects.requireNonNull(status, "status");
        LinkedHashMap<Integer, Integer> copiedPages = new LinkedHashMap<>();
        Objects.requireNonNull(pageTextureIds, "pageTextureIds").forEach((page, texture) -> {
            if (page == null || page < 0 || texture == null || texture <= 0) {
                throw new IllegalArgumentException("glyph page and texture ids must be valid");
            }
            copiedPages.put(page, texture);
        });
        this.pageTextureIds = Collections.unmodifiableMap(copiedPages);
        this.failure = failure;
        if (status == Status.SUCCEEDED) {
            if (failure != null) {
                throw new IllegalArgumentException("successful upload cannot carry a failure");
            }
            for (GlyphUploadRequest request : this.requests) {
                Objects.requireNonNull(request, "request");
                if (!this.pageTextureIds.containsKey(request.pageIndex())) {
                    throw new IllegalArgumentException(
                            "successful upload is missing texture for page " + request.pageIndex());
                }
            }
        } else {
            if (failure == null) {
                throw new IllegalArgumentException("failed upload must carry a failure");
            }
            if (!this.pageTextureIds.isEmpty()) {
                throw new IllegalArgumentException("failed upload cannot publish texture ids");
            }
        }
    }

    static UiGlyphUploadResult succeeded(long submissionId,
                                         List<GlyphUploadRequest> requests,
                                         Map<Integer, Integer> pageTextureIds) {
        return new UiGlyphUploadResult(submissionId, requests, Status.SUCCEEDED,
                pageTextureIds, null);
    }

    static UiGlyphUploadResult failed(long submissionId,
                                      List<GlyphUploadRequest> requests,
                                      Throwable failure) {
        return new UiGlyphUploadResult(submissionId, requests, Status.FAILED,
                Map.of(), Objects.requireNonNull(failure, "failure"));
    }

    /** 返回 render-thread submission 的稳定编号。 */
    public long submissionId() {
        return submissionId;
    }

    /** 返回必须作为一个整体处理的稳定 request 列表。 */
    public List<GlyphUploadRequest> requests() {
        return requests;
    }

    /** 返回批次终态。 */
    public Status status() {
        return status;
    }

    /** 判断整批上传是否成功。 */
    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }

    /** 返回成功批次中逻辑 atlas page 对应的稳定 GL texture id。 */
    public int pageTextureId(int pageIndex) {
        if (!succeeded()) {
            throw new IllegalStateException("failed glyph upload has no page texture ids");
        }
        Integer texture = pageTextureIds.get(pageIndex);
        if (texture == null) {
            throw new IllegalArgumentException("glyph upload result does not contain page " + pageIndex);
        }
        return texture;
    }

    /** 返回不可变的 page→texture 诊断映射。 */
    public Map<Integer, Integer> pageTextureIds() {
        return pageTextureIds;
    }

    /** 返回失败原因；成功批次为空。 */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }
}
