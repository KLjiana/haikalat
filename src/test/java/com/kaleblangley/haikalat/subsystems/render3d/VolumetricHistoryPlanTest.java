package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VolumetricHistoryPlanTest {
    private static VolumetricFogSettings fog() {
        return new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.homogeneous(.05f,new Vector3f(.6f),new Vector3f()),List.of(),
                0,true,.9f,1337,new Vector3f());
    }
    private static ExternalCamera camera(float x,boolean ortho) {
        Matrix4f projection=ortho?new Matrix4f().ortho(-8,8,-4,4,.125f,64):new Matrix4f().perspective(1.2f,2,.125f,64);
        Matrix4f view=new Matrix4f().translation(-x,0,0);
        return new ExternalCamera(view,projection,new Matrix4f(projection).mul(view),new Vector3f(x,0,0),0,.125f,64,(long)(100+x*10));
    }
    private static RenderFrameContext context(Scene scene,ExternalCamera camera,int frame,RenderFrameContext old) {
        return RenderFrameContext.capture(scene,camera,129,65,1f/60,frame,frame,0,old);
    }
    private static void prepare(VolumetricHistoryPlan plan,VolumetricFrameState state,RenderFrameContext context,VolumetricFogSettings settings) {
        var current=state.prepare(context,settings);
        plan.prepare(context,current,state.previous(),state.valid(),
                FrameLightTable.build(context.lightEntries(),context.camera().view(),ClusteredLightingSettings.defaults()),ShadowFramePlan.EMPTY);
    }

    @Test void nativeMediumBoundsSurviveCoarseOverflowAndFailedAttempts() {
        var camera=camera(0,true);var scene=new Scene(camera);
        for(int i=0;i<33;i++)scene.addLight(SceneLight.point(new Vector3f(i*.2f,0,-8),new Vector3f(1),1,2));
        var initial=LocalFogVolume.box(new Vector3f(1,0,-8),new Vector3f(.2f,.3f,.4f),.2f,new Vector3f(.5f));
        var moved=LocalFogVolume.box(new Vector3f(-1,0,-8),initial.extent(),.2f,new Vector3f(.5f));
        var settings=new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.vacuum(),List.of(initial),0,true,.9f,1337,new Vector3f());
        var next=new VolumetricFogSettings(true,32,settings.quality(),settings.globalMedium(),List.of(moved),0,true,.9f,1337,new Vector3f());
        var state=new VolumetricFrameState();var plan=new VolumetricHistoryPlan();
        var first=context(scene,camera,0,null);prepare(plan,state,first,settings);plan.commit();state.commit();
        for(int i=0;i<33;i++)scene.setLight(i,SceneLight.point(new Vector3f(i*.2f,0,-8),new Vector3f(.5f),2,2));
        prepare(plan,state,context(scene,camera,1,first),next);
        assertEquals(VolumetricHistoryPlan.Reason.DOMAIN_OVERFLOW,plan.reason());assertEquals(0,plan.count());
        assertEquals(2,plan.nativeMediumCount());assertEquals(64,plan.nativeMediumDomains().remaining());
        assertEquals(1,plan.nativeMediumDomains().getFloat(0));assertEquals(-1,plan.nativeMediumDomains().getFloat(32));
        assertEquals(.2f,plan.nativeMediumDomains().getFloat(16),"native extents do not inherit coarse interpolation padding");
        plan.discard();state.discard();
        var retry=context(scene,camera,2,first);prepare(plan,state,retry,next);
        assertEquals(2,plan.nativeMediumCount());assertTrue(plan.nativeReactive());
        plan.commit();state.commit();
        prepare(plan,state,context(scene,camera,3,retry),next);
        assertEquals(0,plan.nativeMediumCount());assertEquals(0,plan.nativeMediumDomains().remaining());assertFalse(plan.nativeReactive());
    }

    @Test void moreThanSixtyFourDirtyDomainsRejectsWholeVolumeAndTheNextStableFrameRecovers() {
        var scene=new Scene(camera(0,true));
        for(int i=0;i<33;i++)scene.addLight(SceneLight.point(new Vector3f(i*.2f,0,-8),new Vector3f(1),1,2));
        var state=new VolumetricFrameState();var plan=new VolumetricHistoryPlan();var settings=fog();
        var first=context(scene,camera(0,true),0,null);prepare(plan,state,first,settings);plan.commit();state.commit();
        for(int i=0;i<33;i++)scene.setLight(i,SceneLight.point(new Vector3f(i*.2f,0,-8),new Vector3f(.5f),2,2));
        var second=context(scene,camera(0,true),1,first);prepare(plan,state,second,settings);
        assertEquals(VolumetricHistoryPlan.Reason.DOMAIN_OVERFLOW,plan.reason());assertEquals(0,plan.count());
        assertEquals(0,plan.domains().remaining());assertTrue(plan.nativeLightFull());plan.commit();state.commit();
        prepare(plan,state,context(scene,camera(0,true),2,second),settings);
        assertEquals(VolumetricHistoryPlan.Reason.NONE,plan.reason());assertEquals(0,plan.count());
        assertFalse(plan.nativeReactive());assertFalse(plan.nativeLightFull());
    }

    @Test void viewSpaceLightChangesAreNotContentChangesAndRemovedFastLightRetainsItsOldFootprint() {
        var scene=new Scene(camera(0,true));scene.addLight(SceneLight.point(new Vector3f(1,0,-8),new Vector3f(1),1,2));
        var state=new VolumetricFrameState();var plan=new VolumetricHistoryPlan();var settings=fog();
        var first=context(scene,camera(0,true),0,null);prepare(plan,state,first,settings);plan.commit();state.commit();
        var second=context(scene,camera(.04f,true),1,first);prepare(plan,state,second,settings);
        assertEquals(0,plan.count());assertEquals(VolumetricHistoryPlan.Reason.NONE,plan.reason());plan.commit();state.commit();
        scene.setLightVolumeHints(0,new LightVolumeHints(1,false,false));
        var third=context(scene,camera(.08f,true),2,second);prepare(plan,state,third,settings);
        assertTrue(plan.count()>0);assertTrue(plan.domains().getFloat(12)>2,"domain includes the froxel interpolation support");
        assertEquals(2,plan.domains().getFloat(16),"native visibility keeps the actual radius without coarse padding");
        assertEquals(2,plan.domains().getFloat(28),"fast native light tag remains distinct from the light-off tag");
        assertTrue(plan.nativeReactive());assertFalse(plan.nativeLightFull());plan.commit();state.commit();
        scene.removeLight(0);var fourth=context(scene,camera(.08f,true),3,third);prepare(plan,state,fourth,settings);
        assertEquals(1,plan.count());assertEquals(1,plan.domains().getFloat(0));
        assertEquals(1,plan.domains().getFloat(28));plan.commit();state.commit();
        prepare(plan,state,context(scene,camera(.08f,true),4,fourth),settings);assertEquals(0,plan.count());
        assertEquals(0,plan.shaderReason());
    }

    @Test void directionalLightOffAndOverflowKeepTheirEventFlagUntilSuccessfulCommit() {
        var camera=camera(0,true);var scene=new Scene(camera);var settings=fog();
        scene.addLight(SceneLight.directional(new Vector3f(0,-1,0),new Vector3f(1),1));
        var state=new VolumetricFrameState();var plan=new VolumetricHistoryPlan();
        var first=context(scene,camera,0,null);prepare(plan,state,first,settings);plan.commit();state.commit();
        scene.setLightVolumeHints(0,new LightVolumeHints(0,false,true));
        prepare(plan,state,context(scene,camera,1,first),settings);
        assertEquals(VolumetricHistoryPlan.Reason.DIRECTIONAL_LIGHT,plan.reason());
        assertNotEquals(0,plan.shaderReason()&VolumetricHistoryPlan.LIGHT_OFF_FLAG);
        plan.discard();state.discard();
        var retry=context(scene,camera,2,first);prepare(plan,state,retry,settings);
        assertNotEquals(0,plan.shaderReason()&VolumetricHistoryPlan.LIGHT_OFF_FLAG);
        plan.commit();state.commit();
        prepare(plan,state,context(scene,camera,3,retry),settings);
        assertEquals(0,plan.shaderReason());plan.commit();state.commit();

        for(int i=0;i<65;i++)scene.addLight(SceneLight.point(new Vector3f(i,0,-8),new Vector3f(1),1,2));
        var lit=context(scene,camera,4,retry);prepare(plan,state,lit,settings);plan.commit();state.commit();
        for(int i=1;i<=65;i++)scene.setLightVolumeHints(i,new LightVolumeHints(0,false,true));
        prepare(plan,state,context(scene,camera,5,lit),settings);
        assertEquals(VolumetricHistoryPlan.Reason.DOMAIN_OVERFLOW,plan.reason());assertEquals(0,plan.count());
        assertNotEquals(0,plan.shaderReason()&VolumetricHistoryPlan.LIGHT_OFF_FLAG);
    }

    @Test void failedAttemptDoesNotConsumeTimeOrJitterAndProjectionCutAndSceneChangesHaveExplicitReasons() {
        var camera=camera(0,true);var scene=new Scene(camera);var settings=fog();
        var state=new VolumetricFrameState();var plan=new VolumetricHistoryPlan();
        var first=context(scene,camera,0,null);prepare(plan,state,first,settings);plan.commit();state.commit();
        var failed=context(scene,camera,1,first);prepare(plan,state,failed,settings);
        float phase=state.current().samplePhase(),time=state.current().timeSeconds();
        plan.discard();state.discard();plan.invalidate(VolumetricHistoryPlan.Reason.FRAME_FAILURE);
        prepare(plan,state,context(scene,camera,2,first),settings);
        assertEquals(time,state.current().timeSeconds());assertEquals(phase,state.current().samplePhase());
        assertEquals(VolumetricHistoryPlan.Reason.FRAME_FAILURE,plan.reason());plan.commit();state.commit();
        var changed=context(scene,camera(0,false),3,first);prepare(plan,state,changed,settings);
        assertEquals(VolumetricHistoryPlan.Reason.GRID,plan.reason());plan.commit();state.commit();
        var cut=context(scene,camera(20,false),4,changed);prepare(plan,state,cut,settings);
        assertEquals(VolumetricHistoryPlan.Reason.CAMERA_CUT,plan.reason());plan.commit();state.commit();
        var replacement=new Scene(camera(20,false));prepare(plan,state,context(replacement,camera(20,false),5,cut),settings);
        assertEquals(VolumetricHistoryPlan.Reason.SCENE,plan.reason());
    }

    @Test void shiftedLightIndicesAndFailedChangesStillCompareWithTheCommittedStableIdentities() {
        var camera=camera(0,true);var scene=new Scene(camera);var settings=fog();
        for(float x:new float[]{1,10,20}) scene.addLight(SceneLight.point(new Vector3f(x,0,-8),new Vector3f(1),1,2));
        var state=new VolumetricFrameState();var plan=new VolumetricHistoryPlan();
        var first=context(scene,camera,0,null);prepare(plan,state,first,settings);plan.commit();state.commit();
        scene.removeLight(0);
        var removed=context(scene,camera,1,first);prepare(plan,state,removed,settings);
        assertEquals(VolumetricHistoryPlan.Reason.NONE,plan.reason());
        assertEquals(1,plan.count(),"only the removed light dirties history; surviving indices have shifted");
        assertEquals(1,plan.domains().getFloat(0));plan.commit();state.commit();

        scene.setLight(0,SceneLight.point(new Vector3f(77,0,-8),new Vector3f(1),1,2));
        prepare(plan,state,context(scene,camera,2,removed),settings);
        assertEquals(2,plan.count());assertEquals(10,plan.domains().getFloat(0));assertEquals(77,plan.domains().getFloat(32));
        plan.discard();state.discard();
        scene.setLight(0,SceneLight.point(new Vector3f(10,0,-8),new Vector3f(1),1,2));
        var restored=context(scene,camera,3,removed);prepare(plan,state,restored,settings);
        assertEquals(0,plan.count(),"failed light values must not replace the committed comparison table");
        plan.commit();state.commit();

        // A new directional light sorts before the surviving local light despite its newer stable id.
        scene.removeLight(1);
        scene.addLight(SceneLight.directional(new Vector3f(0,-1,0),new Vector3f(1),1));
        prepare(plan,state,context(scene,camera,4,restored),settings);
        assertEquals(VolumetricHistoryPlan.Reason.DIRECTIONAL_LIGHT,plan.reason());
        assertEquals(0,plan.count());
    }
}
