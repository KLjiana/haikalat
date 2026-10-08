package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.*;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.opengl.GL;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.kaleblangley.haikalat.demo.pbr.VolumetricDemoSceneFactory.SceneId;

/** Linear HDR image gate against independently integrated, no-history full-light rays. */
public final class Render3dVolumetricFogQuality {
    private Render3dVolumetricFogQuality() { }
    public static void main(String[] args) throws Exception {
        SceneId selected=null;Integer selectedFrame=null;boolean candidateHistory=true;var quality=VolumetricFogSettings.Quality.BALANCED;
        int[] probe=null;
        for(String arg:args) {
            if(arg.startsWith("--scene="))selected=SceneId.valueOf(arg.substring(8).toUpperCase(Locale.ROOT));
            else if(arg.startsWith("--frame="))selectedFrame=Integer.valueOf(arg.substring(8));
            else if(arg.equals("--candidate-history=on"))candidateHistory=true;
            else if(arg.equals("--candidate-history=off"))candidateHistory=false;
            else if(arg.startsWith("--quality="))quality=VolumetricFogSettings.Quality.valueOf(arg.substring(10).toUpperCase(Locale.ROOT));
            else if(arg.startsWith("--probe=")) {
                probe=Arrays.stream(arg.substring(8).split(",")).mapToInt(Integer::parseInt).toArray();
                if(probe.length!=2)throw new IllegalArgumentException("probe requires native x,y");
            }
            else throw new IllegalArgumentException("unknown quality option "+arg);
        }
        Config cfg=Config.load();String fingerprint=Render3dClusteredBenchmarkSuite.fingerprint();
        Path output=Path.of("build/reports/render3d-v0250",fingerprint,UUID.randomUUID().toString());Files.createDirectories(output);
        Files.copy(Path.of("config/volumetric-fog/quality.properties"),output.resolve("quality.properties"));
        Map<String,Object> manifest=new LinkedHashMap<>();List<Map<String,Object>> captures=new ArrayList<>();List<String> failures=new ArrayList<>();
        manifest.put("schema",1);manifest.put("kind","volumetric-converged-image-quality");manifest.put("candidateFingerprint",fingerprint);
        manifest.put("runId",output.getFileName().toString());manifest.put("formal",selected==null&&selectedFrame==null&&quality==VolumetricFogSettings.Quality.BALANCED);
        manifest.put("candidateQuality",quality);
        manifest.put("comparison","actual opaque compositor linear HDR copied before transparency at fixed native pixels; independent full-ray transport with adaptive emitter quadrature, shared allocated shadow definition");
        manifest.put("candidateCapture","native RGBA16F opaque output; replicated query used only for transport/probe diagnostics");
        manifest.put("nativeRenderSize",List.of(cfg.width,cfg.height));manifest.put("sampleStride",cfg.stride);
        manifest.put("roi",List.of(cfg.x,cfg.y,cfg.roiWidth,cfg.roiHeight));manifest.put("captures",captures);manifest.put("failures",failures);
        manifest.put("candidateVolumeHistory",candidateHistory);manifest.put("referenceVolumeHistory",false);manifest.put("taa",false);manifest.put("bloom",false);manifest.put("releaseReady",false);
        if(probe!=null)manifest.put("diagnosticProbeNativePixel",probe);
        String oldCounters=System.getProperty("haikalat.internal.volume.diagnostics"),oldReadback=System.getProperty("haikalat.glReadback");
        System.setProperty("haikalat.internal.volume.diagnostics","true");System.setProperty("haikalat.glReadback","true");
        try {
            for(SceneId scene:SceneId.values())if(selected==null||scene==selected)
                scene(scene,selectedFrame,candidateHistory,quality,probe,cfg,output,captures,failures);
            if(!fingerprint.equals(Render3dClusteredBenchmarkSuite.fingerprint()))failures.add("source fingerprint changed during quality run");
            manifest.put("status",failures.isEmpty()?"passed":"failed");
        }catch(Exception|Error failure){manifest.put("status","failed");failures.add(failure.toString());throw failure;}
        finally {
            if(oldCounters==null)System.clearProperty("haikalat.internal.volume.diagnostics");else System.setProperty("haikalat.internal.volume.diagnostics",oldCounters);
            if(oldReadback==null)System.clearProperty("haikalat.glReadback");else System.setProperty("haikalat.glReadback",oldReadback);
            VolumetricEvidenceIO.save(output.resolve("manifest.json"),manifest);System.out.println("Volumetric quality evidence: "+output.toAbsolutePath());
        }
        if(!failures.isEmpty())throw new IllegalStateException("Volumetric image quality failed: "+failures);
    }

