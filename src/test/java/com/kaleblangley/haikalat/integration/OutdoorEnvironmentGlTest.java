package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.*;
import com.kaleblangley.haikalat.subsystems.ui.UiConfig;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.UnavailableTextInputAdapter;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL45.glGetTextureImage;

/** Real GL regressions for the outdoor acceptance failures, including image-space numeric output. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class OutdoorEnvironmentGlTest {
    @Test
    void cameraMotionReprojectsButCutsRejectHistory() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean history = new AtomicBoolean();
            f.pipeline.graph().addPass("HistoryObserver").writeToBackbuffer().noClear()
                    .dependsOn(f.pipeline.finalPassName())
                    .execute((resources, commands) -> history.set(historyReason(f).equals("NONE")));
            f.frame(); f.frame();
            assertTrue(history.get(), "stationary history must be available");
            f.scene.camera().setPosition(new Vector3f(.001f,2,8));
            f.frame();
            assertTrue(history.get(), "ordinary camera movement must not clear reprojectable history");
            f.scene.camera().setPosition(new Vector3f(30,2,8));
            f.frame();
            assertFalse(history.get(), "camera cuts must reject history");
        }
    }

    @Test
    void presetAndHistoryControlsReachTheActiveRenderer() throws Exception {
        try (Fixture f = new Fixture()) {
            var updated = withQuality(f.pipeline.volumetricFog(), VolumetricFogSettings.Quality.BALANCED, false);
            f.pipeline.applyVolumetricFog(updated);
            assertEquals(updated, f.pipeline.volumetricFog());
            f.frame();
            Object owner = field(field(f.pipeline,"activeGeneration"),"volumetric");
            assertEquals(updated, invoke(invoke(field(owner,"frameState"),"previous"),"settings"));
            var golden = OutdoorEnvironmentSettings.goldenHour();
            f.pipeline.applyOutdoorEnvironment(golden);
            assertTrue(golden.sky().sunDirection().distance(f.scene.lights().get(0).direction()) < 1e-6f);
            assertEquals(golden.sky().sunIntensity(),f.scene.lights().get(0).intensity());
        }
    }

    @Test
    void scalarVisualUpdatesRunForThreeHundredFramesWithoutGenerationRebuild() throws Exception {
        try (Fixture f = new Fixture()) {
            long builds = (Long) field(f.pipeline, "generationBuildCount");
            long initialRevision = f.pipeline.visualSettingsRevision();
            VisualSettings current = f.pipeline.visualSettings();
            VisualSettings last = current;
            for (int frame = 0; frame < 300; frame++) {
                Vector3f direction = current.directionalLightDirection()
                        .rotateY(frame * 0.0001f);
                last = new VisualSettings(0.8f + frame * 0.001f,
                        current.iblIntensity(), current.iblRotationRadians(),
                        direction, current.directionalLightColor(),
                        current.directionalLightIntensity() + frame * 0.001f,
                        current.antiAliasingMode(), current.bloom(), current.outdoor(), current.volumetricFog());
                f.pipeline.applyVisualSettings(last);
                f.frame();
            }
            assertEquals(builds, field(f.pipeline, "generationBuildCount"));
            assertEquals(initialRevision + 300, f.pipeline.visualSettingsRevision());
            Object post = field(field(f.pipeline, "activeGeneration"), "postProcess");
            assertEquals(last.manualExposure(), (Float) field(post, "manualExposure"));
            assertEquals(last.directionalLightDirection(), f.scene.lights().getFirst().direction());
            assertEquals(last.directionalLightIntensity(), f.scene.lights().getFirst().intensity());

            long revision = f.pipeline.visualSettingsRevision();
            f.pipeline.applyVisualSettings(last);
            assertEquals(revision, f.pipeline.visualSettingsRevision(),
                    "same profile must be a no-op");
        }
    }

    @Test
    void topologyProfileBuildsCandidateAndAllocationFailureKeepsAllOldValues() throws Exception {
        try (Fixture f = new Fixture()) {
            VisualSettings original = f.pipeline.visualSettings();
            long builds = (Long) field(f.pipeline, "generationBuildCount");
            BloomSettings enabledBloom = BloomSettings.builder().enabled(true)
                    .threshold(1.1f).softKnee(0.4f).intensity(0.12f).maxLevels(3).build();
            VisualSettings topology = new VisualSettings(original.manualExposure(),
                    original.iblIntensity(), original.iblRotationRadians(), AntiAliasingMode.TAA,
                    enabledBloom, original.outdoor()).withVolumetricFog(original.volumetricFog());
            f.pipeline.applyVisualSettings(topology);
            assertEquals(builds + 1, field(f.pipeline, "generationBuildCount"));
            assertTrue(f.pipeline.graph().hasPass(PostProcessTargets.TAA_PASS));
            assertTrue(f.pipeline.graph().hasPass(PostProcessTargets.BLOOM_EXTRACT_PASS));

            VisualSettings accepted = f.pipeline.visualSettings();
            long revision = f.pipeline.visualSettingsRevision();
            var differentShape = withQuality(accepted.volumetricFog(), VolumetricFogSettings.Quality.LOW, true);
            VisualSettings failing = new VisualSettings(2.0f, 0.25f, 0.5f,
                    accepted.antiAliasingMode(), accepted.bloom(), accepted.outdoor()).withVolumetricFog(differentShape);
            System.setProperty("haikalat.test.failVolumeAllocation", "0");
            try {
                assertThrows(IllegalStateException.class,
                        () -> f.pipeline.applyVisualSettings(failing));
            } finally {
                System.clearProperty("haikalat.test.failVolumeAllocation");
            }
            assertEquals(accepted, f.pipeline.visualSettings());
            assertEquals(revision, f.pipeline.visualSettingsRevision());
        }
    }

    @Test
    void visualProfileAndBorrowedEnvironmentCommitOrRollbackTogether() throws Exception {
        try (Fixture f = new Fixture();
             PbrEnvironment candidate = PbrEnvironmentLoader.fromSky(f.device,
                     OutdoorEnvironmentSettings.goldenHour().sky(),
                     PbrEnvironmentSettings.testQuality())) {
            Object originalEnvironment = field(f.pipeline, "pbrEnvironment");
            VisualSettings original = f.pipeline.visualSettings();
            long revision = f.pipeline.visualSettingsRevision();
            VisualSettings changed = new VisualSettings(1.4f, 0.7f, 0.2f,
                    original.antiAliasingMode(), original.bloom(),
                    OutdoorEnvironmentSettings.goldenHour()).withVolumetricFog(original.volumetricFog());
            System.setProperty("haikalat.test.failVolumeAllocation", "0");
            try {
                assertThrows(IllegalStateException.class,
                        () -> f.pipeline.applyVisualSettings(changed, candidate));
            } finally {
                System.clearProperty("haikalat.test.failVolumeAllocation");
            }
            assertSame(originalEnvironment, field(f.pipeline, "pbrEnvironment"));
            assertEquals(original, f.pipeline.visualSettings());
            assertEquals(revision, f.pipeline.visualSettingsRevision());
            assertFalse(candidate.isClosed());

            f.pipeline.applyVisualSettings(changed, candidate);
            assertSame(candidate, field(f.pipeline, "pbrEnvironment"));
            VisualSettings applied = f.pipeline.visualSettings();
            assertEquals(changed.manualExposure(), applied.manualExposure());
            assertEquals(changed.iblIntensity(), applied.iblIntensity());
            assertEquals(changed.outdoor(), applied.outdoor());
            assertTrue(changed.directionalLightDirection()
                    .distance(applied.directionalLightDirection()) < 1e-6f);
            assertEquals(revision + 1, f.pipeline.visualSettingsRevision());
            f.frame();
        }
    }

    @Test
    void perPipelineIblOverridesDoNotMutateSharedEnvironment() throws Exception {
        try (Fixture f = new Fixture();
             PbrEnvironment environment = PbrEnvironmentLoader.fromSky(f.device,
                     f.preset.sky(), PbrEnvironmentSettings.testQuality());
             PbrMaterialBinder first = new PbrMaterialBinder(environment);
             PbrMaterialBinder second = new PbrMaterialBinder(environment)) {
            float borrowedIntensity = environment.intensity();
            float borrowedRotation = environment.rotationRadians();
            first.visualOverride(0.25f, 0.5f);
            second.visualOverride(1.75f, -0.25f);

            assertEquals(borrowedIntensity, environment.intensity());
            assertEquals(borrowedRotation, environment.rotationRadians());
            assertEquals(0.25f, field(first, "intensity"));
            assertEquals(1.75f, field(second, "intensity"));
        }
    }

    @Test
    void uiRebindsAfterQualityAndEnvironmentChangesAndKeepsFailedCandidate() throws Exception {
        try (Fixture f = new Fixture();
             UiSystem ui = UiSystem.create(f.window,UiConfig.defaults(),new UnavailableTextInputAdapter("outdoor regression"))) {
            ui.attachTo(f.pipeline.graph(),f.pipeline.finalPassName());
            var original = f.pipeline.graph();
            var low = withQuality(f.pipeline.volumetricFog(), VolumetricFogSettings.Quality.LOW, true);
            f.pipeline.applyVolumetricFog(low);
            assertTrue(original.isClosed());
            ui.rebindTo(f.pipeline.graph(),f.pipeline.finalPassName());
            assertTrue(f.pipeline.graph().hasPass(UiSystem.OVERLAY_PASS_NAME));
            assertThrows(IllegalStateException.class,()->ui.attachTo(f.pipeline.graph(),f.pipeline.finalPassName()));
            var active = f.pipeline.graph();
            System.setProperty("haikalat.test.failVolumeAllocation","0");
            try {
                assertThrows(IllegalStateException.class,()->f.pipeline.resize(80,80));
            } finally { System.clearProperty("haikalat.test.failVolumeAllocation"); }
            assertSame(active,f.pipeline.graph());
            ui.rebindTo(active,f.pipeline.finalPassName());
            ui.update(f.window.inputSnapshot(),1f/60);
            f.frame();
            try (PbrEnvironment environment = PbrEnvironmentLoader.fromSky(f.device,
                    OutdoorEnvironmentSettings.goldenHour().sky(),PbrEnvironmentSettings.testQuality())) {
                f.pipeline.applyOutdoorEnvironment(OutdoorEnvironmentSettings.goldenHour(), environment);
                ui.rebindTo(f.pipeline.graph(),f.pipeline.finalPassName());
                assertTrue(f.pipeline.graph().hasPass(UiSystem.OVERLAY_PASS_NAME));
                f.frame();
                f.pipeline.close();
                assertFalse(environment.isClosed(),"the pipeline borrows the environment");
            }
        }
    }

    @Test
    void localOnlyMediumProducesBeerLambertTransmittanceAndSeparateSampleDepth() throws Exception {
        try (Fixture f = new Fixture()) {
            var medium = new VolumetricFogSettings(true,10,VolumetricFogSettings.Quality.BALANCED,
                    FogMediumSettings.vacuum(),List.of(new LocalFogVolume(LocalFogVolume.Shape.BOX,
                    new Vector3f(),new Vector3f(100),.1f,new Vector3f(1),new Vector3f(),0,0,0)),
                    0,false,0,1337,new Vector3f());
            f.pipeline.applyVolumetricFog(medium);
            AtomicInteger depth = new AtomicInteger();
            f.pipeline.graph().addPass("VolumeObserver").writeToBackbuffer().noClear()
                    .dependsOn(f.pipeline.finalPassName()).execute((resources,commands)->
                        depth.set(resources.depthAttachment(PostProcessTargets.SCENE_DEPTH)));
            f.frame();
            assertEquals(GL_NO_ERROR,glGetError(),"pipeline execution must be valid before independent texture readback");
            Object owner=field(field(f.pipeline,"activeGeneration"),"volumetric");
            var prefix=(com.kaleblangley.haikalat.backend.texture.Texture3D) invoke(field(owner,"resources"),"prefix");
            assertTrue(prefix.id()>0 && depth.get()>0);
            assertNotEquals(prefix.id(),depth.get(),"the 3D S/T prefix does not alias scene depth");
            FloatBuffer pixels=BufferUtils.createFloatBuffer(prefix.width()*prefix.height()*prefix.depth()*4);
            glGetTextureImage(prefix.id(),0,GL_RGBA,GL_FLOAT,pixels);
            assertEquals(GL_NO_ERROR,glGetError());
            int end=medium.quality().depthSlices()*prefix.width()*prefix.height()*4;
            var frame=invoke(field(owner,"frameState"),"previous");
            var projection=(org.joml.Matrix4f)invoke(invoke(frame,"camera"),"projection");
            int width=f.window.width(),height=f.window.height(),tile=medium.quality().tilePixels();
            for(int y=0;y<prefix.height();y++)for(int x=0;x<prefix.width();x++) {
                double pixelX=.5*(x*tile+Math.min((x+1)*tile,width));
                double pixelY=.5*(y*tile+Math.min((y+1)*tile,height));
                double rayX=(2*pixelX/width-1)/projection.m00();
                double rayY=(2*pixelY/height-1)/projection.m11();
                double length=10*Math.sqrt(1+rayX*rayX+rayY*rayY);
                assertEquals(Math.exp(-.1*length),pixels.get(end+(y*prefix.width()+x)*4+3),.002);
            }

        }
    }

    @Test
    void skyIrradianceMatchesConstantRadianceAndExcludesSunDisc() {
        try (Fixture f = new Fixture()) {
            Vector3f grey=new Vector3f(.5f);
            StylizedSkySettings sky=new StylizedSkySettings(grey,grey,grey,new Vector3f(0,-1,0),
                    new Vector3f(1),100,.03f,.5f,1);
            try(PbrEnvironment environment=PbrEnvironmentLoader.fromSky(f.device,sky,PbrEnvironmentSettings.testQuality())) {
                int size=environment.irradiance().size();
                FloatBuffer pixels=BufferUtils.createFloatBuffer(size*size*6*4);
                glGetTextureImage(environment.irradiance().id(),0,GL_RGBA,GL_FLOAT,pixels);
                for(int i=0;i<pixels.capacity();i++) if(i%4!=3) assertEquals(Math.PI*.5,pixels.get(i),.006);
            }
        }
    }

    private static VolumetricFogSettings physicalFog() {
        return new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                new FogMediumSettings(.01f,new Vector3f(.8f),new Vector3f(),0,0),List.of(),0,true,.86f,1337,new Vector3f());
    }
    private static VolumetricFogSettings withQuality(VolumetricFogSettings f,VolumetricFogSettings.Quality q,boolean history) {
        return new VolumetricFogSettings(f.enabled(),f.fogDistance(),q,f.globalMedium(),f.localVolumes(),f.anisotropy(),
                history,f.historyWeight(),f.noiseSeed(),f.wind());
    }
    private static Object invoke(Object object,String name) throws Exception {
        var method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(object);
    }
    private static String historyReason(Fixture f) {
        try { return field(field(field(field(f.pipeline,"activeGeneration"),"volumetric"),"historyPlan"),"reason").toString(); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }

    private static Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }

    private static final class Fixture implements AutoCloseable {
        final GlfwWindow window = new GlfwWindow.Builder().dimensions(64,64).title("Outdoor regression").visible(false).build();
        final GlRenderDevice device;
        final Scene scene = new Scene(new Camera(new Vector3f(0,2,8)));
        final OutdoorEnvironmentSettings preset = OutdoorEnvironmentSettings.morningFog();
        final RenderPipeline pipeline;
        Fixture() {
            window.bindContext(); GL.createCapabilities(); window.setVsync(false);
            com.kaleblangley.haikalat.backend.GlDebug.enableDebugCallback();
            device=new GlRenderDevice();
            scene.addLight(SceneLight.shadowedDirectional(preset.sky().sunDirection(),preset.sky().sunColor(),preset.sky().sunIntensity()));
            pipeline=new RenderPipeline(window,scene,null,RenderSettings.builder().vsync(false)
                    .antiAliasingMode(AntiAliasingMode.FXAA).toneMappingMode(ToneMappingMode.ACES).build())
                    .directionalCascades(new DirectionalCascadeSettings(4,256,.62f,.08f, Float.MAX_VALUE)).outdoorEnvironment(preset).volumetricFog(physicalFog());
            pipeline.build();
        }
        void frame() { pipeline.execute(device); }
        @Override public void close() { try { pipeline.close(); } finally { window.close(); } }
    }
}
