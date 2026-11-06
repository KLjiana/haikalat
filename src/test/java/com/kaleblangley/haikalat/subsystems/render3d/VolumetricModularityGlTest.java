package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.device.RenderBackendKind;
import com.kaleblangley.haikalat.core.device.ExecutionModel;
import com.kaleblangley.haikalat.core.device.ResourceBarrier;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

/** Production entry points, real pixels and native ownership, including initialized failure paths. */
@EnabledIfSystemProperty(named="haikalat.glSmoke", matches="true")
class VolumetricModularityGlTest {
    @Test void failureBeforeFirstCommandSubmissionLeavesHistoryPixelsAndCommittedFrameUntouched() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var tracking=GlDebug.acquireResourceTracking()) {
                var p=pipeline(new VolumetricGenerationGlTest.Size(),AntiAliasingMode.TAA);
                try {
                    p.volumetricFog(new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                            FogMediumSettings.homogeneous(.04f,new Vector3f(.7f),new Vector3f(.2f)),
                            List.of(),0,true,.9f,242,new Vector3f()));
                    p.build();var device=new GlRenderDevice();p.execute(device);p.execute(device);
                    var volume=p.activeGenerationForTest().volumetric;
                    var taa=p.activeGenerationForTest().postProcess.taaHistoryForTest();
                    var taaRead=taa.readFramebuffer();var read=volume.resources().historyRead();
                    var write=volume.resources().historyWrite();var previous=volume.frameState().previous();
                    var readPixels=VolumetricMediumGlTest.texture(read);var writePixels=VolumetricMediumGlTest.texture(write);
                    var rejecting=new RenderDevice() {
                        public RenderBackendKind backendKind(){return device.backendKind();}
                        public ExecutionModel executionModel(){return device.executionModel();}
                        public CommandBuffer createCommandBuffer(){return device.createCommandBuffer();}
                        public void execute(CommandBuffer commands){throw new IllegalStateException("before first GPU command submission");}
                        public void invalidateState(){device.invalidateState();}
                        public void transition(ResourceBarrier... barriers){device.transition(barriers);}
                    };
                    var error=assertThrows(IllegalStateException.class,()->p.execute(rejecting,.5f));
                    assertEquals("before first GPU command submission",error.getMessage());
                    assertSame(previous,volume.frameState().previous());assertSame(taaRead,taa.readFramebuffer());
                    assertArrayEquals(readPixels,VolumetricMediumGlTest.texture(read),0f);
                    assertArrayEquals(writePixels,VolumetricMediumGlTest.texture(write),0f);
                    assertFalse(volume.currentOutput().available());
                    p.execute(device,1f/60);assertTrue(volume.currentOutput().available());
                    assertEquals(VolumetricHistoryPlan.Reason.FRAME_FAILURE,volume.historyPlan().reason());
                    assertEquals(previous.successfulIndex()+1,volume.frameState().previous().successfulIndex());
                    assertEquals(previous.timeSeconds()+1f/60,volume.frameState().previous().timeSeconds());
                }finally{p.close();}
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test void backToBackEnableDisableBeforeDrawingDiscardsTheUnusedCandidateAndDrawsTheOriginalNoFogPixels() {
        try (var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try (var tracking=GlDebug.acquireResourceTracking()) {
                var p=pipeline(new VolumetricGenerationGlTest.Size(),AntiAliasingMode.NONE);
                try {
                    p.build();var device=new GlRenderDevice();p.execute(device);
                    var expected=p.captureLinearHdrRgbaFloat();
                    p.applyVolumetricFog(fog(.04f,VolumetricFogSettings.Quality.BALANCED));
                    var unused=p.activeGenerationForTest().volumetric.resources().prefix();
                    p.applyVolumetricFog(VolumetricFogSettings.disabled());
                    assertTrue(unused.isClosed(),"an unsubmitted candidate needs no GPU retirement delay");
                    assertNull(p.activeGenerationForTest().volumetric);
                    p.execute(device);assertArrayEquals(expected,p.captureLinearHdrRgbaFloat(),0f);
                    assertFalse(p.graph().hasPass(VolumetricPassBuilder.MEDIUM_PASS));
                } finally {p.close();}
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test void rebuildingFromResourcesKeepsTheOldImageOnShaderReloadFailureAndRecoversOnTheNextBuild() {
        try (var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try (var tracking=GlDebug.acquireResourceTracking()) {
                var p=pipeline(new VolumetricGenerationGlTest.Size(),AntiAliasingMode.NONE);
                try {
                    p.volumetricFog(fog(.04f,VolumetricFogSettings.Quality.BALANCED));p.build();
                    var device=new GlRenderDevice();p.execute(device);
                    var old=p.activeGenerationForTest();var expected=p.captureLinearHdrRgbaFloat();
                    long bytes=GlDebug.resources().estimatedBytes();
                    System.setProperty("haikalat.test.failVolumeCompilation","0");
                    try {assertThrows(IllegalStateException.class,p::build);}
                    finally {System.clearProperty("haikalat.test.failVolumeCompilation");}
                    assertSame(old,p.activeGenerationForTest());assertEquals(bytes,GlDebug.resources().estimatedBytes());
                    p.execute(device);assertArrayEquals(expected,p.captureLinearHdrRgbaFloat(),0f);
                    p.build();assertTrue(old.isClosed());p.execute(device);
                    assertArrayEquals(expected,p.captureLinearHdrRgbaFloat(),0f);
                } finally {p.close();}
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }
    static VolumetricFogSettings fog(float density, VolumetricFogSettings.Quality quality) {
        return new VolumetricFogSettings(true, 32, quality,
                FogMediumSettings.homogeneous(density, new Vector3f(.7f), new Vector3f(.2f)),
                List.of(), 0, false, .9f, 242, new Vector3f());
    }

    static RenderPipeline pipeline(VolumetricGenerationGlTest.Size size, AntiAliasingMode aa) {
        var scene = new Scene(VolumetricMediumGlTest.camera(false, 33f/17));
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(0,-1,-1), new Vector3f(1), 2));
        return new RenderPipeline(size, scene, null, RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                .antiAliasingMode(aa).vsync(false).build());
    }

    static Framebuffer shadow(RenderPipeline pipeline, GlRenderDevice device) {
        AtomicReference<Framebuffer> found = new AtomicReference<>();
        pipeline.graph().addPass("ObserveShadow").computeOnly().dependsOn(pipeline.finalPassName())
                .execute((res, cmd) -> found.set(res.framebufferOfPass(DirectionalShadowMap.PASS_NAME)));
        pipeline.execute(device);
        return found.get();
    }

    @Test void fogSwitchAndQualityReplaceOnlyAffectedOwnersAndInvalidatePixelsWithoutReplacingTaaStorage() {
        try (var window = VolumetricGenerationGlTest.window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                var size = new VolumetricGenerationGlTest.Size();
                var p = pipeline(size, AntiAliasingMode.TAA);
                try {
                    p.build(); var device = new GlRenderDevice();
                    var old = p.activeGenerationForTest(); var light = old.clusteredResources;
                    var binder = old.clusteredLightingBinder; var shader = old.shadowShader;
                    var camera = old.cameraUniforms; var taa = old.postProcess.taaHistoryForTest();
                    var atlas = shadow(p, device); assertNotNull(atlas); assertTrue(taa.valid());
                    assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("TAA_FIRST_FRAME"));
                    var historySlots = java.util.Set.of(taa.readFramebuffer(), taa.writeFramebuffer());
                    p.applyVolumetricFog(fog(.04f, VolumetricFogSettings.Quality.BALANCED));
                    var on = p.activeGenerationForTest(); assertTrue(old.isClosed());
                    assertSame(light, on.clusteredResources); assertSame(binder, on.clusteredLightingBinder);
                    assertSame(shader, on.shadowShader); assertSame(camera, on.cameraUniforms);
                    assertSame(taa, on.postProcess.taaHistoryForTest()); assertFalse(taa.valid());
                    assertEquals(historySlots, java.util.Set.of(taa.readFramebuffer(), taa.writeFramebuffer()));
                    assertSame(atlas, shadow(p, device)); assertTrue(taa.valid());
                    assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("TAA_VOLUMETRIC_TOPOLOGY"));
                    long oldFogBytes = p.volumetricAllocationDiagnostics().activePrivateBytes()
                            + p.volumetricAllocationDiagnostics().activeColorUpperBoundBytes();
                    var prefix = on.volumetric.resources().prefix();
                    p.applyVolumetricFog(fog(.04f, VolumetricFogSettings.Quality.HIGH));
                    var allocation = p.volumetricAllocationDiagnostics();
                    assertTrue(allocation.lastCandidatePeakBytes() >= oldFogBytes + allocation.activePrivateBytes()
                            + allocation.activeColorUpperBoundBytes());
                    assertTrue(allocation.retiringGenerations() <= 4);
                    System.out.println("V0270_ALLOCATION " + allocation);
                    p.drainRetirementsForTest();
                    assertTrue(prefix.isClosed()); assertSame(light, p.activeGenerationForTest().clusteredResources);
                    assertSame(atlas, shadow(p, device));
                    p.applyVolumetricFog(fog(.08f, VolumetricFogSettings.Quality.HIGH));
                    p.execute(device);
                    assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("TAA_VOLUMETRIC_SETTINGS"));
                    p.execute(device);
                    assertFalse(p.lastRender3dDiagnostics().invalidationReasons().stream().anyMatch(s -> s.startsWith("TAA_")));
                    size.width=65;size.height=33;p.resize(size.width,size.height);p.execute(device);
                    assertTrue(p.lastRender3dDiagnostics().invalidationReasons().contains("TAA_RESIZE"));
                    p.applyVolumetricFog(VolumetricFogSettings.disabled());
                    var off = p.activeGenerationForTest(); assertSame(taa, off.postProcess.taaHistoryForTest());
                    assertSame(atlas, shadow(p, device)); assertNull(off.volumetric);
                    assertSame(light, off.clusteredResources); assertSame(shader, off.shadowShader);
                    p.drainRetirementsForTest();
                    allocation=p.volumetricAllocationDiagnostics();
                    assertEquals(0,allocation.activePrivateBytes());assertEquals(0,allocation.activeColorUpperBoundBytes());
                    assertEquals(0,allocation.retiringUpperBoundBytes());
                } finally { p.close(); }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("local volume owner replacement");
        }
    }

    @Test void cpuAllocationShaderAndGraphFailuresLeaveOldRealPixelsAndAllOwnersUsable() {
        try (var window = VolumetricGenerationGlTest.window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                var p = pipeline(new VolumetricGenerationGlTest.Size(), AntiAliasingMode.NONE);
                try {
                    var f = fog(.04f, VolumetricFogSettings.Quality.BALANCED);
                    p.volumetricFog(f); p.build(); var device = new GlRenderDevice(); p.execute(device);
                    var owner = p.activeGenerationForTest(); var expected = p.captureLinearHdrRgbaFloat();
                    long bytes = GlDebug.resources().estimatedBytes();
                    for (String fault : List.of("haikalat.test.failVolumePreparation", "haikalat.test.failVolumeAllocation",
                            "haikalat.test.failVolumeCompilation", "haikalat.test.failVolumeGraph")) {
                        System.setProperty(fault, fault.endsWith("Allocation") || fault.endsWith("Compilation") ? "0" : "true");
                        try { assertThrows(RuntimeException.class, () -> p.applyVolumetricFog(fog(.08f, VolumetricFogSettings.Quality.HIGH))); }
                        finally { System.clearProperty(fault); }
                        assertSame(owner, p.activeGenerationForTest()); assertEquals(f, p.volumetricFog());
                        assertEquals(bytes, GlDebug.resources().estimatedBytes());
                        p.execute(device); assertArrayEquals(expected, p.captureLinearHdrRgbaFloat(), 0f);
                    }
                } finally { p.close(); }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test void scalarChangesAffectPixelsWithoutTopologyRevisionOrAnyNativeAllocation() {
        try (var window = VolumetricGenerationGlTest.window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                var p = pipeline(new VolumetricGenerationGlTest.Size(), AntiAliasingMode.NONE);
                try {
                    p.volumetricFog(fog(.01f, VolumetricFogSettings.Quality.BALANCED)); p.build();
                    var device = new GlRenderDevice(); for (int i=0;i<12;i++) p.execute(device);
                    var owner = p.activeGenerationForTest(); var volume = owner.volumetric;
                    long revision = p.graph().description().topologyRevision();
                    long created = GlDebug.resources().createdCount();
                    var before = p.captureLinearHdrRgbaFloat();
                    for (int i=0;i<16;i++) { p.applyVolumetricFog(fog(.02f+i*.01f, VolumetricFogSettings.Quality.BALANCED)); p.execute(device); }
                    assertSame(owner, p.activeGenerationForTest()); assertSame(volume, owner.volumetric);
                    assertEquals(revision, p.graph().description().topologyRevision());
                    assertEquals(created, GlDebug.resources().createdCount());
                    assertFalse(java.util.Arrays.equals(before, p.captureLinearHdrRgbaFloat()));
                } finally { p.close(); }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test void retirementFailureKeepsPublishedSettingsAndNewPixels() {
        try (var window = VolumetricGenerationGlTest.window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                var p = pipeline(new VolumetricGenerationGlTest.Size(), AntiAliasingMode.NONE);
                try {
                    p.build(); var device = new GlRenderDevice(); p.execute(device);
                    var old = p.activeGenerationForTest(); var f = fog(.04f, VolumetricFogSettings.Quality.BALANCED);
                    System.setProperty("haikalat.test.failVolumeRetirement", "true");
                    try { assertThrows(IllegalStateException.class, () -> p.applyVolumetricFog(f)); }
                    finally { System.clearProperty("haikalat.test.failVolumeRetirement"); }
                    assertTrue(old.isClosed()); assertNotSame(old, p.activeGenerationForTest());
                    assertEquals(f, p.volumetricFog()); p.execute(device); assertTrue(p.volumetricFogDiagnostics().available());
                    p.applyVolumetricFog(VolumetricFogSettings.disabled()); p.execute(device);
                } finally { p.close(); }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test void initializedPipelineRejectsSecondThreadDetachedAndDifferentContextsBeforeMutation() throws Exception {
        try (var window = VolumetricGenerationGlTest.window()) {
            window.bindContext(); var capabilities = GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                var p = pipeline(new VolumetricGenerationGlTest.Size(), AntiAliasingMode.NONE);
                try {
                    p.volumetricFog(fog(.04f, VolumetricFogSettings.Quality.BALANCED)); p.build();
                    var device = new GlRenderDevice(); p.execute(device);
                    long count = GlDebug.resources().createdCount(); var generation = p.activeGenerationForTest();
                    CompletableFuture.runAsync(() -> {
                        assertThrows(IllegalStateException.class, p::close);
                        assertThrows(IllegalStateException.class, () -> p.execute(device));
                        assertThrows(IllegalStateException.class, () -> p.applyVolumetricFog(VolumetricFogSettings.disabled()));
                    }).get(10, TimeUnit.SECONDS);
                    glfwMakeContextCurrent(0); // Keep capabilities deliberately: native Context is authoritative too.
                    try { assertThrows(IllegalStateException.class, p::close); }
                    finally { window.bindContext(); GL.setCapabilities(capabilities); }
                    long other = glfwCreateWindow(8,8,"other-context",0,0); assertNotEquals(0, other);
                    try {
                        glfwMakeContextCurrent(other); GL.createCapabilities();
                        assertThrows(IllegalStateException.class, () -> p.applyVolumetricFog(VolumetricFogSettings.disabled()));
                    } finally { window.bindContext(); GL.setCapabilities(capabilities); glfwDestroyWindow(other); }
                    assertSame(generation, p.activeGenerationForTest()); assertEquals(count, GlDebug.resources().createdCount());
                    p.execute(device);
                } finally { p.close(); }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
                p.close(); assertThrows(IllegalStateException.class, () -> p.applyVolumetricFog(VolumetricFogSettings.disabled()));
            }
        }
    }
}
