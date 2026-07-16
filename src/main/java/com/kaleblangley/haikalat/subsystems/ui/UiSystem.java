package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.ui.event.UiInputRouter;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutEngine;
import com.kaleblangley.haikalat.subsystems.ui.layout.YogaLayoutEngine;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBatcher;
import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiGlyphAtlasGpu;
import com.kaleblangley.haikalat.subsystems.ui.render.UiGlyphUploadResult;
import com.kaleblangley.haikalat.subsystems.ui.render.UiPainter;
import com.kaleblangley.haikalat.subsystems.ui.render.UiRenderSnapshot;
import com.kaleblangley.haikalat.subsystems.ui.render.UiRenderer;
import com.kaleblangley.haikalat.subsystems.ui.render.UiSnapshotExchange;
import com.kaleblangley.haikalat.subsystems.ui.style.StyleResolver;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStylePass;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphAtlasStatistics;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphUploadRequest;
import com.kaleblangley.haikalat.subsystems.ui.text.ShapingCache;
import com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import com.kaleblangley.haikalat.subsystems.windowing.input.QueuedTextInputClient;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.TextInputRect;
import com.kaleblangley.haikalat.subsystems.windowing.input.UnavailableTextInputAdapter;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import com.kaleblangley.haikalat.subsystems.windowing.input.win32.Win32TextInputAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单窗口 retained-mode UI 的公共生命周期入口。
 *
 * <p>可变 tree、事件、style、layout 和 display-list build 受创建线程约束；
 * render pass 只消费不可变 {@link UiRenderSnapshot}。</p>
 */
public final class UiSystem implements AutoCloseable {
    public static final String OVERLAY_PASS_NAME = "UiOverlayPass";
    private static final System.Logger LOG = System.getLogger(UiSystem.class.getName());

    private final RenderWindow window;
    private final UiConfig config;
    private final UiThreadGuard updateThread = new UiThreadGuard("UiSystem update state");
    private final UiDocument document;
    private final UiInputRouter inputRouter;
    private final UiStylePass stylePass;
    private final LayoutEngine layoutEngine;
    private final UiTextEngine textEngine;
    private final UiPainter painter;
    private final UiDisplayList displayList;
    private final UiBatcher emptySnapshotBatcher = new UiBatcher();
    private final UiSnapshotExchange snapshots;
    private final UiRenderer renderer;
    private final TextInputAdapter textInputAdapter;
    private final QueuedTextInputClient textInputClient = new QueuedTextInputClient();
    private final Object renderLifecycle = new Object();
    private final Set<Long> submittedGlyphUploads = ConcurrentHashMap.newKeySet();
    private RenderGraph attachedGraph;
    private UiRenderSnapshot lastRenderedSnapshot;
    private UiSnapshotExchange.Lease lastRenderedLease;
    private volatile UiFrameStats updateStatistics = UiFrameStats.EMPTY;
    private long snapshotSequence;
    private TextField activeTextField;
    private float animationDeltaSeconds;
    private int previousWindowWidth = -1;
    private int previousWindowHeight = -1;
    private double previousContentScaleX = Double.NaN;
    private double previousContentScaleY = Double.NaN;
    private volatile long renderRecordNanos;
    private volatile long renderedDrawCalls;
    private volatile boolean closed;

    private UiSystem(RenderWindow window, UiConfig config, UiDocument document,
                     LayoutEngine layoutEngine, UiTextEngine textEngine, UiPainter painter,
                     TextInputAdapter textInputAdapter) {
        this.window = Objects.requireNonNull(window, "window");
        this.config = Objects.requireNonNull(config, "config");
        this.document = Objects.requireNonNull(document, "document");
        this.layoutEngine = Objects.requireNonNull(layoutEngine, "layoutEngine");
        this.textEngine = Objects.requireNonNull(textEngine, "textEngine");
        this.painter = Objects.requireNonNull(painter, "painter");
        this.textInputAdapter = Objects.requireNonNull(textInputAdapter, "textInputAdapter");
        inputRouter = new UiInputRouter(document);
        stylePass = new UiStylePass(StyleResolver.defaults(config.theme()));
        displayList = new UiDisplayList(config.initialPrimitiveCapacity(),
                config.initialPrimitiveCapacity());
        snapshots = new UiSnapshotExchange(config.snapshotSlots());
        renderer = new UiRenderer(config.maximumPrimitives(),
                config.glyphAtlasWidth(), config.glyphAtlasHeight(),
                config.maximumGlyphAtlasPages());
    }

