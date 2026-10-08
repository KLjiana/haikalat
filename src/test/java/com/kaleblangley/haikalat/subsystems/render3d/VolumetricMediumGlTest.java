package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46.*;

/** Independent double-precision expectations are deliberately separate from the GLSL recurrence. */
@EnabledIfSystemProperty(named="haikalat.glSmoke", matches="true")
class VolumetricMediumGlTest {
    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(33,17).visible(false).title("Froxel medium reference").build();
    }
    static VolumetricFogSettings settings(FogMediumSettings global, List<LocalFogVolume> local) {
        return new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,global,local,
                0,false,0,1337,new Vector3f());
    }
    static ExternalCamera camera(boolean orthographic, float aspect) {
        Matrix4f projection = orthographic ? new Matrix4f().ortho(-4*aspect,4*aspect,-4,4,0.125f,64)
                : new Matrix4f().perspective((float)Math.PI/2,aspect,0.125f,64);
        Matrix4f view = new Matrix4f();
        return new ExternalCamera(view,projection,new Matrix4f(projection),new Vector3f(),0,0.125f,64,1);
    }
    static RenderFrameContext context(ExternalCamera camera, int width, int height, long sequence) {
        Scene scene = new Scene(camera);
        return RenderFrameContext.capture(scene,camera,width,height,1f/60,(int)sequence,sequence,0,null);
    }
    static void run(VolumetricPassBuilder builder, RenderFrameContext context, VolumetricFogSettings settings) {
        builder.prepareFrame(context,settings);
        GlRenderDevice device = new GlRenderDevice();
        var cmd = device.createCommandBuffer();
        builder.recordMedium(cmd); builder.recordSource(cmd); builder.recordTemporal(cmd); builder.recordIntegrate(cmd);
        device.execute(cmd);
        builder.prepareFrameSuccess(); builder.frameSucceeded();
    }
    @Test void analyticIntervalMatchesPhysicalSearchAtEveryBoundaryAndNeighbor() {
        try(var window=window()) {
            window.bindContext();GL.createCapabilities();
            for(boolean ortho:new boolean[]{false,true})for(var quality:VolumetricFogSettings.Quality.values())
                for(float[] range:new float[][]{{.125f,32},{.001f,1024},{1,1.0001f}}) {
                float near=range[0],far=range[1];
                var projection=ortho ? new Matrix4f().ortho(-4,4,-4,4,near,far*2)
                        :new Matrix4f().perspective((float)Math.PI/2,33f/17,near,far*2);
                var camera=new ExternalCamera(new Matrix4f(),projection,new Matrix4f(projection),new Vector3f(),0,near,far*2,1);
                var settings=new VolumetricFogSettings(true,far,quality,FogMediumSettings.vacuum(),List.of(),0,false,0,1337,new Vector3f());
                try(var builder=new VolumetricPassBuilder(71,33,17,settings);
                    var result=new GlBuffer(GL_SHADER_STORAGE_BUFFER,GL_DYNAMIC_DRAW);
                    var query=com.kaleblangley.haikalat.backend.shader.ShaderProgram.fromComputeSource(
                        "#version 460 core\n"+VolumetricPassBuilder.resource("volume-common.glsl")+"""
                        layout(local_size_x=64) in;
                        layout(std430,binding=4)buffer Results{ivec2 values[];};
                        int referenceInterval(float d) {
                            int a=0,b=vGrid.z;
                            while(a+1<b){int m=(a+b)/2;if(volumeBoundary(m)<=d)a=m;else b=m;}
                            return a;
                        }
                        void main() {
                            uint i=gl_GlobalInvocationID.x;
                            if(i>=4096u)return;
                            float d;
                            if(i<uint((vGrid.z+1)*3)) {
                                float boundary=volumeBoundary(int(i)/3);
                                uint bits=floatBitsToUint(boundary);
                                d=i%3u==0u ? boundary : i%3u==1u ? (bits==0u ? 0.0 : uintBitsToFloat(bits-1u)) : uintBitsToFloat(bits+1u);
                            } else d=exp2(mix(log2(vExtentDepth.z/2.0),log2(vExtentDepth.w*2.0),float(i)/4095.0));
                            values[i]=ivec2(volumeInterval(d),referenceInterval(d));
                        }
                        """)) {
                    result.allocateStorage(4096*8,GL_DYNAMIC_STORAGE_BIT);
                    builder.prepareFrame(context(camera,33,17,0),settings);
                    var device=new GlRenderDevice();var cmd=device.createCommandBuffer();
                    builder.bindParameters(cmd);
                    cmd.bindShader(query).bindStorageBuffer(4,result,0,4096*8).dispatchCompute(64,1,1)
                        .memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                    device.execute(cmd);
                    var values=BufferUtils.createIntBuffer(4096*2);
                    glGetNamedBufferSubData(result.id(),0,values);
                    for(int i=0;i<4096;i++)assertEquals(values.get(i*2+1),values.get(i*2),"physical interval ortho="+ortho+" quality="+quality+" near="+near+" far="+far+" case="+i);
                }
            }
            GlDebug.assertNoError("analytic physical interval boundaries");
        }
    }

    @Test void unchangedAdjunctsAfterPartialFailureRestoreTheLastSuccessfulMediumAndResizeUploads() {
        try(var window=window()) {
            window.bindContext();GL.createCapabilities();
            var camera=camera(true,33f/17);
            var global=FogMediumSettings.homogeneous(.05f,new Vector3f(1),new Vector3f());
            var original=settings(global,List.of(LocalFogVolume.box(new Vector3f(0,0,-16),new Vector3f(100),.2f,new Vector3f(.6f))));
            var changed=settings(global,List.of(LocalFogVolume.box(new Vector3f(0,0,-16),new Vector3f(100),.4f,new Vector3f(.3f))));
            try(var builder=new VolumetricPassBuilder(1,33,17,original)) {
                run(builder,context(camera,33,17,0),original);
                run(builder,context(camera,33,17,1),original);
                builder.prepareFrame(context(camera,33,17,2),changed);
                var device=new GlRenderDevice();var cmd=device.createCommandBuffer();
                builder.recordMedium(cmd);builder.recordSource(cmd);builder.recordTemporal(cmd);builder.recordIntegrate(cmd);
                System.setProperty("haikalat.test.failVolumePartialHistoryOnce","true");
                try {assertThrows(RuntimeException.class,()->device.execute(cmd));}
                finally {System.clearProperty("haikalat.test.failVolumePartialHistoryOnce");builder.frameFailed();}
                assertEquals(.45,texture(builder.resources().medium())[3],.0005,"the failed frame actually replaced GPU adjunct contents");
                run(builder,context(camera,33,17,3),original);
                assertEquals(.25,texture(builder.resources().medium())[3],.0003,"same bytes as the last success must be uploaded after failure");
                run(builder,context(camera,33,17,4),changed);
                assertEquals(.45,texture(builder.resources().medium())[3],.0005,"hot edits must replace resident data");
                try(var resize=builder.prepareResize(49,25)) {builder.commitResize(resize);}
                run(builder,context(camera,49,25,5),changed);
                assertEquals(.45,texture(builder.resources().medium())[3],.0005,"new generation storage requires its first upload");
                diagnosticsZero(builder);GlDebug.assertNoError("resident adjunct uploads across partial failure and resize");
            }
        }
    }
    static float[] texture(com.kaleblangley.haikalat.backend.texture.Texture3D texture) {
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        var buffer = BufferUtils.createFloatBuffer(texture.width()*texture.height()*texture.depth()*4);
        glGetTextureImage(texture.id(),0,GL_RGBA,GL_FLOAT,buffer);
        float[] data = new float[buffer.remaining()]; buffer.get(data); return data;
    }
    static void diagnosticsZero(VolumetricPassBuilder builder) {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        var counts = BufferUtils.createIntBuffer(4);
        glGetNamedBufferSubData(builder.resources().diagnostics().id(),0,counts);
        assertArrayEquals(new int[]{0,0,0,0},new int[]{counts.get(0),counts.get(1),counts.get(2),counts.get(3)});
    }

    @Test void vacuumAndUniformEmissionMatchBeerLambertAtEveryBoundaryIncludingObliqueRays() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            for (boolean ortho : new boolean[]{false,true}) for (float sigma : new float[]{0,1e-8f,.0005f,.1f,64}) {
                var settings=settings(FogMediumSettings.homogeneous(sigma,new Vector3f(.6f),new Vector3f(.3f,.6f,.8f)),List.of());
                for (int width : new int[]{1,33}) {
                    var camera=camera(ortho,width/17f);
                    try (var builder=new VolumetricPassBuilder(17,width,17,settings)) {
                        run(builder,context(camera,width,17,0),settings);
                        var grid=builder.currentOutput().grid(); float[] prefix=texture(builder.resources().prefix());
                        int tile=16, nx=(width+15)/16, ny=2, nz=64;
                        for (int y=0;y<ny;y++) for (int x=0;x<nx;x++) for (int z=0;z<=nz;z++) {
                            double pixelX=.5*(x*tile+Math.min((x+1)*tile,width));
                            double pixelY=.5*(y*tile+Math.min((y+1)*tile,17));
                            double rayX=(2*pixelX/width-1)*(width/17f),rayY=2*pixelY/17-1;
                            double depth=z==0 ? 0 : ortho ? 32.0*z/64 : .125*Math.pow(256.0,(z-1)/63.0);
                            double ds=depth*(ortho ? 1 : Math.sqrt(1+rayX*rayX+rayY*rayY));
                            double t=Math.exp(-sigma*ds);
                            double w=sigma==0 ? ds : -Math.expm1(-sigma*ds)/sigma;
                            int offset=((z*ny+y)*nx+x)*4;
                            assertEquals(t,prefix[offset+3],.002,"T at "+x+","+y+","+z+" sigma="+sigma);
                            for (int c=0;c<3;c++) assertEquals(new double[]{.3,.6,.8}[c]*w,prefix[offset+c],
                                    .005*Math.max(1,w),"S boundary");
                        }
                        assertEquals(0,grid.boundary(0)); diagnosticsZero(builder);
                    }
                }
            }
            GlDebug.assertNoError("analytic medium integration");
        }
    }

    @Test void foregroundDepthGuidanceRejectsBackgroundLightWithoutTruncatingAirOrFarTransport() {
        try(var window=window()) {
            window.bindContext();GL.createCapabilities();
            var settings=settings(FogMediumSettings.vacuum(),List.of());
            try(var builder=new VolumetricPassBuilder(22,32,32,settings);
                var result=new GlBuffer(GL_SHADER_STORAGE_BUFFER,GL_DYNAMIC_DRAW);
                var query=com.kaleblangley.haikalat.backend.shader.ShaderProgram.fromComputeSource(
                        "#version 460 core\n"+VolumetricPassBuilder.resource("volume-common.glsl")
                        +VolumetricPassBuilder.resource("volume-query.glsl")+VolumetricPassBuilder.resource("volume-reactive.glsl")+"""
                        layout(local_size_x=1) in;
                        layout(std430,binding=4)buffer Results{vec4 values[];};
                        uniform int uIndex;uniform float uDepth;
                        void main(){values[uIndex*2]=volumeQuery(vec2(16),uDepth);values[uIndex*2+1]=vec4(volumeReactive(vec2(16),uDepth));
                            if(uIndex==1){values[14]=volumeReconstructionWeights(ivec2(0),ivec2(1),vec2(.5),uDepth);
                                values[15]=vec4(volumeReactiveColumn(ivec2(0),uDepth),volumeReactiveColumn(ivec2(1,0),uDepth),
                                    volumeReactiveColumn(ivec2(0,1),uDepth),volumeReactiveColumn(ivec2(1),uDepth));}}
                        """)) {
                builder.prepareFrame(context(camera(true,1),32,32,0),settings);
                var source=BufferUtils.createFloatBuffer(2*2*64*4);
                var prefix=BufferUtils.createFloatBuffer(2*2*66*4);
                var reactive=BufferUtils.createFloatBuffer(2*2*65);
                for(int z=0;z<64;z++)for(int c=0;c<4;c++) {
                    boolean lit=c==1;
                    source.put(lit?.1f:0).put(lit?.2f:0).put(lit?.3f:0).put(0);
                }
                for(int z=0;z<=64;z++)for(int c=0;c<4;c++) {
                    float d=z*.5f;boolean lit=c==1;
                    prefix.put(lit?.1f*d:0).put(lit?.2f*d:0).put(lit?.3f*d:0).put(1);
                    reactive.put(z>0&&lit?1:0);
                }
                for(int c=0;c<4;c++)prefix.put(c==3?7:32).put(0).put(0).put(0);
                source.flip();prefix.flip();reactive.flip();
                glTextureSubImage3D(builder.resources().source().id(),0,0,0,0,2,2,64,GL_RGBA,GL_FLOAT,source);
                glTextureSubImage3D(builder.resources().prefix().id(),0,0,0,0,2,2,66,GL_RGBA,GL_FLOAT,prefix);
                glTextureSubImage3D(builder.resources().reactivePrefix().id(),0,0,0,0,2,2,65,GL_RED,GL_FLOAT,reactive);
                glClearTexImage(builder.resources().reject().id(),0,GL_RED,GL_FLOAT,BufferUtils.createFloatBuffer(1).put(0,1));
                glClearTexImage(builder.resources().lightOffReactive().id(),0,GL_RED,GL_FLOAT,BufferUtils.createFloatBuffer(1));
                float[] depth={7,7,4,32,7.125f-.0001f,7.125f+.0001f,31.9999f};
                result.allocateStorage(depth.length*32+32,GL_DYNAMIC_STORAGE_BIT);
                var device=new GlRenderDevice();var cmd=device.createCommandBuffer();builder.recordMedium(cmd);builder.bindParameters(cmd);
                cmd.bindShader(query).bindTexture3D(14,builder.resources().prefix()).bindTexture3D(15,builder.resources().source())
                        .bindTexture3D(16,builder.resources().reject()).bindTexture3D(17,builder.resources().historyRead())
                        .bindTexture3D(19,builder.resources().reactivePrefix()).bindTexture3D(20,builder.resources().lightOffReactive())
                        .bindStorageBuffer(4,result,0,depth.length*32+32);
                for(int i=0;i<depth.length;i++)cmd.setUniformInt(query,"uIndex",i).setUniformFloat(query,"uDepth",depth[i])
                        .setUniformInt(query,"uVolumeSurfaceGuideEnabled",i==0?0:1).dispatchCompute(1,1,1);
                cmd.memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);device.execute(cmd);
                var values=BufferUtils.createFloatBuffer(depth.length*8+8);glGetNamedBufferSubData(result.id(),0,values);
                for(int i=0;i<depth.length;i++) {
                    assertEquals(1,values.get(i*8+3),.001);
                    float path=i==1||i==4||i==5?0:depth[i];
                    for(int c=0;c<3;c++)assertEquals(path*.25*(c+1)*.1,values.get(i*8+c),.001);
                }
                assertEquals(1,values.get(4),.001,"unguided background flash is visible");
                assertEquals(0,values.get(12),.001,"rejected background column cannot flag foreground reactive; weights="
                        +values.get(56)+","+values.get(57)+","+values.get(58)+","+values.get(59)+" columns="
                        +values.get(60)+","+values.get(61)+","+values.get(62)+","+values.get(63));
                GlDebug.assertNoError("guided foreground and untruncated air/far queries");
            }
        }
    }

    @Test void arbitraryDepthQueryUsesTheLastPartialSegmentAndClampsAtFiniteFogFar() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            var settings=settings(FogMediumSettings.homogeneous(.08f,new Vector3f(.7f),new Vector3f(.2f,.4f,.6f)),List.of());
            try (var builder=new VolumetricPassBuilder(21,33,17,settings);
                 var result=new GlBuffer(GL_SHADER_STORAGE_BUFFER,GL_DYNAMIC_DRAW);
                 var query=com.kaleblangley.haikalat.backend.shader.ShaderProgram.fromComputeSource(
                         "#version 460 core\n"+VolumetricPassBuilder.resource("volume-common.glsl")+
                         VolumetricPassBuilder.resource("volume-query.glsl")+"""
                         layout(local_size_x=1) in;
                         layout(std430,binding=4) buffer QueryResults { vec4 values[]; };
                         uniform vec3 uQuery;
                         uniform int uIndex;
                         void main() { values[uIndex]=volumeQueryColumn(ivec2(uQuery.xy),uQuery.z); }
                         """)) {
                float[] depths={0,.03f,.125f,.2f,2.345f,31.9f,32,80}; result.allocateStorage(depths.length*16,GL_DYNAMIC_STORAGE_BIT);
                builder.prepareFrame(context(camera(false,33f/17),33,17,0),settings);
                var device=new GlRenderDevice(); var cmd=device.createCommandBuffer();
                builder.recordMedium(cmd); builder.recordSource(cmd); builder.recordIntegrate(cmd);
                builder.bindParameters(cmd);
                cmd.bindShader(query).bindTexture3D(14,builder.resources().prefix()).bindTexture3D(15,builder.resources().source())
                        .bindStorageBuffer(4,result,0,depths.length*16);
                for (int index=0;index<depths.length;index++) {
                    cmd.setUniformVec3(query,"uQuery",new Vector3f(2,1,depths[index]))
                            .setUniformInt(query,"uIndex",index).dispatchCompute(1,1,1);
                }
                cmd.memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT); device.execute(cmd);
                var data=BufferUtils.createFloatBuffer(depths.length*4); glGetNamedBufferSubData(result.id(),0,data);
                double rx=(2*32.5/33-1)*(33f/17),ry=2*16.5/17-1;
                double rayScale=Math.sqrt(1+rx*rx+ry*ry);
                for (int index=0;index<depths.length;index++) {
                    double ds=Math.min(32,depths[index])*rayScale,t=Math.exp(-.08*ds),w=-Math.expm1(-.08*ds)/.08;
                    assertEquals(t,data.get(index*4+3),.002);
                    for (int c=0;c<3;c++) assertEquals(new double[]{.2,.4,.6}[c]*w,data.get(index*4+c),.005*Math.max(1,w));
                }
                builder.prepareFrameSuccess(); builder.frameSucceeded(); diagnosticsZero(builder);
                GlDebug.assertNoError("arbitrary depth volume query");
            }
        }
    }

    @Test void overlappingSphereAndBoxAddScatteringExtinctionAndEmissionWithoutBlendingAlbedo() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            var sphere=new LocalFogVolume(LocalFogVolume.Shape.SPHERE,new Vector3f(0,0,-5),new Vector3f(5),
                    .03f,new Vector3f(1,0,0),new Vector3f(.2f,0,0),.4f,0,0);
            var box=new LocalFogVolume(LocalFogVolume.Shape.BOX,new Vector3f(0,0,-7),new Vector3f(5,2,5),
                    .05f,new Vector3f(0,0,1),new Vector3f(0,0,.3f),.3f,0,0);
            var settings=settings(new FogMediumSettings(.01f,new Vector3f(.3f,.4f,.5f),new Vector3f(.02f),1,.1f),List.of(sphere,box));
            try (var builder=new VolumetricPassBuilder(31,33,17,settings)) {
                run(builder,context(camera(true,33f/17),33,17,0),settings);
                var grid=builder.currentOutput().grid(); float[] medium=texture(builder.resources().medium());
                float[] emission=texture(builder.resources().emission());
                int overlap=0, outside=0;
                for (int z=0;z<64;z++) for (int y=0;y<2;y++) for (int x=0;x<3;x++) {
                    Vector3f world=grid.worldSample(x,y,z,new Vector3f());
                    double height=Math.exp(-(world.y-1)*.1), sigma=.01*height;
                    double[] s={.003*height,.004*height,.005*height},e={.02*height,.02*height,.02*height};
                    double a=weight(sphere,world), b=weight(box,world);
                    if (a>0&&b>0) overlap++; if (a==0&&b==0) outside++;
                    sigma+=.03*a+.05*b; s[0]+=.03*a; s[2]+=.05*b; e[0]+=.2*a; e[2]+=.3*b;
                    int offset=((z*2+y)*3+x)*4;
                    assertEquals(sigma,medium[offset+3],.0001);
                    for (int c=0;c<3;c++) { assertEquals(s[c],medium[offset+c],.0001); assertEquals(e[c],emission[offset+c],.0003); }
                }
                assertTrue(overlap>0); assertTrue(outside>0); diagnosticsZero(builder);
            }
        }
    }

    @Test void conservativeRayMediumCandidatesPreserveExhaustiveCoefficientsAtBoundariesAndWithWind() {
        float[][] segments={
                {0,0,0,0,0,-10},{3,0,-4,3,0,-6},{-2,0,-4,2,0,-6},{1,0,-4,1,0,-6},
                {.5f,.0005f,-6.1f,.5f,.0005f,-6.4f},{20,20,-4,21,20,-6},
                {-4,0,-8,-2,0,-8},{0,2,-20,0,-2,-1},{0,0,-5,0,0,-5},{3.6f,0,-4,3.6f,0,-6}};
        int samples=33,count=segments.length*samples,bytes=count*64;
        try(var window=window()) {
            window.bindContext();GL.createCapabilities();
            for(boolean noise:new boolean[]{false,true}) {
                var local=new java.util.ArrayList<LocalFogVolume>();
                local.add(LocalFogVolume.sphere(new Vector3f(0,0,-5),1,.2f,new Vector3f(.6f,.2f,.4f)));
                local.add(new LocalFogVolume(LocalFogVolume.Shape.BOX,new Vector3f(3,0,-5),new Vector3f(.6f,1.2f,1),
                        .15f,new Vector3f(.2f,.7f,.3f),new Vector3f(.03f,.02f,.01f),.25f,0,0));
                local.add(LocalFogVolume.sphere(new Vector3f(.5f,.0005f,-6.25f),.005f,.4f,new Vector3f(1)));
                local.add(new LocalFogVolume(LocalFogVolume.Shape.SPHERE,new Vector3f(-3,0,-8),new Vector3f(1.8f,0,0),
                        .25f,new Vector3f(.8f,.3f,.5f),new Vector3f(.05f),.3f,noise?2.1f:0,noise?.65f:0));
                for(int i=4;i<8;i++)local.add(LocalFogVolume.box(new Vector3f(50+i,40,-20),new Vector3f(1),.1f,new Vector3f(.5f)));
                var fog=new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                        FogMediumSettings.homogeneous(.05f,new Vector3f(.3f),new Vector3f(.01f,.02f,.03f)),local,
                        0,false,0,1337,new Vector3f(.3f,0,.2f));
                try(var builder=new VolumetricPassBuilder(35,33,17,fog);
                    var input=new GlBuffer(GL_SHADER_STORAGE_BUFFER,GL_DYNAMIC_DRAW);
                    var output=new GlBuffer(GL_SHADER_STORAGE_BUFFER,GL_DYNAMIC_DRAW);
                    var query=com.kaleblangley.haikalat.backend.shader.ShaderProgram.fromComputeSource(
                            "#version 460 core\n"+VolumetricPassBuilder.resource("volume-common.glsl")
                                    +VolumetricPassBuilder.resource("volume-noise.glsl")
                                    +"\nlayout(std430,binding=7)buffer ProbeDiagnostics{uint diagnostics[];};\n"
                                    +VolumetricPassBuilder.resource("volume-medium.glsl")+"""
                            layout(local_size_x=1)in;
                            layout(std430,binding=8)readonly buffer Segments{vec4 endpoints[];};
                            layout(std430,binding=4)writeonly buffer Results{vec4 values[];};
                            void main(){uint i=gl_GlobalInvocationID.x;uint ray=i/33u;
                                vec3 a=endpoints[ray*2u].xyz,b=endpoints[ray*2u+1u].xyz;
                                vec3 p=mix(a,b,float(i%33u)/32.0);uint candidates=volumeMediumSegmentMask(a,b);
                                vec4 full,masked;vec3 emission,maskedEmission;
                                volumeMediumAt(p,full,emission);volumeMediumAtMasked(p,candidates,masked,maskedEmission);
                                values[i*4u]=full;values[i*4u+1u]=vec4(emission,float(candidates));
                                values[i*4u+2u]=masked;values[i*4u+3u]=vec4(maskedEmission,0);}
                            """)) {
                    var packed=BufferUtils.createByteBuffer(segments.length*32);
                    for(float[] segment:segments)for(int side=0;side<2;side++)
                        packed.putFloat(segment[side*3]).putFloat(segment[side*3+1]).putFloat(segment[side*3+2]).putFloat(0);
                    packed.flip();input.allocateStorage(packed.remaining(),GL_DYNAMIC_STORAGE_BIT);input.update(0,packed);
                    output.allocateStorage(bytes,GL_DYNAMIC_STORAGE_BIT);
                    var device=new GlRenderDevice();var camera=camera(true,33f/17);
                    for(int frame=0;frame<3;frame++) {
                        builder.prepareFrame(context(camera,33,17,frame),fog);
                        var cmd=device.createCommandBuffer();
                        builder.recordMedium(cmd);builder.recordSource(cmd);builder.recordTemporal(cmd);builder.recordIntegrate(cmd);
                        builder.bindParameters(cmd);
                        cmd.bindShader(query).bindStorageBuffer(6,builder.resources().volumes(),0,VolumetricResources.VOLUMES_BYTES)
                                .bindStorageBuffer(7,builder.resources().diagnostics(),0,VolumetricResources.DIAGNOSTICS_BYTES)
                                .bindStorageBuffer(8,input,0,segments.length*32).bindStorageBuffer(4,output,0,bytes)
                                .dispatchCompute(count,1,1).memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                        device.execute(cmd);builder.prepareFrameSuccess();builder.frameSucceeded();
                        var actual=BufferUtils.createFloatBuffer(count*16);glGetNamedBufferSubData(output.id(),0,actual);
                        for(int ray=0;ray<segments.length;ray++)for(int sample=0;sample<samples;sample++) {
                            int offset=(ray*samples+sample)*16;
                            for(int c=0;c<4;c++)assertEquals(actual.get(offset+c),actual.get(offset+8+c),0,"masked medium ray="+ray+" sample="+sample);
                            for(int c=0;c<3;c++)assertEquals(actual.get(offset+4+c),actual.get(offset+12+c),0,"masked emission");
                            assertTrue(actual.get(offset+7)<255,"sparse candidates must exclude the four distant boxes");
                            if(ray==5)assertEquals(0,actual.get(offset+7),0,"empty ray interval excludes every local volume");
                            if(!noise) {
                                float t=sample/32f;float[] s=segments[ray];
                                var p=new Vector3f(s[0]+(s[3]-s[0])*t,s[1]+(s[4]-s[1])*t,s[2]+(s[5]-s[2])*t);
                                double sigma=.05;double[] scattering={.015,.015,.015},emission={.01,.02,.03};
                                for(var volume:local) {
                                    double w=weight(volume,p);sigma+=volume.extinction()*w;
                                    var albedo=volume.albedo();var e=volume.emission();
                                    for(int c=0;c<3;c++){scattering[c]+=volume.extinction()*albedo.get(c)*w;emission[c]+=e.get(c)*w;}
                                }
                                assertEquals(sigma,actual.get(offset+11),.000002,"independent exhaustive extinction");
                                for(int c=0;c<3;c++){assertEquals(scattering[c],actual.get(offset+8+c),.000002);assertEquals(emission[c],actual.get(offset+12+c),.000002);}
                            }
                        }
                        diagnosticsZero(builder);
                    }
                }
            }
            GlDebug.assertNoError("conservative local medium support across boundaries, small spheres and wind");
        }
    }

    private static double weight(LocalFogVolume volume, Vector3f position) {
        Vector3f center=volume.center(),extent=volume.extent();
        double dx=Math.abs(position.x-center.x),dy=Math.abs(position.y-center.y),dz=Math.abs(position.z-center.z);
        double radius=volume.shape()==LocalFogVolume.Shape.SPHERE ? Math.sqrt(dx*dx+dy*dy+dz*dz)/extent.x
                : Math.max(Math.max(dx/extent.x,dy/extent.y),dz/extent.z);
        if (radius>=1) return 0;
        double f=volume.falloff(); if (f==0||radius<=1-f) return 1;
        double t=(radius-(1-f))/f; return 1-t*t*(3-2*t);
    }

    @Test void densityNoiseIsDeterministicAndScratchReuseDoesNotConsumeFrameIndexAsASeed() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            var local=new LocalFogVolume(LocalFogVolume.Shape.BOX,new Vector3f(0,0,-8),new Vector3f(50),.2f,
                    new Vector3f(.8f),new Vector3f(),0,.35f,.8f);
            var settings=settings(FogMediumSettings.vacuum(),List.of(local));
            try (var builder=new VolumetricPassBuilder(33,33,17,settings)) {
                run(builder,context(camera(false,33f/17),33,17,0),settings);
                float[] first=texture(builder.resources().medium());
                run(builder,context(camera(false,33f/17),33,17,7),settings);
                assertArrayEquals(first,texture(builder.resources().medium()));
                assertNull(builder.resources().emission()); diagnosticsZero(builder);
            }
        }
    }

    @Test void outOfContractDensityIsFiniteAndReportsTheClampInsteadOfPassingAsNormalQuality() {
        try (var window=window()) {
            window.bindContext(); GL.createCapabilities();
            var settings=settings(new FogMediumSettings(64,new Vector3f(1),new Vector3f(16),10,1),List.of());
            try (var builder=new VolumetricPassBuilder(34,1,1,settings)) {
                run(builder,context(camera(false,1),1,1,0),settings);
                for (float value:texture(builder.resources().prefix())) assertTrue(Float.isFinite(value));
                var counts=BufferUtils.createIntBuffer(3); glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                glGetNamedBufferSubData(builder.resources().diagnostics().id(),0,counts);
                assertEquals(0,counts.get(0)); assertTrue(counts.get(1)>0);
            }
        }
    }
}
