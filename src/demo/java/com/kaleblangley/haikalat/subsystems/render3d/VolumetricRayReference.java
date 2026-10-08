package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import static org.lwjgl.opengl.GL46.*;

/** Demo-only, explicitly requested FP32 full-ray reference. Never installed by production. */
public final class VolumetricRayReference implements AutoCloseable {
    private final RenderPipeline pipeline;
    private final ShaderProgram shader;
    private final GlBuffer output;
    private final int width,height,stride,rows,planes;
    private final long bytes;
    private final long imageBytes;
    private int probeX=-1,probeY=-1;
    private final ShaderProgram fullSource,compareSource,copyCandidate;
    private final com.kaleblangley.haikalat.backend.texture.Texture3D referenceSource;
    private final GlBuffer sourceDifference;
    private final ByteBuffer zeroDifference=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private int[] requestedSteps=new int[0];
    private int firstSlot;

    public VolumetricRayReference(RenderPipeline pipeline,int stride,int rows,int maximumReferences) {
        if(!Boolean.getBoolean("haikalat.glReadback"))throw new IllegalStateException("ray reference requires explicit diagnostic readback");
        this.pipeline=pipeline;this.stride=stride;this.rows=rows;
        width=(pipeline.graph().width()+stride-1)/stride;height=(pipeline.graph().height()+stride-1)/stride;
        planes=4+2*maximumReferences;imageBytes=Math.multiplyExact((long)width*height,16L*planes);
        bytes=Math.addExact(imageBytes,32L*16);
        ShaderProgram candidate=null,sourceProgram=null,compareProgram=null,copyProgram=null;GlBuffer storage=null,difference=null;
        com.kaleblangley.haikalat.backend.texture.Texture3D sourceTexture=null;
        try {
            // Production query is used ONLY to collect the candidate. The reference implements
            // its own ray coordinates, medium evaluation, boundaries and FP32 quadrature.
            String source="#version 460 core\n#define VOLUME_FULL_SCAN\n"
                    +VolumetricPassBuilder.resource("volume-common.glsl")+'\n'
                    +VolumetricPassBuilder.resource("volume-query.glsl")+'\n'
                    +VolumetricPassBuilder.resource("volume-lighting.glsl")+'\n'
                    +VolumetricPassBuilder.resource("volume-noise.glsl")+'\n'
                    +VolumetricPassBuilder.resource("volume-medium.glsl")+'\n'
                    +VolumetricPassBuilder.resource("volume-fine.glsl")+'\n'+referenceSource();
            candidate=ShaderProgram.fromComputeSource(source);
            copyProgram=ShaderProgram.fromComputeSource("""
                    #version 460 core
                    layout(local_size_x=8,local_size_y=8)in;
                    layout(binding=0)uniform sampler2D uNativeOpaque;
                    layout(std430,binding=8)writeonly buffer NativeCandidateOutput{vec4 pixels[];};
                    uniform int uWidth,uHeight,uStride;
                    void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(p.x>=uWidth||p.y>=uHeight)return;
                        ivec2 q=min(p*uStride+uStride/2,textureSize(uNativeOpaque,0)-1);
                        pixels[p.x+p.y*uWidth]=vec4(texelFetch(uNativeOpaque,q,0).rgb,1);}
                    """);
            storage=new GlBuffer(GL_SHADER_STORAGE_BUFFER,GL_DYNAMIC_READ);
            storage.allocateStorage(bytes,GL_DYNAMIC_STORAGE_BIT);
            shader=candidate;output=storage;
            sourceProgram=ShaderProgram.fromComputeSource(VolumetricPassBuilder.shaderSource("source.comp",true));
            compareProgram=ShaderProgram.fromComputeSource("""
                    #version 460 core
                    layout(local_size_x=4,local_size_y=4,local_size_z=4)in;
                    layout(binding=2)uniform sampler3D uActual;
                    layout(binding=3)uniform sampler3D uReference;
                    layout(std430,binding=9)buffer SourceDifference{uint maximumError,mismatched,compared,nonfinite;};
                    void main(){ivec3 p=ivec3(gl_GlobalInvocationID);if(any(greaterThanEqual(p,textureSize(uActual,0))))return;
                        vec3 a=texelFetch(uActual,p,0).rgb,b=texelFetch(uReference,p,0).rgb;
                        if(any(isnan(a))||any(isinf(a))||any(isnan(b))||any(isinf(b))){atomicAdd(nonfinite,1u);return;}
                        vec3 e=abs(a-b)/max(vec3(1),abs(b));float m=max(max(e.x,e.y),e.z);
                        atomicMax(maximumError,floatBitsToUint(m));if(m>.002)atomicAdd(mismatched,1u);atomicAdd(compared,1u);}
                    """);
            var volume=pipeline.activeGenerationForTest().volumetric.resources().source();
            sourceTexture=com.kaleblangley.haikalat.backend.texture.Texture3D.create(volume.width(),volume.height(),volume.depth(),volume.format());
            difference=new GlBuffer(GL_SHADER_STORAGE_BUFFER,GL_DYNAMIC_READ);difference.allocateStorage(16,GL_DYNAMIC_STORAGE_BIT);
            fullSource=sourceProgram;compareSource=compareProgram;copyCandidate=copyProgram;
            referenceSource=sourceTexture;sourceDifference=difference;
            pipeline.activeGenerationForTest().volumetric.diagnosticCompositeRecorder(this::recordNativeCandidate);
            pipeline.graph().addPass("VolumetricReferenceOnly").computeOnly()
                    .dependsOn(VolumetricPassBuilder.COMPOSITE_PASS).dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .borrowsExternalStorage("volume.referenceOutput","volume.prefix","volume.filteredSource","volume.currentSource",
                            "volume.parameters","volume.localVolumes","lighting.lightTable","shadows.allocatedAtlases")
                    .execute(this::record);
        } catch(RuntimeException|Error failure) {
            pipeline.activeGenerationForTest().volumetric.diagnosticCompositeRecorder(null);
            for (AutoCloseable resource:new AutoCloseable[]{difference,sourceTexture,compareProgram,sourceProgram,storage,copyProgram,candidate}) {
                if(resource!=null)try{resource.close();}catch(Exception|Error cleanup){failure.addSuppressed(cleanup);}
            }
            throw failure;
        }
    }

