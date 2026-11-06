package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46.*;
import static org.lwjgl.opengl.GL11.glFinish;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ClusterDiagnosticResizeAuditGlTest {
    @Test void gpuResetClearsPoisonAndNeverAccumulatesAcrossCounterSlotsOrFailedFrames() {
        try(var window=new GlfwWindow.Builder().dimensions(64,64).visible(false).title("Cluster GPU counter reset").build()) {
            window.bindContext();GL.createCapabilities();
            var camera=VolumetricMediumGlTest.camera(true,1);var scene=new Scene(camera);
            scene.addLight(SceneLight.point(new Vector3f(0,0,-8),new Vector3f(1),1,32));
            scene.addLight(SceneLight.point(new Vector3f(0,0,-8),new Vector3f(1),1,32));
            var settings=ClusteredLightingSettings.builder().inlineIndicesPerCluster(1).build();
            var grid=ClusterGrid.create(camera,64,64,settings,0);
            try(var resources=new ClusteredLightingResources(64,64,settings);
                var binder=new ClusteredLightingBinder(resources,settings)) {
                var device=new GlRenderDevice();
                int[] expected=null;
                for(int frame=0;frame<8;frame++) {
                    // Force fresh light-table input: cached static frames intentionally
                    // retain their counters instead of resetting diagnostic storage.
                    if((frame&1)==1)scene.addLight(SceneLight.directional(new Vector3f(0,-1,0),new Vector3f(1),1));
                    else if(frame>0)scene.removeLight(2);
                    var table=FrameLightTable.build(scene.lightEntries(),camera.getViewMatrix(),settings);
                    binder.prepare(grid,table,ShadowFramePlan.EMPTY,frame);
                    for(var buffer:resources.storage().clusterCounters) {
                        var poison=org.lwjgl.BufferUtils.createByteBuffer(16);poison.asIntBuffer().put(new int[]{1000,2000,3000,4000});
                        buffer.update(0,poison);
                    }
                    var cmd=device.createCommandBuffer();binder.recordUpload(cmd);binder.recordBounds(cmd);binder.recordAssign(cmd);binder.recordStats(cmd);
                    device.execute(cmd);
                    // Test-only wait confirms GPU reset/reduction and mapped visibility.
                    glFinish();
                    if(frame==3) {
                        binder.frameFailed();assertNull(binder.tryCounterSnapshot(),"a failed frame cannot authorize either counter slot");
                        continue;
                    }
                    binder.frameSucceeded();var snapshot=binder.tryCounterSnapshot();assertNotNull(snapshot);
                    assertEquals(frame,snapshot.frameSequence());
                    int[] actual={snapshot.overflowClusters(),snapshot.maxInlineCount(),snapshot.droppedIndices()};
                    if(expected==null) {expected=actual;assertTrue(expected[0]>0&&expected[1]==2&&expected[2]>0);}
                    else assertArrayEquals(expected,actual,"GPU reset must discard poison and previous-frame totals");
                }
            }
        }
    }

    @Test void identicalLightingReusesGpuWorkButChangedInputsFailureAndResizeRefreshTheRealCounters() {
        try(var window=new GlfwWindow.Builder().dimensions(64,64).visible(false).title("Cluster static input reuse").build()) {
            window.bindContext();GL.createCapabilities();
            var camera=VolumetricMediumGlTest.camera(true,1);var scene=new Scene(camera);
            var near=SceneLight.point(new Vector3f(0,0,-8),new Vector3f(1),1,32);
            scene.addLight(near);scene.addLight(near);
            var settings=ClusteredLightingSettings.builder().inlineIndicesPerCluster(1).build();
            var grid=ClusterGrid.create(camera,64,64,settings,0);
            try(var resources=new ClusteredLightingResources(64,64,settings);var binder=new ClusteredLightingBinder(resources,settings)) {
                var device=new GlRenderDevice();
                for(int frame=0;frame<7;frame++) {
                    if(frame==2)for(int i=0;i<2;i++)scene.setLight(i,SceneLight.point(new Vector3f(1000,0,-8),new Vector3f(1),1,1));
                    if(frame==3)for(int i=0;i<2;i++)scene.setLight(i,near);
                    if(frame==5) {
                        resources.resize(128,64);grid=ClusterGrid.create(camera,128,64,settings,0);
                        assertNull(binder.tryCounterSnapshot(),"old cached counters must not validate resized storage");
                    }
                    if(frame==6) {
                        camera=ExternalCamera.of(new org.joml.Matrix4f().rotationY(.1f),camera.projection(),new Vector3f(),1);
                        grid=ClusterGrid.create(camera,128,64,settings,0);
                    }
                    var table=FrameLightTable.build(scene.lightEntries(),camera.getViewMatrix(),settings);
                    binder.prepare(grid,table,ShadowFramePlan.EMPTY,frame);
                    var cmd=device.createCommandBuffer();binder.recordUpload(cmd);binder.recordBounds(cmd);binder.recordAssign(cmd);binder.recordStats(cmd);
                    if(frame==1)assertEquals(0,cmd.commandCount(),"static reuse must avoid uploads, dispatches and a new fence");
                    else assertTrue(cmd.commandCount()>0,"changed/failed/new storage input must execute");
                    device.execute(cmd);glFinish();
                    if(frame==3) {
                        binder.frameFailed();assertNull(binder.tryCounterSnapshot());continue;
                    }
                    binder.frameSucceeded();var snapshot=binder.tryCounterSnapshot();assertNotNull(snapshot);
                    assertEquals(frame==1?0:frame,snapshot.frameSequence(),"cached diagnostics retain their actual GPU source frame");
                    assertEquals(frame==2?0:2,snapshot.maxInlineCount());
                    if(frame==2)assertEquals(0,snapshot.overflowClusters());
                }
            }
            com.kaleblangley.haikalat.backend.GlDebug.assertNoError("static cluster reuse and invalidation");
        }
    }
    @Test
    void oldStorageFenceCannotValidateNewStorageCounters() {
        try (var window = new GlfwWindow.Builder().dimensions(64, 64)
                .title("Cluster diagnostic resize audit").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            var scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.addLight(SceneLight.point(new Vector3f(), new Vector3f(1), 1, 4));
            var pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false).build());
            try {
                pipeline.build();
                pipeline.execute(new GlRenderDevice());
                // Test-only wait establishes a completed OLD allocation's fence.
                glFinish();
                pipeline.resize(128, 64);
                assertNull(pipeline.activeGenerationForTest().clusteredLightingBinder.tryCounterSnapshot(),
                        "resize must invalidate fences before reading replacement counter storage");
            } finally {
                pipeline.close();
            }
        }
    }
}
