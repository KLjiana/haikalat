package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.*;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.opengl.GL;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static com.kaleblangley.haikalat.demo.pbr.VolumetricDemoSceneFactory.SceneId;

/** Final HDR scene sequences. Counterfactuals isolate light fog; physical image accuracy has its own ray gate. */
public final class Render3dVolumetricFogTemporalQuality {
    private enum Mode {
        UNFILTERED(false,AntiAliasingMode.NONE), VOLUME_OFF_TAA_ON(false,AntiAliasingMode.TAA),
        VOLUME_ON_TAA_OFF(true,AntiAliasingMode.NONE), BOTH_ON(true,AntiAliasingMode.TAA);
        final boolean history;final AntiAliasingMode aa;
        Mode(boolean history,AntiAliasingMode aa){this.history=history;this.aa=aa;}
    }
    public static void main(String[] args) throws Exception {
        SceneId selected=null;
        for(String arg:args) {
            if(arg.startsWith("--scene="))selected=SceneId.valueOf(arg.substring(8).toUpperCase(Locale.ROOT));
            else throw new IllegalArgumentException("unknown temporal quality argument "+arg);
        }
        System.setProperty("haikalat.glReadback","true");System.setProperty("haikalat.internal.volume.diagnostics","true");
        Config cfg=Config.load();String fingerprint=Render3dClusteredBenchmarkSuite.fingerprint();
        Path output=Path.of("build/reports/render3d-v0250",fingerprint,UUID.randomUUID().toString());Files.createDirectories(output);
        var manifest=new LinkedHashMap<String,Object>();var results=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();
        manifest.put("schema",1);manifest.put("kind","volumetric-scene-temporal-quality");manifest.put("candidateFingerprint",fingerprint);
        manifest.put("runId",output.getFileName().toString());manifest.put("started",Instant.now().toString());manifest.put("fullMatrixRequested",selected==null);manifest.put("completeMatrix",false);
        manifest.put("configuration",cfg);manifest.put("results",results);manifest.put("failures",failures);manifest.put("releaseReady",false);
        manifest.put("scope","Final linear HDR after TAA, with material transparency and tavern HDR VFX. Stable world/camera/VFX inputs are frozen while successful-frame history/jitter advance. No ROI exclusions. This is a temporal gate, not an independent transport oracle or moving-wind acceptance.");
        manifest.put("counterfactual","Selected surface light stays lit. Both scenes retain identical volume hints and switch them to zero at the off frame. Baseline GPU source omits only the selected lamp radiance throughout; it retains the candidate's CPU dirty-domain policy and replays its exact native R8 TAA reactive input for every frame. Successful-frame phase/time and dirty-domain bytes are compared exactly, and reactive replay is read back and verified exactly. This controlled photometric ablation identifies lamp fog history; the earlier uncontrolled background/TAA differences remain separate visual diagnostics. Recovery uses absolute RGB differences, including negative ghosts.");
        manifest.put("protocol","controlled-photometric-ablation-v2-shared-native-queries");
        manifest.put("remainingVisualAcceptance","Matched-policy recovery does not prove acceptable visible background changes on physical lamp transitions or moving-wind/VFX events.");
        manifest.put("measurementResources","Native HDR readback staging is freed after each capture. This synchronized diagnostic task bounds heap to 1 GiB and GC-managed direct command staging to 256 MiB; it supplies no CPU/GPU performance proof.");
        manifest.put("stabilityReference","Separate frozen-input renderer with volume history and final AA disabled. Gate uses population standard deviation of the ROI mean luminance, matching the existing fixed GL stability contract. Per-pixel extrema remain diagnostics and are not hidden by the ROI mean. Physical accuracy remains the converged full-ray gate.");
        for(String name:List.of("temporal.properties","quality.properties"))Files.copy(Path.of("config/volumetric-fog",name),output.resolve(name));
        try {
            for(SceneId scene:SceneId.values())if(selected==null||selected==scene) {
                float[] reference=meanStable(render(scene,Mode.UNFILTERED,false,cfg,output),cfg);
                for(Mode mode:Mode.values())if(mode!=Mode.UNFILTERED) {
                    Map<Integer,float[]> candidate=render(scene,mode,false,cfg,output);
                    Map<Integer,float[]> baseline=render(scene,mode,true,cfg,output);
                    List<float[]> stable=new ArrayList<>();for(int f=cfg.stableFirst;f<cfg.stableFirst+cfg.stableFrames;f++)stable.add(candidate.get(f));
                    var stability=VolumetricTemporalMetrics.stability(stable,reference,cfg.roi,cfg.darkLuminance,cfg.relativeStd,cfg.absoluteStd);
                    int before=cfg.offFrame-1,second=cfg.offFrame+cfg.offFrames-1;
                    var recovery=VolumetricTemporalMetrics.recovery(candidate.get(before),baseline.get(before),candidate.get(second),baseline.get(second),cfg.roi,cfg.epsilon,cfg.residualLimit);
                    var row=new LinkedHashMap<String,Object>();row.put("scene",scene.id());row.put("mode",mode);row.put("selectedSceneLightIndex",cfg.lightIndex(scene));
                    row.put("stability",stability);row.put("recovery",recovery);row.put("offFrame",cfg.offFrame);row.put("secondOffFrame",second);
                    row.put("matchedSuccessfulPolicyFrames",cfg.settled+1);row.put("nativeR8ReactiveReplayVerified",mode.aa==AntiAliasingMode.TAA);
                    row.put("passed",stability.passed()&&recovery.passed());results.add(row);
                    if(!stability.passed())failures.add(scene.id()+" "+mode+" stability failed: "+stability);
                    if(!recovery.passed())failures.add(scene.id()+" "+mode+" light-off recovery failed: "+recovery);
                    System.out.println("VOLUME TEMPORAL "+scene.id()+" "+mode+" stability="+stability.passed()+" residual="+recovery.residualRatio()+" recovery="+recovery.passed());
                }
            }
            if(!fingerprint.equals(Render3dClusteredBenchmarkSuite.fingerprint()))throw new IllegalStateException("temporal candidate source changed during measurement");
            manifest.put("status",failures.isEmpty()?"passed":"failed");
        }catch(Exception|Error failure){manifest.put("status","execution-failed");failures.add(failure.toString());throw failure;}
        finally{manifest.put("completeMatrix",selected==null&&results.size()==12);manifest.put("finished",Instant.now().toString());VolumetricEvidenceIO.save(output.resolve("manifest.json"),manifest);System.out.println("Volumetric temporal evidence: "+output.toAbsolutePath());}
        if(!failures.isEmpty())throw new IllegalStateException("volumetric scene temporal gate failed: "+failures);
    }

