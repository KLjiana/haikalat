package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.ui.*;
import com.kaleblangley.haikalat.subsystems.ui.style.*;
import com.kaleblangley.haikalat.subsystems.ui.widget.*;
import com.kaleblangley.haikalat.subsystems.windowing.*;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import org.joml.Vector3f;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/** Retained UI: immutable desired profile, explicit apply, atomic persistence, bounded diagnostics. */
final class VolumetricFogOverlay implements AutoCloseable {
    private final UiSystem ui;private final RenderPipeline pipeline;private final OutdoorEnvironmentResources environments;
    private final Path savePath,loadPath;private final Panel panel;private final Label status,diagnostics,lightLabel;
    private final Toggle pause;private VisualProfile desired,applied;private int selectedLight;private boolean capture;
    private Slider lightIntensity;private Toggle lightShadow,lightHistory;private boolean syncing;
    private final Map<String,Slider> sliders=new LinkedHashMap<>();
    private final Map<String,Toggle> toggles=new LinkedHashMap<>();

    VolumetricFogOverlay(GlfwWindow window,RenderPipeline pipeline,OutdoorEnvironmentResources environments,
                         VisualProfile initial,Path savePath,Path loadPath) {
        this.pipeline=pipeline;this.environments=environments;this.savePath=savePath;this.loadPath=loadPath;desired=applied=initial;
        selectedLight=initial.lightVolumes().isEmpty()?0:initial.lightVolumes().getFirst().sceneLightIndex();
        ui=UiSystem.create(window,UiConfig.defaults());
        ui.document().root().style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.percent(100))
                .padding(UiInsets.points(10)).alignItems(UiStyle.AlignItems.FLEX_END).build());
        panel=new Panel();panel.style(UiStyle.builder().width(UiLength.points(380)).height(UiLength.points(920))
                .flexShrink(0).gap(2).padding(UiInsets.points(8)).flexDirection(UiStyle.FlexDirection.COLUMN).build());
        ScrollView scroll=new ScrollView();scroll.style(UiStyle.builder().width(UiLength.points(390)).height(UiLength.percent(100)).build());
        scroll.content(panel);ui.document().root().add(scroll);panel.add(label("v0.25 Multi-light volumetric fog"));panel.add(label(initial.profileId()));
        status=label("Applied");
        row(button("Apply",this::apply),button("Save",()->attempt(()->VisualProfileCodec.save(savePath,desired))),
                button("Load",()->attempt(()->{var candidate=VisualProfileCodec.load(loadPath,applied);
                    VolumetricDemoSceneFactory.validateLights(pipeline.scene(),candidate);desired=candidate;
                    syncProfile();status.text("Loaded / dirty; press Apply");})),
                button("Capture",()->capture=true));
        panel.add(status);
        row(button("Low",()->quality(VolumetricFogSettings.Quality.LOW)),button("Balanced",()->quality(VolumetricFogSettings.Quality.BALANCED)),
                button("High",()->quality(VolumetricFogSettings.Quality.HIGH)));
        toggle("Fog",initial.settings().volumetricFog().enabled(),b->volume(f->copy(f,b,f.quality(),f.globalMedium(),f.localVolumes(),f.anisotropy(),f.history(),f.fogDistance())));
        toggle("Independent volume history",initial.settings().volumetricFog().history(),b->volume(f->copy(f,f.enabled(),f.quality(),f.globalMedium(),f.localVolumes(),f.anisotropy(),b,f.fogDistance())));
        pause=toggle("Pause camera path",false,b->{});
        slider("Fog distance",4,160,initial.settings().volumetricFog().fogDistance(),v->volume(f->copy(f,f.enabled(),f.quality(),f.globalMedium(),f.localVolumes(),f.anisotropy(),f.history(),(float)v)));
        slider("Extinction / world unit",0,.08,initial.settings().volumetricFog().globalMedium().extinction(),v->medium(m->new FogMediumSettings((float)v,m.albedo(),m.emission(),m.baseHeight(),m.heightFalloff())));
        slider("Height falloff",0,1,initial.settings().volumetricFog().globalMedium().heightFalloff(),v->medium(m->new FogMediumSettings(m.extinction(),m.albedo(),m.emission(),m.baseHeight(),(float)v)));
        slider("Base height",-5,8,initial.settings().volumetricFog().globalMedium().baseHeight(),v->medium(m->new FogMediumSettings(m.extinction(),m.albedo(),m.emission(),(float)v,m.heightFalloff())));
        for(int c=0;c<3;c++){int channel=c;slider("Albedo "+"RGB".charAt(c),0,1,initial.settings().volumetricFog().globalMedium().albedo().get(c),v->medium(m->{var albedo=m.albedo();albedo.setComponent(channel,(float)v);return new FogMediumSettings(m.extinction(),albedo,m.emission(),m.baseHeight(),m.heightFalloff());}));}
        slider("Anisotropy g",-.9,.9,initial.settings().volumetricFog().anisotropy(),v->volume(f->copy(f,f.enabled(),f.quality(),f.globalMedium(),f.localVolumes(),(float)v,f.history(),f.fogDistance())));
        if(!initial.settings().volumetricFog().localVolumes().isEmpty()) {
            var local=initial.settings().volumetricFog().localVolumes().getFirst();
            slider("First volume falloff",0,1,local.falloff(),v->local((old)->new LocalFogVolume(old.shape(),old.center(),old.extent(),old.extinction(),old.albedo(),old.emission(),(float)v,old.noiseScale(),old.noiseAmount())));
            for(int c=0;c<3;c++){int channel=c;slider("First half extent "+"XYZ".charAt(c),.1,24,local.extent().get(c),v->local(old->{Vector3f extent=old.extent();extent.setComponent(channel,(float)v);return new LocalFogVolume(old.shape(),old.center(),extent,old.extinction(),old.albedo(),old.emission(),old.falloff(),old.noiseScale(),old.noiseAmount());}));}
        }
        lightLabel=label("");panel.add(lightLabel);row(button("Previous light",()->select(-1)),button("Next light",()->select(1)));
        lightIntensity=slider("Volume intensity",0,2,hint().scatteringIntensity(),v->changeHint(new LightVolumeHints((float)v,hint().useAllocatedShadow(),hint().temporalAccumulation())));
        lightShadow=toggle("Use allocated shadow",hint().useAllocatedShadow(),b->changeHint(new LightVolumeHints(hint().scatteringIntensity(),b,hint().temporalAccumulation())));
        lightHistory=toggle("Light permits history",hint().temporalAccumulation(),b->changeHint(new LightVolumeHints(hint().scatteringIntensity(),hint().useAllocatedShadow(),b)));
        diagnostics=label("");panel.add(diagnostics);select(0);ui.attachTo(pipeline.graph(),pipeline.finalPassName());
    }

    private void apply(){attempt(()->{VolumetricDemoSceneFactory.validateLights(pipeline.scene(),desired);
        environments.applyProfile(pipeline,desired.settings());VolumetricDemoSceneFactory.applyLights(pipeline.scene(),desired,false);
        applied=desired;status.text("Applied");ui.rebindTo(pipeline.graph(),pipeline.finalPassName());});}
    private void attempt(Runnable work){try{work.run();}catch(RuntimeException failure){status.text("Rejected: "+failure.getMessage());}}
    private void dirty(){status.text(desired.equals(applied)?"Applied":"Dirty; press Apply");}
    private void volume(UnaryOperator<VolumetricFogSettings> update){if(syncing)return;attempt(()->{
        desired=new VisualProfile(2,desired.profileId(),desired.description(),desired.author(),desired.settings().withVolumetricFog(update.apply(desired.settings().volumetricFog())),desired.lightVolumes());dirty();});}
    private void medium(UnaryOperator<FogMediumSettings> update){volume(f->copy(f,f.enabled(),f.quality(),update.apply(f.globalMedium()),f.localVolumes(),f.anisotropy(),f.history(),f.fogDistance()));}
    private void local(UnaryOperator<LocalFogVolume> update){volume(f->{var list=new ArrayList<>(f.localVolumes());
        if(list.isEmpty())throw new IllegalStateException("Loaded profile has no local volume");
        list.set(0,update.apply(list.getFirst()));return copy(f,f.enabled(),f.quality(),f.globalMedium(),list,f.anisotropy(),f.history(),f.fogDistance());});}
    private void quality(VolumetricFogSettings.Quality q){volume(f->copy(f,f.enabled(),q,f.globalMedium(),f.localVolumes(),f.anisotropy(),f.history(),f.fogDistance()));}
    private static VolumetricFogSettings copy(VolumetricFogSettings f,boolean enabled,VolumetricFogSettings.Quality q,FogMediumSettings m,List<LocalFogVolume> locals,float g,boolean history,float distance){return new VolumetricFogSettings(enabled,distance,q,m,locals,g,history,f.historyWeight(),f.noiseSeed(),f.wind());}
    private LightVolumeHints hint(){return desired.lightVolumes().stream().filter(e->e.sceneLightIndex()==selectedLight).map(VisualProfile.LightVolumeOverride::hints).findFirst().orElse(LightVolumeHints.DISABLED);}
    private void changeHint(LightVolumeHints hint){if(syncing)return;var list=new ArrayList<>(desired.lightVolumes());list.removeIf(e->e.sceneLightIndex()==selectedLight);list.add(new VisualProfile.LightVolumeOverride(selectedLight,hint));
        desired=new VisualProfile(2,desired.profileId(),desired.description(),desired.author(),desired.settings(),list);dirty();}
    private void select(int delta){selectedLight=Math.floorMod(selectedLight+delta,pipeline.scene().lights().size());syncing=true;try{lightIntensity.value(hint().scatteringIntensity());lightShadow.value(hint().useAllocatedShadow());lightHistory.value(hint().temporalAccumulation());lightLabel.text("Selected scene light "+selectedLight+" / "+pipeline.scene().lights().get(selectedLight).type());}finally{syncing=false;}}
    private void syncProfile(){syncing=true;try{var f=desired.settings().volumetricFog();var m=f.globalMedium();
        toggles.get("Fog").value(f.enabled());toggles.get("Independent volume history").value(f.history());
        sliders.get("Fog distance").value(f.fogDistance());sliders.get("Extinction / world unit").value(m.extinction());
        sliders.get("Height falloff").value(m.heightFalloff());sliders.get("Base height").value(m.baseHeight());
        sliders.get("Anisotropy g").value(f.anisotropy());
        for(int c=0;c<3;c++)sliders.get("Albedo "+"RGB".charAt(c)).value(m.albedo().get(c));
        if(!f.localVolumes().isEmpty()&&sliders.containsKey("First volume falloff")){
            var local=f.localVolumes().getFirst();sliders.get("First volume falloff").value(local.falloff());
            for(int c=0;c<3;c++)sliders.get("First half extent "+"XYZ".charAt(c)).value(local.extent().get(c));
        }
    }finally{syncing=false;}select(0);}
    void update(WindowInputSnapshot input,float dt){ui.rebindTo(pipeline.graph(),pipeline.finalPassName());ui.update(input,dt);var d=pipeline.volumetricFogDiagnostics();
        diagnostics.text(d.available()?String.format(Locale.ROOT,"%dx%dx%d / %.1f MiB / %s (%d domains)",d.columnsX(),d.columnsY(),d.depthSlices(),d.ownedBytes()/1048576.0,d.rejectionReason(),d.dirtyDomains()):"Fog disabled / no volume storage");}
    boolean routePaused(){return pause.value();}boolean consumeCaptureRequest(){boolean value=capture;capture=false;return value;}
    private Label label(String text){Label value=new Label(text);value.style(UiStyle.builder().width(UiLength.percent(100)).height(UiLength.points(23)).build());return value;}
    private Button button(String text,Runnable action){Button value=new Button(text);value.style(UiStyle.builder().flexGrow(1).height(UiLength.points(25)).build());value.onClick(action);return value;}
    private void row(com.kaleblangley.haikalat.subsystems.ui.UiNode... children){Panel row=new Panel();row.style(UiStyle.builder().height(UiLength.points(27)).flexDirection(UiStyle.FlexDirection.ROW).gap(3).build());for(var child:children)row.add(child);panel.add(row);}
    private Toggle toggle(String text,boolean value,Consumer<Boolean> change){Toggle toggle=new Toggle(text).value(value);toggle.style(UiStyle.builder().height(UiLength.points(24)).build());toggle.onValueChanged(change);panel.add(toggle);toggles.put(text,toggle);return toggle;}
    private Slider slider(String text,double min,double max,double value,DoubleConsumer change){Label label=label(text);label.style(UiStyle.builder().width(UiLength.points(180)).height(UiLength.points(24)).build());Slider slider=new Slider(min,max,value).step((max-min)/200);slider.style(UiStyle.builder().flexGrow(1).height(UiLength.points(24)).build());slider.onValueChanged(change);row(label,slider);sliders.put(text,slider);return slider;}
    @Override public void close(){ui.close();}
}
