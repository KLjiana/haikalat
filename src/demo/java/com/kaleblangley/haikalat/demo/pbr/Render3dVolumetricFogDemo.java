package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.*;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;
import static com.kaleblangley.haikalat.demo.pbr.VolumetricDemoSceneFactory.SceneId;

/** Formal four-scene entry; image/benchmark gates use the same factory and profile codec. */
public final class Render3dVolumetricFogDemo {
    private Render3dVolumetricFogDemo() { }

    public static void main(String[] args) throws Exception {
        Options options=Options.parse(args);
        if(options.diagnostics)System.setProperty("haikalat.internal.volume.diagnostics","true");
        String fingerprint=Render3dClusteredBenchmarkSuite.fingerprint();
        Path output=Path.of("build/reports/render3d-v0250",fingerprint,UUID.randomUUID().toString());
        Files.createDirectories(output);
        VisualProfile initial=VolumetricDemoSceneFactory.profile(options.scene);
        Path profilePath=options.profile==null?Path.of("config/volumetric-fog/profiles",options.scene.id()+".properties"):options.profile;
        VisualProfile loaded=VisualProfileCodec.load(profilePath,initial);
        var visual=loaded.settings();var f=visual.volumetricFog();
        var volume=new VolumetricFogSettings(!options.fogOff&&f.enabled(),f.fogDistance(),
                options.quality==null?f.quality():options.quality,f.globalMedium(),f.localVolumes(),
                f.anisotropy(),!options.historyOff&&f.history(),f.historyWeight(),f.noiseSeed(),f.wind());
        AntiAliasingMode aa=options.aa==null?visual.antiAliasingMode():options.aa;
        visual=new VisualSettings(visual.manualExposure(),visual.iblIntensity(),visual.iblRotationRadians(),
                visual.directionalLightDirection(),visual.directionalLightColor(),visual.directionalLightIntensity(),
                aa,visual.bloom(),visual.outdoor(),volume);
        List<VisualProfile.LightVolumeOverride> hints=new ArrayList<>();
        for(var entry:loaded.lightVolumes()) {
            LightVolumeHints old=entry.hints();boolean directional=entry.sceneLightIndex()==0;
            hints.add(new VisualProfile.LightVolumeOverride(entry.sceneLightIndex(),new LightVolumeHints(
                    options.localVolumeOff&&!directional?0:old.scatteringIntensity(),
                    !options.localShadowOff&&old.useAllocatedShadow(),old.temporalAccumulation())));
        }
        loaded=new VisualProfile(2,loaded.profileId(),loaded.description(),loaded.author(),visual,hints);
        SceneCaptureSpec capture=SceneCaptureSpecCodec.load(options.captureSpec==null
                ?Path.of("config/volumetric-fog/captures",options.scene.id()+".capture.properties"):options.captureSpec);
        if(options.path!=null) capture=new SceneCaptureSpec(capture.sceneRevision(),options.path,capture.seed(),capture.captureFrames());
        VisualProfileCodec.save(output.resolve("profile.properties"),loaded);
        SceneCaptureSpecCodec.save(output.resolve("capture.properties"),capture);
        Map<String,Object> report=new LinkedHashMap<>();report.put("schema",1);report.put("kind","volumetric-demo-capture");
        report.put("candidateFingerprint",fingerprint);report.put("runId",output.getFileName().toString());
        report.put("scene",options.scene.id());report.put("sceneRevision",capture.sceneRevision());
        report.put("cameraPath",capture.cameraPath());report.put("seed",capture.seed());report.put("nativeRenderSize",List.of(options.width,options.height));
        report.put("all128Requested",options.all128);report.put("performanceStatus","not a benchmark");
        report.put("qualityStatus","capture only; independent convergence gate required");report.put("releaseReady",false);
        List<Map<String,Object>> frames=new ArrayList<>();report.put("captures",frames);
        try(GlfwWindow window=new GlfwWindow.Builder().dimensions(options.width,options.height)
                .title("Haikalat v0.25 Volumetric Fog").visible(!options.hidden).build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();window.setVsync(!options.hidden);
            GlDebug.resourceTracking(true);
            RenderSettings settings=RenderSettings.builder().vsync(!options.hidden).antiAliasingMode(aa)
                    .msaaSamples(4).toneMappingMode(ToneMappingMode.ACES).exposureMode(ExposureMode.MANUAL)
                    .exposure(visual.manualExposure()).bloomSettings(visual.bloom()).build();
            try(FrameDriver driver=new FrameDriver(settings);
                OutdoorEnvironmentResources environments=new OutdoorEnvironmentResources(driver.device(),"default");
                PbrFallbackTextures fallback=new PbrFallbackTextures();
                ShaderProgram shader=ShaderProgram.fromResource(Render3dVolumetricFogDemo.class,
                        "/shaders/render3d/pbr/pbr-forward.vert","/shaders/render3d/pbr/pbr-forward.frag");
                var bundle=VolumetricDemoSceneFactory.create(options.scene,shader,fallback);
                VfxRenderer vfx=new VfxRenderer(true)) {
                VolumetricDemoSceneFactory.applyLights(bundle.scene,loaded,options.all128);
                if(options.all128&&bundle.scene.lights().stream().filter(l->l.type()!=LightType.DIRECTIONAL).count()!=128)
                    throw new IllegalArgumentException("--all-128 requires a town scene with exactly 128 local lights");
                RenderPipeline pipeline=new RenderPipeline(window,bundle.scene,null,settings,environments.environmentFor(visual.outdoor()))
                        .clusteredLighting(ClusteredLightingSettings.defaults())
                        .localShadows(LocalShadowPipelineSettings.balanced())
                        .directionalCascades(new DirectionalCascadeSettings(4,2048,.62f,.08f,64));
                EffectSnapshot[] snapshot={bundle.vfx(0)};
                if(options.scene==SceneId.TAVERN_THRESHOLD) pipeline.hdrVfxWithFog((res,cmd,camera,fog)-> {
                    int depth=fog==null?res.colorAttachment(PostProcessTargets.VFX_SCENE_DEPTH):res.colorAttachment(fog.softParticleDepthName());
                    vfx.record(cmd,snapshot[0],camera.projection(),camera.view(),depth,window.width(),window.height(),fog);
                });
                try {
                    pipeline.applyVisualSettings(visual);pipeline.build();
                    try(VolumetricFogOverlay overlay=options.noUi||options.hidden&&!options.overlay?null:new VolumetricFogOverlay(
                            window,pipeline,environments,loaded,output.resolve("profile.properties"),profilePath)) {
                        int limit=options.frames>0?options.frames:options.hidden?421:Integer.MAX_VALUE;
                        for(int frame=0;frame<limit&&!window.shouldClose();frame++) {
                            if(window.consumeResize())pipeline.resize(window.width(),window.height());
                            if(overlay!=null)overlay.update(window.inputSnapshot(),1f/60);
                            if(overlay==null||!overlay.routePaused())SceneCaptureRoutes.apply(bundle.scene.camera(),capture.cameraPath(),frame);
                            bundle.event(frame,pipeline);snapshot[0]=bundle.vfx(frame);
                            driver.beginFrame();
                            try {
                                if(options.orthographic) {
                                    Camera c=bundle.scene.camera();float aspect=(float)window.width()/window.height();
                                    Matrix4f projection=new Matrix4f().ortho(-12*aspect,12*aspect,-12,12,.1f,128);
                                    ExternalCamera camera=new ExternalCamera(c.getViewMatrix(),projection,
                                            new Matrix4f(projection).mul(c.getViewMatrix()),c.position(),0,.1f,128,frame);
                                    pipeline.execute(driver.device(),camera,PresentationTarget.defaultFramebuffer(window.width(),window.height()),1f/60);
                                } else pipeline.execute(driver.device(),1f/60);
                                driver.recordGraph(pipeline.graph());driver.endFrame();
                            } catch(RuntimeException|Error failure) { driver.failFrame(pipeline.graph(),failure);throw failure; }
                            if(capture.captureFrames().contains(frame)||options.captureFinal&&frame==limit-1
                                    ||overlay!=null&&overlay.consumeCaptureRequest()) {
                                String name=String.format(Locale.ROOT,"%s-%04d",options.scene.id(),frame);
                                SceneCaptureIO.display(window.width(),window.height(),output.resolve(name+".png").toString());
                                SceneCaptureIO.linear(output.resolve(name+".h4f.gz"),pipeline.captureLinearHdrRgbaFloat(),window.width(),window.height());
                                Map<String,Object> row=new LinkedHashMap<>();row.put("frame",frame);
                                row.put("cameraPosition",bundle.scene.camera().position().get(new float[3]));
                                row.put("view",bundle.scene.camera().getViewMatrix().get(new float[16]));row.put("orthographic",options.orthographic);
                                Matrix4f stableProjection=options.orthographic
                                        ?new Matrix4f().ortho(-12f*window.width()/window.height(),12f*window.width()/window.height(),-12,12,.1f,128)
                                        :new Matrix4f().perspective((float)Math.toRadians(bundle.scene.camera().zoom()),(float)window.width()/window.height(),.1f,100);
                                row.put("stableProjection",stableProjection.get(new float[16]));row.put("near",.1);row.put("far",options.orthographic?128:100);
                                row.put("volume",pipeline.volumetricFogDiagnostics());row.put("shadows",pipeline.lastRender3dDiagnostics().shadows());
                                if(options.diagnostics&&pipeline.volumetricFogDiagnostics().available())row.put("counters",pipeline.captureVolumetricCounters());
                                row.put("passProfiles",pipeline.graph().lastFrameProfile());frames.add(row);
                                VolumetricSliceExporter.save(output.resolve(name),pipeline,Math.min(24,pipeline.volumetricFogDiagnostics().depthSlices()-1));
                            }
                            GlDebug.assertNoError("volume demo frame");
                            if(!options.hidden){window.pollEvents();window.swapBuffers();}
                        }
                        report.put("surfaceLocalLights",bundle.scene.lights().stream().filter(l->l.type()!=LightType.DIRECTIONAL).count());
                        int injecting=0;for(int i=0;i<bundle.scene.lights().size();i++)if(bundle.scene.lights().get(i).type()!=LightType.DIRECTIONAL
                                &&bundle.scene.lightVolumeHints(i).scatteringIntensity()>0)injecting++;
                        report.put("volumeLocalLights",injecting);report.put("volume",pipeline.volumetricFogDiagnostics());
                        report.put("status","captured");
                    }
                } finally { pipeline.close(); }
            }
            if(!GlDebug.resources().liveResources().isEmpty())throw new IllegalStateException("volume demo resource leak: "+GlDebug.resources());
            report.put("resourceLeakCount",0);
            if(!fingerprint.equals(Render3dClusteredBenchmarkSuite.fingerprint()))throw new IllegalStateException("source changed during capture");
        } catch(Exception|Error failure) { report.put("status","failed");report.put("failure",failure.toString());throw failure; }
        finally { VolumetricEvidenceIO.save(output.resolve("manifest.json"),report); }
        System.out.println("Volumetric demo evidence: "+output.toAbsolutePath());
    }

