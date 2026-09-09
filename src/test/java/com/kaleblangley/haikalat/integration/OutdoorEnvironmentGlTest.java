package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.RenderSettings;
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
                    .execute((resources, commands) -> history.set(f.pipeline.outdoorVolumeHistoryValid()));
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
            var v = f.preset.volumetricSun();
            var updated = new VolumetricSunSettings(true,v.steps(),v.downsample(),v.maximumDistance(),
                    v.density(),v.scatteringColor(),v.anisotropy(),0,.012f,v.noiseStrength());
            f.pipeline.applyOutdoorEnvironment(f.preset.withVolumetricSun(updated));
            Object post = field(field(f.pipeline,"activeGeneration"),"postProcess");
            assertEquals(0f,(Float)field(post,"outdoorHistoryWeight"));
            assertEquals(.012f,(Float)field(post,"outdoorDepthReject"));
            var golden = OutdoorEnvironmentSettings.goldenHour();
            f.pipeline.applyOutdoorEnvironment(golden);
            assertTrue(golden.sky().sunDirection().distance(f.scene.lights().get(0).direction()) < 1e-6f);
            assertEquals(golden.sky().sunIntensity(),f.scene.lights().get(0).intensity());
        }
    }

    @Test
    void uiRebindsAfterQualityAndEnvironmentChangesAndKeepsFailedCandidate() throws Exception {
        try (Fixture f = new Fixture();
             UiSystem ui = UiSystem.create(f.window,UiConfig.defaults(),new UnavailableTextInputAdapter("outdoor regression"))) {
            ui.attachTo(f.pipeline.graph(),f.pipeline.finalPassName());
            var original = f.pipeline.graph();
            var v = f.preset.volumetricSun();
            var low = new VolumetricSunSettings(true,16,4,v.maximumDistance(),v.density(),
                    v.scatteringColor(),v.anisotropy(),v.historyWeight(),v.depthRejectThreshold(),v.noiseStrength());
            f.pipeline.applyOutdoorEnvironment(f.preset.withVolumetricSun(low));
            assertTrue(original.isClosed());
            ui.rebindTo(f.pipeline.graph(),f.pipeline.finalPassName());
            assertTrue(f.pipeline.graph().hasPass(UiSystem.OVERLAY_PASS_NAME));
            assertThrows(IllegalStateException.class,()->ui.attachTo(f.pipeline.graph(),f.pipeline.finalPassName()));
            var active = f.pipeline.graph();
            System.setProperty("haikalat.test.failOutdoorHistoryAllocation","true");
            try {
                assertThrows(IllegalStateException.class,()->f.pipeline.resize(80,80));
            } finally { System.clearProperty("haikalat.test.failOutdoorHistoryAllocation"); }
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
            var volume = new VolumetricSunSettings(true,32,2,10,0,new Vector3f(1),0,0,.008f,0);
            var medium = f.preset.withVolumetricSun(volume).withGlobalFog(FogSettings.disabled())
                    .withLocalFogVolumes(List.of(new LocalFogVolume(LocalFogVolume.Shape.BOX,
                            new Vector3f(),new Vector3f(100),.1f,new Vector3f(1),0,0)));
            f.pipeline.applyOutdoorEnvironment(medium);
            AtomicInteger color = new AtomicInteger(), depth = new AtomicInteger();
            f.pipeline.graph().addPass("VolumeObserver").writeToBackbuffer().noClear()
                    .dependsOn(f.pipeline.finalPassName()).execute((resources,commands)-> {
                        color.set(resources.colorAttachment(PostProcessTargets.OUTDOOR_VOLUME_COLOR));
                        depth.set(resources.colorAttachment(PostProcessTargets.OUTDOOR_VOLUME_SAMPLE_DEPTH));
                    });
            f.frame();
            assertTrue(color.get()>0 && depth.get()>0);
            assertNotEquals(color.get(),depth.get());
            int width=org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(color.get(),0,GL_TEXTURE_WIDTH);
            int height=org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(color.get(),0,GL_TEXTURE_HEIGHT);
            FloatBuffer pixels=BufferUtils.createFloatBuffer(width*height*4);
            glGetTextureImage(color.get(),0,GL_RGBA,GL_FLOAT,pixels);
            assertEquals(GL_NO_ERROR,glGetError());
            for(int i=3;i<pixels.capacity();i+=4) assertEquals(Math.exp(-1),pixels.get(i),.002);
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
            device=new GlRenderDevice();
            scene.addLight(SceneLight.shadowedDirectional(preset.sky().sunDirection(),preset.sky().sunColor(),preset.sky().sunIntensity()));
            pipeline=new RenderPipeline(window,scene,null,RenderSettings.builder().vsync(false)
                    .antiAliasingMode(AntiAliasingMode.FXAA).toneMappingMode(ToneMappingMode.ACES).build())
                    .directionalCascades(new DirectionalCascadeSettings(4,256,.62f,.08f)).outdoorEnvironment(preset);
            pipeline.build();
        }
        void frame() { pipeline.execute(device); }
        @Override public void close() { try { pipeline.close(); } finally { window.close(); } }
    }
}
