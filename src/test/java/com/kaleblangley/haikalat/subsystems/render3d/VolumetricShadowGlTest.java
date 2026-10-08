package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class VolumetricShadowGlTest {
    private static final String VERTEX="""
            #version 460 core
            layout(location=0) in vec3 aPos;
            layout(std140) uniform CameraBlock { mat4 uProjection; mat4 uView; };
            uniform mat4 uModel;
            void main(){gl_Position=uProjection*uView*uModel*vec4(aPos,1.0);}
            """;
    private static final String FRAGMENT="#version 460 core\nout vec4 color;void main(){color=vec4(.2,.2,.2,1.0);}";
    static VolumetricFogSettings fog() {
        return VolumetricMediumGlTest.settings(FogMediumSettings.homogeneous(.04f,new Vector3f(1),new Vector3f()),List.of());
    }

    @Test void allocatedDirectionalPointAndSpotShadowsOccludeAirBehindAWallAndHintsOnlyDisableOcclusion() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var mesh=Mesh.from(BuiltinMeshData.coloredCube("volume-wall"));
                var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var material=Material.builder(shader).build()) {
                for(LightType type:LightType.values()) {
                    var size=new VolumetricGenerationGlTest.Size();size.width=1;size.height=1;
                    var scene=new Scene(VolumetricMediumGlTest.camera(false,1));
                    scene.add(MeshRenderer.of(mesh,material,Transform.at(0,0,-5).scale(8,8,.25f)));
                    var light=switch(type) {
                        case DIRECTIONAL->SceneLight.shadowedDirectional(new Vector3f(0,0,-1),new Vector3f(1),5);
                        case POINT->SceneLight.shadowedPoint(new Vector3f(0,0,-2),new Vector3f(1),10,30);
                        case SPOT->SceneLight.shadowedSpot(new Vector3f(0,0,-2),new Vector3f(0,0,-1),new Vector3f(1),10,30,.2f,.8f);
                    };
                    scene.addLight(light);
                    var pipeline=new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                            .antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build()).volumetricFog(fog());
                    try {
                        pipeline.build();var device=new GlRenderDevice();pipeline.execute(device);
                        var builder=pipeline.activeGenerationForTest().volumetric;var grid=builder.currentOutput().grid();
                        float[] shadow=VolumetricMediumGlTest.texture(builder.resources().source());
                        scene.setLightVolumeHints(0,new LightVolumeHints(1,false,true));pipeline.execute(device);
                        float[] unshadowed=VolumetricMediumGlTest.texture(builder.resources().source());
                        int behind=grid.interval(8)*4,front=grid.interval(3)*4;
                        assertTrue(unshadowed[behind]>.00001,"air light remains enabled "+type);
                        assertTrue(shadow[behind]<unshadowed[behind]*.15,"wall occludes volume light "+type+" shadow="+shadow[behind]+" lit="+unshadowed[behind]);
                        assertEquals(unshadowed[front],shadow[front],.001*unshadowed[front]+.000001,"front air remains lit "+type);
                        assertEquals(shadow[behind+3],unshadowed[behind+3],"shadow switch does not change the medium");
                        scene.setLightVolumeHints(0,LightVolumeHints.DISABLED);pipeline.execute(device);
                        assertEquals(0,VolumetricMediumGlTest.texture(builder.resources().source())[behind]);
                        assertEquals(light,scene.lights().getFirst());VolumetricMediumGlTest.diagnosticsZero(builder);
                    } finally {pipeline.close();}
                }
                GlDebug.assertNoError("volume allocated shadow wall");
            }
        }
    }

    @Test void finiteCsmCoverageRestoresUnshadowedFarFogForPerspectiveAndOrthographicCameras() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var mesh=Mesh.from(BuiltinMeshData.coloredCube("volume-csm-wall"));
                var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var material=Material.builder(shader).build()) {
                for(boolean ortho:new boolean[]{false,true}) {
                    var size=new VolumetricGenerationGlTest.Size();size.width=1;size.height=1;
                    var scene=new Scene(VolumetricMediumGlTest.camera(ortho,1));
                    scene.add(MeshRenderer.of(mesh,material,Transform.at(0,0,-5).scale(8,8,.25f)));
                    scene.addLight(SceneLight.shadowedDirectional(new Vector3f(0,0,-1),new Vector3f(1),5));
                    var pipeline=new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                            .antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build()).volumetricFog(fog())
                            .directionalCascades(new DirectionalCascadeSettings(2,256,.6f,.1f,10));
                    try {
                        pipeline.build();var device=new GlRenderDevice();pipeline.execute(device);
                        var builder=pipeline.activeGenerationForTest().volumetric;var grid=builder.currentOutput().grid();
                        float[] shadow=VolumetricMediumGlTest.texture(builder.resources().source());
                        scene.setLightVolumeHints(0,new LightVolumeHints(1,false,true));pipeline.execute(device);
                        float[] lit=VolumetricMediumGlTest.texture(builder.resources().source());
                        assertEquals(10f,pipeline.lastRender3dDiagnostics().shadows().cascadeSplits().get(1).floatValue());
                        assertTrue(shadow[grid.interval(7)*4]<lit[grid.interval(7)*4]*.2,"inside CSM coverage is occluded");
                        assertEquals(lit[grid.interval(20)*4],shadow[grid.interval(20)*4],.000001,"outside CSM coverage is unshadowed");
                        assertEquals(32,grid.far());VolumetricMediumGlTest.diagnosticsZero(builder);
                    } finally {pipeline.close();}
                }
            }
        }
    }

    @Test void removingAnAllocatedLightReassignsTheSlotWithoutCrossingFrameLightIndicesOrDroppingUnallocatedContribution() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var mesh=Mesh.from(BuiltinMeshData.coloredCube("volume-slot-wall"));
                var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var material=Material.builder(shader).build()) {
                var size=new VolumetricGenerationGlTest.Size();size.width=1;size.height=1;
                var scene=new Scene(VolumetricMediumGlTest.camera(false,1));
                scene.add(MeshRenderer.of(mesh,material,Transform.at(0,0,-5).scale(8,8,.25f)));
                scene.addLight(SceneLight.shadowedPoint(new Vector3f(0,0,-2),new Vector3f(1,0,0),10,30));
                scene.addLight(SceneLight.shadowedPoint(new Vector3f(8,0,-7),new Vector3f(0,1,0),10,30));
                long greenId=scene.lightEntries().get(1).stableId();
                var local=new LocalShadowSettings(128,.05f,.0025f);
                var pipeline=new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                        .antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build()).volumetricFog(fog())
                        .localShadows(new LocalShadowPipelineSettings(local,local,1,1,ShadowSelectionMode.SCENE_ORDER,
                                ShadowFilterMode.PCF_3X3,0,1.15f,true));
                try {
                    pipeline.build();var device=new GlRenderDevice();pipeline.execute(device);
                    var generation=pipeline.activeGenerationForTest();var builder=generation.volumetric;
                    int behind=builder.currentOutput().grid().interval(8)*4;
                    float[] both=VolumetricMediumGlTest.texture(builder.resources().source());
                    assertTrue(both[behind+1]>.00001,"unallocated green still contributes");
                    assertEquals(0,both[behind],.000001,"allocated red is blocked");
                    scene.removeLight(0);pipeline.execute(device);
                    assertSame(generation,pipeline.activeGenerationForTest());assertEquals(greenId,scene.lightEntries().getFirst().stableId());
                    float[] green=VolumetricMediumGlTest.texture(builder.resources().source());
                    assertEquals(0,green[behind],.000001);assertEquals(both[behind+1],green[behind+1],.000002);
                    var selected=pipeline.lastRender3dDiagnostics().shadows().pointSelected();
                    assertEquals(1,selected);
                    VolumetricMediumGlTest.diagnosticsZero(builder);GlDebug.assertNoError("volume stable shadow slot reassignment");
                } finally {pipeline.close();}
            }
        }
    }
}