    /** 创建不依赖 RenderGraph 或当前 GL context 的 UI 系统。 */
    public static UiSystem create(RenderWindow window, UiConfig config) {
        RenderWindow requiredWindow = Objects.requireNonNull(window, "window");
        return create(requiredWindow, config, defaultTextInputAdapter(requiredWindow));
    }

    /** 使用显式 adapter 创建 UI 系统，供确定性测试和非默认平台集成。 */
    public static UiSystem create(RenderWindow window, UiConfig config,
                                  TextInputAdapter textInputAdapter) {
        Objects.requireNonNull(window, "window");
        config = Objects.requireNonNullElseGet(config, UiConfig::defaults);
        Objects.requireNonNull(textInputAdapter, "textInputAdapter");
        UiDocument document = new UiDocument();
        LayoutEngine layout = null;
        UiTextEngine text = null;
        try {
            text = UiTextEngine.createBundled(config.glyphAtlasWidth(),
                    config.glyphAtlasHeight(), config.maximumGlyphAtlasPages());
            layout = new YogaLayoutEngine(text::measure);
            UiPainter painter = new UiPainter(com.kaleblangley.haikalat.subsystems.ui.render.UiImageResolver.empty(),
                    text, config.debugOptions());
            return new UiSystem(window, config, document, layout, text, painter, textInputAdapter);
        } catch (RuntimeException | Error failure) {
            closeSuppressed(layout, failure);
            closeSuppressed(text, failure);
            closeSuppressed(document, failure);
            closeSuppressed(textInputAdapter, failure);
            throw failure;
        }
    }

    public UiDocument document() {
        updateThread.check();
        ensureOpen("UiDocument");
        return document;
    }

    /** 返回当前已注册、可在运行时切换的 UI 字体族。 */
    public List<String> fontFamilies() {
        updateThread.check();
        ensureOpen("UiSystem.fontFamilies");
        return textEngine.fontFamilies();
    }

    /** 返回当前全局 UI 首选字体族。 */
    public String activeFontFamily() {
        updateThread.check();
        ensureOpen("UiSystem.activeFontFamily");
        return textEngine.activeFontFamily();
    }

    /** 从 OTF/TTF 文件注册一个运行时 UI 字体。 */
    public void registerFont(String familyName, Path path) throws IOException {
        updateThread.check();
        ensureOpen("UiSystem.registerFont");
        textEngine.registerFont(familyName, path);
        invalidateTextLayout(document.root());
        invalidateTextLayout(document.overlayRoot());
    }

    /** 从内存注册一个运行时 UI 字体。 */
    public void registerFont(String familyName, byte[] fontData) {
        updateThread.check();
        ensureOpen("UiSystem.registerFont");
        textEngine.registerFont(familyName, fontData);
        invalidateTextLayout(document.root());
        invalidateTextLayout(document.overlayRoot());
    }

    /**
     * 切换全局 UI 首选字体并使整棵 retained tree 在下一次 update 重新测量和布局。
     *
     * @return 字体是否发生变化
     */
    public boolean selectFontFamily(String familyName) {
        updateThread.check();
        ensureOpen("UiSystem.selectFontFamily");
        if (!textEngine.selectFontFamily(familyName)) return false;
        invalidateTextLayout(document.root());
        invalidateTextLayout(document.overlayRoot());
        return true;
    }

