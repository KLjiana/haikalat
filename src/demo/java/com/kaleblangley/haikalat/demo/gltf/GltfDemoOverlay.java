package com.kaleblangley.haikalat.demo.gltf;

import com.kaleblangley.haikalat.runtime.DebugOverlaySnapshot;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;

import java.util.List;
import java.util.Locale;

/** 独立 glTF Demo 的 retained 只读资产检查器；F1 可隐藏。 */
final class GltfDemoOverlay implements AutoCloseable {
    private final UiSystem ui;
    private final Panel inspector;
    private final Label telemetry;

    private GltfDemoOverlay(GlfwWindow window, RenderPipeline pipeline, List<String> lines) {
        ui = UiSystem.create(window, UiConfig.defaults());
        Panel root = ui.document().root();
        root.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.percent(100))
                .padding(UiInsets.points(12)).alignItems(UiStyle.AlignItems.FLEX_START).build());
        inspector = new Panel();
        inspector.debugName("GltfDemoInspector");
        inspector.style(UiStyle.builder().width(UiLength.points(590)).height(UiLength.points(390))
                .padding(UiInsets.points(9)).gap(5)
                .flexDirection(UiStyle.FlexDirection.COLUMN).build());
        inspector.add(line("v0.13 glTF inspector / F1 隐藏 | 仅 showcase.gltf + radio.gltf"));
        telemetry = line("FPS -- | CPU -- ms | GPU -- ms");
        inspector.add(telemetry);

        Panel content = new Panel();
        content.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(Math.max(24, lines.size() * 21 + 8)))
                .padding(UiInsets.points(3)).gap(1)
                .flexDirection(UiStyle.FlexDirection.COLUMN).build());
        lines.forEach(text -> content.add(line(text)));
        ScrollView scroll = new ScrollView();
        scroll.debugName("GltfDemoInspectorScroll");
        scroll.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(320)).build());
        scroll.content(content);
        inspector.add(scroll);
        root.add(inspector);
        ui.attachTo(pipeline.graph(), pipeline.finalPassName());
    }

    static GltfDemoOverlay attach(GlfwWindow window, RenderPipeline pipeline, List<String> lines) {
        return new GltfDemoOverlay(window, pipeline, List.copyOf(lines));
    }

    void update(WindowInputSnapshot input, float deltaSeconds, DebugOverlaySnapshot statistics) {
        if (input.keyPressed(Key.F1)) {
            inspector.visibility(inspector.visibility() == UiVisibility.VISIBLE
                    ? UiVisibility.COLLAPSED : UiVisibility.VISIBLE);
        }
        telemetry.text(String.format(Locale.ROOT,
                "FPS %.1f | CPU %.3f ms | GPU %.3f ms | draw %d",
                statistics.fps(), statistics.cpuSubmitMillis(), statistics.gpuMillis(),
                statistics.drawCalls()));
        ui.update(input, deltaSeconds);
    }

    @Override
    public void close() {
        ui.close();
    }

    private static Label line(String text) {
        Label label = new Label(text);
        label.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(20)).build());
        return label;
    }
}
