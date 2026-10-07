package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class VolumetricLightingGlTest {
    static VolumetricFogSettings fog(float g) {
        return new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.homogeneous(.04f,new Vector3f(.7f,.8f,.9f),new Vector3f(.001f)),List.of(),
                g,false,0,1337,new Vector3f());
    }
    static RenderPipeline pipeline(VolumetricGenerationGlTest.Size size,Scene scene,VolumetricFogSettings fog,int capacity) {
        return new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                .antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build()).volumetricFog(fog)
                .clusteredLighting(ClusteredLightingSettings.builder().inlineIndicesPerCluster(capacity).build());
    }

    @Test void disabledDiagnosticsLeaveStorageUntouchedAndSourceIntegralClampsUseTheirDeclaredCounters() {
        String previous=System.getProperty("haikalat.internal.volume.diagnostics");
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            var size=new VolumetricGenerationGlTest.Size();
            var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
            scene.addLight(SceneLight.directional(new Vector3f(0,-1,0),new Vector3f(1),1e14f));
            var fog=VolumetricMediumGlTest.settings(FogMediumSettings.homogeneous(.0001f,new Vector3f(1),new Vector3f()),List.of());
            var pipeline=pipeline(size,scene,fog,64);
            try {
                pipeline.build();var volume=pipeline.activeGenerationForTest().volumetric;
                var sentinel=BufferUtils.createByteBuffer(16);sentinel.asIntBuffer().put(new int[]{11,22,33,44});
                volume.resources().diagnostics().update(0,sentinel);
                System.setProperty("haikalat.internal.volume.diagnostics","false");pipeline.execute(new GlRenderDevice());
                var raw=BufferUtils.createIntBuffer(4);glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                glGetNamedBufferSubData(volume.resources().diagnostics().id(),0,raw);
                assertArrayEquals(new int[]{11,22,33,44},new int[]{raw.get(0),raw.get(1),raw.get(2),raw.get(3)});
                assertThrows(IllegalStateException.class,pipeline::captureVolumetricCounters);
                System.setProperty("haikalat.internal.volume.diagnostics","true");pipeline.execute(new GlRenderDevice());
                var counts=pipeline.captureVolumetricCounters();assertEquals(0,counts.nonfiniteMedium());assertEquals(0,counts.limitedMedium());
                assertTrue(counts.limitedSource()>0);assertTrue(counts.limitedIntegral()>0);
                for(float value:VolumetricMediumGlTest.texture(volume.resources().prefix())) {
                    assertTrue(Float.isFinite(value));assertTrue(value<=32768);
                }
            } finally { pipeline.close(); }
            GlDebug.assertNoError("optional diagnostic counters and pressure classification");
        } finally {
            if(previous==null)System.clearProperty("haikalat.internal.volume.diagnostics");
            else System.setProperty("haikalat.internal.volume.diagnostics",previous);
        }
    }

    @Test void independentLightDirectionsAttenuationAndSpotConeAgreeWithDoubleReference() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext(); GL.createCapabilities();
            for(boolean ortho:new boolean[]{false,true}) for(float g:new float[]{-.9f,0,.6f,.9f}) {
                var size=new VolumetricGenerationGlTest.Size(); var camera=VolumetricMediumGlTest.camera(ortho,33f/17);
                var scene=new Scene(camera);
                scene.addLight(SceneLight.point(new Vector3f(2,1,-4),new Vector3f(.9f,.5f,.3f),3,12));
                scene.addLight(SceneLight.spot(new Vector3f(-2,3,-2),new Vector3f(.15f,-.1f,-1),new Vector3f(.2f,.5f,1),4,20,.2f,.8f));
                scene.addLight(SceneLight.directional(new Vector3f(.2f,-.3f,-1),new Vector3f(.8f,.7f,.4f),2));
                scene.setLightVolumeHints(1,new LightVolumeHints(.6f,false,false));
                var fog=fog(g); var pipeline=pipeline(size,scene,fog,64);
                try {
                    pipeline.build(); pipeline.execute(new GlRenderDevice());
                    var builder=pipeline.activeGenerationForTest().volumetric;
                    float[] medium=VolumetricMediumGlTest.texture(builder.resources().medium());
                    float[] source=VolumetricMediumGlTest.texture(builder.resources().source());
                    var grid=builder.currentOutput().grid();
                    for(int z=0;z<grid.nz();z++) for(int y=0;y<grid.ny();y++) for(int x=0;x<grid.nx();x++) {
                        var world=grid.worldSample(x,y,z,new Vector3f()); int offset=((z*grid.ny()+y)*grid.nx()+x)*4;
                        double[] light=reference(scene,world,g,ortho);
                        for(int c=0;c<3;c++) {
                            double q=.001+medium[offset+c]*light[c];
                            assertEquals(q,source[offset+c],.000003+.002*q,"independent q at "+x+","+y+","+z+" g="+g);
                        }
                        assertEquals(medium[offset+3],source[offset+3]);
                    }
                    VolumetricMediumGlTest.diagnosticsZero(builder);
                } finally {pipeline.close();}
            }
        }
    }

    @Test void perSampleClusterMappingNearFallbackAndOverflowMatchAllLightGpuReference() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext(); GL.createCapabilities();
            for(int count:new int[]{16,128,256}) for(int capacity:new int[]{1,64}) {
                var size=new VolumetricGenerationGlTest.Size(); size.width=129;size.height=65;
                var camera=VolumetricMediumGlTest.camera(false,129f/65); var scene=new Scene(camera);
                scene.addLight(SceneLight.point(new Vector3f(0,0,-.02f),new Vector3f(1,0,0),.1f,.08f));
                for(int i=1;i<count;i++) {
                    var position=new Vector3f((i%7-3)*2f,(i%5-2)*1.5f,-3-(i%11)*2f);
                    var color=new Vector3f(.2f+(i%3)*.3f,.4f,.8f);
                    if(i%3==0) scene.addLight(SceneLight.spot(position,new Vector3f(.1f,-.1f,-1),color,.15f,40,.25f,1.1f));
                    else scene.addLight(SceneLight.point(position,color,.2f,40));
                    scene.setLightVolumeHints(i,i%7==0 ? LightVolumeHints.DISABLED : new LightVolumeHints(.8f,false,true));
                }
                scene.addLight(SceneLight.directional(new Vector3f(0,0,-1),new Vector3f(.6f),.2f));
                var pipeline=pipeline(size,scene,fog(.4f),capacity);
                try {
                    pipeline.build(); var builder=pipeline.activeGenerationForTest().volumetric;
                    try(var all=Texture3D.create(9,5,64,RenderFormat.RGBA32F);
                        var reference=ShaderProgram.fromComputeSource(VolumetricPassBuilder.shaderSource("source.comp",true))) {
                        pipeline.graph().addPass("AllLightVolumeReference").computeOnly().dependsOn(VolumetricPassBuilder.LIGHTING_PASS)
                                .execute((res,cmd)->builder.recordReferenceSource(res,cmd,reference,all));
                        pipeline.execute(new GlRenderDevice());
                        float[] actual=VolumetricMediumGlTest.texture(builder.resources().source());
                        float[] expected=VolumetricMediumGlTest.texture(all);
                        for(int i=0;i<actual.length;i++) assertEquals(expected[i],actual[i],.000001+.002*Math.abs(expected[i]),
                                "cluster/all mismatch at "+i+" count="+count+" capacity="+capacity);
                        var stats=BufferUtils.createIntBuffer(16); glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                        glGetNamedBufferSubData(builder.resources().diagnostics().id(),0,stats);
                        assertTrue(stats.get(6)>0,"near-camera volume used full scan");
                        if(count>capacity) assertTrue(stats.get(5)>0,"overflow volume used full scan");
                        VolumetricMediumGlTest.diagnosticsZero(builder);
                        assertTrue(builder.resources().lightHintsBytes()>256*16,"adjunct covers the entire unchanged surface table capacity");
                    }
                } finally {pipeline.close();}
            }
            GlDebug.assertNoError("volume candidate reference matrix");
        }
    }

    @Test void phaseHasCorrectNormalizationAndForwardBackwardsSignOnRealGpu() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext(); GL.createCapabilities();
            String shader="#version 460 core\n"+VolumetricPassBuilder.resource("volume-common.glsl")+"""
                    layout(local_size_x=1) in;
                    layout(std430,binding=4) buffer PhaseResults { vec4 result[]; };
                    uniform float uG;
                    void main() {
                        double sum=0.0; const int n=65536;
                        for(int i=0;i<n;i++) sum+=double(volumePhase(uG,-1.0+2.0*(float(i)+0.5)/float(n)));
                        result[0]=vec4(float(sum)*12.566370614359172/float(n),volumePhase(uG,-1.0),volumePhase(uG,1.0),volumePhase(0.0,0.3));
                    }
                    """;
            try(var program=ShaderProgram.fromComputeSource(shader);
                var buffer=new com.kaleblangley.haikalat.backend.buffer.GlBuffer(GL_SHADER_STORAGE_BUFFER,GL_DYNAMIC_DRAW)) {
                buffer.allocateStorage(16,GL_DYNAMIC_STORAGE_BIT); var device=new GlRenderDevice();
                for(float g:new float[]{-.9f,-.5f,0,.5f,.9f}) {
                    var cmd=device.createCommandBuffer(); cmd.memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT)
                            .bindShader(program).setUniformFloat(program,"uG",g)
                            .bindStorageBuffer(4,buffer,0,16).dispatchCompute(1,1,1).memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                    device.execute(cmd); var result=BufferUtils.createFloatBuffer(4);glGetNamedBufferSubData(buffer.id(),0,result);
                    GlDebug.assertNoError("phase g="+g);
                    assertEquals(g,glGetUniformf(program.id(),program.uniformLocation("uG")));
                    assertEquals(1,result.get(0),.001);assertEquals(1/(4*Math.PI),result.get(3),1e-7);
                    if(g>0) assertTrue(result.get(2)>result.get(1),"g="+g+" back="+result.get(1)+" forward="+result.get(2));
                    if(g<0) assertTrue(result.get(1)>result.get(2),"g="+g+" back="+result.get(1)+" forward="+result.get(2));
                }
            }
        }
    }

    private static double[] reference(Scene scene,Vector3f world,float g,boolean ortho) {
        double[] result=new double[3]; double vx=-world.x,vy=-world.y,vz=-world.z;
        double vl=Math.sqrt(vx*vx+vy*vy+vz*vz); if(ortho){vx=0;vy=0;vz=1;vl=1;}
        vx/=vl;vy/=vl;vz/=vl;
        for(int index=0;index<scene.lights().size();index++) {
            var light=scene.lights().get(index); double weight=scene.lightVolumeHints(index).scatteringIntensity();
            double lx,ly,lz,attenuation=1,cone=1;
            if(light.type()==LightType.DIRECTIONAL) {
                var d=light.direction(); double length=Math.sqrt(d.x*d.x+d.y*d.y+d.z*d.z);lx=-d.x/length;ly=-d.y/length;lz=-d.z/length;
            } else {
                var p=light.position();lx=p.x-world.x;ly=p.y-world.y;lz=p.z-world.z;
                double distance=Math.sqrt(lx*lx+ly*ly+lz*lz); if(distance>=light.range())continue;
                double reciprocal=1/Math.max(distance,1e-5);lx*=reciprocal;ly*=reciprocal;lz*=reciprocal;
                double window=Math.max(0,1-distance/light.range());attenuation=window*window/Math.max(distance*distance,1e-4);
                if(light.type()==LightType.SPOT) {
                    var d=light.direction();double length=Math.sqrt(d.x*d.x+d.y*d.y+d.z*d.z);
                    double angle=Math.acos(Math.max(-1,Math.min(1,(-lx*d.x-ly*d.y-lz*d.z)/length)));
                    double t=Math.max(0,Math.min(1,(angle-light.innerConeRadians())/(light.outerConeRadians()-light.innerConeRadians())));
                    cone=1-t*t*(3-2*t);
                }
            }
            double mu=-lx*vx-ly*vy-lz*vz;
            double phase=(1-g*g)/(4*Math.PI*Math.pow(1+g*g-2*g*mu,1.5));
            var color=light.color();double factor=light.intensity()*attenuation*cone*phase*weight;
            result[0]+=color.x*factor;result[1]+=color.y*factor;result[2]+=color.z*factor;
        }
        return result;
    }
}