    private static void scene(SceneId id,Integer selectedFrame,boolean candidateHistory,VolumetricFogSettings.Quality quality,int[] probe,Config cfg,Path output,List<Map<String,Object>> captures,List<String> failures) throws Exception {
        var initial=VolumetricDemoSceneFactory.profile(id);var f=initial.settings().volumetricFog();
        var fog=new VolumetricFogSettings(true,f.fogDistance(),quality,f.globalMedium(),f.localVolumes(),
                f.anisotropy(),candidateHistory,f.historyWeight(),f.noiseSeed(),f.wind());var visual=initial.settings().withVolumetricFog(fog);
        if(visual.antiAliasingMode()!=AntiAliasingMode.NONE||visual.bloom().enabled())throw new IllegalStateException("formal image profile requires NONE/Bloom off");
        var profile=new VisualProfile(2,initial.profileId(),initial.description(),initial.author(),visual,initial.lightVolumes());
        var spec=SceneCaptureSpecCodec.load(Path.of("config/volumetric-fog/captures",id.id()+".capture.properties"));
        var frames=selectedFrame==null?List.of(60,240,420):List.of(selectedFrame);
        Path directory=output.resolve(id.id());Files.createDirectories(directory);VisualProfileCodec.save(directory.resolve("profile.properties"),profile);
        SceneCaptureSpecCodec.save(directory.resolve("capture.properties"),spec);
        try(var window=new GlfwWindow.Builder().dimensions(cfg.width,cfg.height).title("Volume image reference "+id).visible(false).build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();GlDebug.resourceTracking(true);window.setVsync(false);
            var settings=RenderSettings.builder().vsync(false).antiAliasingMode(AntiAliasingMode.NONE).toneMappingMode(ToneMappingMode.ACES)
                    .exposureMode(ExposureMode.MANUAL).exposure(visual.manualExposure()).bloomSettings(visual.bloom()).build();
            try(var driver=new FrameDriver(settings);var environments=new OutdoorEnvironmentResources(driver.device(),"default");
                var fallback=new PbrFallbackTextures();var shader=ShaderProgram.fromResource(Render3dVolumetricFogQuality.class,
                        "/shaders/render3d/pbr/pbr-forward.vert","/shaders/render3d/pbr/pbr-forward.frag");
                var bundle=VolumetricDemoSceneFactory.create(id,shader,fallback)) {
                VolumetricDemoSceneFactory.applyLights(bundle.scene,profile,false);
                var pipeline=new RenderPipeline(window,bundle.scene,null,settings,environments.environmentFor(visual.outdoor()))
                        .clusteredLighting(ClusteredLightingSettings.defaults()).localShadows(LocalShadowPipelineSettings.balanced())
                        .directionalCascades(new DirectionalCascadeSettings(4,2048,.62f,.08f,64));
                try {
                    pipeline.applyVisualSettings(visual);pipeline.build();
                    try(var reference=new VolumetricRayReference(pipeline,cfg.stride,cfg.rows,cfg.steps.length)) {
                        if(probe!=null)reference.probe(probe[0],probe[1]);
                        for(int frame=0;frame<=frames.getLast();frame++) {
                            SceneCaptureRoutes.apply(bundle.scene.camera(),spec.cameraPath(),frame);bundle.event(frame,pipeline);
                            boolean capture=frames.contains(frame);if(capture)reference.request(0,cfg.steps[0],cfg.steps[1]);
                            execute(driver,pipeline,1f/60);
                            if(capture) {
                                String stem=String.format(Locale.ROOT,"%04d",frame);Map<String,Object> row=new LinkedHashMap<>();captures.add(row);
                                row.put("scene",id.id());row.put("frame",frame);row.put("cameraPosition",bundle.scene.camera().position().get(new float[3]));
                                row.put("view",bundle.scene.camera().getViewMatrix().get(new float[16]));row.put("volume",pipeline.volumetricFogDiagnostics());
                                row.put("shadow",pipeline.lastRender3dDiagnostics().shadows());row.put("referenceStorageBytes",reference.storageBytes());
                                row.put("counters",pipeline.captureVolumetricCounters());
                                var samePoint=reference.captureSourceComparison();row.put("samePointSourceComparison",samePoint);
                                if(samePoint.mismatchedVoxels()!=0||samePoint.nonfiniteVoxels()!=0)failures.add(id.id()+" frame "+frame+" full-light same-point source "+samePoint);
                                List<Map<String,Object>> convergence=new ArrayList<>();row.put("convergence",convergence);
                                int selected=1;float[] a=reference.capturePlane(3),b=reference.capturePlane(5);
                                for(int index=1;index<cfg.steps.length;index++) {
                                    if(index>1) {reference.request(index,cfg.steps[index]);execute(driver,pipeline,0);a=b;b=reference.capturePlane(3+2*index);}
                                    var metrics=cfg.measure(a,b,reference.width());boolean converged=metrics.passed(cfg.convergenceNrmse,cfg.convergenceP95,cfg.darkAbsolute);
                                    convergence.add(Map.of("steps",List.of(cfg.steps[index-1],cfg.steps[index]),"metrics",metrics,"converged",converged));
                                    save(directory,stem+"-reference-"+cfg.steps[index-1],a,reference.width(),reference.height());
                                    save(directory,stem+"-reference-"+cfg.steps[index],b,reference.width(),reference.height());
                                    selected=index;if(converged)break;
                                    if(index==cfg.steps.length-1)failures.add(id.id()+" frame "+frame+" reference did not converge");
                                }
                                float[] candidate=reference.capturePlane(0),candidateST=reference.capturePlane(1),referenceST=reference.capturePlane(4+2*selected);
                                var metrics=cfg.measure(candidate,b,reference.width());boolean passed=metrics.passed(cfg.imageNrmse,cfg.imageP95,cfg.darkAbsolute);
                                row.put("selectedReferenceSteps",cfg.steps[selected]);row.put("imageMetrics",metrics);row.put("transportMetrics",cfg.measure(candidateST,referenceST,reference.width()));
                                float[] evaluations=reference.captureEvaluationCounts();double min=Double.POSITIVE_INFINITY,max=0,total=0;int truncated=0;
                                for(int p=0;p<evaluations.length;p+=4){min=Math.min(min,evaluations[p]);max=Math.max(max,evaluations[p]);total+=evaluations[p];if(evaluations[p+1]>0)truncated++;}
                                row.put("referenceEvaluations",Map.of("minimum",min,"maximum",max,"mean",total/(evaluations.length/4),"truncatedRays",truncated));
                                if(truncated!=0)failures.add(id.id()+" frame "+frame+" truncated adaptive reference rays");
                                row.put("passed",passed);if(!passed)failures.add(id.id()+" frame "+frame+" image metrics "+metrics);
                                if(probe!=null)row.put("reconstructionProbe",reference.captureProbe());
                                var counters=pipeline.captureVolumetricCounters();
                                if(counters.nonfiniteMedium()+counters.limitedMedium()+counters.limitedSource()+counters.limitedIntegral()!=0)
                                    failures.add(id.id()+" frame "+frame+" numeric diagnostic counters are nonzero");
                                save(directory,stem+"-candidate",candidate,reference.width(),reference.height());
                                save(directory,stem+"-candidate-ST",candidateST,reference.width(),reference.height());
                                save(directory,stem+"-reference-ST",referenceST,reference.width(),reference.height());
                                save(directory,stem+"-opaque-base",reference.capturePlane(2),reference.width(),reference.height());
                                VolumetricImageMetrics.errorImage(directory.resolve(stem+"-error.png"),candidate,b,reference.width(),reference.height());
                                SceneCaptureIO.linear(directory.resolve(stem+"-native-final.h4f.gz"),pipeline.captureLinearHdrRgbaFloat(),cfg.width,cfg.height);
                                System.out.println("Volume image "+id.id()+" frame="+frame+" steps="+cfg.steps[selected]+" NRMSE="+metrics.nrmse()+" P95="+metrics.p95Normalized()+" passed="+passed);
                            }
                        }
                    }
                }finally{pipeline.close();}
            }
            if(!GlDebug.resources().liveResources().isEmpty())throw new IllegalStateException("image reference resource leak: "+GlDebug.resources());
        }
    }
    private static void execute(FrameDriver driver,RenderPipeline pipeline,float dt) {
        driver.beginFrame();try{pipeline.execute(driver.device(),dt);driver.recordGraph(pipeline.graph());driver.endFrame();}
        catch(RuntimeException|Error e){driver.failFrame(pipeline.graph(),e);throw e;}GlDebug.assertNoError("volume image reference");
    }
    private static void save(Path directory,String stem,float[] rgba,int width,int height) throws IOException {
        SceneCaptureIO.linear(directory.resolve(stem+".h4f.gz"),rgba,width,height);
        // H4F stores RGB; the separate RGBA file preserves T for transport evidence.
        try(var out=new DataOutputStream(Files.newOutputStream(directory.resolve(stem+".rgba.f32")))){
            out.writeInt(width);out.writeInt(height);for(float value:rgba)out.writeFloat(value);}
        VolumetricImageMetrics.preview(directory.resolve(stem+".png"),rgba,width,height);
    }