    private static Map<Integer,float[]> render(SceneId id,Mode mode,boolean counterfactual,Config cfg,Path output) throws Exception {
        var original=VolumetricDemoSceneFactory.profile(id);var v=original.settings();var f=v.volumetricFog();
        var fog=new VolumetricFogSettings(f.enabled(),f.fogDistance(),f.quality(),f.globalMedium(),f.localVolumes(),f.anisotropy(),mode.history,f.historyWeight(),f.noiseSeed(),f.wind());
        var visual=new VisualSettings(v.manualExposure(),v.iblIntensity(),v.iblRotationRadians(),v.directionalLightDirection(),v.directionalLightColor(),v.directionalLightIntensity(),mode.aa,v.bloom(),v.outdoor(),fog);
        if(visual.bloom().enabled())throw new IllegalStateException("temporal input requires Bloom off");
        var profile=new VisualProfile(2,original.profileId(),original.description(),original.author(),visual,original.lightVolumes());
        var capture=SceneCaptureSpecCodec.load(Path.of("config/volumetric-fog/captures",id.id()+".capture.properties"));
        Path modeDirectory=output.resolve(id.id()).resolve(mode.name().toLowerCase(Locale.ROOT));
        Path directory=modeDirectory.resolve(counterfactual?"lamp-volume-ablated":"candidate");Files.createDirectories(directory);
        VisualProfileCodec.save(directory.resolve("profile.properties"),profile);SceneCaptureSpecCodec.save(directory.resolve("capture.properties"),capture);
        Map<Integer,float[]> images=new LinkedHashMap<>();var frames=new ArrayList<Map<String,Object>>();
        try(var window=new GlfwWindow.Builder().dimensions(cfg.width,cfg.height).title("Volume temporal "+id+" "+mode).visible(false).build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();GlDebug.resourceTracking(true);window.setVsync(false);
            var settings=RenderSettings.builder().vsync(false).antiAliasingMode(mode.aa).toneMappingMode(ToneMappingMode.ACES)
                    .exposureMode(ExposureMode.MANUAL).exposure(visual.manualExposure()).bloomSettings(visual.bloom()).build();
            try(var driver=new FrameDriver(settings);var environments=new OutdoorEnvironmentResources(driver.device(),"default");
                var fallback=new PbrFallbackTextures();var shader=ShaderProgram.fromResource(Render3dVolumetricFogTemporalQuality.class,"/shaders/render3d/pbr/pbr-forward.vert","/shaders/render3d/pbr/pbr-forward.frag");
                var bundle=VolumetricDemoSceneFactory.create(id,shader,fallback);var vfx=new VfxRenderer(true)) {
                VolumetricDemoSceneFactory.applyLights(bundle.scene,profile,false);int lightIndex=cfg.lightIndex(id);
                LightVolumeHints selected=bundle.scene.lightVolumeHints(lightIndex);
                if(selected.scatteringIntensity()<=0)throw new IllegalStateException("temporal selected light has no volume contribution");
                var pipeline=new RenderPipeline(window,bundle.scene,null,settings,environments.environmentFor(visual.outdoor()))
                        .clusteredLighting(ClusteredLightingSettings.defaults()).localShadows(LocalShadowPipelineSettings.balanced())
                        .directionalCascades(new DirectionalCascadeSettings(4,2048,.62f,.08f,64));
                EffectSnapshot[] snapshot={bundle.vfx(0)};
                if(id==SceneId.TAVERN_THRESHOLD)pipeline.hdrVfxWithFog((res,cmd,camera,volume)->{
                    int depth=volume==null?res.colorAttachment(PostProcessTargets.VFX_SCENE_DEPTH):res.colorAttachment(volume.softParticleDepthName());
                    vfx.record(cmd,snapshot[0],camera.projection(),camera.view(),depth,cfg.width,cfg.height,volume);
                });
                try {
                    pipeline.applyVisualSettings(visual);pipeline.build();
                    try(var control=counterfactual?new VolumetricTemporalCounterfactual(pipeline,bundle.scene,lightIndex,modeDirectory.resolve("candidate"),mode.aa==AntiAliasingMode.TAA):null) {
                    for(int frame=0;frame<=cfg.settled;frame++) {
                        int worldFrame=Math.min(frame,cfg.freezeFirst-1);SceneCaptureRoutes.apply(bundle.scene.camera(),capture.cameraPath(),worldFrame);
                        // The scene demo's physical lamp-off event also changes surface illumination.
                        // This isolated fog sequence retains the lit surface and changes only volume hints.
                        if(!(id==SceneId.TAVERN_THRESHOLD&&frame==180))bundle.event(frame,pipeline);
                        snapshot[0]=bundle.vfx(worldFrame);
                        if(frame==cfg.offFrame) {
                            bundle.scene.setLightVolumeHints(lightIndex,new LightVolumeHints(0,selected.useAllocatedShadow(),selected.temporalAccumulation()));
                        }
                        if(control!=null)control.frame(frame);
                        driver.beginFrame();
                        try{pipeline.execute(driver.device(),frame<cfg.freezeFirst?1f/60:0);driver.recordGraph(pipeline.graph());driver.endFrame();}
                        catch(RuntimeException|Error failure){driver.failFrame(pipeline.graph(),failure);throw failure;}
                        if(control!=null)control.verifyPolicy();
                        else if(mode!=Mode.UNFILTERED)VolumetricTemporalCounterfactual.savePolicy(pipeline,frame,directory,mode.aa==AntiAliasingMode.TAA);
                        if(cfg.capture(frame)) {
                            float[] image=sample(pipeline.captureLinearHdrRgbaFloat(),cfg);images.put(frame,image);
                            String name=String.format(Locale.ROOT,"%04d",frame);save(directory.resolve(name+".rgba.f32.gz"),image,cfg.roi.width(),cfg.roi.height());
                            SceneCaptureIO.linear(directory.resolve(name+".h4f.gz"),image,cfg.roi.width(),cfg.roi.height());
                            if(frame==cfg.offFrame-1||frame==cfg.offFrame+cfg.offFrames-1)VolumetricImageMetrics.preview(directory.resolve(name+".png"),image,cfg.roi.width(),cfg.roi.height());
                            var counters=pipeline.captureVolumetricCounters();
                            if(counters.nonfiniteMedium()+counters.limitedMedium()+counters.limitedSource()+counters.limitedIntegral()!=0)throw new IllegalStateException("temporal numeric counters nonzero");
                            var row=new LinkedHashMap<String,Object>();row.put("frame",frame);row.put("worldFrame",worldFrame);row.put("volume",pipeline.volumetricFogDiagnostics());
                            row.put("selectedLight",bundle.scene.lights().get(lightIndex));row.put("selectedVolumeHints",bundle.scene.lightVolumeHints(lightIndex));row.put("gpuAblatedSceneLightIndex",counterfactual?lightIndex:null);row.put("view",bundle.scene.camera().getViewMatrix().get(new float[16]));row.put("counters",counters);
                            frames.add(row);
                        }
                        GlDebug.assertNoError("volume final HDR temporal sequence");
                    }
                    }
                }finally{pipeline.close();VolumetricEvidenceIO.save(directory.resolve("frames.json"),frames);}
            }
            if(!GlDebug.resources().liveResources().isEmpty())throw new IllegalStateException("temporal resources leaked "+GlDebug.resources());
        }
        return images;
    }
    private static float[] sample(float[] nativeRgba,Config cfg) {
        float[] image=new float[cfg.roi.width()*cfg.roi.height()*4];
        for(int y=0;y<cfg.roi.height();y++)for(int x=0;x<cfg.roi.width();x++)System.arraycopy(nativeRgba,
                4*((y*cfg.stride+cfg.stride/2)*cfg.width+x*cfg.stride+cfg.stride/2),image,4*(x+y*cfg.roi.width()),4);
        return image;
    }
    private static void save(Path file,float[] image,int width,int height)throws IOException {
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(width);out.writeInt(height);for(float value:image)out.writeFloat(value);
        }
    }
    private static float[] meanStable(Map<Integer,float[]> images,Config cfg) {
        double[] mean=new double[cfg.roi.width()*cfg.roi.height()*4];
        for(int f=cfg.stableFirst;f<cfg.stableFirst+cfg.stableFrames;f++){float[] values=images.get(f);for(int p=0;p<mean.length;p++)mean[p]+=values[p]/(double)cfg.stableFrames;}
        float[] result=new float[mean.length];for(int p=0;p<mean.length;p++)result[p]=(float)mean[p];return result;
    }
    private record Config(int width,int height,int stride,VolumetricTemporalMetrics.Roi roi,int freezeFirst,int stableFirst,int stableFrames,
                          int offFrame,int offFrames,int settled,double epsilon,double darkLuminance,double relativeStd,double absoluteStd,double residualLimit,List<Integer> lights) {
        int lightIndex(SceneId id){return lights.get(id.ordinal());}
        boolean capture(int f){return f>=stableFirst&&f<stableFirst+stableFrames||f>=offFrame&&f<=offFrame+offFrames||f==settled;}
        static Config load()throws IOException {
            Properties p=new Properties(),q=new Properties();try(var in=Files.newInputStream(Path.of("config/volumetric-fog/temporal.properties"))){p.load(in);}try(var in=Files.newInputStream(Path.of("config/volumetric-fog/quality.properties"))){q.load(in);}
            if(!"1".equals(p.getProperty("schema"))||!"true".equals(p.getProperty("stableFreezeCameraAndWorld"))||!"full-quality-roi".equals(p.getProperty("roiPolicy"))
                    ||!"population-stddev-of-roi-mean-luminance".equals(p.getProperty("stabilityMetric"))
                    ||!"sum-absolute-rgb-counterfactual-difference".equals(p.getProperty("recoveryMetric"))
                    ||!"photometric-ablation-with-matched-history-and-reactive".equals(p.getProperty("counterfactualPolicy")))throw new IllegalArgumentException("unsupported temporal contract");
            int width=i(q,"nativeWidth"),height=i(q,"nativeHeight"),stride=i(q,"sampleStride");
            if(stride<=0||width%stride!=0||height%stride!=0)throw new IllegalArgumentException("temporal sampling must divide native dimensions");
            var roi=new VolumetricTemporalMetrics.Roi(width/stride,height/stride,i(q,"roi.x"),i(q,"roi.y"),i(q,"roi.width"),i(q,"roi.height"));
            var lights=new ArrayList<Integer>();for(SceneId id:SceneId.values())lights.add(i(p,id.id()+".lightIndex"));
            var cfg=new Config(width,height,stride,roi,i(p,"freezeFirstFrame"),i(p,"stableFirstFrame"),i(q,"stableFrames"),i(p,"lightOffFrame"),i(q,"lightOffFrames"),i(p,"settledFrame"),d(q,"normalizationEpsilon"),d(q,"darkLuminanceThreshold"),d(q,"stableRelativeStdDev"),d(q,"stableDarkAbsoluteStdDev"),d(q,"lightOffResidual"),List.copyOf(lights));
            if(cfg.freezeFirst<1||cfg.stableFirst<=cfg.freezeFirst||cfg.stableFrames<2||cfg.stableFirst+cfg.stableFrames!=cfg.offFrame||cfg.offFrames<1||cfg.settled<cfg.offFrame+cfg.offFrames)throw new IllegalArgumentException("invalid temporal frame schedule");
            return cfg;
        }
        private static int i(Properties p,String k){return Integer.parseInt(Objects.requireNonNull(p.getProperty(k),k));}
        private static double d(Properties p,String k){double v=Double.parseDouble(Objects.requireNonNull(p.getProperty(k),k));if(!Double.isFinite(v)||v<=0)throw new IllegalArgumentException("invalid temporal threshold "+k);return v;}
    }
}
