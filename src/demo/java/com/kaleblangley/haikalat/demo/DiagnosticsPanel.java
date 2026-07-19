package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.core.graph.PassProfile;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsJsonExporter;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsSnapshot;
import com.kaleblangley.haikalat.runtime.diagnostics.FrameDiagnostics;
import com.kaleblangley.haikalat.runtime.diagnostics.FrozenDiagnostics;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.event.KeyEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.windowing.input.ClipboardService;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 主 Demo 使用同一 UiSystem 树的只读诊断工具面板。 */
final class DiagnosticsPanel {
    private static final float BODY_WIDTH = 718.0f;
    private static final float BODY_VIEWPORT_HEIGHT = 230.0f;
    private static final float BODY_LINE_HEIGHT = 22.0f;
    private static final float BODY_APPROXIMATE_GLYPH_WIDTH = 16.0f;

    private final FrameDiagnostics diagnostics;
    private final Path exportPath;
    private final ClipboardService clipboard;
    private final Panel root = new Panel();
    private final Panel body = new Panel();
    private final List<DiagnosticRow> rows = new ArrayList<>();
    private final BitSet selectedRows = new BitSet();
    private final Label status = new Label("F2 opens diagnostics / F2 打开诊断");
    private View view = View.OVERVIEW;
    private FrozenDiagnostics frozen;
    private int filterIndex;
    private int activeRowCount;
    private int selectionAnchor = -1;
    private float bodyHeight = 1200.0f;
    private String notice = "Click a row, then Ctrl+C to copy / 点击一行后按 Ctrl+C 复制";

