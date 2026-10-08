package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.BufferUploadTarget;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.PassResources;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL46.*;

/** Sole owner of froxel storage/state and recorder of all volume graph passes. */
final class VolumetricPassBuilder implements AutoCloseable {
    static final String MEDIUM_PASS = "VolumetricMedium";
    static final String LIGHTING_PASS = "VolumetricLighting";
    static final String INTEGRATE_PASS = "VolumetricIntegrate";
    static final String TEMPORAL_PASS = "VolumetricTemporal";
    static final String REACTIVE_PASS = "VolumetricReactive";
    static final String REACTIVE_TEXTURE = "volume.fogReactive";
    static final String COMPOSITE_PASS = "VolumetricOpaqueComposite";
    static final String SURFACE_GUIDE_PASS = "VolumetricSurfaceGuide";
    static final String TRANSPARENT_PASS = "VolumetricTransparency";
    static final String COMPOSITE_COLOR = "volume.opaqueColor";
    static final String OPAQUE_REACTIVE = "volume.opaqueReactive";
    static final String SCENE_COLOR = "volume.sceneColor";
    static final int PARAMETERS_USED_BYTES = 1232;
    static final int VOLUME_STRIDE = 96;
    private static final long STORAGE_BUDGET = 128L*1024*1024;
    private final long generationId;
    private final VolumetricFogSettings.Quality quality;
    private final boolean emissionAllocated;
    private VolumetricResources resources;
    private final VolumetricFrameState frameState = new VolumetricFrameState();
    private final VolumetricHistoryPlan historyPlan = new VolumetricHistoryPlan();
    private ShaderProgram medium, lighting, integrate, temporal;
    private RenderGraph.PassExecutor diagnosticSourceRecorder;
    private java.util.function.Consumer<CommandBuffer> diagnosticReactiveRecorder;
    private Texture3D filteredSource;
    private long temporalSequence = -1;
    private ShaderProgram composite;
    private ShaderProgram reactive;
    private ShaderProgram surfaceGuide;
    private long surfaceGuideSequence=-1;
    private int opaqueDepthTexture;
    private int diagnosticOmittedLight=-1;
    private RenderGraph.PassExecutor diagnosticCompositeRecorder;
    private boolean reactiveRecording;
    private final java.util.List<java.util.function.Consumer<CommandBuffer>> reactiveRecorders=new java.util.ArrayList<>();
    private ScreenQuad compositeQuad;
    private ShaderProgram softDepthCopy;
    private String softParticleDepthName;
    private int colorSamples=1;
    private boolean colorVfx;

