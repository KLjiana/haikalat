package com.kaleblangley.haikalat.subsystems.render3d.preview;

import com.kaleblangley.haikalat.runtime.diagnostics.PreviewSummary;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * update/UI 与 render 线程之间只交换逻辑请求和值摘要的 preview 协调器。
 * GPU owner 由 {@link GraphPreviewRenderer} 独占。
 */
public final class GraphPreviewController {
    public static final long IMAGE_ID = 0x48504B505256L;
    private static final int ERROR_CAPACITY = 16;
    private final AtomicReference<Request> request = new AtomicReference<>(Request.idle());
    private final ArrayDeque<String> recentErrors = new ArrayDeque<>(ERROR_CAPACITY);
    private volatile PreviewSourceCatalog catalog;
    private volatile PreviewOutput output;
    private volatile PreviewSummary summary = PreviewSummary.idle();

    public void detailedDiagnostics(boolean enabled) {
        update(value -> value.withDetailed(enabled));
        if (!enabled) clearOutput();
    }

    public void panelVisible(boolean visible) {
        update(value -> value.withPanelVisible(visible));
        if (!visible) clearOutput();
    }

    public void frozen(boolean frozen) {
        update(value -> value.withFrozen(frozen));
    }

    public void paused(boolean paused) {
        update(value -> value.withPaused(paused));
    }

    public void select(PreviewSourceKey key) {
        Objects.requireNonNull(key, "key");
        update(value -> value.withSelection(key));
        clearOutput();
    }

    public void clearSelection() {
        update(value -> value.withSelection(null));
        clearOutput();
    }

    public Optional<PreviewSourceKey> selected() {
        return Optional.ofNullable(request.get().key());
    }

    public PreviewOptions options() { return request.get().options(); }

    public void options(PreviewOptions options) {
        Objects.requireNonNull(options, "options");
        update(value -> value.withOptions(options));
        clearOutput();
    }

    public List<PreviewSourceDescription> sources() {
        PreviewSourceCatalog value = catalog;
        return value == null ? List.of() : value.sources();
    }

    public Optional<PreviewSourceDescription> selectedDescription() {
        PreviewSourceCatalog currentCatalog = catalog;
        PreviewSourceKey key = request.get().key();
        return currentCatalog == null || key == null ? Optional.empty() : currentCatalog.find(key);
    }

    public PreviewSummary summary() { return summary; }

    /**
     * 返回当前 preview-owned output 的瞬时映射。
     * 调用方必须在 render-record 边界重新读取，不得把 native id 缓存到下一帧。
     */
    public Optional<PreviewOutput> output() {
        PreviewOutput value = output;
        return value != null && value.requestRevision() == request.get().revision()
                ? Optional.of(value) : Optional.empty();
    }

    Request request() { return request.get(); }

    void publishCatalog(PreviewSourceCatalog value) {
        catalog = Objects.requireNonNull(value, "value");
    }

    void publishOutput(long requestRevision, int textureId, int samplerId, int width, int height) {
        if (textureId <= 0 || samplerId < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid preview output mapping");
        }
        if (request.get().revision() != requestRevision) return;
        output = new PreviewOutput(requestRevision, textureId, samplerId, width, height);
    }

    void clearOutput() { output = null; }

    void publishSummary(long requestRevision, PreviewSummary value) {
        if (request.get().revision() != requestRevision) return;
        summary = Objects.requireNonNull(value, "value");
    }

    synchronized void reportError(String code, String message) {
        String entry = Objects.requireNonNullElse(code, "UNKNOWN") + ":"
                + Objects.requireNonNullElse(message, "preview failure");
        if (!recentErrors.isEmpty() && recentErrors.getLast().equals(entry)) return;
        if (recentErrors.size() == ERROR_CAPACITY) recentErrors.removeFirst();
        recentErrors.addLast(entry);
    }

    public synchronized List<String> recentErrors() { return List.copyOf(recentErrors); }

    private void update(java.util.function.UnaryOperator<Request> mutation) {
        request.updateAndGet(value -> mutation.apply(value).withRevision(value.revision() + 1L));
    }

    record Request(long revision, boolean detailed, boolean panelVisible, boolean frozen, boolean paused,
                   PreviewSourceKey key, PreviewOptions options) {
        static Request idle() {
            return new Request(0L, false, false, false, false, null, PreviewOptions.defaults());
        }

        boolean active() {
            return detailed && panelVisible && !frozen && !paused && key != null;
        }

        Request withDetailed(boolean value) {
            return new Request(revision, value, panelVisible, frozen, paused, key, options);
        }
        Request withPanelVisible(boolean value) {
            return new Request(revision, detailed, value, frozen, paused, key, options);
        }
        Request withFrozen(boolean value) {
            return new Request(revision, detailed, panelVisible, value, paused, key, options);
        }
        Request withPaused(boolean value) {
            return new Request(revision, detailed, panelVisible, frozen, value, key, options);
        }
        Request withSelection(PreviewSourceKey value) {
            return new Request(revision, detailed, panelVisible, frozen, paused, value, options);
        }
        Request withOptions(PreviewOptions value) {
            return new Request(revision, detailed, panelVisible, frozen, paused, key, value);
        }

        Request withRevision(long value) {
            return new Request(value, detailed, panelVisible, frozen, paused, key, options);
        }
    }

    /** 仅供 render-time UI adapter 使用的 preview-owned 输出映射。 */
    public record PreviewOutput(long requestRevision, int textureId, int samplerId,
                                int width, int height) {
        public PreviewOutput {
            if (requestRevision < 0L || textureId <= 0 || samplerId < 0
                    || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("invalid preview output");
            }
        }
    }
}
