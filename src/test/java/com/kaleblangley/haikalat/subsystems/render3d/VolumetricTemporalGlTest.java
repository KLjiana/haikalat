package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class VolumetricTemporalGlTest {
    private static final String VERTEX="""
        #version 460 core
        layout(location=0) in vec3 aPosition;
        layout(std140) uniform CameraBlock { mat4 uProjection; mat4 uView; };
        uniform mat4 uModel; out vec3 world;
        void main(){world=(uModel*vec4(aPosition,1)).xyz;gl_Position=uProjection*uView*vec4(world,1);}
        """;
    private static final String FRAGMENT="""
        #version 460 core
        // HAIKALAT_VOLUME_FOG_CONTRACT
        in vec3 world; uniform float uAlpha;
        layout(location=0) out vec4 color;
        void main(){color=vec4(0,0,0,uAlpha);
        #ifdef HAIKALAT_VOLUME_FOG
        color=volumeFogSurface(color,world,false);
        #endif
        }
        """;
    private static VolumetricFogSettings fog(boolean history,List<LocalFogVolume> volumes) {
        return new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.homogeneous(.05f,new Vector3f(1),new Vector3f(.001f)),volumes,
                0,history,.9f,1337,new Vector3f());
    }
    private static RenderPipeline pipeline(VolumetricGenerationGlTest.Size size,Scene scene,boolean history,AntiAliasingMode aa) {
        return new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                .antiAliasingMode(aa).vsync(false).build()).volumetricFog(fog(history,List.of()));
    }
    private static int[] counters(VolumetricPassBuilder b) {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        var ints=BufferUtils.createIntBuffer(8);glGetNamedBufferSubData(b.resources().diagnostics().id(),1010L*4,ints);
        int[] result=new int[8];ints.get(result);return result;
    }
    private static float[] mask(RenderPipeline p) {
        String name=p.graph().hasPass(PostProcessTargets.SCENE_REACTIVE_PASS)?PostProcessTargets.SCENE_REACTIVE:VolumetricPassBuilder.REACTIVE_TEXTURE;
        float[] rgba=p.graph().readColorAttachmentRgbaFloat(name),result=new float[rgba.length/4];
        for(int i=0;i<result.length;i++)result[i]=rgba[i*4];return result;
    }
    @Test void dimLampFogDoesNotSurviveFinalTaaHistory(@TempDir java.nio.file.Path controlRoot) throws Exception {
        String gradient="""
            #version 460 core
            // HAIKALAT_VOLUME_FOG_CONTRACT
            in vec3 world; layout(location=0)out vec4 color;
            void main(){color=vec4(.45+.012*world.x,.35+.012*world.y,.25+.007*world.x,1);}
            """;
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();var size=new VolumetricGenerationGlTest.Size();
            try(var shader=ShaderProgram.fromSources(VERTEX,gradient);var mesh=Mesh.from(BuiltinMeshData.texturedQuad("dim-fog-gradient"));
                var material=Material.builder(shader).build()) {
                for(boolean directional:new boolean[]{true,false})for(boolean history:new boolean[]{false,true}) {
                    SceneLight light=directional?SceneLight.directional(new Vector3f(-.5f,-1,-.2f),new Vector3f(1),1)
                            :SceneLight.point(new Vector3f(0,5,-6),new Vector3f(1),20,16);
                    LightVolumeHints hints=new LightVolumeHints(directional?.02f:.08f,false,true);
                    var candidateScene=dimRecoveryScene(mesh,material,light,hints);
                    var baselineScene=dimRecoveryScene(mesh,material,light,hints);
                    var settings=RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES).antiAliasingMode(AntiAliasingMode.TAA).vsync(false).build();
                    var volume=new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                            FogMediumSettings.homogeneous(.05f,new Vector3f(1),new Vector3f()),List.of(),0,history,.9f,1337,new Vector3f());
                    var candidate=new RenderPipeline(size,candidateScene,null,settings).clusteredLighting(ClusteredLightingSettings.defaults()).volumetricFog(volume);
                    var baseline=new RenderPipeline(size,baselineScene,null,settings).clusteredLighting(ClusteredLightingSettings.defaults()).volumetricFog(volume);
                    java.nio.file.Path directory=controlRoot.resolve((directional?"directional":"point")+"-"+history);
                    try {
                        candidate.build();baseline.build();var device=new GlRenderDevice();
                        float[] before=null,beforeBase=null,after=null,afterBase=null;
                        try(var control=new VolumetricTemporalCounterfactual(baseline,baselineScene,0,directory,true)) {
                            for(int frame=0;frame<=21;frame++) {
                                if(frame==20) {
                                    var off=new LightVolumeHints(0,false,true);
                                    candidateScene.setLightVolumeHints(0,off);baselineScene.setLightVolumeHints(0,off);
                                }
                                candidate.execute(device,0);VolumetricTemporalCounterfactual.savePolicy(candidate,frame,directory,true);
                                control.frame(frame);baseline.execute(device,0);control.verifyPolicy();
                                if(frame==19){before=candidate.captureLinearHdrRgbaFloat();beforeBase=baseline.captureLinearHdrRgbaFloat();
                                    float[] validity=candidate.graph().readColorAttachmentRgbaFloat(PostProcessTargets.SCENE_VALIDITY);
                                    assertTrue(validity[(8*33+16)*4]>.5,"fixture must exercise valid TAA surface history");}
                                if(frame==21){after=candidate.captureLinearHdrRgbaFloat();afterBase=baseline.captureLinearHdrRgbaFloat();}
                            }
                        }
                        double beforeEnergy=absoluteRgbDifference(before,beforeBase);
                        double afterEnergy=absoluteRgbDifference(after,afterBase);
                        System.out.println("dim "+light.type()+" volume history="+history+" residual="+afterEnergy/beforeEnergy
                                +" before="+beforeEnergy+" after="+afterEnergy);
                        assertTrue(beforeEnergy>.01,"fixture must have a visible lamp increment");
                        assertTrue(afterEnergy/beforeEnergy<=.05,"dim "+light.type()+" volume history="+history
                                +" residual="+afterEnergy/beforeEnergy+" before="+beforeEnergy+" after="+afterEnergy);
                        GlDebug.assertNoError("dim light final TAA recovery");
                    }finally{candidate.close();baseline.close();}
                }
            }
        }
    }
    private static double absoluteRgbDifference(float[] candidate,float[] baseline) {
        assertEquals(candidate.length,baseline.length);
        double energy=0;
        for(int i=0;i<candidate.length;i+=4)for(int c=0;c<3;c++) {
            assertTrue(Float.isFinite(candidate[i+c])&&Float.isFinite(baseline[i+c]));
            energy+=Math.abs((double)candidate[i+c]-baseline[i+c]);
        }
        return energy;
    }
    private static Scene dimRecoveryScene(Mesh mesh,Material material,SceneLight light,LightVolumeHints hints) {
        var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
        scene.add(MeshRenderer.of(mesh,material,Transform.at(0,0,-12).scale(40)));
        scene.addLight(light);scene.setLightVolumeHints(0,hints);return scene;
    }
    @Test void fusedOpaqueMaskMatchesStandaloneReductionBeforeTransparentReplay() throws Exception {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();var size=new VolumetricGenerationGlTest.Size();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);var mesh=Mesh.from(BuiltinMeshData.texturedQuad("partial-reactive"));
                var material=Material.builder(shader).setFloat("uAlpha",1).build()) {
                var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
                scene.add(MeshRenderer.of(mesh,material,Transform.at(0,0,-.4f).scale(40)));
                var p=pipeline(size,scene,false,AntiAliasingMode.NONE);
                try {
                    p.build();var device=new GlRenderDevice();p.execute(device);float[] fused=mask(p);
                    var b=p.activeGenerationForTest().volumetric;var camera=b.frameState().previous().camera();
                    String vertex;
                    try(var in=getClass().getResourceAsStream("/shaders/postprocess/screen-quad.vert")) {
                        vertex=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    }
                    String fragment="#version 460 core\n"+VolumetricPassBuilder.resource("volume-common.glsl")+"\n"
                            +VolumetricPassBuilder.resource("volume-query.glsl")+"\n"+VolumetricPassBuilder.resource("volume-reactive.glsl")+"\n"
                            +"uniform mat4 uFogRasterInverseProjection,uFogStableProjection;layout(location=0)out float r;"
                            +"void main(){float d=texelFetch(uVolumeOpaqueDepth,ivec2(gl_FragCoord.xy),0).r;"
                            +"vec4 v=uFogRasterInverseProjection*vec4(2*gl_FragCoord.xy/vExtentDepth.xy-1,2*d-1,1);v/=v.w;"
                            +"vec4 s=uFogStableProjection*v;r=volumeReactive((s.xy/s.w*.5+.5)*vExtentDepth.xy,d>=1?vExtentDepth.w:-v.z);}";
                    // The opaque wall is planar at z=-0.4; derive its device depth independently.
                    var clip=camera.projection().transform(new org.joml.Vector4f(0,0,-.4f,1));
                    float rawDepth=.5f*(clip.z/clip.w)+.5f;
                    try(var reference=ShaderProgram.fromSources(vertex,fragment);var quad=new ScreenQuad();
                        var target=com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptor(
                                com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.colorOnly(33,17,GL_R8))) {
                        int depthTexture=glGenTextures();glBindTexture(GL_TEXTURE_2D,depthTexture);
                        var depthValues=BufferUtils.createFloatBuffer(33*17);for(int i=0;i<33*17;i++)depthValues.put(rawDepth);depthValues.flip();
                        glTexImage2D(GL_TEXTURE_2D,0,GL_R32F,33,17,0,GL_RED,GL_FLOAT,depthValues);
                        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                        try {
                        var cmd=device.createCommandBuffer().bindFramebuffer(target).viewport(0,0,33,17).enableBlend(false).enableDepthTest(false).bindShader(reference);
                        // Inspect completed private fields explicitly; the borrowed live-frame binder correctly rejects this point.
                        cmd.bindUniformBuffer(7,b.resources().parameters(),0,4096).bindTexture3D(14,b.resources().prefix())
                                .bindTexture3D(15,b.resources().historyRead()).bindTexture3D(16,b.resources().reject())
                                .bindTexture3D(17,b.resources().historyWrite()).bindTexture(18,depthTexture)
                                .bindTexture3D(19,b.resources().reactivePrefix()).bindTexture3D(20,b.resources().lightOffReactive());
                        cmd.setUniformMat4(reference,"uFogRasterInverseProjection",camera.inverseProjection())
                                .setUniformMat4(reference,"uFogStableProjection",camera.projection()).bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES,0,6);device.execute(cmd);
                        var pixels=BufferUtils.createFloatBuffer(33*17);glBindFramebuffer(GL_READ_FRAMEBUFFER,target.id());glReadPixels(0,0,33,17,GL_RED,GL_FLOAT,pixels);
                        assertTrue(fused[8*33+16]>.05&&fused[8*33+16]<.8,"partial-depth reactive must exercise an unsaturated value");
                        for(int i=0;i<fused.length;i++)assertEquals(pixels.get(i),fused[i],1.0/255,"fused mask differs at "+i);
                        } finally {glDeleteTextures(depthTexture);}
                    }
                    GlDebug.assertNoError("fused opaque mask equals standalone visible-depth reduction");
                } finally {p.close();}
            }
        }
    }
    private static void upload(com.kaleblangley.haikalat.backend.texture.Texture3D texture,float[] values) {
        var floats=BufferUtils.createFloatBuffer(values.length).put(values).flip();
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        glTextureSubImage3D(texture.id(),0,0,0,0,texture.width(),texture.height(),texture.depth(),GL_RGBA,GL_FLOAT,floats);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    @Test void physicalPreviousSampleDepthInterpolationAndCurrentExtinctionAreIndependentOfHistoryRgb() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            for(boolean ortho:new boolean[]{false,true}) {
                var camera=VolumetricMediumGlTest.camera(ortho,1);var scene=new Scene(camera);var settings=fog(true,List.of());
                try(var b=new VolumetricPassBuilder(1,1,1,settings)) {
                    var device=new GlRenderDevice();RenderFrameContext previous=null;
                    for(int frame=0;frame<2;frame++) {
                        var context=RenderFrameContext.capture(scene,camera,1,1,1f/60,frame,frame,0,previous);
                        b.prepareFrame(context,settings);var cmd=device.createCommandBuffer();b.recordMedium(cmd);device.execute(cmd);
                        float[] q=new float[64*4];
                        for(int z=0;z<64;z++) { q[z*4]=.01f+.001f*z-(frame==1?.001f:0);q[z*4+1]=q[z*4]*.5f;q[z*4+2]=q[z*4]*.25f;q[z*4+3]=frame==0?.05f:.051f; }
                        upload(b.resources().source(),q);
                        cmd=device.createCommandBuffer();b.recordTemporal(cmd);b.recordIntegrate(cmd);device.execute(cmd);
                        b.prepareFrameSuccess();b.frameSucceeded();previous=context;
                        if(frame==1) {
                            float[] actual=VolumetricMediumGlTest.texture(b.resources().historyRead());
                            for(int z=0;z<64;z++) {
                                double depth=boundary(z,ortho)*.675+boundary(z+1,ortho)*.325;
                                int lo=0;while(lo<63 && sampleDepth(lo+1,ortho,.5)<=depth)lo++;
                                double layer=depth<=sampleDepth(0,ortho,.5)?0:lo>=63?63:
                                    lo+(depth-sampleDepth(lo,ortho,.5))/(sampleDepth(lo+1,ortho,.5)-sampleDepth(lo,ortho,.5));
                                double historical=.01+.001*layer,now=.009+.001*z;
                                historical=Math.max(.009+.001*Math.max(0,z-1),Math.min(.009+.001*Math.min(63,z+1),historical));
                                // Two half-float stores (history input and resolved output) contribute up to two ULPs here.
                                assertEquals(.1*now+.9*historical,actual[z*4],.00012,"physical depth z="+z+" ortho="+ortho);
                                assertEquals(.051,actual[z*4+3],.00002,"alpha always uses current sigma");
                            }
                            assertTrue(counters(b)[1]>60,"history was actually reused");
                        }
                    }
                }
            }
            GlDebug.assertNoError("volume physical history interpolation");
        }
    }
    private static double boundary(int z,boolean ortho) { return z==0?0:ortho?32.0*z/64:.125*Math.pow(256.0,(z-1)/63.0); }
    private static double sampleDepth(int z,boolean ortho,double phase) { return boundary(z,ortho)*(1-phase)+boundary(z+1,ortho)*phase; }

    @Test void previousXySamplingPositionsIncludeIndependentPhasesAndClippedLastTiles() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            for(boolean ortho:new boolean[]{false,true}) {
                var camera=VolumetricMediumGlTest.camera(ortho,33f/17);var scene=new Scene(camera);var settings=fog(true,List.of());
                try(var b=new VolumetricPassBuilder(1,33,17,settings)) {
                    var device=new GlRenderDevice();RenderFrameContext previous=null;
                    for(int frame=0;frame<2;frame++) {
                        var context=RenderFrameContext.capture(scene,camera,33,17,1f/60,frame,frame,0,previous);
                        b.prepareFrame(context,settings);var cmd=device.createCommandBuffer();b.recordMedium(cmd);device.execute(cmd);
                        float[] q=new float[3*2*64*4];
                        for(int z=0;z<64;z++)for(int y=0;y<2;y++)for(int x=0;x<3;x++) {
                            int p=((z*2+y)*3+x)*4;q[p]=.01f+.006f*x+.008f*y+.001f*z;q[p+3]=.05f;
                        }
                        upload(b.resources().source(),q);cmd=device.createCommandBuffer();b.recordTemporal(cmd);b.recordIntegrate(cmd);device.execute(cmd);
                        b.prepareFrameSuccess();b.frameSucceeded();previous=context;
                        if(frame==1) {
                            float[] actual=VolumetricMediumGlTest.texture(b.resources().historyRead());
                            for(int z=0;z<64;z++)for(int y=0;y<2;y++)for(int x=0;x<3;x++) {
                                // Independent arithmetic from the declared base-3/base-5 sequence,
                                // brute-force brackets rather than the production column mapper.
                                double pixelX=x*16+(.15+.7*2/3)*Math.min(16,33-x*16);
                                double pixelY=y*16+(.15+.7*2/5)*Math.min(16,17-y*16);
                                double hx=independentSampleColumn(pixelX,3,33,.15+.7/3);
                                double hy=independentSampleColumn(pixelY,2,17,.15+.7/5);
                                double depth=sampleDepth(z,ortho,.325);int lo=0;
                                while(lo<63&&sampleDepth(lo+1,ortho,.5)<=depth)lo++;
                                double hz=depth<=sampleDepth(0,ortho,.5)?0:lo==63?63:
                                        lo+(depth-sampleDepth(lo,ortho,.5))/(sampleDepth(lo+1,ortho,.5)-sampleDepth(lo,ortho,.5));
                                double now=.01+.006*x+.008*y+.001*z,old=.01+.006*hx+.008*hy+.001*hz;
                                double minimum=now,maximum=now;
                                int[][] neighbours={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
                                for(int[] n:neighbours)if(x+n[0]>=0&&x+n[0]<3&&y+n[1]>=0&&y+n[1]<2&&z+n[2]>=0&&z+n[2]<64) {
                                    double neighbour=.01+.006*(x+n[0])+.008*(y+n[1])+.001*(z+n[2]);
                                    minimum=Math.min(minimum,neighbour);maximum=Math.max(maximum,neighbour);
                                }
                                old=Math.max(minimum,Math.min(maximum,old));
                                assertEquals(.1*now+.9*old,actual[((z*2+y)*3+x)*4],.00015,"XYZ history at "+x+","+y+","+z+" ortho="+ortho);
                            }
                            assertTrue(counters(b)[1]>360,"independent XY history actually contributes");
                        }
                    }
                }
            }
        }
    }
    private static double independentSampleColumn(double pixel,int count,int extent,double phase) {
        double[] centers=new double[count];for(int x=0;x<count;x++)centers[x]=16*x+phase*Math.min(16,extent-16*x);
        if(pixel<=centers[0])return 0;if(pixel>=centers[count-1])return count-1;
        for(int x=0;x<count-1;x++)if(pixel<=centers[x+1])return x+(pixel-centers[x])/(centers[x+1]-centers[x]);
        throw new AssertionError("unbracketed independent XY sample");
    }

    @Test void oneOfThirtyTwoLightsFlickersMovesAndIsRemovedWithoutRejectingUnchangedRegions() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            var size=new VolumetricGenerationGlTest.Size();size.width=129;size.height=65;
            var scene=new Scene(VolumetricMediumGlTest.camera(true,129f/65));
            scene.addLight(SceneLight.directional(new Vector3f(0,0,-1),new Vector3f(1),.2f));
            for(int i=0;i<32;i++) scene.addLight(SceneLight.point(new Vector3f((i%8-4)*1.5f,(i/8-2)*1.5f,-8),new Vector3f(1),.2f,1));
            var p=pipeline(size,scene,true,AntiAliasingMode.NONE);
            try {
                p.build();var device=new GlRenderDevice();p.execute(device);p.execute(device);
                var b=p.activeGenerationForTest().volumetric;
                assertTrue(counters(b)[1]>.9*counters(b)[0]);
                scene.setLight(1,SceneLight.point(new Vector3f(-6,-3,-8),new Vector3f(1,0,0),4,1));p.execute(device);
                assertEquals(VolumetricHistoryPlan.Reason.NONE,b.historyPlan().reason());assertEquals(2,b.historyPlan().count());
                assertTrue(counters(b)[2]>0);assertTrue(counters(b)[1]>.85*counters(b)[0]);
                scene.setLight(1,SceneLight.point(new Vector3f(4,2,-8),new Vector3f(0,1,0),4,1));p.execute(device);
                var domains=b.historyPlan().domains();assertEquals(-6,domains.getFloat(0));assertEquals(4,domains.getFloat(32));
                scene.removeLight(1);p.execute(device);
                assertEquals(1,b.historyPlan().count());assertEquals(4,b.historyPlan().domains().getFloat(0));
                float[] off=VolumetricMediumGlTest.texture(b.resources().lightOffReactive());int offCells=0;
                for(int i=0;i<off.length;i+=4)if(off[i]>.5)offCells++;
                assertTrue(offCells>0 && offCells<.15*(off.length/4),"light-off response stays within the old local footprint");
                assertTrue(counters(b)[1]>.85*counters(b)[0]);
                p.execute(device);assertEquals(0,b.historyPlan().count());
                float[] stable=VolumetricMediumGlTest.texture(b.resources().lightOffReactive());
                for(int i=0;i<stable.length;i+=4)
                    assertEquals(0,stable[i],"the light-off event must not persist into a stable frame");
                GlDebug.assertNoError("local old/new/removed light history domains");
            } finally {p.close();}
        }
    }

    @Test void lightOffSecondFrameResidualMeetsFivePercentInAllThreeHistoryCombinations() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);var mesh=Mesh.from(BuiltinMeshData.texturedQuad("temporal-wall"));
                var material=Material.builder(shader).setFloat("uAlpha",1).build()) {
                for(int mode=0;mode<3;mode++) {
                    boolean history=mode!=0;var aa=mode==1?AntiAliasingMode.NONE:AntiAliasingMode.TAA;
                    var size=new VolumetricGenerationGlTest.Size();var camera=VolumetricMediumGlTest.camera(true,33f/17);
                    var scene=new Scene(camera);scene.add(MeshRenderer.of(mesh,material,Transform.at(0,0,-12).scale(40)));
                    scene.addLight(SceneLight.point(new Vector3f(0,0,-6),new Vector3f(1,.3f,.1f),50,8));
                    scene.setLightVolumeHints(0,new LightVolumeHints(1,false,false));
                    var p=pipeline(size,scene,history,aa);
                    try {
                        p.build();var device=new GlRenderDevice();
                        for(int i=0;i<12;i++)p.execute(device);
                        float[] lit=p.captureLinearHdrRgbaFloat();
                        scene.setLight(0,SceneLight.point(new Vector3f(0,0,-6),new Vector3f(1,.3f,.1f),0,8));
                        p.execute(device);float reactive=mask(p)[8*33+16];
                        assertTrue(reactive>.5,"fast light signaled final TAA mode="+mode+" reactive="+reactive
                                +" stats="+java.util.Arrays.toString(counters(p.activeGenerationForTest().volumetric)));
                        p.execute(device);float[] second=p.captureLinearHdrRgbaFloat();
                        for(int i=0;i<8;i++)p.execute(device);float[] dark=p.captureLinearHdrRgbaFloat();
                        double before=0,residual=0;
                        for(int y=3;y<14;y++)for(int x=3;x<30;x++)for(int c=0;c<3;c++) {
                            int offset=(y*33+x)*4+c;before+=Math.max(0,lit[offset]-dark[offset]);residual+=Math.max(0,second[offset]-dark[offset]);
                        }
                        assertTrue(before>.1);assertTrue(residual<=.05*before,"mode="+mode+" residual="+residual/before);
                        System.out.println("lightOff mode="+mode+" before="+before+" secondFrameResidual="+residual+" ratio="+residual/before);
                        assertNotNull(p.temporalFrameStateForTest().previous(),"volume rejection did not discard successful camera state");
                    } finally {p.close();}
                }
            }
            GlDebug.assertNoError("three independent temporal combinations");
        }
    }

    @Test void fineOnlyLocalScatteringReceivesVisibleLightOffReactive() {
        verifyFineOnlyMediumEvent(FineEvent.LIGHT_OFF);
    }

    @Test void fineOnlyMovedLocalScatteringReceivesVisibleNativeReactive() {
        verifyFineOnlyMediumEvent(FineEvent.MEDIUM_MOVE);
    }

    @Test void fineOnlyLocalScatteringReceivesVisibleMovedLightReactive() {
        verifyFineOnlyMediumEvent(FineEvent.LIGHT_MOVE);
    }

    @Test void fineOnlyLocalScatteringReceivesVisibleFastLightReactive() {
        verifyFineOnlyMediumEvent(FineEvent.FAST_LIGHT);
    }

    private enum FineEvent { LIGHT_OFF, MEDIUM_MOVE, LIGHT_MOVE, FAST_LIGHT }

    private void verifyFineOnlyMediumEvent(FineEvent event) {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var mesh=Mesh.from(BuiltinMeshData.texturedQuad("fine-only-fog-background"));
                var material=Material.builder(shader).setFloat("uAlpha",1).build()) {
                for(int mode=0;mode<4;mode++)for(boolean sphere:new boolean[]{false,true})for(float depth:new float[]{3,10}) {
                boolean history=mode>=2;var aa=mode%2==0?AntiAliasingMode.NONE:AntiAliasingMode.TAA;
                var size=new VolumetricGenerationGlTest.Size();size.width=129;size.height=65;
                var scene=new Scene(VolumetricMediumGlTest.camera(true,129f/65));
                scene.add(MeshRenderer.of(mesh,material,Transform.at(0,0,-depth).scale(40)));
                scene.addLight(SceneLight.point(new Vector3f(.45f,0,-5.3f),new Vector3f(1),.2f,8));
                scene.setLightVolumeHints(0,new LightVolumeHints(1,false,event!=FineEvent.FAST_LIGHT));
                var center=new Vector3f(.4923077f,0,-5.3f);
                var volume=sphere?LocalFogVolume.sphere(center,.2f,.02f,new Vector3f(.3f))
                        :LocalFogVolume.box(center,new Vector3f(.2f,.2f,.8f),.02f,new Vector3f(.3f));
                var fog=new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                        FogMediumSettings.homogeneous(0,new Vector3f(),new Vector3f()),List.of(volume),0,history,.9f,242,new Vector3f());
                var pipeline=new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                        .antiAliasingMode(aa).vsync(false).build()).volumetricFog(fog)
                        .clusteredLighting(ClusteredLightingSettings.defaults());
                try {
                    pipeline.build();var device=new GlRenderDevice();pipeline.execute(device);
                    int pixel=32*129+68;
                    float[] nativeImage=pipeline.graph().readColorAttachmentRgbaFloat(VolumetricPassBuilder.COMPOSITE_COLOR);
                    // Jitter moves this subcell footprint across native pixels.
                    // Track a pixel that actually received scattering before final AA.
                    if(depth>5)for(int candidate=0;candidate<nativeImage.length/4;candidate++)
                        if(nativeImage[4*candidate]>nativeImage[4*pixel])pixel=candidate;
                    float nativeLit=nativeImage[4*pixel];
                    if(depth>5)assertTrue(nativeLit>.0001,"native refinement must see the small medium history="+history+" aa="+aa+" sphere="+sphere+" depth="+depth+" lit="+nativeLit);
                    else assertEquals(0,nativeLit,.00001,"foreground blocks the medium");
                    for(int frame=1;frame<12;frame++)pipeline.execute(device);
                    float[] before=pipeline.captureLinearHdrRgbaFloat();
                    // Use the actual pre-event HDR peak: final TAA and native jitter need not
                    // retain the first frame's brightest pixel for this subcell medium.
                    if(depth>5)for(int candidate=0;candidate<before.length/4;candidate++)
                        if(before[4*candidate]>before[4*pixel])pixel=candidate;
                    float lit=before[4*pixel];
                    if(depth>5)assertTrue(lit>.000001,"visible final HDR has nonzero pre-event scattering history="+history+" aa="+aa+" sphere="+sphere+" lit="+lit);
                    if(event==FineEvent.FAST_LIGHT) {
                        float[] steadyMask=mask(pipeline);
                        assertEquals(depth>5?1:0,steadyMask[pixel],.005,"nonaccumulating native light responds on every visible frame");
                        assertEquals(0,steadyMask[32*129+20],.005,"fast-light response excludes unrelated rays");
                    }
                    var resources=pipeline.activeGenerationForTest().volumetric.resources();
                    for(float value:VolumetricMediumGlTest.texture(resources.medium()))
                        assertEquals(0,value,"coarse sampling must miss this fixture");
                    if(event==FineEvent.MEDIUM_MOVE) {
                        var nextCenter=new Vector3f(-.4923077f,0,-5.3f);
                        var moved=sphere?LocalFogVolume.sphere(nextCenter,.2f,.02f,new Vector3f(.3f))
                                :LocalFogVolume.box(nextCenter,new Vector3f(.2f,.2f,.8f),.02f,new Vector3f(.3f));
                        pipeline.applyVolumetricFog(new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                                fog.globalMedium(),List.of(moved),0,history,.9f,242,new Vector3f()));
                    } else if(event==FineEvent.LIGHT_MOVE) {
                        scene.setLight(0,SceneLight.point(new Vector3f(10,0,-5.3f),new Vector3f(1),.2f,8));
                    } else scene.setLightVolumeHints(0,new LightVolumeHints(0,false,event!=FineEvent.FAST_LIGHT));
                    pipeline.execute(device);
                    for(float value:VolumetricMediumGlTest.texture(resources.medium()))
                        assertEquals(0,value,"coarse sampling must still miss the event fixture");
                    float[] changedMask=mask(pipeline);
                    assertEquals(depth>5?1:0,changedMask[pixel],.005,"native event uses the visible ray segment: "+event+" history="+history+" aa="+aa+" sphere="+sphere);
                    if(event==FineEvent.MEDIUM_MOVE)
                        assertEquals(depth>5?1:0,changedMask[32*129+60],.005,"moved medium rejects its new visible footprint too");
                    assertEquals(0,changedMask[32*129+20],.005,"unaffected rays remain stable");
                    pipeline.execute(device);assertEquals(0,mask(pipeline)[pixel],.005,"event clears on the next stable frame");
                    float second=pipeline.captureLinearHdrRgbaFloat()[4*pixel];
                    System.out.println("fine-medium event="+event+" history="+history+" aa="+aa+" sphere="+sphere+" depth="+depth+" before="+lit+" reactive="+changedMask[pixel]+" second="+second);
                    assertTrue(second<=.05*lit+.0000001,"fine fog second-frame residual history="+history+" aa="+aa+" sphere="+sphere+" before="+lit+" after="+second);
                } finally {pipeline.close();}
                }
            }
            GlDebug.assertNoError("fine-only local medium and light native reactive response");
        }
    }

    @Test void movedMediumRejectsBothDomainsAndKeepsCurrentExtinctionOutsideThem() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();var size=new VolumetricGenerationGlTest.Size();size.width=129;size.height=65;
            var scene=new Scene(VolumetricMediumGlTest.camera(true,129f/65));
            var old=LocalFogVolume.box(new Vector3f(-3,0,-8),new Vector3f(2,2,2),.2f,new Vector3f(.6f));
            var next=LocalFogVolume.sphere(new Vector3f(3,0,-8),2,.3f,new Vector3f(.4f));
            var p=pipeline(size,scene,true,AntiAliasingMode.NONE);p.applyVolumetricFog(fog(true,List.of(old)));
            try {
                p.build();var device=new GlRenderDevice();p.execute(device);p.execute(device);var gen=p.activeGenerationForTest();
                p.applyVolumetricFog(fog(true,List.of(next)));assertSame(gen,p.activeGenerationForTest());p.execute(device);
                assertEquals(VolumetricHistoryPlan.Reason.NONE,gen.volumetric.historyPlan().reason());assertEquals(2,gen.volumetric.historyPlan().count());
                float[] q=VolumetricMediumGlTest.texture(gen.volumetric.resources().source());
                float[] filtered=VolumetricMediumGlTest.texture(gen.volumetric.resources().historyRead());
                for(int i=3;i<q.length;i+=4)assertEquals(q[i],filtered[i]);
                assertTrue(counters(gen.volumetric)[2]>0);assertTrue(counters(gen.volumetric)[1]>.65*counters(gen.volumetric)[0]);
            } finally {p.close();}
        }
    }

    @Test void actualPartialHistoryWriteNeverPublishesOrConsumesSuccessfulJitterAndCamera() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var tracking=GlDebug.acquireResourceTracking()) {
                var size=new VolumetricGenerationGlTest.Size();var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
                var p=pipeline(size,scene,true,AntiAliasingMode.NONE);
                try {
                    p.build();var device=new GlRenderDevice();p.execute(device);var b=p.activeGenerationForTest().volumetric;
                    var previous=b.frameState().previous();var read=b.resources().historyRead();var write=b.resources().historyWrite();
                    var sentinel=new float[write.width()*write.height()*write.depth()*4];java.util.Arrays.fill(sentinel,7);upload(write,sentinel);
                    System.setProperty("haikalat.test.failVolumePartialHistoryOnce","true");
                    var failure=assertThrows(com.kaleblangley.haikalat.backend.GlException.class,()->p.execute(device));
                    assertTrue(failure.getMessage().contains("partial history write"));
                    glFinish();float[] partial=VolumetricMediumGlTest.texture(write);
                    assertNotEquals(7,partial[0]);assertEquals(7,partial[partial.length-1]);
                    assertSame(read,b.resources().historyRead());assertSame(previous,b.frameState().previous());assertFalse(b.resources().historyValid());
                    p.execute(device);assertEquals(previous.successfulIndex()+1,b.frameState().previous().successfulIndex());
                    assertEquals(.325,b.frameState().previous().samplePhase(),.000001);assertTrue(b.resources().historyValid());
                    assertEquals(0,counters(b)[1]);
                } finally {System.clearProperty("haikalat.test.failVolumePartialHistoryOnce");p.close();}
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("partially written volume history recovery");
        }
    }

    @Test void cameraOnlyCsmRefitsKeepHistoryWhileChangedCasterContentRejectsIt() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();var size=new VolumetricGenerationGlTest.Size();size.width=129;size.height=65;
            var camera=new Camera();var scene=new Scene(camera);
            scene.addLight(SceneLight.shadowedDirectional(new Vector3f(.1f,-.2f,-1),new Vector3f(1),.5f));
            var p=pipeline(size,scene,true,AntiAliasingMode.NONE).directionalCascades(new DirectionalCascadeSettings(2,128,.5f,.08f,24));
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);var mesh=Mesh.from(BuiltinMeshData.coloredCube("history-caster"));
                var material=Material.builder(shader).setFloat("uAlpha",1).build()) {
                try {
                    p.build();var device=new GlRenderDevice();p.execute(device);p.execute(device);var b=p.activeGenerationForTest().volumetric;
                    int refitFrames=0;
                    for(int frame=1;frame<=12;frame++) {
                        camera.setPosition(new Vector3f(frame*.04f,0,0));p.execute(device);
                        assertEquals(VolumetricHistoryPlan.Reason.NONE,b.historyPlan().reason());
                        if(b.historyPlan().shadowRefit())refitFrames++;
                        int[] counts=counters(b);assertTrue(counts[1]>.75*counts[0],"camera refit retained world-space source history");
                    }
                    assertTrue(refitFrames>0,"test actually refreshed the CSM projection");
                    System.out.println("csmRefit frames="+refitFrames+" measuredFrames=12 minimumHistoryCoverage=0.75");
                    var transform=Transform.at(0,0,-8).scale(3,3,.2f);scene.add(MeshRenderer.of(mesh,material,transform));p.execute(device);
                    assertEquals(VolumetricHistoryPlan.Reason.DIRECTIONAL_SHADOW,b.historyPlan().reason());assertEquals(0,counters(b)[1]);
                    p.execute(device);assertEquals(VolumetricHistoryPlan.Reason.NONE,b.historyPlan().reason());
                    transform.position(2,0,-8);p.execute(device);
                    assertEquals(VolumetricHistoryPlan.Reason.DIRECTIONAL_SHADOW,b.historyPlan().reason());assertEquals(0,counters(b)[1]);
                    GlDebug.assertNoError("camera refit versus shadow contents");
                } finally {p.close();}
            }
        }
    }

    @Test void nearStaticWindUsesSuccessfulTimeWithoutWholeVolumeResetAndStaysWithinTwoPercent() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();var size=new VolumetricGenerationGlTest.Size();
            var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
            scene.addLight(SceneLight.directional(new Vector3f(0,0,-1),new Vector3f(1),.5f));
            var volume=new LocalFogVolume(LocalFogVolume.Shape.BOX,new Vector3f(0,0,-16),new Vector3f(100),.03f,
                    new Vector3f(.7f),new Vector3f(.001f),0,.02f,.15f);
            var basic=fog(true,List.of(volume));var windy=new VolumetricFogSettings(true,32,basic.quality(),basic.globalMedium(),basic.localVolumes(),
                    0,true,.9f,1337,new Vector3f(.01f,0,.002f));
            var p=pipeline(size,scene,true,AntiAliasingMode.NONE);p.applyVolumetricFog(windy);
            try {
                p.build();var device=new GlRenderDevice();double[] luminance=new double[32];
                for(int frame=0;frame<56;frame++) {
                    p.execute(device);var b=p.activeGenerationForTest().volumetric;
                    if(frame>0)assertEquals(VolumetricHistoryPlan.Reason.NONE,b.historyPlan().reason());
                    if(frame>=24) {
                        float[] image=p.captureLinearHdrRgbaFloat();double value=0;
                        for(int y=3;y<14;y++)for(int x=3;x<30;x++){int i=(y*33+x)*4;value+=.2126*image[i]+.7152*image[i+1]+.0722*image[i+2];}
                        luminance[frame-24]=value/(11*27);
                        assertTrue(counters(b)[1]>.85*counters(b)[0]);
                    }
                }
                double mean=java.util.Arrays.stream(luminance).average().orElseThrow(),variance=0;
                for(double value:luminance)variance+=(value-mean)*(value-mean);
                assertTrue(Math.sqrt(variance/luminance.length)/mean<=.02,"stable near-wind relative stddev="+Math.sqrt(variance/luminance.length)/mean);
                System.out.println("nearStaticWind samples="+java.util.Arrays.toString(luminance)+" relativeStdDev="+Math.sqrt(variance/luminance.length)/mean);
                GlDebug.assertNoError("near-static medium successful time");
            } finally {p.close();}
        }
    }

    @Test void reactiveDoesNotSeeAFlashBehindOpaqueDepthButRecoversItWhenVisible() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();var size=new VolumetricGenerationGlTest.Size();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);var mesh=Mesh.from(BuiltinMeshData.texturedQuad("reactive-occluder"));
                var material=Material.builder(shader).setFloat("uAlpha",1).build()) {
                var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));var wall=Transform.at(0,0,-5).scale(40);
                scene.add(MeshRenderer.of(mesh,material,wall));
                scene.addLight(SceneLight.point(new Vector3f(0,0,-16),new Vector3f(1),100,5));
                scene.setLightVolumeHints(0,new LightVolumeHints(1,false,false));
                var p=pipeline(size,scene,true,AntiAliasingMode.TAA);
                try {
                    p.build();var device=new GlRenderDevice();for(int i=0;i<5;i++)p.execute(device);
                    float[] hidden=mask(p);for(int y=3;y<14;y++)for(int x=3;x<30;x++)assertEquals(0,hidden[y*33+x],.005);
                    int[] counts=counters(p.activeGenerationForTest().volumetric);assertTrue(counts[2]>0,"occluded air source was still updated");
                    wall.position(0,0,-24);scene.setLight(0,SceneLight.point(new Vector3f(0,0,-16),new Vector3f(1),200,5));p.execute(device);
                    assertTrue(mask(p)[8*33+16]>.5,"revealed flash contributes only through its visible depth range");
                    GlDebug.assertNoError("visible-depth reactive reduction");
                } finally {p.close();}
            }
        }
    }
}
