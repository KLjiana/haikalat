package com.kaleblangley.haikalat.runtime.diagnostics;

import java.util.List;
import java.util.Objects;

/** freeze 时捕获的一致、不可变且不暴露 backend 类型的诊断历史。 */
public record FrozenDiagnostics(int schemaVersion, long epoch,
                                List<DiagnosticsSnapshot> history,
                                ResourceTable resources,
                                MessageTable messages,
                                Metadata metadata) {
    public FrozenDiagnostics(int schemaVersion, long epoch, List<DiagnosticsSnapshot> history) {
        this(schemaVersion, epoch, history, ResourceTable.EMPTY,
                MessageTable.EMPTY, Metadata.EMPTY);
    }

    public FrozenDiagnostics {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported schema version");
        if (epoch < 0L) throw new IllegalArgumentException("epoch must be non-negative");
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        resources = Objects.requireNonNull(resources, "resources");
        messages = Objects.requireNonNull(messages, "messages");
        metadata = Objects.requireNonNull(metadata, "metadata");
    }

    public DiagnosticsSnapshot latest() {
        return history.isEmpty() ? DiagnosticsSnapshot.empty(DiagnosticsLevel.OFF)
                : history.get(history.size() - 1);
    }

    /** 当前帧边界对应的资源明细。native id 仅保留在内存，默认导出会省略。 */
    public record Resource(long resourceSequence, String kind, String label, int nativeId,
                           long createdFrameSequence, long estimatedBytes,
                           long contextIdentity) {
        public Resource {
            kind = Objects.requireNonNull(kind, "kind");
            label = Objects.requireNonNull(label, "label");
        }
    }

    public record ResourceTable(List<Resource> live, long estimatedBytes,
                                long createdCount, long closedCount, long highWaterMark) {
        public static final ResourceTable EMPTY = new ResourceTable(List.of(), 0L, 0L, 0L, 0L);

        public ResourceTable {
            live = List.copyOf(Objects.requireNonNull(live, "live"));
        }
    }

    /** 当前帧边界对应的结构化驱动消息。 */
    public record Message(long sequence, String source, String type, String severity,
                          int driverId, String text, long firstFrameSequence,
                          long lastFrameSequence, long repeatCount, long contextIdentity,
                          String phase) {
        public Message {
            source = Objects.requireNonNull(source, "source");
            type = Objects.requireNonNull(type, "type");
            severity = Objects.requireNonNull(severity, "severity");
            text = Objects.requireNonNull(text, "text");
        }
    }

    public record MessageTable(List<Message> entries, long droppedCount) {
        public static final MessageTable EMPTY = new MessageTable(List.of(), 0L);

        public MessageTable {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /** capture 身份与当前 OpenGL context 的稳定值类型元数据。 */
    public record Metadata(String engineVersion, String buildRevision,
                           String glVendor, String glRenderer, String glVersion) {
        public static final Metadata EMPTY = new Metadata(
                "unknown", "unknown", "unavailable", "unavailable", "unavailable");

        public Metadata {
            engineVersion = requireText(engineVersion, "engineVersion");
            buildRevision = requireText(buildRevision, "buildRevision");
            glVendor = requireText(glVendor, "glVendor");
            glRenderer = requireText(glRenderer, "glRenderer");
            glVersion = requireText(glVersion, "glVersion");
        }

        private static String requireText(String value, String name) {
            value = Objects.requireNonNull(value, name).trim();
            if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }
}
