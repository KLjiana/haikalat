package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.*;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke", matches="true")
class VolumetricGenerationGlTest {
    static final class Size implements RenderWindow { int width=33,height=17; public int width(){return width;} public int height(){return height;} }
    static VolumetricFogSettings fog(float emission) {
        return VolumetricMediumGlTest.settings(FogMediumSettings.homogeneous(.04f,new Vector3f(.7f),new Vector3f(emission)),List.of());
    }
    static RenderPipeline pipeline(Size size,VolumetricFogSettings fog) {
        return new RenderPipeline(size,new Scene(VolumetricMediumGlTest.camera(false,33f/17)),null,
                RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES).antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build())
                .volumetricFog(fog);
    }
    static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(33,17).visible(false).title("Volume generation transaction").build();
    }

    @Test void visualProfileVolumeCandidateRollsBackAndScalarUpdatePreservesHistoryOwner() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking=GlDebug.acquireResourceTracking()) {
                var pipeline=pipeline(new Size(),VolumetricFogSettings.disabled());
                pipeline.scene().addLight(SceneLight.directional(new Vector3f(0,-1,-1),new Vector3f(1),2));
                pipeline.build();
                try {
                    var original=pipeline.visualSettings(); var generation=pipeline.activeGenerationForTest();
                    long revision=pipeline.visualSettingsRevision();
                    var enabled=original.withVolumetricFog(fog(.1f));
                    System.setProperty("haikalat.test.failVolumeCompilation","2");
                    try { assertThrows(IllegalStateException.class,()->pipeline.applyVisualSettings(enabled)); }
                    finally { System.clearProperty("haikalat.test.failVolumeCompilation"); }
                    assertEquals(original,pipeline.visualSettings()); assertSame(generation,pipeline.activeGenerationForTest());
                    assertEquals(revision,pipeline.visualSettingsRevision());
                    pipeline.applyVisualSettings(enabled); pipeline.execute(new GlRenderDevice(),1f/60);
                    var owner=pipeline.activeGenerationForTest(); var resources=owner.volumetric.resources();
                    assertTrue(resources.historyValid());
                    var scalar=new VisualSettings(1.2f,enabled.iblIntensity(),enabled.iblRotationRadians(),
                            enabled.directionalLightDirection(),enabled.directionalLightColor(),enabled.directionalLightIntensity(),
                            enabled.antiAliasingMode(),enabled.bloom(),enabled.outdoor(),enabled.volumetricFog());
                    pipeline.applyVisualSettings(scalar);
                    assertSame(owner,pipeline.activeGenerationForTest()); assertSame(resources,owner.volumetric.resources());
                    assertTrue(resources.historyValid()); assertEquals(scalar,pipeline.visualSettings());
                    pipeline.execute(new GlRenderDevice(),1f/60);
                    VolumetricMediumGlTest.diagnosticsZero(owner.volumetric);
                } finally { pipeline.close(); }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("visual profile physical volume transaction");
        }
    }

    @Test void buildOwnsOnlyOneVolumeAndDisabledFogHasNoVolumePassOrStorage() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking=GlDebug.acquireResourceTracking()) {
                var size=new Size(); var pipeline=pipeline(size,VolumetricFogSettings.disabled());
                try {
                    pipeline.build(); var disabled=pipeline.activeGenerationForTest();
                    assertNull(disabled.volumetric);
                    assertFalse(disabled.graph.hasPass(VolumetricPassBuilder.MEDIUM_PASS));
                    pipeline.applyVolumetricFog(fog(.2f));
                    var generation=pipeline.activeGenerationForTest(); assertTrue(disabled.isClosed());
                    assertNotNull(generation.volumetric); assertFalse(pipeline.outdoorEnvironment().enabled());
                    assertTrue(generation.graph.description().passes().stream()
                            .filter(pass->pass.name().equals(VolumetricPassBuilder.MEDIUM_PASS))
                            .allMatch(pass->pass.borrowedExternalStorage().contains("volume.medium")));
                    pipeline.execute(new GlRenderDevice(),1f/60);
                    var output=generation.volumetric.currentOutput(); assertTrue(output.available());
                    assertEquals(generation.id,output.generationId()); assertEquals(3,output.grid().nx());
                    VolumetricMediumGlTest.diagnosticsZero(generation.volumetric);
                    pipeline.applyVolumetricFog(VolumetricFogSettings.disabled());
                    assertTrue(generation.isClosed()); assertTrue(output.prefix().isClosed());
                    assertNull(pipeline.activeGenerationForTest().volumetric);
                } finally { pipeline.close(); pipeline.close(); }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("formal volume owner");
        }
    }

    @Test void compilationAndResizeAllocationFailuresKeepAllActiveGenerationHandlesAndSettings() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking=GlDebug.acquireResourceTracking()) {
                var size=new Size(); var pipeline=pipeline(size,VolumetricFogSettings.disabled()); pipeline.build();
                try {
                    var previous=pipeline.activeGenerationForTest(); long bytes=GlDebug.resources().estimatedBytes();
                    long count=GlDebug.resources().liveResources().size();
                    for(int shader=0;shader<6;shader++) {
                        System.setProperty("haikalat.test.failVolumeCompilation",Integer.toString(shader));
                        try { assertThrows(IllegalStateException.class,()->pipeline.applyVolumetricFog(fog(.2f))); }
                        finally { System.clearProperty("haikalat.test.failVolumeCompilation"); }
                        assertSame(previous,pipeline.activeGenerationForTest()); assertFalse(pipeline.volumetricFog().enabled());
                        assertEquals(bytes,GlDebug.resources().estimatedBytes()); assertEquals(count,GlDebug.resources().liveResources().size());
                    }
                    pipeline.applyVolumetricFog(fog(.2f)); var generation=pipeline.activeGenerationForTest();
                    var observedColor=new java.util.concurrent.atomic.AtomicInteger();
                    generation.graph.addPass("VolumeResizeObserver").computeOnly().dependsOn(pipeline.finalPassName())
                            .execute((res,cmd)->observedColor.set(res.colorAttachment(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_COLOR)));
                    pipeline.execute(new GlRenderDevice());
                    int sceneColor=observedColor.get(); assertTrue(sceneColor>0);
                    int medium=generation.volumetric.resources().medium().id();
                    bytes=GlDebug.resources().estimatedBytes(); count=GlDebug.resources().liveResources().size();
                    for(int allocation=0;allocation<13;allocation++) {
                        System.setProperty("haikalat.test.failVolumeAllocation",Integer.toString(allocation));
                        try { assertThrows(IllegalStateException.class,()->pipeline.resize(65,33)); }
                        finally { System.clearProperty("haikalat.test.failVolumeAllocation"); }
                        assertEquals(33,generation.graph.width()); assertEquals(33,generation.topology.width());
                        assertEquals(medium,generation.volumetric.resources().medium().id());
                        assertEquals(bytes,GlDebug.resources().estimatedBytes()); assertEquals(count,GlDebug.resources().liveResources().size());
                        pipeline.execute(new GlRenderDevice()); assertEquals(sceneColor,observedColor.get());
                    }
                    pipeline.resize(65,33); size.width=65;size.height=33;
                    pipeline.execute(new GlRenderDevice()); assertSame(generation,pipeline.activeGenerationForTest());
                    assertEquals(5,generation.volumetric.currentOutput().grid().nx());
                    assertEquals(3,generation.volumetric.currentOutput().grid().ny());
                    assertTrue(generation.volumetric.currentOutput().available());
                } finally { pipeline.close(); }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test void failedRealGpuSubmissionDoesNotPublishFrameTimeOrCameraAndRecoveryIsIsolatedBetweenPipelines() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            var size=new Size(); var a=pipeline(size,fog(.2f)); var b=pipeline(size,fog(.6f));
            try {
                a.build(); b.build(); var device=new GlRenderDevice();
                a.execute(device,1f/60); b.execute(device,1f/60);
                var va=a.activeGenerationForTest().volumetric; var vb=b.activeGenerationForTest().volumetric;
                var previous=va.frameState().previous(); var other=vb.frameState().previous();
                var failing=new RenderDevice() {
                    public RenderBackendKind backendKind(){return device.backendKind();}
                    public ExecutionModel executionModel(){return device.executionModel();}
                    public CommandBuffer createCommandBuffer(){return device.createCommandBuffer();}
                    public void execute(CommandBuffer commands){device.execute(commands);throw new IllegalStateException("after real volume GPU execution");}
                    public void invalidateState(){device.invalidateState();}
                    public void transition(ResourceBarrier... barriers){device.transition(barriers);}
                };
                assertThrows(IllegalStateException.class,()->a.execute(failing,.5f));
                assertSame(previous,va.frameState().previous()); assertFalse(va.currentOutput().available());
                assertSame(other,vb.frameState().previous()); assertTrue(vb.currentOutput().available());
                assertNotEquals(va.resources().source().id(),vb.resources().source().id());
                a.execute(device,1f/60);
                assertEquals(previous.timeSeconds()+1f/60,va.frameState().previous().timeSeconds());
                assertTrue(va.currentOutput().available());
                VolumetricMediumGlTest.diagnosticsZero(va); VolumetricMediumGlTest.diagnosticsZero(vb);
                GlDebug.assertNoError("volume successful frame publication");
            } finally { a.close();b.close(); }
        }
    }
}