    void configureColorBudget(PipelineTopology topology) {
        colorSamples=topology.sampleCount(); colorVfx=topology.hdrVfx();
        validateExtentBudget(topology.width(),topology.height());
    }
    void validateExtentBudget(int width,int height) {
        long volume=VolumetricResources.estimatedBytes(columns(width),columns(height),quality.depthSlices(),emissionAllocated)
                -VolumetricResources.LIGHT_HINTS_BYTES+resources.lightHintsBytes();
        long fullPixels=Math.multiplyExact((long)width,height);
        // Single-sample composition also writes an R8 opaque mask; replay copies and max-merges it.
        long extraColor=Math.multiplyExact(fullPixels,8L*colorSamples+1+(colorVfx && colorSamples==1 ? 4 : 0));
        long total=Math.addExact(volume,extraColor);
        long limit=(colorSamples==1 ? 192L : 384L)*1024*1024;
        if (total>limit) throw new IllegalArgumentException("volume/color storage "+total+" exceeds "+limit+" bytes at "+width+"x"+height);
    }
    private final java.util.Map<ShaderProgram,ShaderProgram> transparentVariants = new java.util.IdentityHashMap<>();
    private final ByteBuffer parameters = ByteBuffer.allocateDirect(PARAMETERS_USED_BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer volumes = ByteBuffer.allocateDirect(8*VOLUME_STRIDE).order(ByteOrder.nativeOrder());
    private final ByteBuffer zeroDiagnostics = ByteBuffer.allocateDirect(VolumetricResources.DIAGNOSTICS_BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer lightHints;
    private final ParameterUploadCache volumeUploads = new ParameterUploadCache(8*VOLUME_STRIDE);
    private final ParameterUploadCache dirtyUploads = new ParameterUploadCache(VolumetricResources.DIRTY_DOMAINS_BYTES);
    private final ParameterUploadCache hintUploads;
    private ClusteredLightingBinder borrowedLighting;
    private LightingInputs lightingInputs;
    private PassResources fragmentInputs;
    private long integratedSequence = -1;
    private boolean closed;

    record OutputView(long generationId, long frameSequence, VolumetricGrid grid,
                      Texture3D prefix, Texture3D source, boolean available) { }
    @FunctionalInterface interface LightingInputs {
        void record(PassResources resources, CommandBuffer commands, ShaderProgram shader);
    }

    VolumetricPassBuilder(long generationId, int width, int height, VolumetricFogSettings settings) {
        this(generationId, width, height, settings, 256);
    }

    VolumetricPassBuilder(long generationId, int width, int height, VolumetricFogSettings settings, int lightCapacity) {
        if (!settings.enabled()) throw new IllegalArgumentException("disabled fog does not allocate a builder");
        this.generationId = generationId; quality = settings.quality(); emissionAllocated = settings.hasEmission();
        lightHints = ByteBuffer.allocateDirect(Math.multiplyExact(lightCapacity, LightVolumePacker.RECORD_BYTES)).order(ByteOrder.nativeOrder());
        hintUploads = new ParameterUploadCache(lightHints.capacity());
        try {
            resources = new VolumetricResources(columns(width), columns(height), quality.depthSlices(),
                    emissionAllocated, STORAGE_BUDGET, STORAGE_BUDGET*2, lightCapacity);
            medium = compile("medium.comp", 0);
            lighting = compile("source.comp", 1);
            integrate = compile("integrate.comp", 2);
            temporal = compile("temporal.comp", 4);
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private int columns(int size) { return Math.toIntExact(((long)size+quality.tilePixels()-1)/quality.tilePixels()); }

    private static ShaderProgram compile(String name, int ordinal) {
        if (Integer.getInteger("haikalat.test.failVolumeCompilation", -1) == ordinal) {
            throw new IllegalStateException("injected volume shader compilation failure " + ordinal);
        }
        return ShaderProgram.fromComputeSource(shaderSource(name, name.equals("source.comp")
                && Boolean.getBoolean("haikalat.internal.benchmark.fullScanReference")));
    }

    static String shaderSource(String name) {
        return shaderSource(name, false);
    }
    static String shaderSource(String name, boolean fullScanReference) {
        return "#version 460 core\n" + (fullScanReference ? "#define VOLUME_FULL_SCAN\n" : "")
                + resource("volume-common.glsl") + "\n"
                + (name.equals("medium.comp") || name.equals("temporal.comp") || name.equals("source.comp") ? resource("volume-noise.glsl")+"\n" : "")
                + (name.equals("source.comp") || name.equals("surface-guide.comp") ? resource("volume-lighting.glsl") + "\n" : "")
                + (name.equals("source.comp") ? resource("volume-medium.glsl")+"\n" : "") + resource(name);
    }
    static String resource(String name) {
        String path = "/shaders/render3d/volumetric/" + name;
        try (var stream = VolumetricPassBuilder.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("missing volume shader " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("cannot read volume shader " + path, failure); }
    }

    void addPasses(RenderGraph graph, ClusteredLightingBinder lightingBinder, LightingInputs inputs) {
        borrowedLighting = lightingBinder; lightingInputs = inputs;
        graph.addPass(MEDIUM_PASS).computeOnly()
                .borrowsExternalStorage("volume.medium", "volume.emission", "volume.parameters", "volume.localVolumes")
                .execute((res, cmd) -> recordMedium(cmd));
        graph.addPass(LIGHTING_PASS).computeOnly().dependsOn(MEDIUM_PASS)
                .dependsOn(graph.hasPass(ClusteredLightingPassBuilder.CLUSTER_ASSIGN_PASS)
                        ? ClusteredLightingPassBuilder.CLUSTER_ASSIGN_PASS : ClusteredLightingPassBuilder.LIGHT_UPLOAD_PASS)
                .borrowsExternalStorage("volume.medium", "volume.emission", "volume.currentSource", "volume.lightHints",
                        "lighting.lightTable", "lighting.clusterHeaders", "lighting.clusterIndices", "shadows.allocatedAtlases")
                .execute((res, cmd) -> {
                    if (diagnosticSourceRecorder == null) recordSource(res, cmd, lighting, resources.source());
                    else diagnosticSourceRecorder.execute(res, cmd);
                });
        graph.addPass(TEMPORAL_PASS).computeOnly().dependsOn(LIGHTING_PASS)
                .borrowsExternalStorage("volume.currentSource", "volume.historyRead", "volume.historyWrite", "volume.reject", "volume.dirtyDomains", "volume.localVolumes", "volume.medium", "volume.lightOffReactive")
                .execute((res,cmd)->recordTemporal(cmd));
        graph.addPass(INTEGRATE_PASS).computeOnly().dependsOn(TEMPORAL_PASS)
                .borrowsExternalStorage("volume.filteredSource", "volume.prefix", "volume.reject", "volume.historyRead", "volume.reactivePrefix", "volume.lightOffReactive")
                .execute((res, cmd) -> recordIntegrate(cmd));
    }

    void addColorPasses(RenderGraph graph, Scene scene, PipelineTopology topology,
                        RenderGraph.PassExecutor transparentExecutor) {
        for (MeshRenderer renderer : scene.renderers()) {
            var material=renderer.material().material();
            if (material.blendMode() != com.kaleblangley.haikalat.core.BlendMode.OPAQUE
                    && !material.volumetricFogOptOut() && !transparentVariants.containsKey(material.shader())) {
                transparentVariants.put(material.shader(), material.shader().withFragmentPrelude(
                        VolumetricFogView.SHADER_CONTRACT, VolumetricFogView.fragmentPrelude()));
            }
        }
        boolean ms=topology.sampleCount()>1;
        if (Integer.getInteger("haikalat.test.failVolumeCompilation",-1)==3)
            throw new IllegalStateException("injected fog composite compilation failure");
        composite=ShaderProgram.fromSources(readAbsolute("/shaders/postprocess/screen-quad.vert"),
                "#version 460 core\n"+(ms ? "#define VOLUME_MSAA\n" : "")
                        +resource("volume-common.glsl")+"\n"+resource("volume-query.glsl")+"\n"
                        +(ms ? "" : resource("volume-reactive.glsl")+"\n")
                        +resource("volume-lighting.glsl")+"\n"+resource("volume-noise.glsl")+"\n"
                        +resource("volume-medium.glsl")+"\n"+resource("volume-fine.glsl")+"\n"
                        +resource("composite.frag"));
        compositeQuad=new ScreenQuad();
        surfaceGuide=compile("surface-guide.comp",5);
        var guide=graph.addPass(SURFACE_GUIDE_PASS).computeOnly().dependsOn(INTEGRATE_PASS)
                .dependsOn(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS)
                .borrowsExternalStorage("volume.prefix","scene.depth","volume.lightHints","lighting.lightTable","lighting.clusterParameters");
        if(ms)guide.dependsOn(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_SURFACE_RESOLVE_PASS);
        guide.execute((res,cmd)->recordSurfaceGuide(res,cmd,ms));
        reactive=ShaderProgram.fromSources(readAbsolute("/shaders/postprocess/screen-quad.vert"),
                "#version 460 core\n"
                        +resource("volume-common.glsl")+"\n"+resource("volume-query.glsl")+"\n"
                        +resource("volume-reactive.glsl")+"\n"+resource("volume-noise.glsl")+"\n"
                        +resource("volume-native-reactive.glsl")+"\n"+resource("reactive.frag"));
        softParticleDepthName=ms ? com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_DEPTH : "volume.softParticleDepth";
        if (topology.hdrVfx() && !ms) {
            softDepthCopy=ShaderProgram.fromSources(readAbsolute("/shaders/postprocess/screen-quad.vert"),
                    "#version 460 core\nlayout(binding=0) uniform sampler2D uDepth; layout(location=0) out float depth;"
                            +"void main(){depth=texelFetch(uDepth,ivec2(gl_FragCoord.xy),0).r;}");
            graph.addPass("VolumetricSoftDepthCopy").createColor(softParticleDepthName,RenderFormat.R32F).noClear()
                    .dependsOn(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_SURFACE_PASS)
                    .execute((res,cmd)->cmd.bindShader(softDepthCopy).enableDepthTest(false).enableBlend(false)
                            .bindTexture(0,res.depthAttachment(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_DEPTH))
                            .bindVertexArray(compositeQuad.id()).drawArrays(GL_TRIANGLES,0,6));
        }
        RenderGraph.PassBuilder pass=graph.addPass(COMPOSITE_PASS);
        if (ms) pass.createColorsMS(java.util.List.of(COMPOSITE_COLOR),java.util.List.of(RenderFormat.RGBA16F),topology.sampleCount());
        else pass.createColors(java.util.List.of(COMPOSITE_COLOR,OPAQUE_REACTIVE),java.util.List.of(RenderFormat.RGBA16F,RenderFormat.R8));
        pass.noClear().dependsOn(SURFACE_GUIDE_PASS).dependsOn(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS)
                .borrowsExternalStorage("volume.prefix","volume.filteredSource","volume.currentSource","volume.localVolumes","volume.lightHints",
                        "volume.dirtyDomains","lighting.lightTable","lighting.clusterHeaders","lighting.clusterIndices","shadows.allocatedAtlases")
                .execute((res,cmd)->recordComposite(res,cmd,ms));
        RenderGraph.PassBuilder transparent=graph.addPass(TRANSPARENT_PASS)
                .shareColorTextures(java.util.List.of(SCENE_COLOR),java.util.List.of(RenderFormat.RGBA16F),COMPOSITE_PASS,topology.sampleCount())
                .shareDepthTexture(ms ? com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_DEPTH_MS
                                : com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_DEPTH,
                        com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_SURFACE_PASS)
                .dependsOn(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_SURFACE_PASS)
                .noClear().borrowsExternalStorage("volume.prefix","volume.filteredSource","volume.currentSource","volume.localVolumes",
                        "volume.lightHints","volume.diagnostics","volume.dirtyDomains","lighting.lightTable","shadows.allocatedAtlases");
        if (topology.hdrVfx()) transparent.dependsOn(ms
                ? com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_SURFACE_RESOLVE_PASS : "VolumetricSoftDepthCopy");
        transparent.execute(transparentExecutor);
    }
    void addReactivePass(RenderGraph graph,PipelineTopology topology,RenderGraph.PassExecutor executor) {
        if (topology.sceneBuffers().requires(SceneBufferChannel.REACTIVE)) return;
        var pass=graph.addPass(REACTIVE_PASS).dependsOn(TRANSPARENT_PASS)
                .borrowsExternalStorage("volume.reject","volume.historyRead","volume.filteredSource","volume.prefix","volume.reactivePrefix","volume.lightOffReactive","volume.localVolumes","volume.dirtyDomains");
        if (topology.sampleCount()==1) pass.shareColorAttachments(java.util.List.of(REACTIVE_TEXTURE),
                java.util.List.of(RenderFormat.R8),COMPOSITE_PASS,java.util.List.of(1),1).noClear();
        else pass.createColor(REACTIVE_TEXTURE,RenderFormat.R8).clearColor(0,0,0,0)
                .dependsOn(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_SURFACE_RESOLVE_PASS);
        pass.execute(executor);
    }
    void recordReactive(PassResources inputs,CommandBuffer cmd) {
        var frame=requireFrame();
        reactiveRecording=true;
        // The compositor writes the coarse mask into this R8 texture. Native-ray
        // changed native medium/light coverage replays on events and fast-light frames; own-depth draws merge with MAX.
        if(colorSamples==1 && !historyPlan.nativeReactive()) return;
        cmd.bindShader(reactive).enableBlend(false).enableDepthTest(false).depthMask(false).enableCullFace(false);
        bindQuery(cmd,reactive);
        cmd.setUniformMat4(reactive,"uFogRasterInverseProjection",rasterInverseProjection)
            .setUniformMat4(reactive,"uFogStableProjection",frame.camera().projection());
        cmd.bindVertexArray(compositeQuad.id()).drawArrays(GL_TRIANGLES,0,6);
    }
    void recordReactiveVfx(CommandBuffer cmd) {
        for (var recorder:reactiveRecorders) recorder.accept(cmd);
        if (diagnosticReactiveRecorder != null) diagnosticReactiveRecorder.accept(cmd);
        cmd.blendEquation(GL_FUNC_ADD).enableBlend(false).enableDepthTest(true).depthMask(true);
    }

    /** Package-private seams for the demo's controlled photometric ablation; no production caller. */
    void diagnosticRecorders(RenderGraph.PassExecutor source,
                             java.util.function.Consumer<CommandBuffer> finalReactive) {
        ensureOpen();
        if (!Boolean.getBoolean("haikalat.glReadback"))
            throw new IllegalStateException("controlled source/reactive ablation requires explicit diagnostic readback");
        diagnosticSourceRecorder = source;
        diagnosticReactiveRecorder = finalReactive;
    }
    void diagnosticOmittedLight(int index) {
        if(!Boolean.getBoolean("haikalat.glReadback"))throw new IllegalStateException("light ablation requires diagnostic readback");
        diagnosticOmittedLight=index;
    }
    void diagnosticCompositeRecorder(RenderGraph.PassExecutor recorder) {
        if(recorder!=null&&!Boolean.getBoolean("haikalat.glReadback"))throw new IllegalStateException("opaque capture requires diagnostic readback");
        diagnosticCompositeRecorder=recorder;
    }

    private static String readAbsolute(String path) {
        try (var stream=VolumetricPassBuilder.class.getResourceAsStream(path)) {
            if (stream==null) throw new IllegalStateException("missing shader "+path);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private void recordComposite(PassResources inputs,CommandBuffer cmd,boolean ms) {
        var geometry=inputs.framebufferOfPass(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS);
        var frame=requireFrame();
        opaqueDepthTexture=ms ? inputs.colorAttachment(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_DEPTH) : geometry.depthAttachment();
        cmd.bindShader(composite).enableBlend(false).enableDepthTest(false).depthMask(false)
                .enableCullFace(false).enableFramebufferSrgb(false).sampleShading(ms,ms ? 1 : 0);
        bindQuery(cmd,composite);
        bindLightingInputs(inputs,cmd,composite);
        cmd.setUniformInt(composite,"uVolumeDiagnosticsEnabled",0).setUniformInt(composite,"uFineOmittedLight",diagnosticOmittedLight)
                .bindStorageBuffer(6,resources.volumes(),0,VolumetricResources.VOLUMES_BYTES)
                .bindStorageBuffer(7,resources.diagnostics(),0,VolumetricResources.DIAGNOSTICS_BYTES);
        if (ms) cmd.bindTextureMultisample(0,geometry.colorAttachment()).bindTextureMultisample(1,geometry.depthAttachment());
        else cmd.bindTexture(0,geometry.colorAttachment()).bindTexture(1,geometry.depthAttachment());
        cmd.setUniformMat4(composite,"uFogRasterInverseProjection",rasterInverseProjection)
                .setUniformMat4(composite,"uFogStableProjection",frame.camera().projection())
                .bindVertexArray(compositeQuad.id()).drawArrays(GL_TRIANGLES,0,6)
                .sampleShading(false,0).depthMask(true).enableDepthTest(true);
        if(diagnosticCompositeRecorder!=null)diagnosticCompositeRecorder.execute(inputs,cmd);
    }
    private final org.joml.Matrix4f guideRasterProjection=new org.joml.Matrix4f();
    private void recordSurfaceGuide(PassResources inputs,CommandBuffer cmd,boolean ms) {
        var frame=requireFrame();
        int depth=ms ? inputs.colorAttachment(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_DEPTH)
                : inputs.framebufferOfPass(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS).depthAttachment();
        bindLightingInputs(inputs,cmd,surfaceGuide);
        cmd.memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT).bindShader(surfaceGuide).bindTexture(0,depth)
                .bindImageTexture(0,resources.prefix(),0,ImageAccess.WRITE_ONLY,RenderFormat.RGBA16F)
                .setUniformMat4(surfaceGuide,"uGuideRasterProjection",guideRasterProjection.set(rasterInverseProjection).invert())
                .setUniformMat4(surfaceGuide,"uGuideRasterInverseProjection",rasterInverseProjection)
                .dispatchCompute((frame.grid().nx()+7)/8,(frame.grid().ny()+7)/8,1)
                .memoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
        surfaceGuideSequence=frame.sequence();
    }
    private final org.joml.Matrix4f rasterInverseProjection=new org.joml.Matrix4f();
    void rasterProjection(org.joml.Matrix4f inverse) { rasterInverseProjection.set(inverse); }
    ShaderProgram transparentShader(com.kaleblangley.haikalat.core.material.Material material) {
        if (material.volumetricFogOptOut()) return material.shader();
        ShaderProgram variant=transparentVariants.get(material.shader());
        if (variant==null) throw new IllegalStateException("transparent material was not frozen for this fog frame");
        return variant;
    }
    /** Atomically validates variants for newly added transparent materials before graph recording. */
    void prepareTransparentVariants(SceneFrame frame) {
        java.util.Map<ShaderProgram,ShaderProgram> added=null;
        try {
            for(int i=0;i<frame.forwardCount;i++) {
                int entry=frame.forwardEntry(i); if(frame.castsOpaqueShadow(entry))continue;
                var material=frame.renderer(entry).material().material();
                if(material.volumetricFogOptOut() || transparentVariants.containsKey(material.shader())
                        || added!=null && added.containsKey(material.shader()))continue;
                if(added==null)added=new java.util.IdentityHashMap<>();
                added.put(material.shader(),material.shader().withFragmentPrelude(VolumetricFogView.SHADER_CONTRACT,VolumetricFogView.fragmentPrelude()));
            }
        } catch(RuntimeException|Error failure) {
            if(added!=null)for(var candidate:added.values())try {candidate.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
        if(added!=null)transparentVariants.putAll(added);
    }
    void bindQuery(CommandBuffer cmd, ShaderProgram shader) {
        bindParameters(cmd);
        cmd.trySetUniformInt(shader,"uFineOmittedLight",diagnosticOmittedLight);
        if(shader.hasStorageBlock("VolumeReactiveDomains"))
            cmd.bindStorageBuffer(14,resources.dirtyDomains(),0,VolumetricResources.DIRTY_DOMAINS_BYTES);
        if(shader.hasStorageBlock("FogVolumes"))
            cmd.bindStorageBuffer(6,resources.volumes(),0,VolumetricResources.VOLUMES_BYTES);
        if(shader.hasStorageBlock("FineLightTableBlock")) {
            if(fragmentInputs==null)throw new IllegalStateException("fine fog query requires this frame's fragment inputs");
            cmd.setUniformInt(shader,"uFineLightingEnabled",borrowedLighting==null?0:1)
                    .trySetUniformInt(shader,"uFineVolumeDiagnosticsEnabled",0)
                    .bindStorageBuffer(4,resources.lightHints(),0,resources.lightHintsBytes())
                    .bindStorageBuffer(5,resources.diagnostics(),0,VolumetricResources.DIAGNOSTICS_BYTES)
                    .bindStorageBuffer(6,resources.volumes(),0,VolumetricResources.VOLUMES_BYTES);
            if(borrowedLighting!=null)borrowedLighting.bindFineLightTable(cmd);
            if(lightingInputs!=null)lightingInputs.record(fragmentInputs,cmd,shader);
        }
        cmd.bindTexture3D(14,resources.prefix()).bindTexture3D(15,querySource())
                .bindTexture3D(21,resources.source());
        cmd.trySetUniformInt(shader,"uVolumeCurrentSourceEnabled",diagnosticSourceRecorder==null ? 1 : 0);
        // The color pass tests against the original depth; only the later color-only mask target may sample it.
        cmd.bindTexture3D(16,resources.reject()).bindTexture3D(17,resources.historyRead()).bindTexture(18,reactiveRecording ? opaqueDepthTexture : 0)
                .bindTexture3D(19,resources.reactivePrefix()).bindTexture3D(20,resources.lightOffReactive());
        cmd.trySetUniformInt(shader,"uVolumeReactiveOnly",0);
        cmd.trySetUniformInt(shader,"uVolumeSurfaceGuideEnabled",surfaceGuideSequence==requireFrame().sequence() ? 1 : 0);
        cmd.trySetUniformMat4(shader,"uVolumeRasterInverseProjection",rasterInverseProjection);
    }
    void fragmentInputs(PassResources inputs) { requireFrame();fragmentInputs=java.util.Objects.requireNonNull(inputs); }
    VolumetricFogView borrowedView() {
        long sequence=requireFrame().sequence();
        return new VolumetricFogView(generationId,sequence,softParticleDepthName,(cmd,shader)-> {
            if (requireFrame().sequence()!=sequence) throw new IllegalStateException("stale fog frame view");
            if (!shader.hasUniformBlock("VolumeParameters")) throw new IllegalArgumentException("shader does not accept VolumeParameters");
            bindQuery(cmd,shader);
        },recorder-> {
            if (requireFrame().sequence()!=sequence) throw new IllegalStateException("stale fog reactive view");
            if(reactiveRecording)throw new IllegalStateException("fog reactive replay list is already frozen");
            reactiveRecorders.add(recorder);
        });
    }

    void prepareFrame(RenderFrameContext context, VolumetricFogSettings settings) {
        prepareFrame(context,settings,ShadowFramePlan.EMPTY);
    }
    void shadowPolicy(Object directional,LocalShadowPipelineSettings local,DirectionalCascadeSettings cascades) {
        historyPlan.shadowPolicy(directional,local,cascades);
    }
    void prepareFrame(RenderFrameContext context, VolumetricFogSettings settings, ShadowFramePlan shadows) {
        ensureOpen();
        if (settings.quality() != quality || settings.hasEmission() != emissionAllocated) {
            throw new IllegalStateException("volume storage shape changed without a generation transaction");
        }
        VolumetricFrameState.Frame frame = frameState.prepare(context, settings);
        VolumetricGrid grid = frame.grid();
        if (grid.nx() != resources.source().width() || grid.ny() != resources.source().height()) {
            frameState.discard();
            throw new IllegalStateException("volume extent changed without a resize transaction");
        }
        historyPlan.prepare(context,frame,frameState.previous(),frameState.valid() && resources.historyValid(),
                borrowedLighting==null ? null : borrowedLighting.stagedTable(),shadows);
        pack(frame);
        if (borrowedLighting != null) {
            LightVolumePacker.pack(borrowedLighting.stagedTable(), lightHints);
        } else lightHints.limit(0);
        integratedSequence = -1;
        surfaceGuideSequence = -1;
        temporalSequence = -1; filteredSource = null;
        reactiveRecorders.clear();
        fragmentInputs=null;
        reactiveRecording=false;
    }

    private void pack(VolumetricFrameState.Frame frame) {
        ExternalCamera camera = frame.camera(); VolumetricGrid grid = frame.grid();
        VolumetricFogSettings settings = frame.settings(); FogMediumSettings global = settings.globalMedium();
        camera.inverseProjection().get(0, parameters); camera.inverseView().get(64, parameters);
        camera.view().get(128, parameters); camera.viewProjection().get(192, parameters);
        VolumetricFrameState.Frame previous=frameState.previous()==null ? frame : frameState.previous();
        ExternalCamera old=previous.camera();
        old.viewProjection().get(256, parameters); old.view().get(320, parameters);
        old.inverseView().get(384, parameters); old.projection().get(448, parameters);
        parameters.putInt(512, grid.nx()).putInt(516, grid.ny()).putInt(520, grid.nz()).putInt(524, grid.tilePixels());
        parameters.putFloat(528, grid.width()).putFloat(532, grid.height()).putFloat(536, grid.near()).putFloat(540, grid.far());
        vector(parameters, 544, global.albedoInternal(), global.extinction());
        vector(parameters, 560, global.emissionInternal(), global.baseHeight());
        parameters.putFloat(576, global.heightFalloff()).putFloat(580, settings.anisotropy())
                .putFloat(584, frame.timeSeconds()).putFloat(588, settings.history() ? settings.historyWeight() : 0);
        vector(parameters, 592, settings.windInternal(), 0); parameters.putInt(604, settings.noiseSeed());
        parameters.putInt(608, settings.localVolumes().size()).putInt(612, emissionAllocated ? 1 : 0)
                .putInt(616, grid.perspective() ? 1 : 0).putInt(620, frameState.valid() && resources.historyValid() ? 1 : 0);
        parameters.putFloat(624, previous.grid().near()).putFloat(628, previous.grid().far())
                .putFloat(632, previous.timeSeconds()).putFloat(636, 0.98f);
        parameters.putFloat(640,frame.samplePhase()).putFloat(644,previous.samplePhase()).putFloat(648,0.002f).putFloat(652,0.05f);
        parameters.putInt(656,historyPlan.count()).putInt(660,historyPlan.shaderReason())
                .putInt(664,settings.history()?1:0).putInt(668,Boolean.getBoolean("haikalat.internal.volume.diagnostics")?1:0);
        parameters.putFloat(672,0.1f).putFloat(676,0.98f).putFloat(680,historyPlan.shadowRefit()?1:0).putFloat(684,0.005f);
        parameters.putFloat(688,frame.samplePhaseX()).putFloat(692,frame.samplePhaseY())
                .putFloat(696,previous.samplePhaseX()).putFloat(700,previous.samplePhaseY());
        // Use spare capacity in the existing 4096-byte UBO, preserving resource ownership/bindings.
        parameters.putInt(704,historyPlan.nativeMediumCount()).putInt(708,historyPlan.nativeLightFull()?1:0).putInt(712,0).putInt(716,0);
        parameters.position(720).put(historyPlan.nativeMediumDomains().duplicate());
        parameters.position(0).limit(PARAMETERS_USED_BYTES);
        volumes.clear();
        for (int index=0; index<settings.localVolumes().size(); index++) {
            LocalFogVolume v = settings.localVolumes().get(index); int offset = index*VOLUME_STRIDE;
            vector(volumes, offset, v.centerInternal(), v.shape() == LocalFogVolume.Shape.SPHERE ? 0 : 1);
            vector(volumes, offset+16, v.extentInternal(), v.extinction());
            vector(volumes, offset+32, v.albedoInternal(), v.falloff());
            vector(volumes, offset+48, v.emissionInternal(), v.noiseScale());
            volumes.putFloat(offset+64, v.noiseAmount());
        }
        volumes.position(0).limit(settings.localVolumes().size()*VOLUME_STRIDE);
    }

    private static void vector(ByteBuffer data, int offset, Vector3f value, float w) {
        data.putFloat(offset, value.x).putFloat(offset+4, value.y).putFloat(offset+8, value.z).putFloat(offset+12, w);
    }

    void bindParameters(CommandBuffer cmd) {
        ensureOpen(); requireFrame();
        cmd.bindUniformBuffer(7, resources.parameters(), 0, PARAMETERS_USED_BYTES);
    }

    void recordMedium(CommandBuffer cmd) {
        requireFrame();
        // Also orders last frame's sampler reads before reusing the image scratch storage.
        cmd.memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT)
                .uploadBufferRegion(resources.parameters(), 0, parameters);
        if (diagnosticCountersEnabled()) cmd.uploadBufferRegion(resources.diagnostics(), 0, zeroDiagnostics);
        if (volumes.hasRemaining()) volumeUploads.record(cmd,resources.volumes(),volumes);
        if (historyPlan.domains().hasRemaining()) dirtyUploads.record(cmd,resources.dirtyDomains(),historyPlan.domains());
        bindParameters(cmd);
        cmd.bindStorageBuffer(6, resources.volumes(), 0, VolumetricResources.VOLUMES_BYTES)
                .bindStorageBuffer(7, resources.diagnostics(), 0, VolumetricResources.DIAGNOSTICS_BYTES)
                .bindShader(medium).bindImageTexture(0, resources.medium(), 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F);
        if (emissionAllocated) cmd.bindImageTexture(1, resources.emission(), 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F);
        dispatchVolume(cmd);
        cmd.memoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    void recordSource(CommandBuffer cmd) {
        recordSource(null, cmd, lighting, resources.source());
    }

    void recordReferenceSource(PassResources inputs, CommandBuffer cmd, ShaderProgram reference, Texture3D target) {
        recordSource(inputs, cmd, reference, target);
    }

    private void recordSource(PassResources inputs, CommandBuffer cmd, ShaderProgram shader, Texture3D target) {
        requireFrame(); bindParameters(cmd);
        cmd.bindShader(shader).bindTexture3D(0, resources.medium())
                .bindStorageBuffer(7, resources.diagnostics(), 0, VolumetricResources.DIAGNOSTICS_BYTES)
                .bindImageTexture(0, target, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA32F);
        bindLightingInputs(inputs,cmd,shader);
        if(shader!=lighting)cmd.setUniformInt(shader,"uVolumeDiagnosticsEnabled",0);
        if (emissionAllocated) cmd.bindTexture3D(1, resources.emission());
        dispatchVolume(cmd); cmd.memoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
    }

    /** Also used by the explicitly installed demo-only full-ray reference recorder. */
    void bindLightingInputs(PassResources inputs, CommandBuffer cmd, ShaderProgram shader) {
        requireFrame();bindParameters(cmd);
        cmd.setUniformInt(shader,"uLightingEnabled",borrowedLighting != null ? 1 : 0)
                .trySetUniformInt(shader,"uVolumeDiagnosticsEnabled",Boolean.getBoolean("haikalat.internal.volume.diagnostics") ? 1 : 0);
        if (borrowedLighting != null) {
            if (lightHints.hasRemaining()) hintUploads.record(cmd,resources.lightHints(),lightHints);
            cmd.bindStorageBuffer(4, resources.lightHints(),0,resources.lightHintsBytes());
            borrowedLighting.bindForward(cmd,shader);
            // Indexed near-light queries can optimize away ClusterParametersBlock.
            // They still require an explicit table binding in this pass.
            if(shader.hasStorageBlock(ClusteredLightingResources.LIGHT_TABLE_BLOCK))borrowedLighting.bindVolumeLightTable(cmd);
            if (lightingInputs != null && shader!=surfaceGuide) lightingInputs.record(inputs,cmd,shader);
        }
    }

    void recordIntegrate(CommandBuffer cmd) {
        VolumetricFrameState.Frame frame = requireFrame(); bindParameters(cmd);
        cmd.bindShader(integrate).bindTexture3D(0, querySource())
                .bindTexture3D(1,resources.reject()).bindTexture3D(2,resources.historyRead()).bindTexture3D(3,resources.lightOffReactive())
                .bindStorageBuffer(7, resources.diagnostics(), 0, VolumetricResources.DIAGNOSTICS_BYTES)
                .bindImageTexture(0, resources.prefix(), 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .bindImageTexture(1,resources.reactivePrefix(),0,ImageAccess.WRITE_ONLY,RenderFormat.R8)
                .dispatchCompute((frame.grid().nx()+7)/8, (frame.grid().ny()+7)/8, 1)
                .memoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
        integratedSequence = frame.sequence();
    }
    void recordTemporal(CommandBuffer cmd) {
        requireFrame(); bindParameters(cmd);
        filteredSource=resources.historyWrite();
        cmd.bindShader(temporal).bindTexture3D(0,resources.source()).bindTexture3D(1,resources.historyRead()).bindTexture3D(2,resources.medium())
                .bindStorageBuffer(5,resources.dirtyDomains(),0,VolumetricResources.DIRTY_DOMAINS_BYTES)
                .bindStorageBuffer(6,resources.volumes(),0,VolumetricResources.VOLUMES_BYTES)
                .bindStorageBuffer(7,resources.diagnostics(),0,VolumetricResources.DIAGNOSTICS_BYTES)
                .bindImageTexture(0,filteredSource,0,ImageAccess.WRITE_ONLY,RenderFormat.RGBA16F)
                .bindImageTexture(1,resources.reject(),0,ImageAccess.WRITE_ONLY,RenderFormat.R8)
                .bindImageTexture(2,resources.lightOffReactive(),0,ImageAccess.WRITE_ONLY,RenderFormat.R8);
        dispatchVolume(cmd);
        cmd.memoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        temporalSequence=requireFrame().sequence(); resources.stageHistoryWrite();
    }
    private Texture3D querySource() { return filteredSource==null ? resources.source() : filteredSource; }

    private void dispatchVolume(CommandBuffer cmd) {
        VolumetricGrid grid = requireFrame().grid();
        cmd.dispatchCompute((grid.nx()+3)/4, (grid.ny()+3)/4, (grid.nz()+3)/4);
    }

    OutputView currentOutput() {
        VolumetricFrameState.Frame frame = frameState.current() == null ? frameState.previous() : frameState.current();
        if (frame == null) throw new IllegalStateException("volume output has no frame identity");
        return new OutputView(generationId, frame.sequence(), frame.grid(), resources.prefix(), querySource(),
                !closed && integratedSequence == frame.sequence() && (frameState.current() != null || frameState.valid()));
    }
    VolumetricResources resources() { ensureOpen(); return resources; }
    VolumetricFrameState frameState() { return frameState; }
    VolumetricResources.ResizeCandidate prepareResize(int width, int height) {
        ensureOpen(); validateExtentBudget(width,height);
        return resources.prepareResize(columns(width), columns(height), quality.depthSlices(), emissionAllocated);
    }
    void commitResize(VolumetricResources.ResizeCandidate candidate) {
        resources.commitResize(candidate); frameState.invalidate(); integratedSequence = -1;
        discardParameterUploads();
        historyPlan.invalidate(VolumetricHistoryPlan.Reason.RESIZE);
    }
    void prepareFrameSuccess() {
        if (integratedSequence != requireFrame().sequence()) throw new IllegalStateException("volume graph was not integrated");
        if(filteredSource!=null && temporalSequence!=requireFrame().sequence())throw new IllegalStateException("volume history was not resolved");
    }
    void frameSucceeded() {
        resources.commitHistory(); historyPlan.commit();
        frameState.commit();
        volumeUploads.commit(); dirtyUploads.commit(); hintUploads.commit();
    }
    void frameFailed() {
        frameState.discard(); resources.discardHistory(); historyPlan.discard(); integratedSequence = -1; surfaceGuideSequence=-1;
        historyPlan.invalidate(VolumetricHistoryPlan.Reason.FRAME_FAILURE);
        discardParameterUploads();
    }
    private void discardParameterUploads() {
        volumeUploads.discard(); dirtyUploads.discard(); hintUploads.discard();
    }

    /** Static adjuncts remain resident; only successful frames authorize skipping an upload. */
    private static final class ParameterUploadCache {
        private final ByteBuffer committed,staged;
        private int committedBufferId,stagedBufferId;
        private boolean recorded;

        ParameterUploadCache(int capacity) {
            committed=ByteBuffer.allocateDirect(capacity); staged=ByteBuffer.allocateDirect(capacity);
            committed.limit(0);
        }
        void record(CommandBuffer cmd,BufferUploadTarget buffer,ByteBuffer payload) {
            if(!recorded && committedBufferId==buffer.id() && committed.mismatch(payload)==-1)return;
            cmd.uploadBufferRegion(buffer,0,payload);
            staged.clear().put(payload.duplicate()).flip();
            stagedBufferId=buffer.id(); recorded=true;
        }
        void commit() {
            if(recorded) {
                committed.clear().put(staged.duplicate()).flip();
                committedBufferId=stagedBufferId;
            }
            recorded=false;
        }
        void discard() { committedBufferId=0; recorded=false; }
    }
    VolumetricHistoryPlan historyPlan() { return historyPlan; }
    boolean diagnosticCountersEnabled() { return parameters.getInt(668)!=0; }
    void invalidateHistory() {
        frameState.invalidate(); resources.discardHistory(); historyPlan.invalidate(VolumetricHistoryPlan.Reason.EXPLICIT_RESET);
    }
    private VolumetricFrameState.Frame requireFrame() {
        ensureOpen();
        if (frameState.current() == null) throw new IllegalStateException("volume frame has not been prepared");
        return frameState.current();
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("volume builder is closed"); }
    @Override public void close() {
        if (closed) return; closed = true;
        RuntimeException failure = null;
        java.util.List<AutoCloseable> owned=new java.util.ArrayList<>(transparentVariants.values());
        owned.addAll(java.util.Arrays.asList(softDepthCopy,compositeQuad,surfaceGuide,reactive,composite,temporal,integrate,lighting,medium,resources));
        for (AutoCloseable resource : owned) {
            if (resource == null) continue;
            try { resource.close(); } catch (Exception error) {
                RuntimeException runtime = error instanceof RuntimeException r ? r : new IllegalStateException(error);
                if (failure == null) failure = runtime; else failure.addSuppressed(runtime);
            }
        }
        if (failure != null) throw failure;
    }
}
