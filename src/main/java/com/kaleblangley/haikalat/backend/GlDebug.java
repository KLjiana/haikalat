package com.kaleblangley.haikalat.backend;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.Callback;

import java.util.IdentityHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.glfwGetCurrentContext;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class GlDebug {
    private static final Logger LOG = Logger.getLogger(GlDebug.class.getName());
    private static final Map<GLCapabilities, Callback> DEBUG_CALLBACKS = new IdentityHashMap<>();
    private static final Map<GLCapabilities, ContextFacts> CONTEXTS = new IdentityHashMap<>();
    private static final Map<Long, ContextFacts> RESOURCE_OWNERS = new LinkedHashMap<>();
    private static final AtomicLong NEXT_CONTEXT = new AtomicLong(1L);
    private static final AtomicLong NEXT_MESSAGE = new AtomicLong(1L);
    private static final AtomicLong NEXT_RESOURCE = new AtomicLong(1L);
    private static final ThreadLocal<Long> FRAME_SEQUENCE = ThreadLocal.withInitial(() -> -1L);
    private static final ThreadLocal<ArrayDeque<String>> GROUPS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final int MESSAGE_CAPACITY = 256;
    private static final int MAX_MESSAGE_LENGTH = 1024;
    private static final int MAX_LABEL_LENGTH = 160;

    private GlDebug() {
    }

    /**
     * 为当前 context 启用 OpenGL 4.6 debug output。
     *
     * <p>每个 LWJGL capabilities 对象最多安装一次 callback，并在进程生命周期内持有，
     * 防止原生 OpenGL 调用已经被回收的 callback。
     *
     * @return 当前 context 已安装或已经启用 callback 时返回 {@code true}
     */
    public static synchronized boolean enableDebugCallback() {
        GLCapabilities capabilities = currentCapabilitiesOrNull();
        if (capabilities == null) {
            return false;
        }
        if (DEBUG_CALLBACKS.containsKey(capabilities)) {
            return true;
        }

        glEnable(GL_DEBUG_OUTPUT);
        glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
        GLDebugMessageCallback callback = GLDebugMessageCallback.create(GlDebug::handleDebugMessage);
        glDebugMessageCallback(callback, NULL);
        DEBUG_CALLBACKS.put(capabilities, callback);
        LOG.info("OpenGL debug output enabled");
        return true;
    }

    /**
     * 在销毁当前 GLFW context 前解除 callback 并释放该 context 的诊断表。
     * 返回值保留销毁时仍存活资源的值类型摘要，便于 lifecycle 测试。
     */
    public static synchronized ContextRelease releaseCurrentContext() {
        GLCapabilities capabilities = currentCapabilitiesOrNull();
        if (capabilities == null) return ContextRelease.EMPTY;
        ContextFacts facts = CONTEXTS.remove(capabilities);
        Callback callback = DEBUG_CALLBACKS.remove(capabilities);
        if (callback != null) {
            glDebugMessageCallback(null, NULL);
            callback.free();
        }
        ResourceSnapshot resources = facts == null ? ResourceSnapshot.EMPTY : facts.resourceSnapshot();
        MessageSnapshot messages = facts == null ? MessageSnapshot.EMPTY : facts.messageSnapshot();
        if (facts != null) {
            for (Long sequence : List.copyOf(facts.resources.keySet())) RESOURCE_OWNERS.remove(sequence);
        }
        GROUPS.remove();
        FRAME_SEQUENCE.remove();
        if (!resources.liveResources().isEmpty()) {
            LOG.warning("OpenGL context destroyed with " + resources.liveResources().size()
                    + " tracked live resources");
        }
        return new ContextRelease(resources, messages);
    }

    public static void checkError(String context) {
        if (!hasCurrentContext()) {
            return;
        }
        int error;
        while ((error = glGetError()) != GL_NO_ERROR) {
            LOG.warning(formatError(context, error));
        }
    }

    public static void assertNoError(String context) {
        if (!hasCurrentContext()) {
            return;
        }
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new GlException(formatError(context, error));
        }
        DebugMessage high = latestHighSeverityMessageOrNull();
        if (high != null) {
            throw new GlException(context + ": OpenGL " + high.type() + " [HIGH] source="
                    + high.source() + " id=" + high.driverId() + ": " + high.message());
        }
    }

    public static void labelObject(int identifier, int object, String label) {
        if (object == 0 || label == null || label.isBlank() || !hasCurrentContext()) {
            return;
        }
        glObjectLabel(identifier, object, label.trim());
    }

    /** 在当前 context 中压入一个供驱动、RenderDoc 和 Nsight 识别的调试分组。 */
    public static void pushGroup(String label) {
        if (!hasCurrentContext()) return;
        String safe = label == null || label.isBlank() ? "unnamed" : label.trim();
        glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, safe);
        GROUPS.get().addLast(safe);
    }

    /** 弹出当前调试分组。 */
    public static void popGroup() {
        if (hasCurrentContext()) glPopDebugGroup();
        ArrayDeque<String> groups = GROUPS.get();
        if (!groups.isEmpty()) groups.removeLast();
    }

    /** 仅供诊断恢复验证读取当前线程的框架 debug group 深度。 */
    public static int debugGroupDepth() {
        return GROUPS.get().size();
    }

    /** 设置 callback 关联的当前正式渲染帧身份。 */
    public static void frameSequence(long frameSequence) {
        FRAME_SEQUENCE.set(frameSequence);
    }

    /** 返回当前 context 的结构化、有界 GL 消息快照。 */
    public static synchronized MessageSnapshot messages() {
        ContextFacts facts = currentFactsOrNull();
        return facts == null ? MessageSnapshot.EMPTY : facts.messageSnapshot();
    }

    /** 返回当前 context 的框架资源值类型快照。 */
    public static synchronized ResourceSnapshot resources() {
        ContextFacts facts = currentFactsOrNull();
        return facts == null ? ResourceSnapshot.EMPTY : facts.resourceSnapshot();
    }

    /** 返回当前 context 的驱动身份；无 current context 时返回显式 unavailable 值。 */
    public static synchronized ContextInfo contextInfo() {
        if (currentCapabilitiesOrNull() == null) return ContextInfo.UNAVAILABLE;
        return new ContextInfo(safeGlString(GL_VENDOR), safeGlString(GL_RENDERER),
                safeGlString(GL_VERSION));
    }

    /** 获取当前 context 的引用计数式资源跟踪租约。 */
    public static synchronized ResourceTrackingLease acquireResourceTracking() {
        ContextFacts facts = currentFactsOrNull();
        if (facts == null) return new ResourceTrackingLease(null);
        facts.resourceTrackingSessions++;
        return new ResourceTrackingLease(facts);
    }

    /** 为当前 context 开关逐条资源元数据；关闭时清空 registry，但不影响资源生命周期。 */
    public static synchronized void resourceTracking(boolean enabled) {
        ContextFacts facts = currentFactsOrNull();
        if (facts == null || facts.manualResourceTracking == enabled) return;
        facts.manualResourceTracking = enabled;
        if (!facts.trackingResources()) clearTrackedResources(facts);
    }

    /** backend wrapper 创建资源后登记；返回与可复用 native id 无关的 token。 */
    public static synchronized long trackResource(String kind, int nativeId, String label,
                                                  long estimatedBytes) {
        ContextFacts facts = currentFactsOrNull();
        if (facts == null || !facts.trackingResources() || nativeId == 0) return -1L;
        long sequence = NEXT_RESOURCE.getAndIncrement();
        ResourceInfo info = new ResourceInfo(sequence, sanitize(kind, 32), sanitize(label, MAX_LABEL_LENGTH),
                nativeId, FRAME_SEQUENCE.get(), estimatedBytes < 0L ? -1L : estimatedBytes,
                facts.contextIdentity);
        facts.resources.put(sequence, info);
        facts.created++;
        facts.highWaterMark = Math.max(facts.highWaterMark, facts.resources.size());
        facts.cachedResources = null;
        RESOURCE_OWNERS.put(sequence, facts);
        return sequence;
    }

    /** 幂等关闭登记；不要求删除时 context 仍是 current。 */
    public static synchronized void closeResource(long resourceSequence) {
        if (resourceSequence < 0L) return;
        ContextFacts facts = RESOURCE_OWNERS.remove(resourceSequence);
        if (facts != null && facts.resources.remove(resourceSequence) != null) {
            facts.closed++;
            facts.cachedResources = null;
        }
    }

    /** 在已知不可变存储大小后更新估算字节数。 */
    public static synchronized void updateResourceBytes(long resourceSequence, long estimatedBytes) {
        if (resourceSequence < 0L || estimatedBytes < 0L) return;
        ContextFacts facts = RESOURCE_OWNERS.get(resourceSequence);
        if (facts == null) return;
        ResourceInfo old = facts.resources.get(resourceSequence);
        if (old != null) {
            facts.resources.put(resourceSequence, new ResourceInfo(old.resourceSequence(),
                    old.kind(), old.label(), old.nativeId(), old.createdFrameSequence(), estimatedBytes,
                    old.contextIdentity()));
            facts.cachedResources = null;
        }
    }

    public static boolean hasCurrentContext() {
        return currentCapabilitiesOrNull() != null;
    }

    static String formatError(String context, int error) {
        String safeContext = context == null || context.isBlank() ? "OpenGL" : context;
        return safeContext + ": " + errorName(error) + " (" + error + ")";
    }

    public static String errorName(int error) {
        return switch (error) {
            case GL_INVALID_ENUM -> "GL_INVALID_ENUM";
            case GL_INVALID_VALUE -> "GL_INVALID_VALUE";
            case GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
            default -> "GL_ERROR_UNKNOWN";
        };
    }

    static String sourceName(int source) {
        return switch (source) {
            case GL_DEBUG_SOURCE_API -> "API";
            case GL_DEBUG_SOURCE_WINDOW_SYSTEM -> "WINDOW_SYSTEM";
            case GL_DEBUG_SOURCE_SHADER_COMPILER -> "SHADER_COMPILER";
            case GL_DEBUG_SOURCE_THIRD_PARTY -> "THIRD_PARTY";
            case GL_DEBUG_SOURCE_APPLICATION -> "APPLICATION";
            case GL_DEBUG_SOURCE_OTHER -> "OTHER";
            default -> "UNKNOWN_SOURCE(" + source + ")";
        };
    }

    static String typeName(int type) {
        return switch (type) {
            case GL_DEBUG_TYPE_ERROR -> "ERROR";
            case GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR -> "DEPRECATED_BEHAVIOR";
            case GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR -> "UNDEFINED_BEHAVIOR";
            case GL_DEBUG_TYPE_PORTABILITY -> "PORTABILITY";
            case GL_DEBUG_TYPE_PERFORMANCE -> "PERFORMANCE";
            case GL_DEBUG_TYPE_MARKER -> "MARKER";
            case GL_DEBUG_TYPE_PUSH_GROUP -> "PUSH_GROUP";
            case GL_DEBUG_TYPE_POP_GROUP -> "POP_GROUP";
            case GL_DEBUG_TYPE_OTHER -> "OTHER";
            default -> "UNKNOWN_TYPE(" + type + ")";
        };
    }

    static String severityName(int severity) {
        return switch (severity) {
            case GL_DEBUG_SEVERITY_HIGH -> "HIGH";
            case GL_DEBUG_SEVERITY_MEDIUM -> "MEDIUM";
            case GL_DEBUG_SEVERITY_LOW -> "LOW";
            case GL_DEBUG_SEVERITY_NOTIFICATION -> "NOTIFICATION";
            default -> "UNKNOWN_SEVERITY(" + severity + ")";
        };
    }

    static String objectIdentifierName(int identifier) {
        return switch (identifier) {
            case GL_BUFFER -> "BUFFER";
            case GL_SHADER -> "SHADER";
            case GL_PROGRAM -> "PROGRAM";
            case GL_VERTEX_ARRAY -> "VERTEX_ARRAY";
            case GL_QUERY -> "QUERY";
            case GL_SAMPLER -> "SAMPLER";
            case GL_TEXTURE -> "TEXTURE";
            case GL_RENDERBUFFER -> "RENDERBUFFER";
            case GL_FRAMEBUFFER -> "FRAMEBUFFER";
            default -> "OBJECT(" + identifier + ")";
        };
    }

    private static void handleDebugMessage(int source, int type, int id, int severity,
                                           int length, long message, long userParam) {
        try {
            String text = GLDebugMessageCallback.getMessage(length, message);
            if (type != GL_DEBUG_TYPE_PUSH_GROUP && type != GL_DEBUG_TYPE_POP_GROUP
                    && type != GL_DEBUG_TYPE_MARKER) {
                recordMessage(source, type, id, severity, text);
            }
            Level level = severity == GL_DEBUG_SEVERITY_HIGH ? Level.SEVERE
                    : severity == GL_DEBUG_SEVERITY_MEDIUM ? Level.WARNING
                    : severity == GL_DEBUG_SEVERITY_LOW ? Level.INFO : Level.FINE;
            LOG.log(level, () -> "OpenGL " + typeName(type)
                    + " [" + severityName(severity) + "]"
                    + " source=" + sourceName(source)
                    + " id=" + id
                    + ": " + text);
        } catch (RuntimeException | Error callbackFailure) {
            try {
                LOG.log(Level.WARNING, "OpenGL debug callback processing failed", callbackFailure);
            } catch (RuntimeException | Error ignored) {
                // 绝不允许 Java 异常穿过 native callback 边界。
            }
        }
    }

    private static GLCapabilities currentCapabilitiesOrNull() {
        GLCapabilities capabilities;
        try {
            capabilities = GL.getCapabilities();
        } catch (IllegalStateException ignored) {
            // Headless JVM tests have neither GL capabilities nor an initialized GLFW library.
            // Return before glfwGetCurrentContext(), which would emit GLFW_NOT_INITIALIZED.
            return null;
        }
        return glfwGetCurrentContext() == NULL ? null : capabilities;
    }

    private static synchronized ContextFacts currentFactsOrNull() {
        GLCapabilities capabilities = currentCapabilitiesOrNull();
        if (capabilities == null) return null;
        return CONTEXTS.computeIfAbsent(capabilities,
                ignored -> new ContextFacts(NEXT_CONTEXT.getAndIncrement()));
    }

    private static synchronized DebugMessage latestHighSeverityMessageOrNull() {
        ContextFacts facts = currentFactsOrNull();
        if (facts == null) return null;
        return facts.messages.reversed().stream()
                .filter(message -> message.severity().equals("HIGH"))
                .findFirst().orElse(null);
    }

    private static synchronized void recordMessage(int source, int type, int id,
                                                   int severity, String rawText) {
        ContextFacts facts = currentFactsOrNull();
        if (facts == null) return;
        String text = sanitize(rawText, MAX_MESSAGE_LENGTH);
        long frame = FRAME_SEQUENCE.get();
        String phase = GROUPS.get().peekLast();
        DebugMessage last = facts.messages.peekLast();
        if (last != null && last.source().equals(sourceName(source))
                && last.type().equals(typeName(type)) && last.severity().equals(severityName(severity))
                && last.driverId() == id && last.message().equals(text)
                && Objects.equals(last.phase(), phase)) {
            facts.messages.removeLast();
            facts.messages.addLast(new DebugMessage(last.sequence(), last.source(), last.type(),
                    last.severity(), id, text, last.firstFrameSequence(), frame,
                    last.repeatCount() + 1L, facts.contextIdentity, phase));
            facts.cachedMessages = null;
            return;
        }
        if (facts.messages.size() == MESSAGE_CAPACITY) {
            DebugMessage expendable = facts.messages.stream()
                    .filter(item -> item.severity().equals("NOTIFICATION") || item.severity().equals("LOW"))
                    .findFirst().orElse(facts.messages.peekFirst());
            facts.messages.remove(expendable);
            facts.droppedMessages++;
        }
        facts.messages.addLast(new DebugMessage(NEXT_MESSAGE.getAndIncrement(), sourceName(source),
                typeName(type), severityName(severity), id, text, frame, frame, 1L,
                facts.contextIdentity, phase));
        facts.cachedMessages = null;
    }

    private static String sanitize(String value, int maximumLength) {
        String safe = value == null ? "" : value.replace('\0', ' ').replaceAll("[\\r\\n\\t]+", " ").trim();
        return safe.length() <= maximumLength ? safe : safe.substring(0, maximumLength);
    }

    private static String safeGlString(int name) {
        String value = glGetString(name);
        return value == null || value.isBlank() ? "unavailable" : sanitize(value, 256);
    }

    private static synchronized void releaseResourceTracking(ContextFacts facts) {
        if (facts == null) return;
        if (facts.resourceTrackingSessions <= 0) {
            throw new IllegalStateException("resource tracking lease underflow");
        }
        facts.resourceTrackingSessions--;
        if (!facts.trackingResources()) clearTrackedResources(facts);
    }

    private static void clearTrackedResources(ContextFacts facts) {
        for (Long sequence : List.copyOf(facts.resources.keySet())) RESOURCE_OWNERS.remove(sequence);
        facts.resources.clear();
        facts.cachedResources = null;
    }

    /** 结构化 OpenGL debug callback 消息。 */
    public record DebugMessage(long sequence, String source, String type, String severity,
                               int driverId, String message, long firstFrameSequence,
                               long lastFrameSequence, long repeatCount, long contextIdentity,
                               String phase) {
    }

    /** 有界消息环的一致副本。 */
    public record MessageSnapshot(List<DebugMessage> messages, long droppedCount) {
        public static final MessageSnapshot EMPTY = new MessageSnapshot(List.of(), 0L);
        public MessageSnapshot { messages = List.copyOf(messages); }
    }

    /** backend wrapper 的存活资源元数据，不持有 wrapper 强引用。 */
    public record ResourceInfo(long resourceSequence, String kind, String label, int nativeId,
                               long createdFrameSequence, long estimatedBytes,
                               long contextIdentity) {
    }

    /** 当前 context 的资源清单和累计计数。 */
    public record ResourceSnapshot(List<ResourceInfo> liveResources, long estimatedBytes,
                                   long createdCount, long closedCount, long highWaterMark) {
        public static final ResourceSnapshot EMPTY = new ResourceSnapshot(List.of(), 0L, 0L, 0L, 0L);
        public ResourceSnapshot { liveResources = List.copyOf(liveResources); }
    }

    /** context 销毁边界的最终值类型摘要。 */
    public record ContextRelease(ResourceSnapshot resources, MessageSnapshot messages) {
        public static final ContextRelease EMPTY = new ContextRelease(
                ResourceSnapshot.EMPTY, MessageSnapshot.EMPTY);
    }

    /** OpenGL vendor/renderer/version 的值类型快照。 */
    public record ContextInfo(String vendor, String renderer, String version) {
        public static final ContextInfo UNAVAILABLE = new ContextInfo(
                "unavailable", "unavailable", "unavailable");
    }

    /** 关闭时仅释放自身引用，不会关闭同 context 的其他详细诊断 session。 */
    public static final class ResourceTrackingLease implements AutoCloseable {
        private ContextFacts facts;

        private ResourceTrackingLease(ContextFacts facts) {
            this.facts = facts;
        }

        @Override
        public void close() {
            ContextFacts owned;
            synchronized (GlDebug.class) {
                owned = facts;
                facts = null;
            }
            if (owned != null) releaseResourceTracking(owned);
        }
    }

    private static final class ContextFacts {
        final long contextIdentity;
        final ArrayDeque<DebugMessage> messages = new ArrayDeque<>(MESSAGE_CAPACITY);
        final Map<Long, ResourceInfo> resources = new LinkedHashMap<>();
        long droppedMessages;
        long created;
        long closed;
        long highWaterMark;
        int resourceTrackingSessions;
        boolean manualResourceTracking;
        MessageSnapshot cachedMessages;
        ResourceSnapshot cachedResources;

        ContextFacts(long contextIdentity) { this.contextIdentity = contextIdentity; }

        boolean trackingResources() {
            return manualResourceTracking || resourceTrackingSessions > 0;
        }

        MessageSnapshot messageSnapshot() {
            if (cachedMessages == null) {
                cachedMessages = new MessageSnapshot(new ArrayList<>(messages), droppedMessages);
            }
            return cachedMessages;
        }

        ResourceSnapshot resourceSnapshot() {
            if (cachedResources != null) return cachedResources;
            List<ResourceInfo> live = resources.values().stream()
                    .sorted(Comparator.comparingLong(ResourceInfo::resourceSequence)).toList();
            long bytes = live.stream().mapToLong(item -> Math.max(0L, item.estimatedBytes())).sum();
            cachedResources = new ResourceSnapshot(live, bytes, created, closed, highWaterMark);
            return cachedResources;
        }
    }
}