    static final class Options {
        SceneId scene=SceneId.FOREST_MORNING;int width=1280,height=720,frames;
        boolean hidden,noUi,overlay,fogOff,localVolumeOff,localShadowOff,all128,historyOff,orthographic,captureFinal,diagnostics;
        Path profile,captureSpec;String path;AntiAliasingMode aa;VolumetricFogSettings.Quality quality;
        static Options parse(String[] args) {
            Options result=new Options();
            for(int i=0;i<args.length;i++) {
                String arg=args[i];int equals=arg.indexOf('=');String key=equals<0?arg:arg.substring(0,equals);
                Set<String> flags=Set.of("--hidden","--no-ui","--overlay","--fog-off","--local-volume-off","--local-shadow-off",
                        "--all-128","--history-off","--orthographic","--capture-final","--diagnostics");
                String value=equals<0?flags.contains(key)?"true":++i<args.length?args[i]:null:arg.substring(equals+1);
                if(value==null)throw new IllegalArgumentException("missing value for "+key);
                if(flags.contains(key)&&!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("boolean flag "+key+" must be true or false");
                switch(key) {
                    case "--scene" -> result.scene=SceneId.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--size" -> {String[] size=value.split("x");if(size.length!=2)throw new IllegalArgumentException("size must be WIDTHxHEIGHT");
                        result.width=Integer.parseInt(size[0]);result.height=Integer.parseInt(size[1]);}
                    case "--frames" -> result.frames=Integer.parseInt(value);
                    case "--hidden" -> result.hidden=Boolean.parseBoolean(value);case "--no-ui" -> result.noUi=Boolean.parseBoolean(value);
                    case "--overlay" -> result.overlay=Boolean.parseBoolean(value);
                    case "--fog-off" -> result.fogOff=Boolean.parseBoolean(value);case "--local-volume-off" -> result.localVolumeOff=Boolean.parseBoolean(value);
                    case "--local-shadow-off" -> result.localShadowOff=Boolean.parseBoolean(value);case "--all-128" -> result.all128=Boolean.parseBoolean(value);
                    case "--history-off" -> result.historyOff=Boolean.parseBoolean(value);case "--orthographic" -> result.orthographic=Boolean.parseBoolean(value);
                    case "--capture-final" -> result.captureFinal=Boolean.parseBoolean(value);
                    case "--diagnostics" -> result.diagnostics=Boolean.parseBoolean(value);
                    case "--profile" -> result.profile=Path.of(value);case "--capture-spec" -> result.captureSpec=Path.of(value);
                    case "--camera-path" -> result.path=value;case "--aa" -> result.aa=AntiAliasingMode.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--quality" -> result.quality=VolumetricFogSettings.Quality.valueOf(value.toUpperCase(Locale.ROOT));
                    default -> throw new IllegalArgumentException("unknown volume demo option "+key);
                }
            }
            if(result.width<1||result.height<1||result.frames<0)throw new IllegalArgumentException("invalid size or frame count");return result;
        }
    }
}