    private void recordNativeCandidate(PassResources inputs,CommandBuffer cmd) {
        if(requestedSteps.length==0)return;
        // Read the real compositor attachment before transparency reuses it.
        cmd.memoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT)
                .bindShader(copyCandidate).bindTexture(0,inputs.colorAttachment(VolumetricPassBuilder.COMPOSITE_COLOR))
                .bindStorageBuffer(8,output,0,bytes).setUniformInt(copyCandidate,"uWidth",width)
                .setUniformInt(copyCandidate,"uHeight",height).setUniformInt(copyCandidate,"uStride",stride)
                .dispatchCompute((width+7)/8,(height+7)/8,1).memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private static String referenceSource() {
        try(var in=VolumetricRayReference.class.getResourceAsStream("/shaders/reference/volumetric-raymarch.comp")) {
            if(in==null)throw new IllegalStateException("missing demo ray reference");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException e){throw new IllegalStateException(e);}
    }

    public void request(int firstSlot,int... steps) {
        if(firstSlot<0||3+2*(firstSlot+steps.length)>planes-1)throw new IllegalArgumentException("reference slot out of range");
        for(int step:steps)if(step<2||step>8192)throw new IllegalArgumentException("reference step out of range");
        this.firstSlot=firstSlot;requestedSteps=steps.clone();
    }

    /** Explicit diagnostic only: compare fine-Z rays along the four production XY columns. */
    public void probe(int nativeX,int nativeY) {
        if(nativeX<0||nativeY<0||nativeX>=pipeline.graph().width()||nativeY>=pipeline.graph().height()
                ||nativeX%stride!=stride/2||nativeY%stride!=stride/2)
            throw new IllegalArgumentException("probe must be a captured native pixel");
        probeX=nativeX;probeY=nativeY;
    }

