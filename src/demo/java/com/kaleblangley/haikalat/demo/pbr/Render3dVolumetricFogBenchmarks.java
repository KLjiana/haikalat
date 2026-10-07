package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget;
import com.kaleblangley.haikalat.runtime.*;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Native fog on/off matrix using submission identities and complete same-frame GPU sums. */
public final class Render3dVolumetricFogBenchmarks {
    private static final Set<String> VOLUME_PASSES=Set.of("VolumetricMedium","VolumetricLighting",
            "VolumetricTemporal","VolumetricIntegrate","VolumetricOpaqueComposite","VolumetricReactive");
    private Render3dVolumetricFogBenchmarks() { }

    public static void main(String[] arguments) throws Exception {
        Options options=Options.parse(arguments);Inputs inputs=Inputs.load();String fingerprint=Render3dClusteredBenchmarkSuite.fingerprint();
        String runId=UUID.randomUUID().toString();Path output=Path.of("build/reports/render3d-v0250",fingerprint,runId);Files.createDirectories(output);
        Files.copy(Path.of("config/volumetric-fog/benchmark.properties"),output.resolve("benchmark.properties"));
        Files.copy(Path.of("config/volumetric-fog/verification.properties"),output.resolve("verification.properties"));
        Map<String,Object> manifest=new LinkedHashMap<>();List<String> failures=new ArrayList<>();List<Map<String,Object>> comparisons=new ArrayList<>();
        manifest.put("schema",1);manifest.put("kind",options.isolation?"volumetric-cpu-attribution":"volumetric-native-benchmarks");
        manifest.put("candidateFingerprint",fingerprint);manifest.put("runId",runId);manifest.put("started",Instant.now().toString());
        manifest.put("formalSampling",options.formal());manifest.put("fullMatrix",options.only==null);manifest.put("cpuIsolation",options.isolation);
        manifest.put("options",options);manifest.put("inputs",inputs);manifest.put("hardwareBefore",hardware());
        manifest.put("comparisons",comparisons);manifest.put("failures",failures);manifest.put("releaseReady",false);
        manifest.put("disabledAgainstV0243","pending separate frozen-baseline comparison; fog on/off is not evidence for this gate");
        manifest.put("timingContract","Every compiled graph pass is required, including timer intervals around cached/empty passes; each frame is summed before percentile. On/off pairs use round and measured index. All explicit waits are reported.");
        var selected=configs().stream().filter(c->options.only==null||c.name.equals(options.only)).toList();
        if(selected.isEmpty())throw new IllegalArgumentException("unknown benchmark configuration "+options.only);
        Map<String,List<Round>> rounds=new LinkedHashMap<>();
        String oldDiagnostics=System.getProperty("haikalat.internal.volume.diagnostics");System.setProperty("haikalat.internal.volume.diagnostics","false");
        try {
            // Round-major and alternating A/B order. Neither variant changes light/shadow inputs.
            for(int round=0;round<options.rounds;round++)for(Config config:selected) {
                Round[] pair=new Round[2];
                for(boolean on:round%2==0?new boolean[]{false,true}:new boolean[]{true,false}) {
                    String label=config.name+"/round-"+round+"/"+(on?"on":"off");
                    try {
                        Round result=run(config,on,round,options,inputs,runId+"/"+label,output.resolve(label));
                        pair[on?1:0]=result;rounds.computeIfAbsent(config.name,ignored->new ArrayList<>()).add(result);
                        validate(result,options,inputs,failures);
                        System.out.printf(Locale.ROOT,"VOLUME BENCH %s round=%d fog=%s native=%dx%d executeP95=%.4fms gpuP95=%.4fms complete=%d/%d wait=%.3fms%n",
                                config.name,round,on,config.width,config.height,p95(result.cpu.execute),p95(gpuMillis(result)),
                                result.gpu.paired(result.requiredPasses).size(),options.measured,result.waits.stream().mapToDouble(Wait::milliseconds).sum());
                    } catch(Exception|Error failure) {
                        failures.add(label+": "+failure);VolumetricEvidenceIO.save(output.resolve(label).resolve("failure.json"),Map.of("failure",failure.toString()));
                    }
                }
                if(pair[0]!=null&&pair[1]!=null) {
                    Map<String,Object> comparison=compare(config,pair[0],pair[1],inputs,options,failures);comparisons.add(comparison);
                    VolumetricEvidenceIO.save(output.resolve(config.name).resolve("comparison-round-"+round+".json"),comparison);
                }
            }
            for(Config config:selected) {
                List<Round> values=rounds.getOrDefault(config.name,List.of());
                if(values.size()!=options.rounds*2)failures.add(config.name+": incomplete rounds");
                if(values.size()==options.rounds*2) {
                    var combined=combine(config,values,inputs,options,failures);comparisons.add(combined);
                    VolumetricEvidenceIO.save(output.resolve(config.name).resolve("comparison-all-rounds.json"),combined);
                }
            }
            if(!fingerprint.equals(Render3dClusteredBenchmarkSuite.fingerprint()))failures.add("source fingerprint changed during measurement");
            manifest.put("status",failures.isEmpty()?(options.formal()?"passed":"informal-complete"):"failed");
        } catch(Exception|Error failure) { failures.add(failure.toString());manifest.put("status","failed");throw failure; }
        finally {
            if(oldDiagnostics==null)System.clearProperty("haikalat.internal.volume.diagnostics");else System.setProperty("haikalat.internal.volume.diagnostics",oldDiagnostics);
            manifest.put("hardwareAfter",hardware());manifest.put("finished",Instant.now().toString());
            VolumetricEvidenceIO.save(output.resolve("manifest.json"),manifest);System.out.println("Volumetric benchmark evidence: "+output.toAbsolutePath());
        }
        if(!failures.isEmpty())throw new IllegalStateException("Volumetric benchmark gates failed: "+failures);
    }