    /**
     * 消费一份严格递增的窗口输入快照并发布一张完整 render snapshot。
     * 暂停恢复后的 delta 会裁到配置上限，负数、NaN 和 Infinity 会立即失败。
     */
    public void update(WindowInputSnapshot input, float deltaSeconds) {
        updateThread.check();
        ensureOpen("UiSystem update");
        Objects.requireNonNull(input, "input");
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("UI deltaSeconds must be finite and non-negative");
        }
        float boundedDelta = Math.min(deltaSeconds, config.maximumDeltaSeconds());
        long updateStart = System.nanoTime();
        applyCompletedGlyphUploads();
        textEngine.beginFrame(input.contentScaleX(), input.contentScaleY());
        invalidateViewportIfChanged(input);
        long inputEvents = countInputEvents(input);
        applyComposition(input, false);
        long dispatched = inputRouter.update(input);
        applyComposition(input, true);
        synchronizeTextInput(input);
        stylePass.resolve(document);

        long layoutNanos = 0L;
        long layoutPasses = 0L;
        long layoutNodes = 0L;
        if (hasLayoutDirty(document.root()) || hasLayoutDirty(document.overlayRoot())) {
            long start = System.nanoTime();
            layoutEngine.layout(document, input.windowWidth(), input.windowHeight());
            layoutNanos = System.nanoTime() - start;
            layoutPasses = 1L;
            layoutNodes = countNodes(document.root()) + countNodes(document.overlayRoot());
        }

        UiRenderSnapshot complete;
        long paintStart = System.nanoTime();
        painter.paint(document, displayList);
        long paintNanos = System.nanoTime() - paintStart;
        if (activeTextField != null) updateCandidateRect(activeTextField, input);
        try {
            complete = snapshots.captureAndPublish(++snapshotSequence,
                    input.windowWidth(), input.windowHeight(),
                    input.framebufferWidth(), input.framebufferHeight(),
                    input.contentScaleX(), input.contentScaleY(), displayList,
                    textEngine.pendingUploads());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("UiSnapshotExchange publication was interrupted", interrupted);
        }

