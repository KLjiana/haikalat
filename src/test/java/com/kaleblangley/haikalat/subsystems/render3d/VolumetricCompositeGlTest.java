package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.vfx.*;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class VolumetricCompositeGlTest {
    @Test void controlledNearLampAblationReachesOpaqueAlphaAndHdrQueries() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var mesh=Mesh.from(BuiltinMeshData.texturedQuad("fine-ablation-background"));var renderer=new VfxRenderer(true)) {
                for(int mode=0;mode<3;mode++) {
                    var size=new VolumetricGenerationGlTest.Size();size.width=129;size.height=65;
                    var scene=new Scene(VolumetricMediumGlTest.camera(true,129f/65));
                    add(scene,mesh,shader,20,new Vector4f(0,0,0,1),BlendMode.OPAQUE);
                    if(mode<2)add(scene,mesh,shader,10,new Vector4f(0,0,0,1),mode==0?BlendMode.OPAQUE:BlendMode.ALPHA);
                    scene.addLight(SceneLight.point(new Vector3f(.45f,0,-5.3f),new Vector3f(1),.2f,8));
                    scene.setLightVolumeHints(0,new LightVolumeHints(1,false,false));
                    var fog=new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                            FogMediumSettings.homogeneous(0,new Vector3f(),new Vector3f()),
                            List.of(LocalFogVolume.box(new Vector3f(.4923077f,0,-5.3f),new Vector3f(.2f,.2f,.8f),.02f,new Vector3f(.3f))),
                            0,false,.9f,242,new Vector3f());
                    var pipeline=new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES).antiAliasingMode(AntiAliasingMode.NONE)
                            .vsync(false).build()).volumetricFog(fog).clusteredLighting(ClusteredLightingSettings.defaults());
                    try {
                        if(mode==2) {
                            var particle=new EffectSnapshot.ParticleSprite(1,new Vector3f(0,0,-10),32,0,new Vector4f(0,0,0,1),36);
                            var material=VfxMaterial.builder("fine-ablation-smoke").softParticleDistance(0).build();
                            var snapshot=new EffectSnapshot(List.of(particle),List.of(),List.of(),List.of(),List.of(particle),
                                    new VfxVisualSet(material,material,material,material));
                            pipeline.hdrVfxWithFog((res,cmd,camera,view)->renderer.record(cmd,snapshot,camera.projection(),camera.view(),
                                    res.colorAttachment(view.softParticleDepthName()),129,65,view));
                        }
                        pipeline.build();var device=new GlRenderDevice();pipeline.execute(device);
                        var volume=pipeline.activeGenerationForTest().volumetric;
                        int pixel=32*129+68;
                        assertTrue(pipeline.captureLinearHdrRgbaFloat()[4*pixel]>.0001,"fine-only fixture mode="+mode);
                        for(float value:VolumetricMediumGlTest.texture(volume.resources().medium()))assertEquals(0,value);
                        volume.diagnosticOmittedLight(0);pipeline.execute(device);
                        assertEquals(0,pipeline.captureLinearHdrRgbaFloat()[4*pixel],.000001,"ablation mode="+mode);
                        volume.diagnosticOmittedLight(-1);pipeline.execute(device);
                        assertTrue(pipeline.captureLinearHdrRgbaFloat()[4*pixel]>.0001,"ablation reset mode="+mode);
                    } finally {pipeline.close();}
                }
            }
            GlDebug.assertNoError("shared native lamp ablation");
        }
    }

    @Test void linkedPbrFineFogBindingsPreserveVertexPaletteAndMorphBlocks() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var original=ShaderProgram.fromResource(getClass(),
                        "/shaders/render3d/pbr/pbr-forward.vert","/shaders/render3d/pbr/pbr-forward.frag");
                var fog=original.withFragmentPrelude(VolumetricFogView.SHADER_CONTRACT,VolumetricFogView.fragmentPrelude())) {
                assertStorageBlock(fog,"JointPaletteBlock",7,true,false);
                assertStorageBlock(fog,"MorphDeltaBlock",8,true,false);
                assertStorageBlock(fog,"MorphWeightBlock",9,true,false);
                assertStorageBlock(fog,"LightTableBlock",0,false,true);
                assertStorageBlock(fog,"ClusterHeadersBlock",2,false,true);
                assertStorageBlock(fog,"ClusterIndicesBlock",3,false,true);
                assertStorageBlock(fog,"FineLightVolumeHintsBlock",4,false,true);
                assertStorageBlock(fog,"FineVolumeDiagnostics",5,false,true);
                assertStorageBlock(fog,"FineFogVolumes",6,false,true);
                assertStorageBlock(fog,"FineLightTableBlock",13,false,true);
                assertStorageBlock(fog,"ClusterHeadersBlock",2,false,true);
                assertStorageBlock(fog,"ClusterIndicesBlock",3,false,true);
                assertStorageBlock(fog,"FineClusterHeadersBlock",2,false,true);
                assertStorageBlock(fog,"FineClusterIndicesBlock",3,false,true);
                assertStorageBlock(fog,"VolumeReactiveDomains",14,false,true);
                int cachedSource=glGetUniformLocation(fog.id(),"uVolumeCurrentSource");
                assertTrue(cachedSource>=0,"native refinement must consume the current source cache");
                assertEquals(21,glGetUniformi(fog.id(),cachedSource));
                assertTrue(21<glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
            }
            GlDebug.assertNoError("PBR fine fog linked stage bindings");
        }
    }
    private static void assertStorageBlock(ShaderProgram shader,String name,int binding,boolean vertex,boolean fragment) {
        var properties=BufferUtils.createIntBuffer(3).put(new int[]{GL_BUFFER_BINDING,
                GL_REFERENCED_BY_VERTEX_SHADER,GL_REFERENCED_BY_FRAGMENT_SHADER}).flip();
        var values=BufferUtils.createIntBuffer(3);
        glGetProgramResourceiv(shader.id(),GL_SHADER_STORAGE_BLOCK,shader.storageBlockIndex(name),properties,null,values);
        assertEquals(binding,values.get(0),name+" binding");
        assertEquals(vertex?1:0,values.get(1),name+" vertex access");
        assertEquals(fragment?1:0,values.get(2),name+" fragment access");
    }

    @Test void transparencyBorrowsOnlyColorPrefixAndLeavesOpaqueMaskOwnedByCompositor() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            var single=com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(4,4).colorTexture(GL_RGBA16F).build();
            try(var colors=com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptor(
                        com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(4,4).colorTexture(GL_RGBA16F).colorTexture(GL_R8).build());
                var depth=com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptor(
                        com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(4,4).depthTexture().build())) {
                int color=colors.colorAttachment(0),mask=colors.colorAttachment(1),z=depth.depthAttachment();
                var transparent=com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptorSharingAttachments(single,colors,depth);
                assertEquals(color,transparent.colorAttachment());assertEquals(z,transparent.depthAttachment());
                transparent.close();assertTrue(glIsTexture(color));assertTrue(glIsTexture(mask));assertTrue(glIsTexture(z));
                assertThrows(IllegalArgumentException.class,()->com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptorSharingAttachments(
                        com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(4,4).colorTexture(GL_R8).build(),colors,depth));
                var maskDescriptor=com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(4,4).colorTexture(GL_R8).build();
                try(var reactive=com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptorSharingAttachments(
                        maskDescriptor,colors,null,List.of(1))) {
                    assertEquals(mask,reactive.colorAttachment());assertEquals(0,reactive.depthAttachment());
                    assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckNamedFramebufferStatus(reactive.id(),GL_FRAMEBUFFER));
                }
                assertTrue(glIsTexture(mask));
                assertThrows(IllegalArgumentException.class,()->com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptorSharingAttachments(
                        maskDescriptor,colors,null,List.of(2)));
                assertThrows(IllegalArgumentException.class,()->com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptorSharingAttachments(
                        maskDescriptor,colors,null,List.of(0)));
                assertThrows(IllegalArgumentException.class,()->com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptorSharingAttachments(
                        com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(4,4).colorTexture(GL_R8).depthTexture().build(),colors,null,List.of(1)));
                colors.close();assertFalse(glIsTexture(color));assertFalse(glIsTexture(mask));assertTrue(glIsTexture(z));
            }
            GlDebug.assertNoError("borrowed color prefix ownership");
        }
    }
    @Test void singleSampleReactiveAliasSurvivesResizeAndTaaWithoutDuplicatingOpaqueStorage() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var tracking=GlDebug.acquireResourceTracking()) {
                for(var aa:new AntiAliasingMode[]{AntiAliasingMode.NONE,AntiAliasingMode.TAA}) {
                    var size=new VolumetricGenerationGlTest.Size();
                    var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
                    var pipeline=new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                            .antiAliasingMode(aa).vsync(false).build()).volumetricFog(fog());
                    try {
                        pipeline.build();
                        var mask=new AtomicInteger();
                        pipeline.graph().addPass("ReactiveOwnershipObserver").computeOnly().dependsOn(pipeline.finalPassName())
                                .execute((res,cmd)-> {
                                    var compositor=res.framebufferOfPass(VolumetricPassBuilder.COMPOSITE_PASS);
                                    var replay=res.framebufferOfPass(aa==AntiAliasingMode.TAA
                                            ? PostProcessTargets.SCENE_REACTIVE_PASS : VolumetricPassBuilder.REACTIVE_PASS);
                                    assertEquals(compositor.colorAttachment(1),replay.colorAttachment());
                                    assertEquals(0,replay.depthAttachment());
                                    mask.set(replay.colorAttachment());
                                });
                        pipeline.execute(new GlRenderDevice());int previous=mask.get();assertTrue(glIsTexture(previous));
                        pipeline.resize(65,33);size.width=65;size.height=33;
                        pipeline.execute(new GlRenderDevice());assertNotEquals(previous,mask.get());
                        assertFalse(glIsTexture(previous));assertTrue(glIsTexture(mask.get()));
                    } finally { pipeline.close(); }
                    assertTrue(GlDebug.resources().liveResources().isEmpty());
                }
            }
            GlDebug.assertNoError("shared reactive generation ownership");
        }
    }
    private static final String VERTEX="""
            #version 460 core
            layout(location=0) in vec3 aPosition;
            layout(std140) uniform CameraBlock { mat4 uProjection; mat4 uView; };
            uniform mat4 uModel;
            out vec3 world;
            void main(){ world=(uModel*vec4(aPosition,1)).xyz; gl_Position=uProjection*uView*vec4(world,1); }
            """;
    private static final String FRAGMENT="""
            #version 460 core
            // HAIKALAT_VOLUME_FOG_CONTRACT
            in vec3 world; uniform vec4 uColor; uniform int uAdditive;
            layout(location=0) out vec4 color;
            void main(){color=vec4(uColor.rgb*(uAdditive!=0 ? uColor.a : 1),uColor.a);
            #ifdef HAIKALAT_VOLUME_FOG
            color=volumeFogSurface(color,world,uVolumeAdditive!=0);
            #endif
            }
            """;
    static final Vector3f EMISSION=new Vector3f(.03f,.05f,.08f);
    private static VolumetricFogSettings fog() {
        return VolumetricMediumGlTest.settings(FogMediumSettings.homogeneous(.1f,new Vector3f(),EMISSION),List.of());
    }
    private static RenderPipeline pipeline(Scene scene,AntiAliasingMode aa,int samples,boolean enabled) {
        return new RenderPipeline(new VolumetricGenerationGlTest.Size(),scene,null,
                RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES).antiAliasingMode(aa).msaaSamples(samples).vsync(false).build())
                .volumetricFog(enabled ? fog() : VolumetricFogSettings.disabled());
    }
    private static Material material(ShaderProgram shader,Vector4f color,BlendMode blend) {
        return Material.builder(shader).blendMode(blend).setVec4("uColor",color).setInt("uAdditive",blend==BlendMode.ADDITIVE ? 1 : 0).build();
    }
    private static void add(Scene scene,Mesh mesh,ShaderProgram shader,float depth,Vector4f color,BlendMode blend) {
        scene.add(MeshRenderer.of(mesh,material(shader,color,blend),Transform.at(0,0,-depth).scale(32)));
    }
    // Independent continuous transport and alpha recurrence, in double precision.
    private static double[] opaque(Vector4f color,double depth,boolean enabled) {
        double t=enabled ? Math.exp(-.1*depth) : 1;
        return new double[]{t*color.x+(1-t)*.3,t*color.y+(1-t)*.5,t*color.z+(1-t)*.8};
    }
    private static double[] alpha(double[] background,Vector4f color,double depth,boolean enabled) {
        double[] surface=opaque(color,depth,enabled);
        for (int i=0;i<3;i++) surface[i]=color.w*surface[i]+(1-color.w)*background[i];
        return surface;
    }
    private static double[] additive(double[] background,Vector4f color,double depth,boolean enabled) {
        double t=enabled ? Math.exp(-.1*depth) : 1;
        for (int i=0;i<3;i++) background[i]+=t*color.w*color.get(i);
        return background;
    }
    private static void assertCenter(float[] actual,double[] expected) {
        int offset=(8*33+16)*4;
        for (int c=0;c<3;c++) assertEquals(expected[c],actual[offset+c],.004,"HDR channel "+c);
    }

    @Test void nativeFineIntervalsReplaceTheStoredFilteredContributionOnce() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var mesh=Mesh.from(BuiltinMeshData.texturedQuad("filtered-near-lamp"));
                var query=ShaderProgram.fromComputeSource("#version 460 core\n"
                        +VolumetricPassBuilder.resource("volume-common.glsl")
                        +VolumetricPassBuilder.resource("volume-query.glsl")
                        +VolumetricPassBuilder.resource("volume-lighting.glsl")
                        +VolumetricPassBuilder.resource("volume-noise.glsl")
                        +VolumetricPassBuilder.resource("volume-medium.glsl")
                        +VolumetricPassBuilder.resource("volume-fine.glsl")+"""
                        layout(local_size_x=1) in;
                        layout(std430,binding=8) buffer Result { vec4 values[]; };
                        uniform int uProbeBase;
                        void main() {
                            vec2 pixels[3]=vec2[](vec2(68.5,32.5),vec2(68.5,32.5),vec2(5.5,32.5));
                            float depths[3]=float[](10.0,5.75,10.0);
                            for(int i=0;i<3;i++) values[i+uProbeBase]=volumeRefineNativeRay(pixels[i],depths[i],volumeQuery(pixels[i],depths[i]));
                        }
                        """);
                var output=GlBuffer.shaderStorageBuffer(GL_DYNAMIC_READ).allocate(192);
                var storedSource=com.kaleblangley.haikalat.backend.texture.Texture3D.create(9,5,64,RenderFormat.RGBA16F);
                var storedPrefix=com.kaleblangley.haikalat.backend.texture.Texture3D.create(9,5,73,RenderFormat.RGBA16F);
                var sparsePrefix=com.kaleblangley.haikalat.backend.texture.Texture3D.create(9,5,73,RenderFormat.RGBA16F)) {
                var size=new VolumetricGenerationGlTest.Size();size.width=129;size.height=65;
                var scene=new Scene(VolumetricMediumGlTest.camera(true,129f/65));
                add(scene,mesh,shader,10,new Vector4f(0,0,0,1),BlendMode.OPAQUE);
                scene.addLight(SceneLight.directional(new Vector3f(0,0,-1),new Vector3f(.3f,.5f,.7f),1));
                scene.addLight(SceneLight.point(new Vector3f(.45f,.05f,-5.3f),new Vector3f(1,.5f,.25f),.2f,8));
                scene.addLight(SceneLight.point(new Vector3f(.7f,.05f,-5.3f),new Vector3f(.25f,.5f,1),.1f,8));
                for(int light=0;light<3;light++)scene.setLightVolumeHints(light,new LightVolumeHints(1,false,true));
                var fog=VolumetricMediumGlTest.settings(FogMediumSettings.homogeneous(.02f,new Vector3f(.3f),
                        new Vector3f(.0002f,.0003f,.0004f)),List.of());
                var pipeline=VolumetricLightingGlTest.pipeline(size,scene,fog,64);
                try {
                    pipeline.build();var device=new GlRenderDevice();pipeline.execute(device);
                    var volume=pipeline.activeGenerationForTest().volumetric;
                    float[] originalSource=VolumetricMediumGlTest.texture(volume.resources().historyRead());
                    float[] originalPrefix=VolumetricMediumGlTest.texture(volume.resources().prefix());
                    pipeline.graph().addPass("FilteredIntervalProbe").computeOnly().dependsOn(pipeline.finalPassName())
                            .execute((res,cmd)-> {
                                cmd.bindShader(query);volume.bindLightingInputs(res,cmd,query);volume.bindQuery(cmd,query);
                                cmd.bindTexture3D(14,storedPrefix).bindTexture3D(15,storedSource)
                                        .bindStorageBuffer(8,output,0,192)
                                        .setUniformInt(query,"uVolumeCurrentSourceEnabled",0).setUniformInt(query,"uProbeBase",0)
                                        .dispatchCompute(1,1,1).memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                                        .setUniformInt(query,"uVolumeCurrentSourceEnabled",1).setUniformInt(query,"uProbeBase",3)
                                        .dispatchCompute(1,1,1)
                                        .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                                        .bindTexture3D(14,sparsePrefix)
                                        .setUniformInt(query,"uVolumeCurrentSourceEnabled",0).setUniformInt(query,"uProbeBase",6)
                                        .dispatchCompute(1,1,1).memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                                        .setUniformInt(query,"uVolumeCurrentSourceEnabled",1).setUniformInt(query,"uProbeBase",9)
                                        .dispatchCompute(1,1,1)
                                        .memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                            });
                    float[] baseline=null;
                    for(float filteredScale:new float[]{1,.1f,2}) {
                        // Controlled filtered q differs from current q only in the five
                        // intervals replaced by the native ray. Prefix and q agree exactly;
                        // expected transport never calls production integration helpers.
                        float[] source=originalSource.clone(),prefix=originalPrefix.clone();
                        for(int column=0;column<45;column++) {
                            double transmission=1;double[] scatter=new double[3];
                            for(int z=0;z<64;z++) {
                                int offset=(z*45+column)*4;
                                for(int c=0;c<3;c++)prefix[offset+c]=(float)scatter[c];
                                prefix[offset+3]=(float)transmission;
                                if(z>=8&&z<=12)for(int c=0;c<3;c++)source[offset+c]*=filteredScale;
                                double sigma=source[offset+3],step=.5,weight=-Math.expm1(-sigma*step)/sigma;
                                for(int c=0;c<3;c++)scatter[c]+=transmission*source[offset+c]*weight;
                                transmission*=Math.exp(-sigma*step);
                            }
                            int offset=(64*45+column)*4;
                            for(int c=0;c<3;c++)prefix[offset+c]=(float)scatter[c];
                            prefix[offset+3]=(float)transmission;
                        }
                        var q=BufferUtils.createFloatBuffer(source.length).put(source).flip();
                        var st=BufferUtils.createFloatBuffer(prefix.length).put(prefix).flip();
                        float[] sparse=sparseNearLampSelector(prefix,45,64);
                        var sparseSt=BufferUtils.createFloatBuffer(sparse.length).put(sparse).flip();
                        glTextureSubImage3D(storedSource.id(),0,0,0,0,9,5,64,GL_RGBA,GL_FLOAT,q);
                        glTextureSubImage3D(storedPrefix.id(),0,0,0,0,9,5,73,GL_RGBA,GL_FLOAT,st);
                        glTextureSubImage3D(sparsePrefix.id(),0,0,0,0,9,5,73,GL_RGBA,GL_FLOAT,sparseSt);
                        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
                        pipeline.execute(device);
                        var data=BufferUtils.createFloatBuffer(48);glGetNamedBufferSubData(output.id(),0,data);
                        float[] actual=new float[12];data.get(actual);
                        for(int variant=1;variant<4;variant++)for(int component=0;component<12;component++)
                            assertEquals(actual[component],data.get(variant*12+component),.0000005,
                                    "packed/sparse cached/direct same-frame scale="+filteredScale
                                            +" variant="+variant+" component="+component);
                        if(filteredScale==1) {
                            baseline=actual;
                            double worldX=(2.0*68.5/129-1)*4*129.0/65;
                            double point=pointRay(worldX,0,new Vector3f(.45f,.05f,-5.3f));
                            double secondPoint=pointRay(worldX,0,new Vector3f(.7f,.05f,-5.3f));
                            for(int c=0;c<3;c++) {
                                double constant=new double[]{.0002,.0003,.0004}[c]+.006*new double[]{.3,.5,.7}[c]/(4*Math.PI);
                                double expected=point*Math.scalb(1.0,-c)+secondPoint*.5*new double[]{.25,.5,1}[c]
                                        +constant*(-Math.expm1(-.02*10)/.02);
                                assertEquals(expected,actual[c],.00002+.03*expected,"independent mixed-light ray channel="+c);
                            }
                        } else {
                            for(int pixel=0;pixel<2;pixel++)for(int c=0;c<4;c++)
                                assertEquals(baseline[pixel*4+c],actual[pixel*4+c],.000012,
                                        "replace stored contribution once: scale="+filteredScale+" depth="+pixel+" channel="+c);
                            assertTrue(Math.abs(actual[8]-baseline[8])>.00002,"unrefined ray must retain the filtered field");
                        }
                        VolumetricMediumGlTest.diagnosticsZero(volume);
                    }
                } finally {pipeline.close();}
            }
            GlDebug.assertNoError("filtered near-lamp interval replacement");
        }
    }

    private static float[] sparseNearLampSelector(float[] prefix,int columns,int slices) {
        float[] sparse=prefix.clone();int maximum=0;
        for(int column=0;column<columns;column++) {
            float[] selected=new float[2];int count=0;
            for(int slot=0;slot<16;slot++) {
                int offset=((slices+5+slot/4)*columns+column)*4+slot%4;
                assertTrue(prefix[offset]>=0,"two-lamp fixture must not overflow");
                if(prefix[offset]>0) {assertTrue(count<2);selected[count++]=prefix[offset];}
                sparse[offset]=0;
            }
            maximum=Math.max(maximum,count);
            for(int lamp=0;lamp<count;lamp++) {
                int slot=lamp==0?7:15;
                sparse[((slices+5+slot/4)*columns+column)*4+slot%4]=selected[lamp];
            }
        }
        assertEquals(2,maximum,"fixture must use both sparse slots and preserve their order");
        return sparse;
    }

    @Test void nearPointNativePixelsAndSelectorFallbacksAgreeWithIndependentContinuousRays() {
        String previousDiagnostics=System.getProperty("haikalat.internal.volume.diagnostics");
        try {
            for(boolean diagnosticsEnabled:new boolean[]{true,false}) {
                System.setProperty("haikalat.internal.volume.diagnostics",Boolean.toString(diagnosticsEnabled));
                assertNearPointContinuousRaysAndSelectorFallbacks();
            }
        } finally {
            if(previousDiagnostics==null)System.clearProperty("haikalat.internal.volume.diagnostics");
            else System.setProperty("haikalat.internal.volume.diagnostics",previousDiagnostics);
        }
    }
    private void assertNearPointContinuousRaysAndSelectorFallbacks() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var mesh=Mesh.from(BuiltinMeshData.texturedQuad("near-lamp-background"))) {
                for(int count:new int[]{1,16,17,2048}) {
                    var size=new VolumetricGenerationGlTest.Size();size.width=129;size.height=65;
                    var scene=new Scene(VolumetricMediumGlTest.camera(true,129f/65));
                    add(scene,mesh,shader,10,new Vector4f(0,0,0,1),BlendMode.OPAQUE);
                    var position=new Vector3f(.45f,.05f,-5.3f);
                    for(int i=0;i<count;i++) {
                        boolean contributes=count<2048||i==count-1;
                        scene.addLight(SceneLight.point(contributes?position:new Vector3f(1000+i,0,-5),
                                new Vector3f(1,.5f,.25f),contributes?.2f/(count<2048?count:1):0,8));
                        scene.setLightVolumeHints(i,contributes?new LightVolumeHints(1,false,false):LightVolumeHints.DISABLED);
                    }
                    var fog=VolumetricMediumGlTest.settings(FogMediumSettings.homogeneous(.02f,new Vector3f(.3f),new Vector3f()),List.of());
                    var pipeline=new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                            .antiAliasingMode(AntiAliasingMode.NONE).vsync(false).build()).volumetricFog(fog)
                            .clusteredLighting(ClusteredLightingSettings.builder().maxLocalLights(2048).inlineIndicesPerCluster(1).build());
                    try {
                        pipeline.build();
                        // Disabled counters must retain initialized storage while
                        // the zero-radiance path is checked against continuous rays.
                        glNamedBufferSubData(pipeline.activeGenerationForTest().volumetric.resources().diagnostics().id(),0,new int[1024]);
                        pipeline.execute(new GlRenderDevice());
                        float[] actual=pipeline.captureLinearHdrRgbaFloat();
                        for(int y:new int[]{31,32,33})for(int x:new int[]{67,68,69}) {
                            double worldX=(2.0*(x+.5)/129-1)*4*129.0/65;
                            double worldY=(2.0*(y+.5)/65-1)*4;
                            double expected=pointRay(worldX,worldY,position);
                            for(int c=0;c<3;c++)assertEquals(expected*Math.scalb(1.0,-c),actual[(y*129+x)*4+c],
                                    .00002+.03*expected,"continuous near-light ray "+x+","+y+" lights="+count+" channel="+c);
                        }
                        var volume=pipeline.activeGenerationForTest().volumetric;
                        if(count>=16) {
                            float[] prefix=VolumetricMediumGlTest.texture(volume.resources().prefix());
                            int plane=volume.currentOutput().grid().nz()+8;
                            boolean overflow=false;
                            for(int i=plane*9*5*4+3;i<(plane+1)*9*5*4;i+=4)overflow|=prefix[i]<0;
                            if(count>16)assertTrue(overflow,"fixture must exercise count overflow or an index not exactly encodable in half");
                            else {
                                assertFalse(overflow,"sixteen encodable lights fit exactly");
                                int maximum=0;
                                for(int column=0;column<45;column++) {
                                    int selected=0;
                                    for(int slot=0;slot<16;slot++)
                                        if(prefix[((64+5+slot/4)*45+column)*4+slot%4]>0)selected++;
                                    maximum=Math.max(maximum,selected);
                                }
                                assertEquals(16,maximum,"fixture must consume every selector slot");
                            }
                        }
                        VolumetricMediumGlTest.diagnosticsZero(volume);
                    } finally {pipeline.close();}
                }
            }
            GlDebug.assertNoError("native near-light continuous reference and selector fallback");
        }
    }
    private static double pointRay(double x,double y,Vector3f lamp) {
        // Continuous single scattering, independent of froxel boundaries and GLSL quadrature.
        int n=65536;double step=10.0/n,sum=0;
        for(int i=0;i<=n;i++) {
            double t=i*step,dx=lamp.x-x,dy=lamp.y-y,dz=lamp.z+t;
            double squared=dx*dx+dy*dy+dz*dz,distance=Math.sqrt(squared);
            double window=Math.max(0,1-distance/8);
            double q=.02*.3*.2*window*window/(Math.max(squared,.0001)*4*Math.PI);
            sum+=(i==0||i==n?1:i%2==0?2:4)*Math.exp(-.02*t)*q;
        }
        return sum*step/3;
    }

    @Test void nearLightAlphaAndHdrParticleAtOwnDepthMatchOpaqueNativeFog() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var mesh=Mesh.from(BuiltinMeshData.texturedQuad("near-lamp-own-depth"));var renderer=new VfxRenderer(true)) {
                for(var aa:new AntiAliasingMode[]{AntiAliasingMode.NONE,AntiAliasingMode.MSAA}) {
                    float[] opaque=null;
                    for(int mode=0;mode<3;mode++) {
                        var size=new VolumetricGenerationGlTest.Size();size.width=129;size.height=65;
                        var scene=new Scene(VolumetricMediumGlTest.camera(true,129f/65));
                        add(scene,mesh,shader,20,new Vector4f(0,0,0,1),BlendMode.OPAQUE);
                        if(mode<2)add(scene,mesh,shader,10,new Vector4f(0,0,0,1),mode==0?BlendMode.OPAQUE:BlendMode.ALPHA);
                        scene.addLight(SceneLight.point(new Vector3f(.45f,.05f,-5.3f),new Vector3f(1,.5f,.25f),.2f,8));
                        scene.setLightVolumeHints(0,new LightVolumeHints(1,false,false));
                        var fog=VolumetricMediumGlTest.settings(FogMediumSettings.homogeneous(.02f,new Vector3f(.3f),new Vector3f()),List.of());
                        var pipeline=new RenderPipeline(size,scene,null,RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                                .antiAliasingMode(aa).msaaSamples(4).vsync(false).build()).volumetricFog(fog)
                                .clusteredLighting(ClusteredLightingSettings.defaults());
                        try {
                            if(mode==2) {
                                var particle=new EffectSnapshot.ParticleSprite(1,new Vector3f(0,0,-10),32,0,new Vector4f(0,0,0,1),36);
                                var material=VfxMaterial.builder("near-light-smoke").softParticleDistance(0).build();
                                var snapshot=new EffectSnapshot(List.of(particle),List.of(),List.of(),List.of(),List.of(particle),
                                        new VfxVisualSet(material,material,material,material));
                                pipeline.hdrVfxWithFog((res,cmd,camera,view)->renderer.record(cmd,snapshot,camera.projection(),camera.view(),
                                        res.colorAttachment(view.softParticleDepthName()),129,65,view));
                            }
                            pipeline.build();pipeline.execute(new GlRenderDevice());
                            float[] actual=pipeline.captureLinearHdrRgbaFloat();
                            if(mode==0)opaque=actual;
                            else for(int y=31;y<=33;y++)for(int x=67;x<=69;x++)for(int c=0;c<3;c++) {
                                int offset=(y*129+x)*4+c;
                                assertEquals(opaque[offset],actual[offset],.00002+.015*opaque[offset],
                                        "own-depth near-light fog mode="+mode+" AA="+aa+" pixel="+x+","+y+" channel="+c);
                            }
                            VolumetricMediumGlTest.diagnosticsZero(pipeline.activeGenerationForTest().volumetric);
                        } finally {pipeline.close();}
                    }
                }
            }
            GlDebug.assertNoError("opaque/alpha/HDR particle share native near-light queries");
        }
    }

    @Test void nativeNearLightExtremeSourceNeverWritesHalfNonfiniteAndReportsDegradation() {
        try(var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);
                var mesh=Mesh.from(BuiltinMeshData.texturedQuad("near-lamp-pressure"))) {
                for(float intensity:new float[]{1e14f,1e38f}) {
                    var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
                    add(scene,mesh,shader,20,new Vector4f(0,0,0,1),BlendMode.OPAQUE);
                    scene.addLight(SceneLight.point(new Vector3f(.1f,.1f,-5.3f),new Vector3f(1),intensity,32));
                    scene.setLightVolumeHints(0,new LightVolumeHints(intensity==1e38f?1e38f:1,false,false));
                    var fog=VolumetricMediumGlTest.settings(FogMediumSettings.homogeneous(.0001f,new Vector3f(1),new Vector3f()),List.of());
                    var pipeline=VolumetricLightingGlTest.pipeline(new VolumetricGenerationGlTest.Size(),scene,fog,1);
                    try {
                        pipeline.build();pipeline.execute(new GlRenderDevice());
                        for(float value:pipeline.captureLinearHdrRgbaFloat()) {
                            assertTrue(Float.isFinite(value),"native fine-ray half output must remain finite");
                            assertTrue(value>=0&&value<=32768,"native output retains the declared integral cap");
                        }
                        var counters=pipeline.captureVolumetricCounters();
                        if(intensity==1e38f)assertTrue(counters.nonfiniteMedium()>0);
                        else {assertTrue(counters.limitedSource()>0);assertTrue(counters.limitedIntegral()>0);}
                    } finally {pipeline.close();}
                }
            }
            GlDebug.assertNoError("native fine-ray extreme source classification");
        }
    }

    @Test void alphaEndpointsTwoLayersAdditiveAndFogOffFollowAnalyticOwnDepthBlending() {
        try (var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try (var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);var mesh=Mesh.from(BuiltinMeshData.texturedQuad("fog-layer"))) {
                var bg=new Vector4f(.8f,.2f,.1f,1); var far=new Vector4f(.1f,.7f,.9f,.4f);
                for (boolean enabled:new boolean[]{false,true}) for (float a:new float[]{0,.25f,1}) {
                    var near=new Vector4f(.9f,.1f,.2f,a);var spark=new Vector4f(.4f,.2f,.7f,.35f);
                    for (AntiAliasingMode aa:new AntiAliasingMode[]{AntiAliasingMode.NONE,AntiAliasingMode.FXAA,AntiAliasingMode.TAA,AntiAliasingMode.MSAA}) {
                        Scene scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
                        add(scene,mesh,shader,10,bg,BlendMode.OPAQUE);
                        // Deliberately insert near first: the frozen alpha queue must still draw far first.
                        add(scene,mesh,shader,6,near,BlendMode.ALPHA);add(scene,mesh,shader,8,far,BlendMode.ALPHA);
                        add(scene,mesh,shader,3,spark,BlendMode.ADDITIVE);
                        add(scene,mesh,shader,12,new Vector4f(10,10,10,1),BlendMode.ALPHA); // hidden behind opaque depth
                        var pipeline=pipeline(scene,aa,4,enabled);
                        try {
                            pipeline.build();pipeline.execute(new GlRenderDevice());
                            assertCenter(pipeline.captureLinearHdrRgbaFloat(),additive(alpha(alpha(opaque(bg,10,enabled),far,8,enabled),near,6,enabled),spark,3,enabled));
                            if (enabled) {
                                var order=pipeline.graph().description().executionOrder();
                                assertTrue(order.indexOf(VolumetricPassBuilder.COMPOSITE_PASS)<order.indexOf(VolumetricPassBuilder.TRANSPARENT_PASS));
                                if (aa==AntiAliasingMode.MSAA) assertTrue(order.indexOf(VolumetricPassBuilder.TRANSPARENT_PASS)<order.indexOf(PostProcessTargets.HDR_RESOLVE_PASS));
                            }
                            GlDebug.assertNoError("alpha fog matrix "+aa+" alpha="+a);
                        } finally { pipeline.close(); }
                    }
                }
            }
        }
    }

    @Test void multisampleCompositeUsesEachOriginalDepthAndColorAtThinForegroundEdges() {
        try (var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try (var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);var mesh=Mesh.from(BuiltinMeshData.texturedQuad("fog-edge"))) {
                for (int samples:new int[]{2,4}) {
                    Scene scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
                    add(scene,mesh,shader,20,new Vector4f(.2f,.7f,.9f,1),BlendMode.OPAQUE);
                    // A slanted edge crosses sample locations in the center pixel column.
                    scene.add(MeshRenderer.of(mesh,material(shader,new Vector4f(.9f,.1f,.2f,1),BlendMode.OPAQUE),
                            Transform.at(-3.91f,0,-6).scale(8).rotationRadians(0,0,.035f)));
                    var pipeline=pipeline(scene,AntiAliasingMode.MSAA,samples,true);
                    try {
                        pipeline.build();AtomicInteger color=new AtomicInteger(),depth=new AtomicInteger(),targetDepth=new AtomicInteger(),sharedColor=new AtomicInteger();
                        pipeline.graph().addPass("FogSampleObserver").computeOnly().dependsOn(pipeline.finalPassName()).execute((res,cmd)-> {
                            var geometry=res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                            color.set(geometry.colorAttachment());depth.set(geometry.depthAttachment());
                            targetDepth.set(res.framebufferOfPass(VolumetricPassBuilder.COMPOSITE_PASS).depthAttachment());
                            sharedColor.set(res.framebufferOfPass(VolumetricPassBuilder.TRANSPARENT_PASS).colorAttachment());
                            assertEquals(res.framebufferOfPass(VolumetricPassBuilder.COMPOSITE_PASS).colorAttachment(),sharedColor.get());
                        });
                        pipeline.execute(new GlRenderDevice());assertEquals(0,targetDepth.get());
                        float[] original=readSamples(color.get(),depth.get(),samples),actual=pipeline.captureLinearHdrRgbaFloat();
                        int mixed=0;
                        for(int pixel=0;pixel<33*17;pixel++) {
                            double[] expected=new double[3];double first=-1;boolean differs=false;
                            for(int s=0;s<samples;s++) {
                                int o=(pixel*samples+s)*4;
                                double d=.125+original[o+3]*(64-.125);if (s==0) first=d; else differs |= Math.abs(d-first)>1;
                                double[] fogged=opaque(new Vector4f(original[o],original[o+1],original[o+2],1),Math.min(d,32),true);
                                for(int c=0;c<3;c++)expected[c]+=fogged[c]/samples;
                            }
                            if (differs) mixed++;
                            for(int c=0;c<3;c++)assertEquals(expected[c],actual[pixel*4+c],.004,"sample resolve pixel="+pixel+" c="+c);
                        }
                        assertTrue(mixed>4,"fixture must contain foreground/background samples within one pixel");
                        GlDebug.assertNoError("MSAA "+samples+" per-depth fog");
                    } finally { pipeline.close(); }
                }
            }
        }
    }
    private static float[] readSamples(int color,int depth,int samples) {
        String source="""
                #version 460 core
                layout(local_size_x=64) in;
                layout(binding=0) uniform sampler2DMS color; layout(binding=1) uniform sampler2DMS depth;
                layout(std430,binding=0) writeonly buffer Result {vec4 data[];}; uniform int samples;
                void main(){uint i=gl_GlobalInvocationID.x;if(i>=uint(33*17*samples))return;
                  int pixel=int(i)/samples,s=int(i)%samples;ivec2 p=ivec2(pixel%33,pixel/33);
                  data[i]=vec4(texelFetch(color,p,s).rgb,texelFetch(depth,p,s).r);}
                """;
        int n=33*17*samples*4;
        try(var program=ShaderProgram.fromComputeSource(source);var buffer=GlBuffer.shaderStorageBuffer(GL_DYNAMIC_READ).allocate(n*4L)) {
            var device=new GlRenderDevice();var cmd=device.createCommandBuffer();
            cmd.bindShader(program).bindTextureMultisample(0,color).bindTextureMultisample(1,depth)
                    .bindStorageBuffer(0,buffer,0,n*4L).setUniformInt(program,"samples",samples).dispatchCompute((n/4+63)/64,1,1)
                    .memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            device.execute(cmd);var values=BufferUtils.createFloatBuffer(n);glGetNamedBufferSubData(buffer.id(),0,values);
            float[] result=new float[n];values.get(result);return result;
        }
    }

    @Test void customShaderContractRejectsAtBuildAndExplicitOptOutPreservesItsBlend() {
        try (var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try (var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT.replace("// HAIKALAT_VOLUME_FOG_CONTRACT",""));
                 var mesh=Mesh.from(BuiltinMeshData.texturedQuad("custom-transparency"))) {
                for (boolean optOut:new boolean[]{false,true}) {
                    var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));
                    var mat=Material.builder(shader).blendMode(BlendMode.ALPHA).volumetricFogOptOut(optOut)
                            .setVec4("uColor",new Vector4f(.8f,.1f,.2f,.5f)).setInt("uAdditive",0).build();
                    scene.add(MeshRenderer.of(mesh,mat,Transform.at(0,0,-6).scale(32)));
                    var pipeline=pipeline(scene,AntiAliasingMode.NONE,1,true);
                    try {
                        if (!optOut) assertThrows(IllegalArgumentException.class,pipeline::build);
                        else { pipeline.build();pipeline.execute(new GlRenderDevice()); }
                    } finally { pipeline.close(); }
                }
                var pipeline=pipeline(new Scene(VolumetricMediumGlTest.camera(true,33f/17)),AntiAliasingMode.NONE,1,true);
                try {
                    pipeline.hdrVfx((res,cmd)->{});
                    assertTrue(assertThrows(IllegalStateException.class,pipeline::build).getMessage().contains("hdrVfxWithFog"));
                } finally { pipeline.close(); }
                GlDebug.assertNoError("custom fog contracts");
            }
        }
    }

    @Test void embeddedMsaaFogRestoresSampleShadingAndKeepsHostResourcesAlive() {
        try (var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try(var host=com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptor(
                    com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(33,17).samples(4)
                            .colorTextureMultisample(RenderFormat.RGBA8).depthTextureMultisample().build());
                var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);var mesh=Mesh.from(BuiltinMeshData.texturedQuad("embedded-fog"))) {
                var target=com.kaleblangley.haikalat.core.presentation.PresentationTarget.builder(33,17).framebuffer(host.id()).samples(4)
                        .color(new com.kaleblangley.haikalat.core.presentation.ExternalAttachment(host.colorAttachment(),
                                com.kaleblangley.haikalat.core.presentation.AttachmentRole.COLOR,RenderFormat.RGBA8,33,17,4,
                                com.kaleblangley.haikalat.core.material.ResourceOwnership.BORROWED))
                        .depth(new com.kaleblangley.haikalat.core.presentation.ExternalAttachment(host.depthAttachment(),
                                com.kaleblangley.haikalat.core.presentation.AttachmentRole.DEPTH,RenderFormat.DEPTH_COMPONENT24,33,17,4,
                                com.kaleblangley.haikalat.core.material.ResourceOwnership.BORROWED)).build();
                var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));add(scene,mesh,shader,6,new Vector4f(.7f,.2f,.1f,1),BlendMode.OPAQUE);
                var pipeline=new RenderPipeline(target,scene,null,RenderSettings.builder()
                        .toneMappingMode(ToneMappingMode.ACES).antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4).vsync(false).build()).volumetricFog(fog());
                int fbo=host.id(),color=host.colorAttachment();
                try {
                    pipeline.build();glClearNamedFramebufferfv(fbo,GL_COLOR,0,new float[]{.05f,.1f,.2f,1});
                    glClearNamedFramebufferfv(fbo,GL_DEPTH,0,new float[]{1});
                    glEnable(GL_SAMPLE_SHADING);glMinSampleShading(.375f);
                    glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT,GL_FUNC_SUBTRACT);
                    glBindFramebuffer(GL_FRAMEBUFFER,0);glViewport(2,3,19,13);
                    pipeline.execute(new GlRenderDevice(),VolumetricMediumGlTest.camera(true,33f/17),target,1f/60);
                    assertTrue(glIsEnabled(GL_SAMPLE_SHADING));assertEquals(.375f,glGetFloat(GL_MIN_SAMPLE_SHADING_VALUE));
                    assertEquals(GL_FUNC_REVERSE_SUBTRACT,glGetInteger(GL_BLEND_EQUATION_RGB));
                    assertEquals(GL_FUNC_SUBTRACT,glGetInteger(GL_BLEND_EQUATION_ALPHA));
                    assertEquals(0,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
                    assertEquals(0,glGetInteger(GL_READ_FRAMEBUFFER_BINDING));
                    var view=BufferUtils.createIntBuffer(4);glGetIntegerv(GL_VIEWPORT,view);
                    assertArrayEquals(new int[]{2,3,19,13},new int[]{view.get(0),view.get(1),view.get(2),view.get(3)});
                    assertCenter(pipeline.captureLinearHdrRgbaFloat(),opaque(new Vector4f(.7f,.2f,.1f,1),6,true));
                    GlDebug.assertNoError("embedded per-sample fog host");
                } finally { pipeline.close();glDisable(GL_SAMPLE_SHADING);glMinSampleShading(0);glBlendEquation(GL_FUNC_ADD); }
                assertTrue(glIsFramebuffer(fbo));assertTrue(glIsTexture(color));
            }
        }
    }

    @Test void builtinHdrSmokeAndSparksUseOwnDepthAndSeparateSoftParticleDepth() {
        try (var window=VolumetricGenerationGlTest.window()) {
            window.bindContext();GL.createCapabilities();
            try (var shader=ShaderProgram.fromSources(VERTEX,FRAGMENT);var mesh=Mesh.from(BuiltinMeshData.texturedQuad("vfx-background"));var renderer=new VfxRenderer(true)) {
                Vector4f bg=new Vector4f(.8f,.2f,.1f,1),smoke=new Vector4f(.2f,.6f,.7f,.3f),spark=new Vector4f(.5f,.2f,.1f,.4f);
                var alpha=VfxMaterial.builder("smoke").softParticleDistance(2).build();var additive=VfxMaterial.builder("spark").blendMode(BlendMode.ADDITIVE).build();
                var particle=new EffectSnapshot.ParticleSprite(1,new Vector3f(0,0,-6),32,0,smoke,36);
                var flash=new EffectSnapshot.MeshInstance(2,BuiltinMeshData.texturedQuad("spark"),new org.joml.Matrix4f().translation(0,0,-3).scale(32),spark,9);
                var snapshot=new EffectSnapshot(List.of(particle),List.of(),List.of(),List.of(flash),List.of(particle,flash),new VfxVisualSet(alpha,alpha,alpha,additive));
                for (AntiAliasingMode aa:new AntiAliasingMode[]{AntiAliasingMode.NONE,AntiAliasingMode.TAA,AntiAliasingMode.MSAA}) {
                    var scene=new Scene(VolumetricMediumGlTest.camera(true,33f/17));add(scene,mesh,shader,10,bg,BlendMode.OPAQUE);
                    var pipeline=pipeline(scene,aa,4,true);
                    try {
                        pipeline.hdrVfxWithFog((res,cmd,camera,fog)-> {
                            int soft=res.colorAttachment(fog.softParticleDepthName());assertNotEquals(soft,res.currentTarget().depthAttachment());
                            renderer.record(cmd,snapshot,camera.projection(),camera.view(),soft,33,17,fog);
                        });
                        pipeline.build();pipeline.execute(new GlRenderDevice());
                        assertCenter(pipeline.captureLinearHdrRgbaFloat(),additive(alpha(opaque(bg,10,true),smoke,6,true),spark,3,true));
                        GlDebug.assertNoError("HDR VFX fog "+aa);
                    } finally { pipeline.close(); }
                }
            }
        }
    }
}