    private static List<Config> configs() {
        return List.of(new Config("budget-128-1080p",1920,1080,128,false,true,false),
                new Config("budget-128-4k",3840,2160,128,false,true,false),
                new Config("stress-256-4k",3840,2160,256,false,false,false),
                new Config("stress-overlap-128-4k",3840,2160,128,true,false,false),
                new Config("tavern-16-vfx-1080p",1920,1080,128,false,false,true));
    }

    private static Round run(Config config,boolean on,int round,Options options,Inputs inputs,String runId,Path directory) throws Exception {
        Files.createDirectories(directory);long runStart=System.nanoTime();
        var id=config.vfx?VolumetricDemoSceneFactory.SceneId.TAVERN_THRESHOLD:VolumetricDemoSceneFactory.SceneId.TOWN_NIGHT;
        VisualProfile original=VolumetricDemoSceneFactory.profile(id);VisualProfile profile=config.vfx?original:stressProfile(original,inputs,config.lights);
        var f=profile.settings().volumetricFog();var fog=new VolumetricFogSettings(on,f.fogDistance(),f.quality(),f.globalMedium(),f.localVolumes(),
                f.anisotropy(),f.history(),f.historyWeight(),f.noiseSeed(),f.wind());
        var visual=profile.settings().withVolumetricFog(fog);
        profile=new VisualProfile(2,profile.profileId(),profile.description(),profile.author(),visual,profile.lightVolumes());
        VisualProfileCodec.save(directory.resolve("profile.properties"),profile);
        Round result;
        try(var window=new GlfwWindow.Builder().dimensions(64,64).visible(false).title("Native volume benchmark "+config.name).build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();GlDebug.resourceTracking(true);window.setVsync(false);
            var settings=RenderSettings.builder().vsync(false).antiAliasingMode(AntiAliasingMode.NONE).toneMappingMode(ToneMappingMode.ACES)
                    .exposureMode(ExposureMode.MANUAL).exposure(visual.manualExposure()).bloomSettings(BloomSettings.disabled()).build();
            try(var target=OwnedPresentationTarget.create(config.width,config.height,RenderFormat.RGBA16F,true,1);
                var driver=new FrameDriver(settings);var environments=new OutdoorEnvironmentResources(driver.device(),"default");
                var fallback=new PbrFallbackTextures();var shader=ShaderProgram.fromResource(Render3dVolumetricFogBenchmarks.class,
                        "/shaders/render3d/pbr/pbr-forward.vert","/shaders/render3d/pbr/pbr-forward.frag");
                var bundle=VolumetricDemoSceneFactory.create(id,shader,fallback);var vfx=new VfxRenderer(true)) {
                Scene scene=stressScene(bundle.scene,config,inputs);VolumetricDemoSceneFactory.applyLights(scene,profile,false);
                if(config.vfx){bundle.event(120,null);SceneCaptureRoutes.apply(scene.camera(),"threshold",120);}
                else SceneCaptureRoutes.apply(scene.camera(),inputs.cameraPath,inputs.cameraFrame);
                var c=scene.camera();var view=c.getViewMatrix(new Matrix4f());var projection=new Matrix4f().perspective((float)Math.toRadians(c.zoom()),
                        config.width/(float)config.height,.1f,100);
                var camera=ExternalCamera.of(view,projection,c.position(),0);
                var pipeline=new RenderPipeline(target.target(),scene,null,settings,environments.environmentFor(visual.outdoor()))
                        .clusteredLighting(ClusteredLightingSettings.defaults()).localShadows(LocalShadowPipelineSettings.balanced())
                        .directionalCascades(new DirectionalCascadeSettings(4,2048,.62f,.08f,64));
                if(config.vfx) {
                    var snapshot=bundle.vfx(120);
                    pipeline.hdrVfxWithFog((resources,cmd,frameCamera,volume)-> {
                        int depth=volume==null?resources.colorAttachment(PostProcessTargets.VFX_SCENE_DEPTH):resources.colorAttachment(volume.softParticleDepthName());
                        vfx.record(cmd,snapshot,frameCamera.projection(),frameCamera.view(),depth,config.width,config.height,volume);
                    });
                }
                try {
                    pipeline.applyVisualSettings(visual);pipeline.build();pipeline.enableBenchmarkCpuTiming();pipeline.graph().enableGpuSampleCollection();
                    Set<String> required=Set.copyOf(pipeline.graph().description().executionOrder());
                    if(on&&!required.containsAll(VOLUME_PASSES))throw new IllegalStateException("missing formal volume timer intervals "+required);
                    if(!on&&required.stream().anyMatch(n->n.startsWith("Volumetric")))throw new IllegalStateException("disabled volume graph contains volume passes");
                    var collector=new BenchmarkSampleCollector(runId);List<Wait> waits=new ArrayList<>();List<CpuFrame> cpu=new ArrayList<>();
                    List<Map<String,Object>> warmupCpu=new ArrayList<>();Map<String,Object> state=new LinkedHashMap<>();long generation=-1;
                    for(int frame=0;frame<options.warmup+options.measured;frame++) {
                        boolean measured=frame>=options.warmup;int index=measured?frame-options.warmup:-1;
                        if(frame==options.warmup) {
                            long start=System.nanoTime();GL11.glFinish();drain(pipeline,collector,generation);
                            waits.add(new Wait("warmup-boundary",-1,millis(System.nanoTime()-start),false));
                            var diagnostics=pipeline.lastRender3dDiagnostics();generation=diagnostics.activeGenerationId();
                            state.put("shadow",diagnostics.shadows());state.put("volume",pipeline.volumetricFogDiagnostics());state.put("resources",GlDebug.resources());
                            state.put("graph",pipeline.graph().description());state.put("renderer",GL11.glGetString(GL11.GL_RENDERER));state.put("version",GL11.glGetString(GL11.GL_VERSION));
                            state.put("projection",projection.get(new float[16]));state.put("view",view.get(new float[16]));state.put("camera",c.position().get(new float[3]));
                            state.put("lights",lightInputs(scene));state.put("actualLocalLights",scene.lights().stream().filter(l->l.type()!=LightType.DIRECTIONAL).count());
                            state.put("contributingLocalLights",java.util.stream.IntStream.range(0,scene.lights().size()).filter(i->scene.lights().get(i).type()!=LightType.DIRECTIONAL&&scene.lightVolumeHints(i).scatteringIntensity()>0&&scene.lights().get(i).intensity()>0).count());
                            int expectedVolumeLights=config.vfx?16:config.lights;
                            if(((Number)state.get("contributingLocalLights")).intValue()!=expectedVolumeLights)throw new IllegalStateException("unexpected contributing volume light count "+state);
                            if(diagnostics.shadows().pointSelected()!=2||diagnostics.shadows().spotSelected()!=4)throw new IllegalStateException("formal input needs 2 Point + 4 Spot allocated shadow slots");
                            if(pipeline.graph().width()!=config.width||pipeline.graph().height()!=config.height)throw new IllegalStateException("internal render size is not native output size");
                            if(!on&&pipeline.volumetricFogDiagnostics().available())throw new IllegalStateException("disabled volume resources were allocated");
                        }
                        if(measured&&options.isolation) {
                            long start=System.nanoTime();GL11.glFinish();waits.add(new Wait("nonformal-cpu-isolation",index,millis(System.nanoTime()-start),false));
                        }
                        driver.beginFrame();long start=System.nanoTime();
                        long elapsed;
                        try{pipeline.execute(driver.device(),camera,target.target(),1f/60);elapsed=System.nanoTime()-start;driver.recordGraph(pipeline.graph());driver.endFrame();}
                        catch(RuntimeException|Error failure){driver.failFrame(pipeline.graph(),failure);throw failure;}
                        // The execute scope excludes collector and driver bookkeeping on both sides.
                        var profileFrame=pipeline.graph().lastFrameProfile();
                        if(generation<0)generation=pipeline.lastRender3dDiagnostics().activeGenerationId();
                        collector.register(new BenchmarkSampleCollector.FrameTicket(generation,profileFrame.frameSequence(),round,
                                measured?BenchmarkSampleCollector.Phase.MEASURED:BenchmarkSampleCollector.Phase.WARMUP,index));
                        for(PassProfile pass:profileFrame.passes())collector.acceptAttempt(generation,profileFrame.frameSequence(),pass);
                        var timing=pipeline.lastBenchmarkCpuTiming();if(!timing.available())throw new IllegalStateException("CPU timing unavailable");
                        if(measured)cpu.add(new CpuFrame(round,index,profileFrame.frameSequence(),elapsed,timing.framePreparationNanos(),timing.lightPackAndRecordNanos(),
                                timing.graphRecordNanos(),timing.deviceSubmitNanos()));
                        else warmupCpu.add(Map.of("frameSequence",profileFrame.frameSequence(),"wallNanos",elapsed));
                        drain(pipeline,collector,generation);
                        if(measured&&((index+1)%inputs.batch==0||index+1==options.measured))waits.add(waitFor(pipeline,collector,required,generation,index,inputs.timeout));
                    }
                    drain(pipeline,collector,generation);var snapshot=collector.snapshot(required);
                    var arrays=CpuArrays.of(cpu);state.put("volumeFinal",pipeline.volumetricFogDiagnostics());state.put("resourcesFinal",GlDebug.resources());
                    result=new Round(config.name,on,round,config.width,config.height,required,arrays,snapshot,List.copyOf(waits),state,millis(System.nanoTime()-runStart));
                    VolumetricEvidenceIO.save(directory.resolve("cpu.json"),Map.of("measured",cpu,"warmup",warmupCpu,"waits",waits));
                    VolumetricEvidenceIO.save(directory.resolve("gpu.json"),snapshot);
                    VolumetricEvidenceIO.save(directory.resolve("result.json"),result);
                    GlDebug.assertNoError("native volume benchmark");
                } finally { pipeline.close(); }
            }
            if(!GlDebug.resources().liveResources().isEmpty())throw new IllegalStateException("benchmark resources leaked "+GlDebug.resources());
        }
        return result;
    }

