package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.StylizedSkySettings;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricSunSettings;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;
import com.kaleblangley.haikalat.subsystems.ui.widget.Toggle;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.DoubleConsumer;

/**
 * Retained-mode controls and bounded diagnostics for the v0.24 outdoor sample.
 * The overlay is deliberately demo-only: the render pipeline remains usable
 * without a window, UI attachment, or mutable configuration object.
 */
final class OutdoorEnvironmentOverlay implements AutoCloseable {
    private final UiSystem ui;
    private final RenderPipeline pipeline;
    private final Path configPath;
    private final Label status;
    private final Label depthDiagnostics;
    private final Label volumeDiagnostics;
    private final Label shadowDiagnostics;
    private final Toggle volumeToggle;
    private final Toggle historyToggle;
    private final Toggle compareToggle;
    private final Slider densitySlider;
    private final Slider distanceSlider;
    private final Slider anisotropySlider;
    private final Slider heightDensitySlider;
    private final Slider localFogSlider;
    private final Slider windSlider;
    private final Slider noiseSlider;
    private final Slider sunSlider;
    private OutdoorEnvironmentSettings desired;
    private OutdoorEnvironmentSettings compareBase;
    private boolean syncing;

    private OutdoorEnvironmentOverlay(GlfwWindow window, RenderPipeline pipeline,
                                      OutdoorEnvironmentSettings initial, Path configPath) {
        this.pipeline = pipeline;
        this.desired = initial;
        this.compareBase = initial;
        this.configPath = configPath.toAbsolutePath().normalize();
        this.ui = UiSystem.create(window, UiConfig.defaults());

        Panel root = ui.document().root();
        root.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.percent(100))
                .padding(UiInsets.points(12)).alignItems(UiStyle.AlignItems.FLEX_END).build());
        Panel panel = new Panel();
        panel.debugName("OutdoorEnvironmentControls");
        panel.style(UiStyle.builder().width(UiLength.points(430)).height(UiLength.points(690))
                .padding(UiInsets.points(10)).gap(3)
                .flexDirection(UiStyle.FlexDirection.COLUMN).build());
        panel.add(line("v0.24 Outdoor Environment"));
        panel.add(line("Preset / sun / volume / history / repeatable capture"));

        Panel presets = new Panel();
        presets.style(rowStyle(100));
        presets.add(button("Morning fog", () -> choosePreset("morning_fog")));
        presets.add(button("Clear day", () -> choosePreset("clear_day")));
        presets.add(button("Golden hour", () -> choosePreset("golden_hour")));
        panel.add(presets);

        Panel quality = new Panel();
        quality.style(rowStyle(100));
        quality.add(button("Low", () -> chooseQuality("low")));
        quality.add(button("Balanced", () -> chooseQuality("balanced")));
        quality.add(button("High", () -> chooseQuality("high")));
        quality.add(button("Reference", () -> chooseQuality("reference")));
        panel.add(quality);

        volumeToggle = new Toggle("Volumetric sun").value(initial.volumetricSun().enabled());
        volumeToggle.onValueChanged(value -> mutate(current -> {
            VolumetricSunSettings sun = current.volumetricSun();
            return current.withVolumetricSun(value ? enabledSun(sun) : VolumetricSunSettings.disabled());
        }));
        panel.add(volumeToggle);
        historyToggle = new Toggle("Independent history / reprojection")
                .value(initial.volumetricSun().historyWeight() > 0.0f);
        historyToggle.onValueChanged(value -> mutate(current -> {
            VolumetricSunSettings sun = current.volumetricSun();
            return current.withVolumetricSun(copySun(sun, sun.enabled(), sun.steps(), sun.downsample(),
                    sun.maximumDistance(), sun.density(), sun.anisotropy(), value ?
                            Math.max(0.70f, sun.historyWeight()) : 0.0f,
                    sun.depthRejectThreshold(), sun.noiseStrength()));
        }));
        panel.add(historyToggle);
        compareToggle = new Toggle("Compare: volume off").value(false);
        compareToggle.onValueChanged(value -> applyCompare(value));
        panel.add(compareToggle);

        densitySlider = addSlider(panel, "Sun density", 0.0, 0.06,
                initial.volumetricSun().density(), value -> mutate(current -> {
                    VolumetricSunSettings sun = current.volumetricSun();
                    return current.withVolumetricSun(copySun(sun, sun.enabled(), sun.steps(), sun.downsample(),
                            sun.maximumDistance(), (float) value, sun.anisotropy(), sun.historyWeight(),
                            sun.depthRejectThreshold(), sun.noiseStrength()));
                }));
        distanceSlider = addSlider(panel, "Max distance", 8.0, 160.0,
                initial.volumetricSun().maximumDistance(), value -> mutate(current -> {
                    VolumetricSunSettings sun = current.volumetricSun();
                    return current.withVolumetricSun(copySun(sun, sun.enabled(), sun.steps(), sun.downsample(),
                            (float) value, sun.density(), sun.anisotropy(), sun.historyWeight(),
                            sun.depthRejectThreshold(), sun.noiseStrength()));
                }));
        anisotropySlider = addSlider(panel, "Scattering direction", -0.90, 0.90,
                initial.volumetricSun().anisotropy(), value -> mutate(current -> {
                    VolumetricSunSettings sun = current.volumetricSun();
                    return current.withVolumetricSun(copySun(sun, sun.enabled(), sun.steps(), sun.downsample(),
                            sun.maximumDistance(), sun.density(), (float) value, sun.historyWeight(),
                            sun.depthRejectThreshold(), sun.noiseStrength()));
                }));
        heightDensitySlider = addSlider(panel, "Height fog density", 0.0, 0.06,
                initial.globalFog().heightDensity(), value -> mutate(current -> {
                    FogSettings fog = current.globalFog();
                    return current.withGlobalFog(new FogSettings(fog.enabled(), fog.red(), fog.green(),
                            fog.blue(), fog.distanceDensity(), (float) value, fog.heightFalloff(),
                            fog.baseHeight(), fog.maximumOpacity()));
                }));
        localFogSlider = addSlider(panel, "Local fog scale", 0.0, 2.0,
                1.0, value -> mutate(current -> scaleLocalFog(current, (float) value)));
        windSlider = addSlider(panel, "Wind speed", 0.0, 1.5, initial.windSpeed(),
                value -> mutate(current -> current.withWindSpeed((float) value)));
        noiseSlider = addSlider(panel, "Noise strength", 0.0, 1.0,
                initial.volumetricSun().noiseStrength(), value -> mutate(current -> {
                    VolumetricSunSettings sun = current.volumetricSun();
                    return current.withVolumetricSun(copySun(sun, sun.enabled(), sun.steps(), sun.downsample(),
                            sun.maximumDistance(), sun.density(), sun.anisotropy(), sun.historyWeight(),
                            sun.depthRejectThreshold(), (float) value));
                }));
        sunSlider = addSlider(panel, "Sun intensity", 0.0, 8.0,
                initial.sky().sunIntensity(), value -> mutate(current -> {
                    StylizedSkySettings sky = current.sky();
                    return current.withSky(new StylizedSkySettings(sky.zenithColor(), sky.horizonColor(),
                            sky.nadirColor(), sky.sunDirection(), sky.sunColor(), (float) value,
                            sky.sunAngularRadius(), sky.haloIntensity(), sky.environmentIntensity()));
                }));

        Panel persistence = new Panel();
        persistence.style(rowStyle(100));
        persistence.add(button("Save config", this::saveConfig));
        persistence.add(button("Load config", this::loadConfig));
        persistence.add(line("file=" + this.configPath));
        panel.add(persistence);

        status = line("");
        depthDiagnostics = line("");
        volumeDiagnostics = line("");
        shadowDiagnostics = line("");
        status.maximumLines(2).ellipsis(true);
        depthDiagnostics.ellipsis(true);
        volumeDiagnostics.ellipsis(true);
        shadowDiagnostics.ellipsis(true);
        panel.add(status).add(depthDiagnostics).add(volumeDiagnostics).add(shadowDiagnostics);
        root.add(panel);
        ui.attachTo(pipeline.graph(), pipeline.finalPassName());
        syncControls(initial);
        setStatus("Ready; capture and benchmark values are reproducible");
    }

    static OutdoorEnvironmentOverlay attach(GlfwWindow window, RenderPipeline pipeline,
                                             OutdoorEnvironmentSettings initial, Path configPath) {
        return new OutdoorEnvironmentOverlay(window, pipeline, initial, configPath);
    }

    void update(WindowInputSnapshot input, float deltaSeconds) {
        ui.update(input, deltaSeconds);
        Render3dDiagnostics snapshot = pipeline.lastRender3dDiagnostics();
        Render3dDiagnostics.ShadowSummary shadow = snapshot.shadows();
        VolumetricSunSettings sun = desired.volumetricSun();
        float transmittance = (float) Math.exp(-sun.density() * sun.maximumDistance());
        float scattering = 1.0f - transmittance;
        String history = pipeline.outdoorVolumeHistoryValid() ? "valid" : "rejected/invalidated";
        depthDiagnostics.text(String.format(Locale.ROOT, "DEPTH %d x %d   CSM %d   RANGE %s",
                snapshot.depthResolve().width(), snapshot.depthResolve().height(),
                shadow.cascadeCount(), shadow.cascadeSplits()));
        volumeDiagnostics.text(String.format(Locale.ROOT,
                "SCATTER %.3f   TRANS %.3f   HISTORY %s",
                scattering, transmittance, history));
        shadowDiagnostics.text(String.format(Locale.ROOT,
                "CASTERS %s   SHADOW DRAWS %d",
                shadow.cascadeCasters(), pipeline.lastShadowCasterDrawCount()));
    }

    @Override
    public void close() {
        ui.close();
    }

    private void choosePreset(String preset) {
        OutdoorEnvironmentSettings next = Render3dOutdoorEnvironmentDemo.applyVolumeQuality(
                Render3dOutdoorEnvironmentDemo.preset(preset), qualityFor(desired.volumetricSun()));
        compareBase = next;
        mutateIgnoredCompare(next);
    }

    private void chooseQuality(String quality) {
        OutdoorEnvironmentSettings next = Render3dOutdoorEnvironmentDemo.applyVolumeQuality(
                desired, quality);
        compareBase = next;
        mutateIgnoredCompare(next);
    }

    private String qualityFor(VolumetricSunSettings sun) {
        if (!sun.enabled()) return "balanced";
        if (sun.steps() >= 96) return "reference";
        if (sun.steps() >= 64) return "high";
        if (sun.steps() <= 16) return "low";
        return "balanced";
    }

    private void mutateIgnoredCompare(OutdoorEnvironmentSettings next) {
        desired = next;
        pipeline.applyOutdoorEnvironment(compareToggle.value()
                ? next.withVolumetricSun(VolumetricSunSettings.disabled()) : next);
        syncControls(next);
        setStatus("Applied " + next.preset() + " / quality=" + qualityFor(next.volumetricSun()));
    }

    private void mutate(java.util.function.UnaryOperator<OutdoorEnvironmentSettings> operation) {
        if (syncing) return;
        OutdoorEnvironmentSettings next = operation.apply(desired);
        desired = next;
        try {
            pipeline.applyOutdoorEnvironment(compareToggle.value()
                    ? next.withVolumetricSun(VolumetricSunSettings.disabled()) : next);
        } catch (RuntimeException failure) {
            setStatus("Rejected: " + failure.getMessage());
            return;
        }
        setStatus("Applied " + next.preset());
    }

    private void applyCompare(boolean compare) {
        try {
            pipeline.applyOutdoorEnvironment(compare
                    ? desired.withVolumetricSun(VolumetricSunSettings.disabled()) : desired);
            setStatus(compare ? "Comparison: volume disabled" : "Comparison: volume enabled");
        } catch (RuntimeException failure) {
            setStatus("Comparison rejected: " + failure.getMessage());
        }
    }

    private void saveConfig() {
        try {
            Render3dOutdoorEnvironmentDemo.saveEnvironmentConfig(configPath, desired, true);
            setStatus("Saved reproducible config: " + configPath);
        } catch (RuntimeException failure) {
            setStatus("Save failed: " + failure.getMessage());
        }
    }

    private void loadConfig() {
        try {
            OutdoorEnvironmentSettings next = Render3dOutdoorEnvironmentDemo.loadEnvironmentConfig(
                    configPath, desired);
            compareBase = next;
            desired = next;
            pipeline.applyOutdoorEnvironment(compareToggle.value()
                    ? next.withVolumetricSun(VolumetricSunSettings.disabled()) : next);
            syncControls(next);
            setStatus("Loaded reproducible config: " + configPath);
        } catch (RuntimeException failure) {
            setStatus("Load failed: " + failure.getMessage());
        }
    }

    private void syncControls(OutdoorEnvironmentSettings value) {
        syncing = true;
        try {
            volumeToggle.value(value.volumetricSun().enabled());
            historyToggle.value(value.volumetricSun().historyWeight() > 0.0f);
            densitySlider.value(value.volumetricSun().density());
            distanceSlider.value(value.volumetricSun().maximumDistance());
            anisotropySlider.value(value.volumetricSun().anisotropy());
            heightDensitySlider.value(value.globalFog().heightDensity());
            windSlider.value(value.windSpeed());
            noiseSlider.value(value.volumetricSun().noiseStrength());
            sunSlider.value(value.sky().sunIntensity());
            localFogSlider.value(value.localFogVolumes().isEmpty() ? 0.0
                    : averageLocalDensity(value) / Math.max(0.0001, averageLocalDensity(value)));
        } finally {
            syncing = false;
        }
    }

    private static double averageLocalDensity(OutdoorEnvironmentSettings value) {
        return value.localFogVolumes().stream().mapToDouble(v -> v.density()).average().orElse(0.0);
    }

    private static OutdoorEnvironmentSettings scaleLocalFog(OutdoorEnvironmentSettings current, float scale) {
        List<com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume> volumes = new ArrayList<>();
        for (var volume : current.localFogVolumes()) {
            volumes.add(new com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume(volume.shape(),
                    volume.center(), volume.extent(), volume.density() * scale, volume.color(),
                    volume.noiseScale(), volume.noiseAmount()));
        }
        if (volumes.isEmpty() && current.enabled() && scale > 0.0f) {
            volumes.add(com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume.sphere(
                    new Vector3f(-2.2f, -0.7f, -9.0f), 4.0f, 0.045f * scale,
                    new Vector3f(0.62f, 0.72f, 0.80f)));
        }
        return current.withLocalFogVolumes(volumes);
    }

    private static VolumetricSunSettings enabledSun(VolumetricSunSettings sun) {
        if (sun.enabled()) return sun;
        VolumetricSunSettings balanced = VolumetricSunSettings.balanced();
        return copySun(balanced, true, balanced.steps(), balanced.downsample(), balanced.maximumDistance(),
                balanced.density(), balanced.anisotropy(), balanced.historyWeight(),
                balanced.depthRejectThreshold(), balanced.noiseStrength());
    }

    private static VolumetricSunSettings copySun(VolumetricSunSettings source, boolean enabled, int steps,
                                                 int downsample, float distance, float density,
                                                 float anisotropy, float history, float reject, float noise) {
        return new VolumetricSunSettings(enabled, steps, downsample, distance, density,
                source.scatteringColor(), anisotropy, history, reject, noise);
    }

    private static UiStyle rowStyle(float height) {
        return UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(height))
                .flexDirection(UiStyle.FlexDirection.ROW).alignItems(UiStyle.AlignItems.CENTER)
                .gap(4).build();
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.style(UiStyle.builder().flexGrow(1).height(UiLength.points(28)).build());
        button.onClick(action);
        return button;
    }

    private static Slider addSlider(Panel panel, String text, double min, double max,
                                    double initial, DoubleConsumer listener) {
        Panel row = new Panel();
        row.style(rowStyle(29));
        Label label = line(text);
        label.style(UiStyle.builder().width(UiLength.points(160)).height(UiLength.points(25)).build());
        Slider slider = new Slider(min, max, Math.max(min, Math.min(max, initial)))
                .step((max - min) / 100.0);
        slider.style(UiStyle.builder().width(UiLength.points(225)).height(UiLength.points(26)).build());
        slider.onValueChanged(listener);
        row.add(label).add(slider);
        panel.add(row);
        return slider;
    }

    private static Label line(String text) {
        Label label = new Label(text);
        label.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(24)).build());
        return label;
    }

    private void setStatus(String value) {
        status.text(value == null ? "" : value);
    }
}