    private record Config(int width,int height,int stride,int x,int y,int roiWidth,int roiHeight,int rows,int[] steps,
            double epsilon,double floor,double darkLuminance,double darkAbsolute,double imageNrmse,double imageP95,
            double convergenceNrmse,double convergenceP95) {
        static Config load() throws IOException {
            Properties p=new Properties();try(var in=Files.newInputStream(Path.of("config/volumetric-fog/quality.properties"))){p.load(in);}
            java.util.function.ToIntFunction<String> i=k->Integer.parseInt(Objects.requireNonNull(p.getProperty(k),k));
            java.util.function.ToDoubleFunction<String> d=k->Double.parseDouble(Objects.requireNonNull(p.getProperty(k),k));
            int[] steps=Arrays.stream(p.getProperty("referenceSteps").split(",")).mapToInt(Integer::parseInt).toArray();
            Config value=new Config(i.applyAsInt("nativeWidth"),i.applyAsInt("nativeHeight"),i.applyAsInt("sampleStride"),i.applyAsInt("roi.x"),i.applyAsInt("roi.y"),
                    i.applyAsInt("roi.width"),i.applyAsInt("roi.height"),i.applyAsInt("referenceDispatchRows"),steps,d.applyAsDouble("normalizationEpsilon"),
                    d.applyAsDouble("pixelNormalizationFloor"),d.applyAsDouble("darkLuminanceThreshold"),d.applyAsDouble("darkAbsoluteThreshold"),
                    d.applyAsDouble("imageNrmse"),d.applyAsDouble("imageP95Normalized"),d.applyAsDouble("convergenceNrmse"),d.applyAsDouble("convergenceP95Normalized"));
            if(i.applyAsInt("schema")!=1||i.applyAsInt("referenceInitialSteps")!=2||value.width<1||value.height<1||value.stride<1||value.rows<1
                    ||value.x<0||value.y<0||value.roiWidth<1||value.roiHeight<1||value.x+value.roiWidth>(value.width+value.stride-1)/value.stride
                    ||value.y+value.roiHeight>(value.height+value.stride-1)/value.stride||steps.length<2||steps[0]!=256||steps[1]!=512)
                throw new IllegalArgumentException("invalid frozen image quality configuration");return value;
        }
        VolumetricImageMetrics.Result measure(float[] a,float[] b,int width){return VolumetricImageMetrics.measure(a,b,width,x,y,roiWidth,roiHeight,epsilon,floor,darkLuminance);}
    }
}