    private static Scene stressScene(Scene source,Config config,Inputs inputs) {
        if(!config.overlap&&config.lights==128)return source;
        Scene result=new Scene(source.camera());for(var renderer:source.renderers())result.add(renderer);
        for(SceneLight light:source.lights())result.addLight(copyLight(light,config.overlap?inputs.overlapRange:light.range(),light.castShadows()));
        for(int index=128;index<config.lights;index++) {
            SceneLight light=source.lights().get(1+index%128);result.addLight(copyLight(light,light.range(),false));
        }
        return result;
    }
    private static SceneLight copyLight(SceneLight l,float range,boolean shadow) {
        return new SceneLight(l.type(),l.color(),l.intensity(),l.direction(),l.position(),range,l.innerConeRadians(),l.outerConeRadians(),shadow);
    }
    private static VisualProfile stressProfile(VisualProfile profile,Inputs inputs,int lights) {
        var original=profile.settings().volumetricFog();List<LocalFogVolume> volumes=new ArrayList<>();
        for(int i=0;i<8;i++)volumes.add(new LocalFogVolume(i%2==0?LocalFogVolume.Shape.BOX:LocalFogVolume.Shape.SPHERE,
                new Vector3f(i%2==0?-3.5f:3.5f,1.25f,18-i*4.5f),new Vector3f(4,1.5f,5),.002f,
                new Vector3f(.75f,.8f,.88f),i==0?new Vector3f(.0001f,.00004f,.00001f):new Vector3f(),.35f,.2f,.2f));
        var fog=new VolumetricFogSettings(true,inputs.fogDistance,VolumetricFogSettings.Quality.BALANCED,original.globalMedium(),volumes,
                original.anisotropy(),true,.9f,242,new Vector3f());
        List<VisualProfile.LightVolumeOverride> hints=java.util.stream.IntStream.rangeClosed(1,lights)
                .mapToObj(i->new VisualProfile.LightVolumeOverride(i,LightVolumeHints.DEFAULT)).toList();
        return new VisualProfile(2,"town-all-local-8-volumes","Frozen native stress input, all local lights, static cached six local shadows",
                profile.author(),profile.settings().withVolumetricFog(fog),hints);
    }
    private static List<Map<String,Object>> lightInputs(Scene scene) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(int index=0;index<scene.lights().size();index++) {
            SceneLight l=scene.lights().get(index);var row=new LinkedHashMap<String,Object>();row.put("sceneIndex",index);
            row.put("type",l.type());row.put("position",l.position().get(new float[3]));row.put("direction",l.direction().get(new float[3]));
            row.put("color",l.color().get(new float[3]));row.put("intensity",l.intensity());row.put("range",l.range());
            row.put("innerCone",l.innerConeRadians());row.put("outerCone",l.outerConeRadians());row.put("castShadow",l.castShadows());row.put("volumeHints",scene.lightVolumeHints(index));result.add(row);
        }
        return result;
    }
    private static void drain(RenderPipeline p,BenchmarkSampleCollector collector,long generation) {
        for(PassProfile sample:p.graph().drainGpuSamples())collector.acceptAvailable(generation,sample);
    }
    private static Wait waitFor(RenderPipeline p,BenchmarkSampleCollector collector,Set<String> required,long generation,int index,long timeout) {
        long start=System.nanoTime(),deadline=start+TimeUnit.MILLISECONDS.toNanos(timeout);GL11.glFlush();
        while(!collector.terminalThroughMeasuredIndex(required,index)&&System.nanoTime()<deadline) {
            drain(p,collector,generation);if(!collector.terminalThroughMeasuredIndex(required,index))LockSupport.parkNanos(1_000_000);
        }
        drain(p,collector,generation);return new Wait("bounded-query-checkpoint-and-tail",index,millis(System.nanoTime()-start),!collector.terminalThroughMeasuredIndex(required,index));
    }
    private static void validate(Round r,Options options,Inputs input,List<String> failures) {
        if(r.cpu.execute.length!=options.measured)failures.add(r.config+" CPU count mismatch");
        for(var coverage:r.gpu.coverage())if(coverage.expected()>0&&coverage.ratio()<input.coverage)
            failures.add(r.config+" round "+r.round+" fog="+r.on+" "+coverage.passName()+" coverage="+coverage.ratio());
        if(r.gpu.paired(r.requiredPasses).size()/(double)options.measured<input.coverage)failures.add(r.config+" round "+r.round+" complete GPU coverage below "+input.coverage);
        if(!r.gpu.failures().isEmpty())failures.addAll(r.gpu.failures());
        if(r.waits.stream().anyMatch(Wait::timedOut))failures.add(r.config+" round "+r.round+" GPU query drain timed out");
        if(r.on) {
            var diag=(VolumetricFogDiagnostics)r.state.get("volume");
            if(diag.ownedBytes()>input.ownedBudget||diag.residentBytes()>input.residentBudget)failures.add(r.config+" volume memory exceeds the frozen budget");
            if(diag.residentBytes()*2>input.peakBudget)failures.add(r.config+" equal-size resize overlap exceeds the frozen budget");
        }
    }
    private static Map<String,Object> compare(Config config,Round off,Round on,Inputs inputs,Options options,List<String> failures) {
        return comparison(config,"round-"+off.round,List.of(off),List.of(on),inputs,options,failures);
    }
    private static Map<String,Object> combine(Config config,List<Round> rounds,Inputs inputs,Options options,List<String> failures) {
        return comparison(config,"all-rounds",rounds.stream().filter(r->!r.on).toList(),rounds.stream().filter(r->r.on).toList(),inputs,options,failures);
    }
    private static Map<String,Object> comparison(Config config,String scope,List<Round> off,List<Round> on,Inputs inputs,Options options,List<String> failures) {
        List<Double> cpuDeltas=new ArrayList<>(),gpuDeltas=new ArrayList<>();List<Map<String,Object>> paired=new ArrayList<>();
        for(Round a:off) {
            Round b=on.stream().filter(r->r.round==a.round).findFirst().orElseThrow();Map<Integer,Long> aa=gpuByIndex(a),bb=gpuByIndex(b);
            if(!shadowInput((Render3dDiagnostics.ShadowSummary)a.state.get("shadow")).equals(shadowInput((Render3dDiagnostics.ShadowSummary)b.state.get("shadow"))))
                failures.add(config.name+" "+scope+" shadow selection/cache input differs on/off");
            for(int i=0;i<a.cpu.execute.length;i++) {
                double cpu=b.cpu.execute[i]-a.cpu.execute[i];cpuDeltas.add(cpu);
                Long x=aa.get(i),y=bb.get(i);if(x!=null&&y!=null) {
                    double gpu=millis(y-x);gpuDeltas.add(gpu);paired.add(Map.of("round",a.round,"measuredIndex",i,"offGpuNanos",x,"onGpuNanos",y,"gpuDeltaMs",gpu,"cpuDeltaMs",cpu));
                }
            }
        }
        double coverage=gpuDeltas.size()/(double)(off.size()*options.measured);double gpuP95=p95(gpuDeltas),cpuP95=p95(cpuDeltas);
        double budget=config.height==1080?inputs.gpu1080:inputs.gpu4k;boolean budgetApplies=config.budget&&!options.isolation;
        var result=new LinkedHashMap<String,Object>();result.put("configuration",config);result.put("scope",scope);result.put("gpuPairedCoverage",coverage);
        result.put("gpuPairedDeltaP50Ms",percentile(gpuDeltas,.5));result.put("gpuPairedDeltaP95Ms",gpuP95);
        result.put("cpuExecutePairedDeltaP50Ms",percentile(cpuDeltas,.5));result.put("cpuExecutePairedDeltaP95Ms",cpuP95);
        result.put("budgetApplies",budgetApplies);result.put("gpuBudgetMs",budget);result.put("cpuBudgetMs",inputs.cpuBudget);result.put("pairedSamples",paired);
        result.put("onPasses",passStatistics(on));result.put("offPasses",passStatistics(off));
        long offBytes=((GlDebug.ResourceSnapshot)off.getFirst().state.get("resources")).estimatedBytes();
        long onBytes=((GlDebug.ResourceSnapshot)on.getFirst().state.get("resources")).estimatedBytes();
        result.put("allTrackedResourceDeltaBytes",onBytes-offBytes);result.put("onAllTrackedBytes",onBytes);result.put("offAllTrackedBytes",offBytes);
        result.put("memoryAccounting","The tracked delta includes shared DEPTH demand and all extra graph targets. Volume diagnostics count private 3D/parameters; native 2D fog composition/reactive plus private bytes are separately gated.");
        var volume=(VolumetricFogDiagnostics)on.getFirst().state.get("volume");
        long fogStorage=volume.residentBytes()+(config.vfx?13L:9L)*config.width*config.height;
        result.put("privateAndSingleSampleFogTargetBytes",fogStorage);result.put("sameSizeResizeTwoGenerationBytes",fogStorage*2);
        if(fogStorage>inputs.residentBudget||fogStorage*2>inputs.peakBudget)failures.add(config.name+" "+scope+" fog storage exceeds frozen resident/transaction budgets");
        result.put("explicitWaitMs",java.util.stream.Stream.concat(off.stream(),on.stream()).flatMap(r->r.waits.stream()).mapToDouble(Wait::milliseconds).sum());
        if(coverage<inputs.coverage)failures.add(config.name+" "+scope+" paired GPU coverage="+coverage);
        if(budgetApplies&&gpuP95>budget)failures.add(config.name+" "+scope+" complete GPU delta p95="+gpuP95+"ms > "+budget+"ms");
        if(budgetApplies&&cpuP95>inputs.cpuBudget)failures.add(config.name+" "+scope+" CPU execute delta p95="+cpuP95+"ms > "+inputs.cpuBudget+"ms");
        System.out.printf(Locale.ROOT,"VOLUME COMPARE %s %s gpuDeltaP95=%.4fms cpuDeltaP95=%.4fms pairedCoverage=%.3f budgetApplies=%s%n",config.name,scope,gpuP95,cpuP95,coverage,budgetApplies);
        return result;
    }
    private static Map<String,Object> shadowInput(Render3dDiagnostics.ShadowSummary s) {
        var result=new LinkedHashMap<String,Object>();
        result.put("selection",List.of(s.directionalSelection(),s.pointSelection(),s.spotSelection()));result.put("cascadeCount",s.cascadeCount());
        result.put("splits",s.cascadeSplits());result.put("casters",s.cascadeCasters());result.put("filter",s.filterMode());
        result.put("atlas",List.of(s.pointResolution(),s.pointAtlasWidth(),s.pointAtlasHeight(),s.spotResolution(),s.spotAtlasWidth(),s.spotAtlasHeight()));
        result.put("depthBytes",s.estimatedDepthBytes());result.put("counts",List.of(s.pointCandidates(),s.pointSelected(),s.spotCandidates(),s.spotSelected()));
        result.put("cache",List.of(s.tilesRendered(),s.tilesReused(),s.cacheHits(),s.cacheMisses()));result.put("missReasons",s.missReasons());
        // IDs are process-local and differ for two independently created scenes; table index/slot and semantic light inputs are identical.
        result.put("selected",s.selectedLights().stream().map(l->new Render3dDiagnostics.SelectedShadowLight(l.frameLightIndex()+1,l.type(),l.frameLightIndex(),l.slot(),l.priority(),l.score())).toList());
        result.put("rejected",s.rejectedLights().stream().map(l->new Render3dDiagnostics.RejectedShadowLight(l.frameLightIndex()+1,l.type(),l.frameLightIndex(),l.reason(),l.priority(),l.score())).toList());
        return result;
    }
    private static Map<String,Object> passStatistics(List<Round> rounds) {
        Map<String,List<Double>> samples=new TreeMap<>();for(Round r:rounds)r.gpu.measuredByPass().forEach((name,values)-> {
            for(var v:values)samples.computeIfAbsent(name,n->new ArrayList<>()).add(millis(v.nanos()));
        });Map<String,Object> result=new TreeMap<>();samples.forEach((name,values)->result.put(name,Map.of("samples",values.size(),"p50Ms",percentile(values,.5),"p95Ms",p95(values))));return result;
    }
    private static Map<Integer,Long> gpuByIndex(Round r) {
        Map<Integer,Long> values=new LinkedHashMap<>();for(var p:r.gpu.paired(r.requiredPasses))values.put(p.measuredIndex(),p.nanos());return values;
    }
    private static List<Double> gpuMillis(Round r){return r.gpu.paired(r.requiredPasses).stream().map(p->millis(p.nanos())).toList();}
    private static double millis(long nanos){return nanos/1_000_000.0;}
    private static double p95(double[] a){return percentile(Arrays.stream(a).boxed().toList(),.95);}
    private static double p95(List<Double> a){return percentile(a,.95);}
    private static double percentile(List<Double> a,double fraction) {
        if(a.isEmpty())throw new IllegalStateException("missing samples, no zero substitution");double[] b=a.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return b[Math.min(b.length-1,Math.max(0,(int)Math.ceil(b.length*fraction)-1))];
    }
    private static Map<String,Object> hardware() {
        Map<String,Object> result=new LinkedHashMap<>();result.put("at",Instant.now().toString());
        result.put("gpuTelemetry",command("nvidia-smi","--query-gpu=name,driver_version,pstate,temperature.gpu,power.draw,power.limit,memory.total,memory.used,utilization.gpu","--format=csv,noheader"));
        result.put("battery",command("powershell.exe","-NoProfile","-Command","Get-CimInstance Win32_Battery | Select-Object BatteryStatus,EstimatedChargeRemaining | ConvertTo-Json -Compress"));
        result.put("unavailableTelemetry","N/A or missing fields are unknown; no inference of a fixed power cap or zero background load");return result;
    }
    private static String command(String... args) {
        try{Process p=new ProcessBuilder(args).redirectErrorStream(true).start();if(!p.waitFor(5,TimeUnit.SECONDS)){p.destroyForcibly();return "unavailable: timeout";}
            return new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();}catch(Exception e){return "unavailable: "+e.getClass().getSimpleName();}
    }
    private record Config(String name,int width,int height,int lights,boolean overlap,boolean budget,boolean vfx) { }
    private record Wait(String reason,int measuredIndex,double milliseconds,boolean timedOut) { }
    private record CpuFrame(int round,int measuredIndex,long sequence,long executeNanos,long framePreparationNanos,long lightPackAndRecordNanos,long graphRecordNanos,long deviceSubmitNanos) { }
    private record CpuArrays(double[] execute,double[] preparation,double[] lights,double[] record,double[] submit) {
        static CpuArrays of(List<CpuFrame> frames){return new CpuArrays(frames.stream().mapToDouble(f->millis(f.executeNanos)).toArray(),frames.stream().mapToDouble(f->millis(f.framePreparationNanos)).toArray(),
                frames.stream().mapToDouble(f->millis(f.lightPackAndRecordNanos)).toArray(),frames.stream().mapToDouble(f->millis(f.graphRecordNanos)).toArray(),frames.stream().mapToDouble(f->millis(f.deviceSubmitNanos)).toArray());}
    }
    private record Round(String config,boolean on,int round,int width,int height,Set<String> requiredPasses,CpuArrays cpu,BenchmarkSampleCollector.Snapshot gpu,
                         List<Wait> waits,Map<String,Object> state,double runWallMilliseconds) { }
    private record Options(int rounds,int warmup,int measured,boolean isolation,String only) {
        static Options parse(String[] args) {
            int rounds=3,warmup=120,measured=300;boolean isolation=false;String only=null;
            for(String arg:args) {
                if(arg.startsWith("--rounds="))rounds=Integer.parseInt(arg.substring(9));
                else if(arg.startsWith("--warmup="))warmup=Integer.parseInt(arg.substring(9));
                else if(arg.startsWith("--measured="))measured=Integer.parseInt(arg.substring(11));
                else if(arg.startsWith("--only="))only=arg.substring(7);
                else if(arg.equals("--cpu-isolation"))isolation=true;else throw new IllegalArgumentException("unknown benchmark argument "+arg);
            }
            if(rounds<1||warmup<1||measured<1)throw new IllegalArgumentException("positive frame counts required");return new Options(rounds,warmup,measured,isolation,only);
        }
        boolean formal(){return !isolation&&rounds>=3&&warmup>=120&&measured>=300;}
    }
    private record Inputs(String sceneRevision,int seed,String cameraPath,int cameraFrame,float fogDistance,float overlapRange,int batch,long timeout,
                          double coverage,double gpu1080,double gpu4k,double cpuBudget,long ownedBudget,long residentBudget,long peakBudget) {
        static Inputs load() throws Exception {
            Properties p=new Properties(),g=new Properties();try(var in=Files.newInputStream(Path.of("config/volumetric-fog/benchmark.properties"))){p.load(in);}
            try(var in=Files.newInputStream(Path.of("config/volumetric-fog/verification.properties"))){g.load(in);}
            if(!"1".equals(p.getProperty("schema"))||!"BALANCED".equals(p.getProperty("quality"))||!"8".equals(p.getProperty("localVolumeCount"))
                    ||!"NONE".equals(p.getProperty("antiAliasing"))||!"false".equals(p.getProperty("bloom")))throw new IllegalArgumentException("unsupported frozen benchmark input");
            return new Inputs(p.getProperty("sceneRevision"),Integer.parseInt(p.getProperty("seed")),p.getProperty("cameraPath"),Integer.parseInt(p.getProperty("cameraFrame")),
                    Float.parseFloat(p.getProperty("fogDistance")),Float.parseFloat(p.getProperty("overlapRange")),Integer.parseInt(p.getProperty("drainBatchFrames")),Long.parseLong(p.getProperty("drainTimeoutMillis")),
                    Double.parseDouble(g.getProperty("benchmark.minimumCoverage")),Double.parseDouble(g.getProperty("benchmark.gpuDelta1080pMs")),Double.parseDouble(g.getProperty("benchmark.gpuDelta4kMs")),
                    Double.parseDouble(g.getProperty("benchmark.cpuExecuteDeltaMs")),Long.parseLong(g.getProperty("budget.owned3dBytes")),Long.parseLong(g.getProperty("budget.singleSampleBytes")),Long.parseLong(g.getProperty("budget.singleSampleTransactionBytes")));
        }
    }
}