        long visibleNodes = document.paintOrder().size();
        long updateNanos = System.nanoTime() - updateStart;
        ShapingCache.Statistics shaping = textEngine.shapingStatistics();
        GlyphAtlasStatistics atlas = textEngine.atlasStatistics();
        updateStatistics = new UiFrameStats(visibleNodes, layoutNodes, layoutPasses,
                textEngine.frameShapedRuns(), shaping.hits(), shaping.misses(),
                atlas.hits(), atlas.misses(), atlas.pages(), atlas.evictions(),
                displayList.primitiveCount(), displayList.quadCount(), textEngine.frameGlyphs(),
                complete.batches().size(), renderedDrawCalls,
                (long) displayList.quadCount() * 4L * 20L,
                (long) displayList.quadCount() * 6L * Short.BYTES,
                atlas.uploadBytes(), inputEvents, dispatched, updateNanos, layoutNanos,
                textEngine.frameShapingNanos(), paintNanos, renderRecordNanos, 0,
                complete.batches().breakStatistics());
        // 后续动画/惯性组件只能接收 boundedDelta，不能重新读取未经裁剪的参数。
        animationDeltaSeconds = boundedDelta;
    }

    /**
     * 把 UI overlay 接到最终 backbuffer pass 后，并冻结 graph pass 拓扑。
     */
    public void attachTo(RenderGraph graph, String dependencyPass) {
        updateThread.check();
        ensureOpen("UiSystem graph attachment");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(dependencyPass, "dependencyPass");
        if (attachedGraph != null) throw new IllegalStateException("UiSystem is already attached to a RenderGraph");
        if (graph.isTopologySealed()) throw new IllegalStateException("RenderGraph topology is already sealed");
        if (!graph.hasPass(dependencyPass)) {
            throw new IllegalArgumentException("UI dependency pass does not exist: " + dependencyPass);
        }
        if (!graph.passWritesToBackbuffer(dependencyPass)) {
            throw new IllegalArgumentException("UI dependency pass does not write to backbuffer: " + dependencyPass);
        }
        if (graph.hasPass(OVERLAY_PASS_NAME)) {
            throw new IllegalStateException("RenderGraph already contains " + OVERLAY_PASS_NAME);
        }

        graph.addPass(OVERLAY_PASS_NAME)
                .writeToBackbuffer()
                .noClear()
                .dependsOn(dependencyPass)
                .execute((resources, commands) -> recordOverlay(commands));
        graph.sealTopology();
        attachedGraph = graph;
    }

    /** 返回最近完成 update 的统计，并合入最近 render record 数据。 */
    public UiFrameStats statistics() {
        ensureOpen("UiFrameStats");
        UiFrameStats value = updateStatistics;
        return new UiFrameStats(value.visibleNodes(), value.layoutNodes(), value.layoutPasses(),
                value.shapedRuns(), value.shapingCacheHits(), value.shapingCacheMisses(),
                value.glyphAtlasHits(), value.glyphAtlasMisses(), value.glyphAtlasPages(),
                value.glyphAtlasEvictions(), value.paintPrimitives(), value.quads(), value.glyphs(),
                value.batches(), renderedDrawCalls, value.vertexBytes(), value.indexBytes(),
                value.atlasUploadBytes(), value.inputEvents(), value.dispatchedEvents(),
                value.uiUpdateNanos(), value.layoutNanos(), value.shapingNanos(), value.paintNanos(),
                renderRecordNanos, renderer.ringWaitNanos(), value.batchBreaks());
    }

    public boolean isAttached() { return attachedGraph != null; }
    public boolean isClosed() { return closed; }

    /** 返回已经成功发布到 snapshot exchange 的快照总数。 */
    public long publishedSnapshotCount() {
        return snapshots.publishedCount();
    }

    /** 返回被 latest-wins 覆盖或在关闭时丢弃的未消费快照总数。 */
    public long droppedSnapshotCount() {
        return snapshots.droppedCount();
    }

    public TextInputAdapter textInputAdapter() {
        updateThread.check();
        ensureOpen("TextInputAdapter");
        return textInputAdapter;
    }

    /**
     * 在拥有 OpenGL context 的 render 线程释放 UI GPU 资源。
     *
     * <p>同步模式无需显式调用，{@link #close()} 会在同一线程完成全部清理。异步模式下，
     * 如果 renderer 已经在独立 render 线程初始化，必须先在该 render 线程调用本方法，再回到
     * update 线程调用 {@code close()}。该方法幂等，也允许在 renderer 尚未初始化时调用。</p>
     */
    public void closeRenderResources() {
        synchronized (renderLifecycle) {
            if (renderer.isClosed()) return;
            releaseLastRenderedLease();
            renderer.close();
            lastRenderedSnapshot = null;
        }
    }

    @Override
    public void close() {
        updateThread.check();
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        snapshots.close();
        synchronized (renderLifecycle) {
            releaseLastRenderedLease();
            if (!renderer.isClosed()) failure = closeCollect(renderer, failure);
            lastRenderedSnapshot = null;
        }
        if (activeTextField != null) {
            try {
                textInputAdapter.deactivate(textInputClient);
            } catch (RuntimeException deactivateFailure) {
                failure = appendFailure(failure, deactivateFailure);
            }
            activeTextField.cancelComposition();
            activeTextField = null;
        }
        failure = closeCollect(textInputAdapter, failure);
        failure = closeCollect(textEngine, failure);
        failure = closeCollect(layoutEngine, failure);
        failure = closeCollect(document, failure);
        attachedGraph = null;
        if (failure != null) throw failure;
    }

    private void recordOverlay(com.kaleblangley.haikalat.core.command.CommandBuffer commands) {
        ensureOpen("UiRenderer");
        synchronized (renderLifecycle) {
            UiSnapshotExchange.Lease acquired = snapshots.tryAcquire();
            if (acquired != null) {
                UiSnapshotExchange.Lease replaced = lastRenderedLease;
                lastRenderedLease = acquired;
                lastRenderedSnapshot = acquired.snapshot();
                if (replaced != null) replaced.close();
            }
            if (lastRenderedSnapshot == null) {
                int width = Math.max(0, window.width());
                int height = Math.max(0, window.height());
                lastRenderedSnapshot = UiRenderSnapshot.capture(0, width, height, width, height,
                        1.0, 1.0, new UiDisplayList(), emptySnapshotBatcher);
            }
            long start = System.nanoTime();
            List<GlyphUploadRequest> claimedUploads = claimGlyphUploads(
                    acquired == null ? List.of() : lastRenderedSnapshot.glyphUploads());
            UiGlyphAtlasGpu.UploadSubmission uploadSubmission = null;
            try {
                if (!claimedUploads.isEmpty()) {
                    uploadSubmission = renderer.recordGlyphUploads(claimedUploads, commands);
                }
                renderer.record(lastRenderedSnapshot, commands);
            } catch (RuntimeException | Error failure) {
                if (uploadSubmission != null) uploadSubmission.executionFailed(failure);
                releaseGlyphUploadClaims(claimedUploads);
                throw failure;
            }
            renderRecordNanos = System.nanoTime() - start;
            renderedDrawCalls = renderer.lastDrawCalls();
        }
    }

    private void releaseLastRenderedLease() {
        UiSnapshotExchange.Lease lease = lastRenderedLease;
        lastRenderedLease = null;
        if (lease != null) lease.close();
    }

    private void ensureOpen(String resource) {
        if (closed) throw new IllegalStateException(resource + " cannot be used after UiSystem.close");
    }

    private void applyCompletedGlyphUploads() {
        for (UiGlyphUploadResult result : renderer.drainCompletedGlyphUploads()) {
            for (GlyphUploadRequest request : result.requests()) {
                submittedGlyphUploads.remove(request.requestId());
                if (result.succeeded()) {
                    textEngine.publishUpload(request);
                } else {
                    textEngine.uploadFailed(request);
                }
            }
        }
    }

    private List<GlyphUploadRequest> claimGlyphUploads(List<GlyphUploadRequest> requests) {
        if (requests.isEmpty()) return List.of();
        ArrayList<GlyphUploadRequest> claimed = new ArrayList<>(requests.size());
        for (GlyphUploadRequest request : requests) {
            if (submittedGlyphUploads.add(request.requestId())) claimed.add(request);
        }
        return List.copyOf(claimed);
    }

    private void releaseGlyphUploadClaims(List<GlyphUploadRequest> requests) {
        for (GlyphUploadRequest request : requests) {
            submittedGlyphUploads.remove(request.requestId());
        }
    }

    private void invalidateViewportIfChanged(WindowInputSnapshot input) {
        if (previousWindowWidth == input.windowWidth()
                && previousWindowHeight == input.windowHeight()
                && Double.compare(previousContentScaleX, input.contentScaleX()) == 0
                && Double.compare(previousContentScaleY, input.contentScaleY()) == 0) {
            return;
        }
        previousWindowWidth = input.windowWidth();
        previousWindowHeight = input.windowHeight();
        previousContentScaleX = input.contentScaleX();
        previousContentScaleY = input.contentScaleY();
        document.root().markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT,
                UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
        document.overlayRoot().markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT,
                UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
    }

    /** 返回最近一次 update 经裁剪后提供给 UI 动画的 delta。 */
    float animationDeltaSeconds() {
        return animationDeltaSeconds;
    }

    private static boolean hasLayoutDirty(UiNode node) {
        if (node.isDirty(UiDirtyFlag.LAYOUT) || node.isDirty(UiDirtyFlag.MEASURE)) return true;
        for (UiNode child : node.children()) if (hasLayoutDirty(child)) return true;
        return false;
    }

    private static void invalidateTextLayout(UiNode node) {
        node.markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT, UiDirtyFlag.PAINT);
        for (UiNode child : node.children()) invalidateTextLayout(child);
    }

    private static long countNodes(UiNode node) {
        long count = 1L;
        for (UiNode child : node.children()) count += countNodes(child);
        return count;
    }

    private static long countInputEvents(WindowInputSnapshot input) {
        long count = input.cursorDeltaX() != 0.0 || input.cursorDeltaY() != 0.0 ? 1L : 0L;
        if (input.scrollX() != 0.0 || input.scrollY() != 0.0) count++;
        for (Key key : Key.values()) {
            if (input.keyPressed(key)) count++;
            if (input.keyReleased(key)) count++;
        }
        for (MouseButton button : MouseButton.values()) {
            if (input.mousePressed(button)) count++;
            if (input.mouseReleased(button)) count++;
        }
        count += input.committedCodePoints().remaining();
        if (input.composition().isPresent()) count++;
        return count;
    }

    private void applyComposition(WindowInputSnapshot input, boolean onlyPresent) {
        UiNode focused = document.focusManager().focused();
        if (!(focused instanceof TextField field)) return;
        if (input.composition().isPresent()) {
            field.updateComposition(input.composition().orElseThrow());
        } else if (!onlyPresent && field.composition() != null) {
            // 先清除旧 preedit，再让同一 snapshot 的 GLFW committed-char 成为唯一提交来源。
            field.cancelComposition();
        }
    }

    private void synchronizeTextInput(WindowInputSnapshot input) {
        TextField focused = input.focused() && document.focusManager().focused() instanceof TextField field
                ? field : null;
        if (focused != activeTextField) {
            if (activeTextField != null) {
                textInputAdapter.deactivate(textInputClient);
                if (!activeTextField.isClosed()) {
                    activeTextField.cancelComposition();
                }
            }
            activeTextField = focused;
            textInputClient.clear();
            if (focused != null) textInputAdapter.activate(textInputClient);
        }
        if (activeTextField == null) {
            textInputClient.clear();
            return;
        }
        textInputClient.drain(command -> {
            if (command instanceof QueuedTextInputClient.Command.Started) {
                activeTextField.updateComposition(
                        new com.kaleblangley.haikalat.subsystems.windowing.input.ImeComposition("", 0, 0, 0));
            } else if (command instanceof QueuedTextInputClient.Command.Updated updated) {
                activeTextField.updateComposition(updated.composition());
            } else if (command instanceof QueuedTextInputClient.Command.Committed committed) {
                activeTextField.commitComposition(committed.text());
            } else if (command instanceof QueuedTextInputClient.Command.Cancelled) {
                activeTextField.cancelComposition();
            }
        });
    }

    private void updateCandidateRect(TextField field, WindowInputSnapshot input) {
        com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox box = document.visualLayoutBox(field);
        com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox nodeBox = field.layoutBox();
        com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox contentBox = field.textContentBox();
        double logicalX = box.x() + contentBox.x() - nodeBox.x() + field.visibleCaretX();
        textInputAdapter.setCandidateRect(new TextInputRect(
                logicalX * input.contentScaleX(), box.y() * input.contentScaleY(),
                Math.max(1.0, input.contentScaleX()),
                Math.max(1.0, box.height() * input.contentScaleY())));
    }

    private static TextInputAdapter defaultTextInputAdapter(RenderWindow window) {
        if (window instanceof GlfwWindow glfwWindow && Win32TextInputAdapter.isSupported()) {
            try {
                return new Win32TextInputAdapter(glfwWindow.handle());
            } catch (RuntimeException | LinkageError failure) {
                LOG.log(System.Logger.Level.WARNING,
                        "Windows IME composition unavailable; GLFW committed-char input remains active", failure);
                return new UnavailableTextInputAdapter(failure.getMessage());
            }
        }
        return new UnavailableTextInputAdapter("platform composition unavailable");
    }

    private static RuntimeException closeCollect(AutoCloseable closeable, RuntimeException current) {
        if (closeable == null) return current;
        try {
            closeable.close();
        } catch (RuntimeException failure) {
            if (current == null) return failure;
            current.addSuppressed(failure);
        } catch (Exception failure) {
            RuntimeException wrapped = new IllegalStateException("UI resource close failed", failure);
            if (current == null) return wrapped;
            current.addSuppressed(wrapped);
        }
        return current;
    }

    private static RuntimeException appendFailure(RuntimeException current, RuntimeException added) {
        if (current == null) return added;
        current.addSuppressed(added);
        return current;
    }

    private static void closeSuppressed(AutoCloseable closeable, Throwable primary) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception | LinkageError cleanup) {
            primary.addSuppressed(cleanup);
        }
    }
}