    private void record(PassResources resources,CommandBuffer cmd) {
        if(requestedSteps.length==0)return;
        var volume=pipeline.activeGenerationForTest().volumetric;
        var geometry=resources.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
        // Same physical production samples, full immutable table, after all current shadow maps.
        // This diagnoses candidate/slot/order errors separately from coarse-grid image reconstruction.
        volume.recordReferenceSource(resources,cmd,fullSource,referenceSource);
        cmd.uploadBufferRegion(sourceDifference,0,zeroDifference).bindStorageBuffer(9,sourceDifference,0,16)
                .bindShader(compareSource).bindTexture3D(2,volume.resources().source()).bindTexture3D(3,referenceSource)
                .dispatchCompute((referenceSource.width()+3)/4,(referenceSource.height()+3)/4,(referenceSource.depth()+3)/4)
                .memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        cmd.bindShader(shader);volume.bindLightingInputs(resources,cmd,shader);volume.bindQuery(cmd,shader);
        // A full ray shader may optimize away the cluster UBO; binding the shared table
        // explicitly avoids the forward binder's material-capability shortcut.
        pipeline.activeGenerationForTest().clusteredResources.storage().bindLightTableRead(cmd);
        cmd.setUniformInt(shader,"uVolumeDiagnosticsEnabled",0)
                .bindStorageBuffer(6,volume.resources().volumes(),0,VolumetricResources.VOLUMES_BYTES)
                .bindStorageBuffer(8,output,0,bytes)
                .bindStorageBuffer(7,volume.resources().diagnostics(),0,VolumetricResources.DIAGNOSTICS_BYTES)
                .bindTexture(0,geometry.depthAttachment()).bindTexture(1,geometry.colorAttachment())
                .setUniformInt(shader,"uReferenceStride",stride).setUniformInt(shader,"uReferenceWidth",width)
                .setUniformInt(shader,"uReferenceHeight",height)
                .setUniformInt(shader,"uReferenceCountPlane",planes-1)
                .setUniformInt(shader,"uReferenceProbeBase",planes*width*height)
                .setUniformInt(shader,"uReferenceProbeX",probeX).setUniformInt(shader,"uReferenceProbeY",probeY)
                .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT);
        for(int index=0;index<requestedSteps.length;index++) {
            cmd.setUniformInt(shader,"uReferenceSteps",requestedSteps[index])
                    .setUniformInt(shader,"uReferencePlane",3+2*(firstSlot+index));
            for(int y=0;y<height;y+=rows)cmd.setUniformInt(shader,"uReferenceRow",y)
                    .setUniformInt(shader,"uReferenceRows",Math.min(rows,height-y))
                    .dispatchCompute((width+7)/8,(Math.min(rows,height-y)+7)/8,1);
        }
        cmd.memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        requestedSteps=new int[0];
    }

    public float[] capturePlane(int plane) {
        if(plane<0||plane>=planes)throw new IllegalArgumentException("reference plane out of range");
        var data=ByteBuffer.allocateDirect(Math.multiplyExact(width*height,16)).order(ByteOrder.nativeOrder());
        output.readSnapshot((long)plane*width*height*16,data);float[] result=new float[width*height*4];data.asFloatBuffer().get(result);return result;
    }
    public int width(){return width;}public int height(){return height;}public long storageBytes(){return bytes+referenceSource.estimatedBytes()+16;}
    public record SourceComparison(float maximumNormalizedError,long mismatchedVoxels,long comparedVoxels,long nonfiniteVoxels) { }
    public SourceComparison captureSourceComparison() {
        var data=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());sourceDifference.readSnapshot(0,data);
        return new SourceComparison(Float.intBitsToFloat(data.getInt(0)),Integer.toUnsignedLong(data.getInt(4)),Integer.toUnsignedLong(data.getInt(8)),Integer.toUnsignedLong(data.getInt(12)));
    }
    public float[] captureEvaluationCounts(){return capturePlane(planes-1);}
    public java.util.Map<String,Object> captureProbe() {
        if(probeX<0)throw new IllegalStateException("no reference probe requested");
        var data=ByteBuffer.allocateDirect(32*16).order(ByteOrder.nativeOrder());output.readSnapshot(imageBytes,data);
        float[] values=new float[128];data.asFloatBuffer().get(values);
        var rows=new java.util.ArrayList<float[]>();
        for(int i=0;i<32;i++)rows.add(java.util.Arrays.copyOfRange(values,i*4,i*4+4));
        return java.util.Map.of("nativePixel",java.util.List.of(probeX,probeY),
                "rows",rows,"layout","worldEndpoint+depth; candidate ST; independent ray ST; fine-Z coarse-XY ST; four fine columns; four column pixels+opaque depths; four production columns; four production interval-start prefixes; four independent interval-start prefixes; four independent last-segment ST (-1 when prefix T<=1e-8); four interval-start/sample/endpoint/cosine rows");
    }
    @Override public void close(){
        pipeline.activeGenerationForTest().volumetric.diagnosticCompositeRecorder(null);
        RuntimeException failure=null;
        for(AutoCloseable resource:new AutoCloseable[]{sourceDifference,referenceSource,compareSource,fullSource,output,shader,copyCandidate}) {
            try{resource.close();}catch(Exception cleanup){
                if(failure==null)failure=new IllegalStateException("cannot close ray reference",cleanup);
                else failure.addSuppressed(cleanup);
            }
        }
        if(failure!=null)throw failure;
    }
}
