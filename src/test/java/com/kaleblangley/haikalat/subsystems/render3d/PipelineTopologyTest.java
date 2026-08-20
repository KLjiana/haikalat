package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PipelineTopologyTest {
    @Test
    void keyIncludesExtentSamplesPassFeaturesAndShadowKinds() {
        Scene scene = new Scene(new Camera());
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.MSAA)
                .msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.AUTO)
                .bloomSettings(BloomSettings.builder().enabled(true).maxLevels(4).build())
                .build();
        PostProcessSettings effects = PostProcessSettings.builder()
                .fog(FogSettings.builder().build())
                .build();
        PipelineTopology base = PipelineTopology.capture(
                scene, settings, effects, 1280, 720, false, false,
                DirectionalCascadeSettings.disabled());

        assertEquals(4, base.sampleCount());
        assertEquals(4, base.bloomLevels());
        assertNotEquals(base, base.withExtent(1920, 1080));
        assertNotEquals(base, PipelineTopology.capture(
                scene, settings, effects, 1280, 720, true, false,
                DirectionalCascadeSettings.disabled()));
        assertNotEquals(base, PipelineTopology.capture(
                scene, settings, effects, 1280, 720, false, false,
                new DirectionalCascadeSettings(4, 4096, 0.6f, 0.08f)));

        scene.addLight(SceneLight.shadowedPoint(new Vector3f(), new Vector3f(1.0f), 1.0f, 10.0f));
        PipelineTopology shadowed = PipelineTopology.capture(
                scene, settings, effects, 1280, 720, false, false,
                DirectionalCascadeSettings.disabled());
        assertNotEquals(base, shadowed);
        assertEquals(true, shadowed.pointShadow());
    }

    @Test
    void runtimeOnlyValuesDoNotChangeTopology() {
        Scene scene = new Scene(new Camera());
        RenderSettings first = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(1.0f)
                .bloomSettings(BloomSettings.builder().enabled(true)
                        .maxLevels(3).threshold(1.0f).intensity(0.1f).build())
                .build();
        RenderSettings second = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(2.0f)
                .bloomSettings(BloomSettings.builder().enabled(true)
                        .maxLevels(3).threshold(2.0f).intensity(0.4f).build())
                .build();

        assertEquals(PipelineTopology.capture(scene, first, PostProcessSettings.defaults(),
                        800, 600, false, false, DirectionalCascadeSettings.disabled()),
                PipelineTopology.capture(scene, second, PostProcessSettings.defaults(),
                        800, 600, false, false, DirectionalCascadeSettings.disabled()));
    }

    @Test
    void gtaoTopologyUsesCeilHalfExtentAndRequiresPbr() {
        Scene scene = new Scene(new Camera());
        PostProcessSettings gtao = PostProcessSettings.builder()
                .gtao(GtaoSettings.defaults().withEnabled(true)).build();
        PipelineTopology topology = PipelineTopology.capture(scene,
                RenderSettings.builder().build(), gtao, 5, 3, false, false,
                DirectionalCascadeSettings.disabled());

        assertEquals(true, topology.gtaoEnabled());
        assertEquals(3, topology.gtaoHalfWidth());
        assertEquals(2, topology.gtaoHalfHeight());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(topology, false).validate());
    }

    @Test
    void emptyGenerationCloseIsIdempotentAndIdsAreDistinct() {
        PipelineTopology topology = PipelineTopology.capture(new Scene(new Camera()),
                RenderSettings.builder().build(), PostProcessSettings.defaults(),
                32, 32, false, false, DirectionalCascadeSettings.disabled());
        PipelineGeneration first = new PipelineGeneration(topology);
        PipelineGeneration second = new PipelineGeneration(topology);
        assertNotEquals(first.id, second.id);

        first.close();
        first.close();
        assertEquals(true, first.isClosed());
    }
}
