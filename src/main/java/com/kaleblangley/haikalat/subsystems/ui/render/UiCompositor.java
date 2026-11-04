package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Executes registered subtrees at their original paint positions; graph owns every attachment.
 * Applications configure layers through UiSystem. The nested diagnostic records are advanced views;
 * direct registration/recording methods are engine integration hooks, not a standalone UI entry point.
 */
public final class UiCompositor implements AutoCloseable {
    public static final String LAYER_PASS_PREFIX="UiLayer/";
    public static final String BLUR_PASS_PREFIX="UiBlur/";
    private static final String PREPARE="@prepare";
    private final UiAttachmentOptions options;
    private Diagnostics diagnostics=Diagnostics.EMPTY;
    private RenderGraph graph;
    private UiCompositorPlan plan;
    private UiLayerFrame frame;
    private UiRenderer renderer;
    private PassResources currentResources;
    private UiRenderer.PreparedDraw prepared;
    private UiCompositeGpu gpu;
    private final UiLayerCache cache=new UiLayerCache();
    private final Set<String> cacheHits=new HashSet<>();
    private final Map<String,String> cacheDecisions=new LinkedHashMap<>();
    private String phase;
    private boolean registered,closed;
    private int composites,blurDraws;
    private String backdropProducer;
    private int backdropAttachment;
    private boolean resolveBackdrop;
    private String backdropFailure;
    private final Set<String> backdropLayers=new HashSet<>();
    private final Set<String> runtimeDisabledEffects=new HashSet<>();
    private static final String RESOLVE="UiCompositor/BackdropResolve";
    private long bytes,peakBytes;
    private LongSupplier retainedPixels=()->0L;
    private final Map<String,UiScreenRect> views=new HashMap<>();

