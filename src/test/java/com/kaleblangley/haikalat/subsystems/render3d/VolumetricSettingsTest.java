package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VolumetricSettingsTest {
    @Test void coefficientsAreFiniteBoundedAndDoNotAcceptHdrColorsAsAlbedo() {
        assertThrows(IllegalArgumentException.class,()->FogMediumSettings.homogeneous(.1f,new Vector3f(1.1f),new Vector3f()));
        assertThrows(IllegalArgumentException.class,()->FogMediumSettings.homogeneous(65,new Vector3f(1),new Vector3f()));
        assertThrows(IllegalArgumentException.class,()->FogMediumSettings.homogeneous(.1f,new Vector3f(1),new Vector3f(17)));
        assertThrows(IllegalArgumentException.class,()->FogMediumSettings.homogeneous(Float.NaN,new Vector3f(1),new Vector3f()));
        assertThrows(IllegalArgumentException.class,()->new LocalFogVolume(LocalFogVolume.Shape.BOX,new Vector3f(),
                new Vector3f(1),.1f,new Vector3f(1),new Vector3f(),1.1f,0,0));
        assertThrows(IllegalArgumentException.class,()->LocalFogVolume.sphere(new Vector3f(),0,.1f,new Vector3f(1)));
    }

    @Test void snapshotsDefendTheirVectorsAndListsAndEnforceEightVolumeLimit() {
        var albedo=new Vector3f(.5f); var emission=new Vector3f(.1f);
        var medium=FogMediumSettings.homogeneous(.02f,albedo,emission); albedo.set(9); emission.set(9);
        medium.albedo().set(9); assertEquals(.5f,medium.albedo().x);
        var local=LocalFogVolume.sphere(new Vector3f(1,2,3),2,.03f,new Vector3f(.6f));
        local.center().set(0); local.albedo().set(2); assertEquals(1,local.center().x); assertEquals(.6f,local.albedo().x);
        var list=new ArrayList<>(List.of(local)); var wind=new Vector3f(.2f,0,0);
        var settings=new VolumetricFogSettings(true,80,VolumetricFogSettings.Quality.BALANCED,medium,list,0,true,.9f,1,wind);
        list.clear(); wind.set(5); settings.wind().set(8);
        assertEquals(1,settings.localVolumes().size()); assertEquals(.2f,settings.wind().x);
        assertThrows(UnsupportedOperationException.class,()->settings.localVolumes().clear());
        assertThrows(IllegalArgumentException.class,()->new VolumetricFogSettings(true,80,settings.quality(),medium,
                java.util.Collections.nCopies(9,local),0,true,.9f,1,new Vector3f()));
    }

    @Test void fogNeedsSharedDepthAndHdrWithoutAnySkyOrDirectionalLight() {
        Scene scene=new Scene(new Camera());
        var fog=new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,FogMediumSettings.vacuum(),List.of(),
                0,false,0,1,new Vector3f());
        for (var aa:AntiAliasingMode.values()) {
            var render=RenderSettings.builder().antiAliasingMode(aa).toneMappingMode(ToneMappingMode.ACES).build();
            var topology=PipelineTopology.capture(scene,render,PostProcessSettings.defaults(),33,17,false,false,
                    DirectionalCascadeSettings.disabled(),LocalShadowPipelineSettings.legacyDefaults(),fog);
            assertTrue(topology.volumetricFog()); assertTrue(topology.sceneBuffers().requires(SceneBufferChannel.DEPTH));
            assertFalse(topology.sceneBuffers().requires(SceneBufferChannel.NORMAL));
            assertDoesNotThrow(()->new PipelineFeaturePolicy(topology,false).validate());
        }
        var ldr=PipelineTopology.capture(scene,RenderSettings.builder().toneMappingMode(ToneMappingMode.NONE).build(),
                PostProcessSettings.defaults(),33,17,false,false,DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(),fog);
        assertThrows(IllegalStateException.class,()->new PipelineFeaturePolicy(ldr,false).validate());
    }
}
