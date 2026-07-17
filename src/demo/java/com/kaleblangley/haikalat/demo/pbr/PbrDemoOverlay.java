package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;
import com.kaleblangley.haikalat.subsystems.ui.widget.Toggle;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.function.DoubleConsumer;

/** PbrDemo 的 retained-mode 参数面板；不把 Demo 调试开关写入 manifest。 */
final class PbrDemoOverlay implements AutoCloseable {
    private final UiSystem ui;
    private final MaterialInstance target;
    private final PbrEnvironment environment;
    private final Vector4f color = new Vector4f(1.0f);
    private final Vector3f emissive = new Vector3f(0.02f);

    private PbrDemoOverlay(GlfwWindow window, RenderPipeline pipeline, RenderSettings settings,
                           MaterialInstance target, PbrEnvironment environment) {
        this.ui = UiSystem.create(window, UiConfig.defaults());
        this.target = target;
        this.environment = environment;
        Panel root = ui.document().root();
        root.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.percent(100))
                .padding(UiInsets.points(12)).alignItems(UiStyle.AlignItems.FLEX_END).build());
        Panel panel = new Panel();
        panel.debugName("PbrControls");
        panel.style(UiStyle.builder().width(UiLength.points(350)).height(UiLength.points(620))
                .padding(UiInsets.points(10)).gap(5)
                .flexDirection(UiStyle.FlexDirection.COLUMN).build());
        panel.add(line("v0.12 PBR / IBL 参数面板"));
        panel.add(line("拖动滑块实时修改右侧纹理金字塔"));
        addSlider(panel, "Base R / 红", 0, 1, 1, value -> updateColor(0, value));
        addSlider(panel, "Base G / 绿", 0, 1, 1, value -> updateColor(1, value));
        addSlider(panel, "Base B / 蓝", 0, 1, 1, value -> updateColor(2, value));
        addSlider(panel, "Metallic / 金属", 0, 1, 0.65,
                value -> target.setFloat("uMetallicFactor", (float) value));
        addSlider(panel, "Roughness / 粗糙", 0, 1, 0.35,
                value -> target.setFloat("uRoughnessFactor", (float) value));
        addSlider(panel, "Normal / 法线", 0, 2, 1,
                value -> target.setFloat("uNormalScale", (float) value));
        addSlider(panel, "Occlusion / 遮蔽", 0, 1, 1,
                value -> target.setFloat("uOcclusionStrength", (float) value));
        addSlider(panel, "Emissive / 自发光", 0, 4, 0.02, value -> {
            emissive.set((float) value);
            target.setVec3("uEmissiveFactor", emissive);
        });
        addSlider(panel, "Environment / 环境", 0, 4, environment.intensity(),
                value -> environment.intensity((float) value));
        addSlider(panel, "Rotation / 旋转", -(float) Math.PI, (float) Math.PI, 0,
                value -> environment.rotationRadians((float) value));
        panel.add(toggle("Direct / 直接光", "uEnableDirect", true));
        panel.add(toggle("Diffuse IBL / 漫反射", "uEnableDiffuseIbl", true));
        panel.add(toggle("Specular IBL / 镜面", "uEnableSpecularIbl", true));
        panel.add(toggle("Normal map / 法线图", "uEnableNormalMap", true));
        Toggle exposure = new Toggle("Auto exposure / 自动曝光").value(
                settings.exposureMode() == com.kaleblangley.haikalat.runtime.ExposureMode.AUTO);
        exposure.enabled(false);
        Toggle bloom = new Toggle("Bloom (rebuild) / 泛光").value(settings.bloomSettings().enabled());
        bloom.enabled(false);
        panel.add(exposure).add(bloom);
        root.add(panel);
        ui.attachTo(pipeline.graph(), pipeline.finalPassName());
    }

    static PbrDemoOverlay attach(GlfwWindow window, RenderPipeline pipeline, RenderSettings settings,
                                 MaterialInstance target, PbrEnvironment environment) {
        return new PbrDemoOverlay(window, pipeline, settings, target, environment);
    }

    void update(WindowInputSnapshot input, float deltaSeconds) {
        ui.update(input, deltaSeconds);
    }

    @Override
    public void close() {
        ui.close();
    }

    private void updateColor(int component, double value) {
        color.setComponent(component, (float) value);
        target.setVec4("uBaseColorFactor", color);
    }

    private Toggle toggle(String text, String uniform, boolean initial) {
        Toggle toggle = new Toggle(text).value(initial);
        toggle.onValueChanged(value -> target.setInt(uniform, value ? 1 : 0));
        return toggle;
    }

    private static void addSlider(Panel panel, String text, double min, double max,
                                  double initial, DoubleConsumer listener) {
        Panel row = new Panel();
        row.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(32))
                .flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER).gap(6).build());
        Label label = line(text);
        label.style(UiStyle.builder().width(UiLength.points(145)).height(UiLength.points(26)).build());
        Slider slider = new Slider(min, max, initial).step((max - min) / 100.0);
        slider.style(UiStyle.builder().width(UiLength.points(170)).height(UiLength.points(28)).build());
        slider.onValueChanged(listener);
        row.add(label).add(slider);
        panel.add(row);
    }

    private static Label line(String text) {
        Label label = new Label(text);
        label.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(25)).build());
        return label;
    }
}