    public UiCompositor(UiAttachmentOptions options) {this.options=Objects.requireNonNull(options);}
    /** Counts UI storage retained by the attachment owner outside this graph. */
    public void retainedIntermediatePixels(LongSupplier retainedPixels) {
        this.retainedPixels=Objects.requireNonNull(retainedPixels);
    }
    /** Preflight for an attachment candidate while the previous graph remains alive. */
    public static long initialPixels(RenderGraph graph,String dependency,UiAttachmentOptions options) {
        return new UiCompositor(options).preflight(graph,dependency);
    }
    /** Candidate preflight includes still-live storage without sacrificing an otherwise accepted group. */
    public static long initialPixels(RenderGraph graph,String dependency,UiAttachmentOptions options,long retained) {
        var compositor=new UiCompositor(options);compositor.retainedIntermediatePixels(()->retained);
        return compositor.preflight(graph,dependency);
    }
    private long preflight(RenderGraph graph,String dependency) {
        plan=UiCompositorPlan.build(options);
        long retained=retainedPixels.getAsLong();
        if(retained<0)throw new IllegalArgumentException("retained UI storage must be non-negative");
        long available=options.maximumIntermediatePixels()-retained;
        if(available<0)throw new IllegalArgumentException("UI candidate peak budget includes retiring attachment");
        var retainedPlan=UiCompositorPlan.build(options,Set.of(),available);
        if(!retainedPlan.acceptedIds().containsAll(plan.acceptedIds()))
            throw new IllegalArgumentException("UI candidate peak budget cannot preserve accepted groups");
        plan=retainedPlan;
        for(var layer:options.layers())validateSupported(layer);
        validateBackdrop(graph,dependency);
        long pixels=plan.intermediatePixels();
        if(resolveBackdrop) {
            var source=graph.description().passes().stream().filter(p->p.name().equals(backdropProducer)).findFirst().orElseThrow();
            pixels=Math.addExact(pixels,Math.multiplyExact((long)source.width(),source.height()));
            if(pixels>available&&plan.acceptedLayers().stream().noneMatch(l->l.backdropRequired())) {
                var disabled=new HashSet<String>();
                for(var layer:plan.acceptedLayers())if(layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR))disabled.add(layer.id());
                var disabledPlan=UiCompositorPlan.build(options,disabled,available);
                if(!disabledPlan.acceptedIds().containsAll(plan.acceptedIds()))
                    throw new IllegalArgumentException("UI candidate resolve budget cannot preserve accepted groups");
                plan=disabledPlan;validateBackdrop(graph,dependency);pixels=plan.intermediatePixels();
            }
        }
        if(pixels>available)throw new IllegalArgumentException("UI initial allocation budget includes backdrop resolve and retiring attachment");
        return pixels;
    }
    public void preparePrograms() {
        if(plan!=UiCompositorPlan.EMPTY&&!plan.acceptedLayers().isEmpty()&&gpu==null)gpu=new UiCompositeGpu();
    }
    /** Standalone topology-only registration is no longer an executable compositor. Use UiSystem. */
    @Deprecated public String registerPasses(RenderGraph graph,String dependency) {
        throw new IllegalStateException("executable UI layers require UiSystem.attachTo");
    }
    public String registerPasses(RenderGraph graph,String dependency,BiConsumer<PassResources,CommandBuffer> recorder) {
        Objects.requireNonNull(graph);Objects.requireNonNull(recorder);
        if(registered||graph.isTopologySealed())throw new IllegalStateException("compositor topology is already fixed");
        this.graph=graph;
        bytes=Math.multiplyExact(preflight(graph,dependency),8);peakBytes=bytes;
        if(plan==UiCompositorPlan.EMPTY){registered=true;return dependency;}
        var layers=new ArrayList<>(plan.acceptedLayers());
        Map<String,UiLayerDescription> byId=new HashMap<>();options.layers().forEach(l->byId.put(l.id(),l));
        layers.sort(Comparator.comparingInt((UiLayerDescription l)->depth(l,byId)).reversed());
        List<String> names=new ArrayList<>();names.add("UiCompositor/Prepare");
        layers.forEach(l->{names.add(LAYER_PASS_PREFIX+l.id());if(hasBlur(l)) {
            names.add(BLUR_PASS_PREFIX+l.id()+"/Horizontal");names.add(BLUR_PASS_PREFIX+l.id()+"/Vertical");
        }});
        if(resolveBackdrop)names.add(RESOLVE);
        for(String name:names)if(graph.hasPass(name))throw new IllegalArgumentException("UI pass already exists: "+name);
        graph.observeFrames((sequence,failure)->{
            cache.complete(failure);
            if(failure!=null&&renderer!=null)renderer.abort(prepared);
            prepared=null;
        });
        graph.addPass(names.getFirst()).computeOnly().dependsOn(dependency)
                .execute((r,c)->recordPhase(PREPARE,recorder,r,c));
        String previous=names.getFirst();
        if(resolveBackdrop) {
            var source=graph.description().passes().stream().filter(p->p.name().equals(backdropProducer)).findFirst().orElseThrow();
            graph.addPass(RESOLVE).createColor("UiBackdropResolved",RenderFormat.RGBA16F).fixedSize(source.width(),source.height())
                    .noClear().dependsOn(previous).execute((r,c)->recordPhase("@resolve",recorder,r,c));
            previous=RESOLVE;
        }
        for(var layer:layers) {
            if(hasBlur(layer)&&layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR))previous=registerBlur(graph,layer,previous,recorder);
            String name=LAYER_PASS_PREFIX+layer.id();
            graph.addPass(name).createColor("UiLayerColor/"+layer.id(),RenderFormat.RGBA16F)
                    .fixedSize(initialWidth(layer),initialHeight(layer))
                    .noClear().dependsOn(previous).execute((r,c)->recordPhase(layer.id(),recorder,r,c));
            previous=name;
            if(hasBlur(layer)&&layer.effects().contains(UiLayerDescription.Effect.BLUR))previous=registerBlur(graph,layer,previous,recorder);
        }
        diagnostics=new Diagnostics(plan.acceptedIds(),plan.fallbacks(),plan.intermediatePixels(),plan.acceptedLayers().size(),plan.directFallbacks());
        registered=true;return previous;
    }
    public void recordOverlay(BiConsumer<PassResources,CommandBuffer> recorder,PassResources r,CommandBuffer c) {
        recordPhase(UiLayerFrame.ROOT,recorder,r,c);
    }
    private void recordPhase(String id,BiConsumer<PassResources,CommandBuffer> recorder,PassResources r,CommandBuffer c) {
        if(closed)throw new IllegalStateException("UI compositor is closed");
        phase=id;try{recorder.accept(r,c);}finally{phase=null;}
    }
    public boolean beginsFrame() {return plan==UiCompositorPlan.EMPTY||PREPARE.equals(phase);}
    public boolean recordCurrentPass(UiRenderSnapshot snapshot,UiRenderer renderer,PassResources r,CommandBuffer c) {
        if(plan==UiCompositorPlan.EMPTY)return false;
        if(PREPARE.equals(phase)){prepare(snapshot,renderer,r);return true;}
        if(frame==null||prepared==null)throw new IllegalStateException("UI frame was not prepared");
        if(phase.equals("@resolve")) {
            if(backdropLayers.isEmpty())return true;
            var source=r.framebufferOfPass(backdropProducer);var target=r.currentTarget();
            c.enableFramebufferSrgb(false).blitFramebuffer(source.id(),target.id(),source.width(),source.height(),target.width(),target.height(),
                    org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT,org.lwjgl.opengl.GL11.GL_NEAREST,backdropAttachment);
            return true;
        }
        if(phase.startsWith("@blurX/")||phase.startsWith("@blurY/")) {recordBlur(phase.substring(7),phase.startsWith("@blurX/"),r,c);return true;}
        if(phase.isEmpty()) {
            var target=r.presentationTarget();int w=target.width(),h=target.height();
            var view=new UiScreenRect(0,0,snapshot.windowWidth(),snapshot.windowHeight());
            renderer.recordSegment(prepared,c,target.drawFramebufferId(),w,h,target.colorFormat()==RenderFormat.SRGB8_ALPHA8,
                    view,frame.operations(""),child->composite(child,c,target.drawFramebufferId(),w,h,target.colorFormat()==RenderFormat.SRGB8_ALPHA8,view));
            renderer.finish(prepared,c);return true;
        }
        var layer=frame.layer(phase);
        if(layer==null||cacheHits.contains(phase))return true;
        var target=r.currentTarget();var view=views.get(phase);
        c.enableScissor(false).clearColor(0,0,0,0).clear(true,false);
        if(backdropLayers.contains(phase)&&layer.description().blurRadius()>0) {
            UiScreenRect clip=layer.clip()==null?layer.bounds():layer.bounds().intersect(layer.clip());
            gpu.composite(c,filteredTarget(phase).colorAttachment(),1,target.id(),target.width(),target.height(),false,view,view,clip);
            composites++;
        }
        renderer.recordSegment(prepared,c,target.id(),target.width(),target.height(),false,view,layer.operations(),
                child->composite(child,c,target.id(),target.width(),target.height(),false,view));
        return true;
    }
    private void prepare(UiRenderSnapshot snapshot,UiRenderer renderer,PassResources resources) {
        this.currentResources=resources;this.renderer=renderer;renderer.abort(prepared);prepared=null;composites=0;blurDraws=0;views.clear();backdropLayers.clear();cache.begin();cacheHits.clear();cacheDecisions.clear();
        runtimeDisabledEffects.clear();
        Set<String> accepted=new HashSet<>(plan.acceptedIds());
        frame=UiLayerFrame.build(snapshot,options.layers(),accepted);
        double sx=(double)resources.framePresentationTarget().width()/Math.max(1,snapshot.windowWidth());
        double sy=(double)resources.framePresentationTarget().height()/Math.max(1,snapshot.windowHeight());
        if(backdropProducer!=null) {
            var source=resources.framebufferOfPass(backdropProducer);
            int texture=source.colorAttachment(backdropAttachment);
            if(source.colorAttachmentIsTexture(backdropAttachment)&&resources.framePresentationTarget().color().isPresent()
                    && resources.framePresentationTarget().color().get().textureId()==texture)
                throw new IllegalArgumentException("backdrop source aliases the current presentation color");
        }
        var extents=layerExtents(snapshot,sx,sy);
        var allocation=allocation(extents);var fallbacks=new ArrayList<>(plan.fallbacks());
        if(allocation.peak()>options.maximumIntermediatePixels()||!withinExtentLimit(extents)) {
            for(var layer:frame.layers())if(hasBlur(layer.description())) {
                runtimeDisabledEffects.add(layer.description().id());
                fallbacks.add(new Fallback(layer.description().id(),FallbackReason.OPTIONAL_EFFECT_BUDGET));
            }
            extents=layerExtents(snapshot,sx,sy);allocation=allocation(extents);
        }
        peakBytes=Math.multiplyExact(allocation.peak(),8);bytes=Math.multiplyExact(allocation.total(),8);
        if(allocation.peak()>options.maximumIntermediatePixels()||!withinExtentLimit(extents)) {
            bytes=Math.multiplyExact(allocation.current(),8);
            peakBytes=Math.multiplyExact(Math.addExact(allocation.current(),retainedPixels.getAsLong()),8);
            frame=UiLayerFrame.build(snapshot,options.layers(),Set.of());views.clear();backdropLayers.clear();
            for(var layer:plan.acceptedLayers())fallbacks.add(new Fallback(layer.id(),withinExtentLimit(extents)?FallbackReason.PIXEL_BUDGET:FallbackReason.EXTENT_LIMIT));
            diagnostics=new Diagnostics(List.of(),fallbacks,allocation.current(),0,options.layers().size());
        } else {
            graph.resizeManagedTargets(extents);
            if(backdropFailure!=null)for(var layer:plan.acceptedLayers())
                if(layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR))fallbacks.add(new Fallback(layer.id(),FallbackReason.BACKDROP_SOURCE_UNAVAILABLE));
            diagnostics=new Diagnostics(plan.acceptedIds(),fallbacks,allocation.total(),plan.acceptedLayers().size(),plan.directFallbacks());
        }
        if(!views.isEmpty()&&gpu==null)gpu=new UiCompositeGpu();
        var mapping=new HashMap<String,String>();
        for(var layer:frame.layers()) {
            String id=layer.description().id();var view=views.get(id);if(view==null)continue;
            var target=graphTarget(id);
            mapping.put(id,target.width()+"x"+target.height()+"/"+resources.framePresentationTarget().colorFormat()
                    +"/"+snapshot.contentScaleX()+"/"+snapshot.contentScaleY()+"/"+(view.x()*sx-Math.floor(view.x()*sx))
                    +"/"+(view.y()*sy-Math.floor(view.y()*sy)));
        }
        var keys=UiLayerCache.keys(frame,snapshot,renderer,views,mapping);
        for(var layer:frame.layers()) {
            String id=layer.description().id();if(!views.containsKey(id))continue;
            String storage=Integer.toString(graphTarget(id).colorAttachment());
            if(hasBlur(layer.description()))storage+="/"+filteredTarget(id).colorAttachment()+"/"+resources.framebufferOfPass(BLUR_PASS_PREFIX+id+"/Horizontal").colorAttachment();
            String decision=cache.evaluate(id,keys.get(id),storage,layer.description().cachePolicy(),
                    layer.description().effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR));
            cacheDecisions.put(id,decision);if(decision.equals("HIT"))cacheHits.add(id);
        }
        boolean needsVertices=frame.operations("").stream().anyMatch(op->op.batch()>=0)
                ||frame.layers().stream().anyMatch(layer->!cacheHits.contains(layer.description().id())&&layer.operations().stream().anyMatch(op->op.batch()>=0));
        prepared=renderer.prepare(snapshot,needsVertices);
    }
    private Map<String,RenderGraph.TargetExtent> layerExtents(UiRenderSnapshot snapshot,double sx,double sy) {
        views.clear();backdropLayers.clear();var extents=new LinkedHashMap<String,RenderGraph.TargetExtent>();
        for(var layer:frame.layers()) {
            validateSupported(layer.description());
            var bounds=frame.rasterBounds(layer.description().id(),snapshot,this::hasBlur)
                    .intersect(new UiScreenRect(0,0,snapshot.windowWidth(),snapshot.windowHeight()));
            if(layer.clip()!=null)bounds=bounds.intersect(layer.clip());
            if(bounds.isEmpty())bounds=new UiScreenRect(bounds.x(),bounds.y(),1/sx,1/sy);
            double padding=hasBlur(layer.description())?layer.description().blurRadius():0;
            bounds=new UiScreenRect(bounds.x()-padding,bounds.y()-padding,bounds.width()+padding*2,bounds.height()+padding*2);
            views.put(layer.description().id(),bounds);
            int width=Math.max(1,(int)Math.ceil(bounds.width()*sx*layer.description().framebufferScale()));
            int height=Math.max(1,(int)Math.ceil(bounds.height()*sy*layer.description().framebufferScale()));
            extents.put(LAYER_PASS_PREFIX+layer.description().id(),new RenderGraph.TargetExtent(width,height));
            if(hasBlur(layer.description())) {
                int downsample=layer.description().blurDownsample();
                var extent=new RenderGraph.TargetExtent((width+downsample-1)/downsample,(height+downsample-1)/downsample);
                extents.put(BLUR_PASS_PREFIX+layer.description().id()+"/Horizontal",extent);
                extents.put(BLUR_PASS_PREFIX+layer.description().id()+"/Vertical",extent);
            } else if(graph.hasPass(BLUR_PASS_PREFIX+layer.description().id()+"/Horizontal")) {
                extents.put(BLUR_PASS_PREFIX+layer.description().id()+"/Horizontal",new RenderGraph.TargetExtent(1,1));
                extents.put(BLUR_PASS_PREFIX+layer.description().id()+"/Vertical",new RenderGraph.TargetExtent(1,1));
            }
            if(hasBlur(layer.description())&&layer.description().effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR)&&backdropProducer!=null)
                backdropLayers.add(layer.description().id());
        }
        if(resolveBackdrop) {
            var source=currentResources.framebufferOfPass(backdropProducer);
            extents.put(RESOLVE,backdropLayers.isEmpty()?new RenderGraph.TargetExtent(1,1):new RenderGraph.TargetExtent(source.width(),source.height()));
        }
        return extents;
    }
    private record Allocation(long current,long total,long peak) { }
    private static boolean withinExtentLimit(Map<String,RenderGraph.TargetExtent> extents) {
        return extents.values().stream().allMatch(e->e.width()<=4096&&e.height()<=4096);
    }
    private Allocation allocation(Map<String,RenderGraph.TargetExtent> extents) {
        long current=0,total=0,extra=0;
        for(var pass:graph.description().passes()) {
            if(!pass.name().startsWith(LAYER_PASS_PREFIX)&&!pass.name().startsWith(BLUR_PASS_PREFIX)&&!pass.name().equals(RESOLVE))continue;
            var target=currentResources.framebufferOfPass(pass.name());current=Math.addExact(current,(long)target.width()*target.height());
            var wanted=extents.get(pass.name());
            if(wanted!=null&&(target.width()!=wanted.width()||target.height()!=wanted.height()))
                extra=Math.addExact(extra,(long)wanted.width()*wanted.height());
            total=Math.addExact(total,wanted==null?(long)target.width()*target.height():(long)wanted.width()*wanted.height());
        }
        return new Allocation(current,total,Math.addExact(Math.max(total,current+extra),retainedPixels.getAsLong()));
    }
    private void composite(String id,CommandBuffer c,int framebuffer,int width,int height,boolean srgb,UiScreenRect view) {
        var layer=frame.layer(id);var bounds=views.get(id);
        if(layer==null||bounds==null)return;
        var fbo=hasBlur(layer.description())&&layer.description().effects().contains(UiLayerDescription.Effect.BLUR)&&layer.description().blurRadius()>0
                ?filteredTarget(id):graphTarget(id);
        UiScreenRect clip=layer.clip();
        if(layer.description().maskMode()==UiLayerDescription.MaskMode.CLIP)
            clip=clip==null?layer.bounds():clip.intersect(layer.bounds());
        gpu.composite(c,fbo.colorAttachment(),layer.description().opacity(),framebuffer,width,height,srgb,view,bounds,clip);composites++;
    }
    private com.kaleblangley.haikalat.backend.framebuffer.Framebuffer graphTarget(String id) {
        // Read through the current frame resource view; no texture is retained across replacement.
        return currentResources.framebufferOfPass(LAYER_PASS_PREFIX+id);
    }
    private boolean hasBlur(UiLayerDescription layer) {
        return !plan.disabledEffects().contains(layer.id())&&!runtimeDisabledEffects.contains(layer.id())
                &&(layer.effects().contains(UiLayerDescription.Effect.BLUR)||layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR));
    }
    private int initialWidth(UiLayerDescription layer) {return Math.max(1,(int)Math.ceil((layer.bounds().width()+2*(hasBlur(layer)?layer.blurRadius():0))*layer.framebufferScale()));}
    private int initialHeight(UiLayerDescription layer) {return Math.max(1,(int)Math.ceil((layer.bounds().height()+2*(hasBlur(layer)?layer.blurRadius():0))*layer.framebufferScale()));}
    private String registerBlur(RenderGraph graph,UiLayerDescription layer,String previous,BiConsumer<PassResources,CommandBuffer> recorder) {
        int downsample=layer.blurDownsample();
        for(boolean horizontal:new boolean[]{true,false}) {
            String name=BLUR_PASS_PREFIX+layer.id()+(horizontal?"/Horizontal":"/Vertical");
            graph.addPass(name).createColor("UiBlurColor/"+layer.id()+(horizontal?"/Horizontal":"/Vertical"),RenderFormat.RGBA16F)
                    .fixedSize((initialWidth(layer)+downsample-1)/downsample,(initialHeight(layer)+downsample-1)/downsample)
                    .noClear().dependsOn(previous).execute((r,c)->recordPhase((horizontal?"@blurX/":"@blurY/")+layer.id(),recorder,r,c));
            previous=name;
        }
        return previous;
    }
    private com.kaleblangley.haikalat.backend.framebuffer.Framebuffer filteredTarget(String id) {
        return currentResources.framebufferOfPass(BLUR_PASS_PREFIX+id+"/Vertical");
    }
    private void recordBlur(String id,boolean horizontal,PassResources resources,CommandBuffer commands) {
        var layer=frame.layer(id);
        if(layer==null||cacheHits.contains(id)||!views.containsKey(id)||!hasBlur(layer.description())||layer.description().blurRadius()==0)return;
        if(layer.description().effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR)&&!backdropLayers.contains(id))return;
        var view=views.get(id);UiScreenRect inputView=view;int texture;
        if(!horizontal)texture=resources.framebufferOfPass(BLUR_PASS_PREFIX+id+"/Horizontal").colorAttachment();
        else if(backdropLayers.contains(id)) {
            texture=resolveBackdrop?resources.framebufferOfPass(RESOLVE).colorAttachment():resources.colorAttachment(options.backdropSource().logicalTextureName());
            inputView=new UiScreenRect(0,0,prepared.snapshot.windowWidth(),prepared.snapshot.windowHeight());
        } else texture=graphTarget(id).colorAttachment();
        var output=resources.currentTarget();
        if(texture==output.colorAttachment())throw new IllegalStateException("UI blur feedback: "+id);
        gpu.blur(commands,texture,layer.description().blurRadius(),horizontal,output.id(),output.width(),output.height(),inputView,view);blurDraws++;
    }
    private void validateBackdrop(RenderGraph graph,String dependency) {
        backdropProducer=null;resolveBackdrop=false;backdropFailure=null;backdropAttachment=0;
        boolean needed=plan.acceptedLayers().stream().anyMatch(l->hasBlur(l)&&l.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR));
        boolean required=options.layers().stream().anyMatch(l->l.backdropRequired());
        if(!needed&&!required)return;
        if(options.backdropSource().isAvailable()) {
            var description=graph.description();int dependencyIndex=description.executionOrder().indexOf(dependency);
            for(var pass:description.passes())for(int i=0;i<pass.colorAttachments().size();i++) {
                var attachment=pass.colorAttachments().get(i);
                if(attachment.logicalName().equals(options.backdropSource().logicalTextureName())) {
                    if(pass.targetKind()!=RenderGraph.TargetKind.MANAGED||description.executionOrder().indexOf(pass.name())>dependencyIndex)
                        throw new IllegalArgumentException("backdrop producer must precede UI: "+pass.name());
                    if(!Set.of("RGBA8","RGBA16F","SRGB8_ALPHA8").contains(attachment.format()))
                        throw new IllegalArgumentException("unsupported backdrop color encoding: "+attachment.format());
                    if(needed){backdropProducer=pass.name();backdropAttachment=i;resolveBackdrop=pass.samples()>1;}
                    return;
                }
            }
        }
        if(required)throw new IllegalArgumentException("required pre-UI backdrop source is unavailable");
        backdropFailure="explicit pre-UI source is unavailable";
    }
    private static int depth(UiLayerDescription layer,Map<String,UiLayerDescription> byId) {
        int depth=0;while(!layer.parentId().isEmpty()){depth++;layer=byId.get(layer.parentId());}return depth;
    }
    static void validateSupported(UiLayerDescription layer) {
        if(layer.blendMode()!=UiBlendMode.PREMULTIPLIED_ALPHA||layer.maskMode()==UiLayerDescription.MaskMode.ROUNDED||layer.zOrder()!=0
                ||layer.effects().stream().anyMatch(e->e!=UiLayerDescription.Effect.BLUR&&e!=UiLayerDescription.Effect.BACKDROP_BLUR)
                ||(layer.effects().contains(UiLayerDescription.Effect.BLUR)&&layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR)))
            throw new IllegalArgumentException("UNSUPPORTED_EFFECT for UI layer "+layer.id());
    }
    public Diagnostics diagnostics(){return diagnostics;}
    public RuntimeStatistics runtimeStatistics(){return new RuntimeStatistics(bytes,peakBytes,composites,blurDraws,renderer==null?0:renderer.lastDrawCalls(),cacheDecisions);}
    public record RuntimeStatistics(long bytes,long peakBytes,int composites,int blurDraws,int draws,Map<String,String> cache) {
        public RuntimeStatistics {cache=Map.copyOf(cache);}
    }
    @Override public void close(){if(closed)return;closed=true;if(renderer!=null)renderer.abort(prepared);if(gpu!=null)gpu.close();}

    public enum FallbackReason {
        LAYER_LIMIT,
        EXTENT_LIMIT,
        PIXEL_BUDGET,
        OPTIONAL_EFFECT_BUDGET,
        BACKDROP_SOURCE_UNAVAILABLE,
        TARGET_CREATION_FAILED
    }

    public record Fallback(String layerId, FallbackReason reason) {
        public Fallback {
            Objects.requireNonNull(layerId, "layerId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Diagnostics(List<String> activeLayerIds, List<Fallback> fallbacks,
                              long intermediatePixels, int activeLayers, int directFallbacks) {
        public static final Diagnostics EMPTY =
                new Diagnostics(List.of(), List.of(), 0L, 0, 0);

        public Diagnostics {
            activeLayerIds = List.copyOf(activeLayerIds);
            fallbacks = List.copyOf(fallbacks);
            if (intermediatePixels < 0L || activeLayers < 0 || directFallbacks < 0
                    || activeLayerIds.size() != activeLayers
                    || fallbacks.size() < directFallbacks) {
                throw new IllegalArgumentException("inconsistent compositor diagnostics");
            }
        }
    }
}
