package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Actual town caster geometry: opening the lamp must illuminate the air below it. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricTownLampGlTest {
    @Test void openStreetFixtureRestoresAllocatedSpotShadowTransmissionWithoutMovingItsLight() throws Exception {
        try (var window = new GlfwWindow.Builder().dimensions(256,128).visible(false)
                .title("Town lamp aperture regression").build()) {
            window.bindContext(); GL.createCapabilities(); GlDebug.enableDebugCallback();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                float[][] sources = new float[3][];
                List<SceneLight> originalLights = null;
                try (var shader = ShaderProgram.fromResource(VolumetricTownLampGlTest.class,
                        "/shaders/render3d/pbr/pbr-forward.vert","/shaders/render3d/pbr/pbr-forward.frag");
                     var fallback = new PbrFallbackTextures();
                     var environments = new OutdoorEnvironmentResources(new GlRenderDevice(),"default")) {
                    for (int variant = 0; variant < 3; variant++) {
                        try (var bundle = variant == 0
                                ? ClusteredDemoSceneFactory.create(ClusteredDemoSceneFactory.Request.town(),shader,fallback)
                                : ClusteredDemoSceneFactory.createVolumetricTown(shader,fallback)) {
                            var scene = bundle.scene;
                            if (variant == 0) originalLights = List.copyOf(scene.lights());
                            else assertEquals(originalLights,scene.lights(),"all surface light positions/values remain identical");
                            assertEquals(128,scene.lights().size()-1);
                            for (int i=0;i<scene.lights().size();i++) scene.setLightVolumeHints(i,LightVolumeHints.DISABLED);
                            scene.setLightVolumeHints(49,new LightVolumeHints(1,variant != 2,false));
                            var medium = FogMediumSettings.homogeneous(.03f,new Vector3f(.8f),new Vector3f());
                            var fog = new VolumetricFogSettings(true,6,VolumetricFogSettings.Quality.BALANCED,
                                    medium,List.of(),0,false,0,0,new Vector3f());
                            var pipeline = new RenderPipeline(window,scene,null,RenderSettings.builder()
                                    .antiAliasingMode(AntiAliasingMode.NONE).toneMappingMode(ToneMappingMode.ACES).vsync(false).build(),
                                    environments.environmentFor(OutdoorEnvironmentSettings.disabled()))
                                    .clusteredLighting(ClusteredLightingSettings.defaults())
                                    .localShadows(LocalShadowPipelineSettings.balanced()).volumetricFog(fog);
                            try {
                                pipeline.build();
                                var position = new Vector3f(-5.7f,2.5f,22);
                                var view = new Matrix4f().lookAt(position,new Vector3f(-5.7f,2.5f,21),new Vector3f(0,1,0));
                                var projection = new Matrix4f().ortho(-.8f,.8f,-.8f,.8f,.1f,6);
                                var camera = new ExternalCamera(view,projection,new Matrix4f(projection).mul(view),position,0,.1f,6,0);
                                pipeline.execute(new GlRenderDevice(),camera,PresentationTarget.defaultFramebuffer(256,128),1f/60);
                                var diagnostics = pipeline.volumetricFogDiagnostics();
                                assertTrue(diagnostics.available());
                                assertEquals(16,diagnostics.columnsX()); assertEquals(8,diagnostics.columnsY());
                                // Orthographic slices are linear: [21,22] * 6/64 surrounds z=20,
                                // directly below the fixed emitter rather than behind the lamp rim.
                                sources[variant] = pipeline.captureVolumetricSlice(VolumetricFogDiagnostics.Field.SOURCE,21);
                                for (float value : sources[variant]) assertTrue(Float.isFinite(value));
                                var counters = pipeline.captureVolumetricCounters();
                                assertEquals(0,counters.nonfiniteMedium()); assertEquals(0,counters.limitedMedium());
                                assertEquals(0,counters.limitedSource()); assertEquals(0,counters.limitedIntegral());
                                String directory = System.getProperty("haikalat.volumeTownEvidenceDir");
                                if (directory != null) {
                                    Path file = Path.of(directory,"lamp-source-"+variant+".csv");
                                    Files.createDirectories(file.getParent());
                                    StringBuilder raw = new StringBuilder("x,y,r,g,b,sigma_t\n");
                                    for (int y=0;y<8;y++) for (int x=0;x<16;x++) {
                                        int offset=(y*16+x)*4;
                                        raw.append(x).append(',').append(y);
                                        for (int c=0;c<4;c++) raw.append(',').append(sources[variant][offset+c]);
                                        raw.append('\n');
                                    }
                                    Files.writeString(file,raw);
                                }
                            } finally { pipeline.close(); }
                        }
                    }
                }
                double legacy = center(sources[0]), open = center(sources[1]), unshadowed = center(sources[2]);
                System.out.printf(java.util.Locale.ROOT,"Town lamp source: legacy=%.9g open=%.9g noShadow=%.9g legacyRatio=%.9g openRatio=%.9g%n",
                        legacy,open,unshadowed,legacy/unshadowed,open/unshadowed);
                assertTrue(unshadowed > 1e-6,"reference beam illuminates air below the fixed light");
                assertTrue(legacy < unshadowed*.05,"legacy solid bottom/support occludes the downward beam");
                assertTrue(open > unshadowed*.8,"open aperture passes allocated shadow visibility");
                assertTrue(GlDebug.resources().liveResources().isEmpty(),"all town/pipeline resources close");
            }
            GlDebug.assertNoError("town lamp aperture");
        }
    }

    private static double center(float[] rgba) {
        double sum=0;
        for (int y=3;y<=4;y++) for (int x=7;x<=8;x++) sum+=rgba[(y*16+x)*4];
        return sum/4;
    }
}