    DiagnosticsPanel(FrameDiagnostics diagnostics, Path exportPath,
                     ClipboardService clipboard) {
        this.diagnostics = diagnostics;
        this.exportPath = exportPath;
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        root.debugName("DiagnosticsPanel");
        root.visibility(UiVisibility.COLLAPSED);
        root.style(UiStyle.builder().width(UiLength.points(760)).height(UiLength.points(380))
                .padding(UiInsets.points(8)).flexDirection(UiStyle.FlexDirection.COLUMN)
                .gap(6).flexShrink(0).build());

        Panel tabs = row(42);
        for (View item : View.values()) {
            Button tab = new Button(item.label);
            tab.style(UiStyle.builder().width(UiLength.points(70)).height(UiLength.points(34))
                    .padding(UiInsets.points(3)).flexShrink(0).build());
            tab.onClick(() -> {
                if (view != item) clearSelection();
                view = item;
                refresh();
            });
            tabs.add(tab);
        }

        body.style(bodyStyle(BODY_WIDTH, BODY_VIEWPORT_HEIGHT));
        ScrollView scroll = new ScrollView();
        scroll.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(BODY_VIEWPORT_HEIGHT))
                .flexShrink(0).build());
        scroll.content(body);

        Panel actions = row(38);
        Button freeze = action("Freeze / 冻结", () -> { frozen = diagnostics.freeze(); refresh(); });
        Button live = action("Live / 实时", () -> { frozen = null; refresh(); });
        Button clear = action("Clear / 清空", () -> { diagnostics.clear(); frozen = null; refresh(); });
        Button filter = action("Filter / 筛选", () -> {
            filterIndex = (filterIndex + 1) % 3;
            clearSelection();
            refresh();
        });
        Button export = action("Export / 导出", this::export);
        actions.add(live).add(freeze).add(clear).add(filter).add(export);
        status.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(24)).build());
        root.add(tabs);
        root.add(scroll);
        root.add(actions);
        root.add(status);
    }

    Panel root() { return root; }
    boolean visible() { return root.visibility() == UiVisibility.VISIBLE; }

    void show() {
        if (!visible()) toggle();
    }

    void toggle() {
        root.visibility(visible() ? UiVisibility.COLLAPSED : UiVisibility.VISIBLE);
        if (visible()) refresh();
    }

    void update(int frame) {
        if (visible() && (frame <= 2 || frame % 10 == 0)) refresh();
    }

    private void refresh() {
        FrameDiagnostics.ReadView live = frozen == null ? diagnostics.read() : null;
        DiagnosticsSnapshot snapshot = frozen == null ? live.snapshot() : frozen.latest();
        FrozenDiagnostics.ResourceTable resources = frozen == null
                ? live.resources() : frozen.resources();
        FrozenDiagnostics.MessageTable messages = frozen == null
                ? live.messages() : frozen.messages();
        setBodyText(switch (view) {
            case OVERVIEW -> overview(snapshot);
            case PASSES -> passes(snapshot);
            case GRAPH -> graph(snapshot);
            case RESOURCES -> resources(resources);
            case MESSAGES -> messages(messages);
        });
        status.text((frozen == null ? "LIVE" : "FROZEN") + " | epoch " + snapshot.epoch()
                + " | frame " + snapshot.frameSequence() + " | " + notice);
    }

    private void setBodyText(String text) {
        String[] lines = text.split("\\R", -1);
        activeRowCount = lines.length;
        if (selectedRows.length() > activeRowCount) {
            selectedRows.clear(activeRowCount, selectedRows.length());
        }
        if (selectionAnchor >= activeRowCount) selectionAnchor = -1;
        float nextWidth = BODY_WIDTH;
        for (int index = 0; index < lines.length; index++) {
            DiagnosticRow row = diagnosticRow(index);
            row.text(lines[index]);
            row.visibility(UiVisibility.VISIBLE);
            nextWidth = Math.max(nextWidth,
                    lines[index].codePointCount(0, lines[index].length())
                            * BODY_APPROXIMATE_GLYPH_WIDTH + 12.0f);
        }
        for (int index = lines.length; index < rows.size(); index++) {
            rows.get(index).visibility(UiVisibility.COLLAPSED);
        }
        bodyHeight = Math.max(BODY_VIEWPORT_HEIGHT, lines.length * BODY_LINE_HEIGHT);
        body.style(bodyStyle(nextWidth, bodyHeight));
        for (int index = 0; index < lines.length; index++) rows.get(index).rowWidth(nextWidth);
    }

    private DiagnosticRow diagnosticRow(int index) {
        while (rows.size() <= index) {
            DiagnosticRow row = new DiagnosticRow(rows.size());
            rows.add(row);
            body.add(row);
        }
        return rows.get(index);
    }

    private void copied(String text) {
        if (text == null) {
            notice = "Copy failed / 复制失败";
        } else {
            String preview = text.length() <= 24 ? text : text.substring(0, 24) + "…";
            notice = "Copied / 已复制: " + preview;
        }
        DiagnosticsSnapshot snapshot = frozen == null ? diagnostics.latest() : frozen.latest();
        status.text((frozen == null ? "LIVE" : "FROZEN") + " | epoch " + snapshot.epoch()
                + " | frame " + snapshot.frameSequence() + " | " + notice);
    }

    private void selectRow(int index, boolean control, boolean shift) {
        if (index < 0 || index >= activeRowCount) return;
        if (shift && selectionAnchor >= 0) {
            if (!control) selectedRows.clear();
            selectedRows.set(Math.min(selectionAnchor, index),
                    Math.max(selectionAnchor, index) + 1);
        } else if (control) {
            selectedRows.flip(index);
            selectionAnchor = index;
        } else {
            selectedRows.clear();
            selectedRows.set(index);
            selectionAnchor = index;
        }
        applySelectionVisuals();
        notice = "Selected / 已选择 " + selectedRows.cardinality() + " line(s)";
    }

    private void selectAllRows() {
        selectedRows.clear();
        selectedRows.set(0, activeRowCount);
        selectionAnchor = activeRowCount == 0 ? -1 : 0;
        applySelectionVisuals();
        notice = "Selected all / 已全选 " + activeRowCount + " line(s)";
    }

    private void copySelection(int fallbackIndex) {
        if (selectedRows.isEmpty() && fallbackIndex >= 0 && fallbackIndex < activeRowCount) {
            selectedRows.set(fallbackIndex);
            selectionAnchor = fallbackIndex;
            applySelectionVisuals();
        }
        StringBuilder text = new StringBuilder();
        for (int index = selectedRows.nextSetBit(0);
             index >= 0 && index < activeRowCount;
             index = selectedRows.nextSetBit(index + 1)) {
            if (!text.isEmpty()) text.append('\n');
            text.append(rows.get(index).text());
        }
        try {
            clipboard.writeText(text.toString());
            copied(text.toString());
        } catch (RuntimeException failure) {
            copied(null);
        }
    }

    private void clearSelection() {
        selectedRows.clear();
        selectionAnchor = -1;
        applySelectionVisuals();
        notice = "Click a row, then Ctrl+C to copy / 点击一行后按 Ctrl+C 复制";
    }

    private void applySelectionVisuals() {
        for (int index = 0; index < rows.size(); index++) {
            rows.get(index).selected(index < activeRowCount && selectedRows.get(index));
        }
    }

    private static UiStyle bodyStyle(float width, float height) {
        return UiStyle.builder().width(UiLength.points(width)).height(UiLength.points(height))
                .flexDirection(UiStyle.FlexDirection.COLUMN).flexShrink(0).build();
    }

    private String overview(DiagnosticsSnapshot snapshot) {
        String overview = String.format(Locale.ROOT,
                "Overview / 总览\n\nlevel %s\nframe %,d / presented %,d\nCPU %.3f ms\nGPU %s\nFPS %.1f\nstate applied %,d\nstate avoided %,d\nskip %.1f%%\nresources %,d / %.2f MiB\nmessages %,d (dropped %,d)",
                snapshot.level(), snapshot.frameSequence(), snapshot.presentedFrameSequence(),
                snapshot.frameProfile().cpuFrameMillis(),
                snapshot.frameProfile().gpuTotalComplete()
                        ? String.format(Locale.ROOT, "%.3f ms", snapshot.frameProfile().totalGpuMillis())
                        : String.format(Locale.ROOT, "partial %.3f ms", snapshot.frameProfile().totalGpuMillis()),
                snapshot.presentFps(), snapshot.state().appliedChanges(), snapshot.state().avoidedChanges(),
                snapshot.state().skipRatio() * 100.0, snapshot.resources().liveCount(),
                snapshot.resources().estimatedBytes() / 1048576.0,
                snapshot.messages().total(), snapshot.messages().dropped());
        if (snapshot.scene().isEmpty() || snapshot.scene().orElseThrow().visibility().isEmpty()) {
            return overview;
        }
        DiagnosticsSnapshot.VisibilitySummary visibility = snapshot.scene().orElseThrow()
                .visibility().orElseThrow();
        double cullRatio = visibility.candidateRenderers() == 0L ? 0.0
                : visibility.forwardCulled() * 100.0 / visibility.candidateRenderers();
        return overview + String.format(Locale.ROOT,
                "\nvisibility %s\nforward %,d / %,d (culled %.1f%%)"
                        + "\nshadow %,d / %,d\nunbounded %,d\nqueue %.3f ms"
                        + "\nstatic/dynamic %,d/%,d model hit %,d bounds hit %,d"
                        + "\nqueue reuse forward/shadow %s/%s"
                        + "\ncommands %,d matrices %,d objects %,d"
                        + "\nqueue changes shader/material/mesh %,d/%,d/%,d",
                visibility.cullingEnabled() ? "enabled" : "disabled",
                visibility.forwardVisible(), visibility.candidateRenderers(), cullRatio,
                visibility.shadowVisible(), visibility.shadowCandidates(),
                visibility.unboundedRenderers(), visibility.totalQueueBuildNanos() / 1_000_000.0,
                visibility.staticRenderers(), visibility.dynamicRenderers(),
                visibility.modelCacheHits(), visibility.boundsCacheHits(),
                visibility.forwardQueueReused(), visibility.shadowQueueReused(),
                visibility.recordedCommands(), visibility.recordedMatrixSnapshots(),
                visibility.recordedObjectPayloads(),
                visibility.shaderChanges(), visibility.materialChanges(),
                visibility.meshChanges());
    }

    private static String passes(DiagnosticsSnapshot snapshot) {
        StringBuilder text = new StringBuilder("Passes / 渲染阶段\n\n");
        for (PassProfile pass : snapshot.frameProfile().passes()) {
            text.append(pass.passName()).append("\n  CPU ")
                    .append(String.format(Locale.ROOT, "%.3f ms", pass.cpuRecordMillis()))
                    .append(" | GPU ");
            if (pass.gpuStatus() == PassProfile.GpuTimingStatus.AVAILABLE) {
                text.append(String.format(Locale.ROOT, "%.3f ms age %,d",
                        pass.gpuMillis(), pass.sampleAgeFrames()));
            } else text.append(pass.gpuStatus());
            if (pass.skippedSubmissions() > 0) text.append(" | skipped ").append(pass.skippedSubmissions());
            text.append('\n');
        }
        return text.toString();
    }

    private static String graph(DiagnosticsSnapshot snapshot) {
        if (snapshot.graph().isEmpty()) return "Graph / 渲染图\n\nRequires DETAILED diagnostics.";
        RenderGraph.Description graph = snapshot.graph().orElseThrow();
        StringBuilder text = new StringBuilder("Graph / 渲染图\n\n")
                .append(graph.width()).append('x').append(graph.height())
                .append(" revision ").append(graph.topologyRevision()).append('\n');
        for (RenderGraph.PassDescription pass : graph.passes()) {
            text.append(pass.name()).append(" -> ").append(pass.targetKind())
                    .append(' ').append(pass.width()).append('x').append(pass.height())
                    .append(" x").append(pass.samples()).append('\n');
            if (!pass.directDependencies().isEmpty()) text.append("  depends ")
                    .append(String.join(", ", pass.directDependencies())).append('\n');
        }
        return text.toString();
    }

    private String resources(FrozenDiagnostics.ResourceTable snapshot) {
        StringBuilder text = new StringBuilder("Resources / 资源\n\n")
                .append("live ").append(snapshot.live().size())
                .append(" | estimated ").append(String.format(Locale.ROOT, "%.2f MiB",
                        snapshot.estimatedBytes() / 1048576.0)).append('\n')
                .append("created ").append(snapshot.createdCount()).append(" | closed ")
                .append(snapshot.closedCount()).append(" | high-water ")
                .append(snapshot.highWaterMark()).append("\n\n");
        for (FrozenDiagnostics.Resource item : snapshot.live()) {
            if (!resourceVisible(item.kind())) continue;
            text.append('#').append(item.resourceSequence()).append(' ').append(item.kind())
                    .append(" id=").append(item.nativeId()).append(' ').append(item.label()).append('\n');
        }
        return text.toString();
    }

    private String messages(FrozenDiagnostics.MessageTable snapshot) {
        StringBuilder text = new StringBuilder("Messages / GL 消息\n\n")
                .append("dropped ").append(snapshot.droppedCount()).append("\n\n");
        for (FrozenDiagnostics.Message item : snapshot.entries()) {
            if (!messageVisible(item.severity())) continue;
            text.append('[').append(item.severity()).append("] #").append(item.sequence())
                    .append(" x").append(item.repeatCount()).append(' ')
                    .append(item.phase() == null ? "" : item.phase() + " ")
                    .append(item.text()).append('\n');
        }
        return text.toString();
    }

    private boolean resourceVisible(String kind) {
        return filterIndex == 0 || filterIndex == 1 && kind.equals("TEXTURE")
                || filterIndex == 2 && (kind.equals("BUFFER") || kind.equals("VAO"));
    }

    private boolean messageVisible(String severity) {
        return filterIndex == 0 || filterIndex == 1 && severity.equals("HIGH")
                || filterIndex == 2 && severity.equals("MEDIUM");
    }

    private void export() {
        try {
            FrozenDiagnostics capture = frozen == null ? diagnostics.freeze() : frozen;
            frozen = capture;
            Path written = DiagnosticsJsonExporter.export(capture, exportPath);
            notice = "Exported / 已导出: " + written;
            refresh();
        } catch (IOException failure) {
            notice = "Export failed / 导出失败: " + failure.getMessage();
            refresh();
        }
    }

    private static Panel row(float height) {
        Panel panel = new Panel();
        panel.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(height))
                .flexDirection(UiStyle.FlexDirection.ROW).gap(5).flexShrink(0).build());
        return panel;
    }

    private static Button action(String label, Runnable action) {
        Button button = new Button(label);
        button.style(UiStyle.builder().width(UiLength.points(112)).height(UiLength.points(32))
                .padding(UiInsets.points(3)).flexShrink(0).build());
        button.onClick(action);
        return button;
    }

    /** 诊断面板内部使用的单行选择项，焦点即选择，复制内容保持原始文本。 */
    private final class DiagnosticRow extends Button {
        private final int index;
        private boolean selected;

        DiagnosticRow(int index) {
            this.index = index;
            rowWidth(BODY_WIDTH);
        }

        void rowWidth(float width) {
            style(UiStyle.builder().width(UiLength.points(width))
                    .height(UiLength.points(BODY_LINE_HEIGHT))
                    .padding(UiInsets.points(4.0f, 1.0f)).flexShrink(0).build());
        }

        void selected(boolean value) {
            selected = value;
            setPressed(value);
        }

        @Override
        protected void handleDefaultEvent(UiEvent event) {
            super.handleDefaultEvent(event);
            switch (event.type()) {
                case POINTER_DOWN -> selectRow(index, event.modifiers().control()
                        || event.modifiers().superKey(), event.modifiers().shift());
                case POINTER_UP, POINTER_LEAVE, POINTER_CANCEL, KEY_UP -> {
                    setPressed(selected);
                }
                case FOCUS_GAINED, FOCUS_LOST -> setPressed(selected);
                case KEY_DOWN -> {
                    if (event instanceof KeyEvent key
                            && (key.modifiers().control() || key.modifiers().superKey())) {
                        if (key.key() == Key.A) {
                            selectAllRows();
                            event.preventDefault();
                        } else if (key.key() == Key.C) {
                            copySelection(index);
                            event.preventDefault();
                        }
                    }
                }
                default -> { }
            }
        }
    }

    private enum View {
        OVERVIEW("Overview"), PASSES("Passes"), GRAPH("Graph"),
        RESOURCES("Resources"), MESSAGES("Messages");
        final String label;
        View(String label) { this.label = label; }
    }
}
