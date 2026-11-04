package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.*;
import com.kaleblangley.haikalat.subsystems.ui.render.*;
import java.util.Objects;
import java.util.Map;
import java.util.function.BiConsumer;

/** Frame-boundary attachment publication. Graph storage always remains owned by its caller. */
final class UiAttachmentController implements AutoCloseable {
    private final String overlayPassName;
    private Binding active;
    private boolean closed;
    private static final class Binding {
        final RenderGraph graph;
        final RenderGraph.PassExecutor prefix;
        final UiAttachmentOptions options;
        final BiConsumer<PassResources,CommandBuffer> recorder;
        final RenderGraph.FrameObserver completion;
        UiCompositor compositor;
        volatile boolean frameOpen;
        Binding(RenderGraph graph,RenderGraph.PassExecutor prefix,UiAttachmentOptions options,
                BiConsumer<PassResources,CommandBuffer> recorder,RenderGraph.FrameObserver completion) {
            this.graph=graph;this.prefix=prefix;this.options=options;this.recorder=recorder;this.completion=completion;
        }
    }
    UiAttachmentController(String name){overlayPassName=Objects.requireNonNull(name);}
    void attach(RenderGraph graph,String dependency,RenderGraph.PassExecutor prefix,BiConsumer<PassResources,CommandBuffer> recorder) {
        if(active!=null)throw new IllegalStateException("UiSystem is already attached to a RenderGraph");
        active=build(graph,dependency,prefix,null,recorder,null);
    }
    void attach(RenderGraph graph,String dependency,UiAttachmentOptions options,BiConsumer<PassResources,CommandBuffer> recorder,
                RenderGraph.FrameObserver completion) {
        if(active!=null)throw new IllegalStateException("UiSystem is already attached to a RenderGraph");
        active=build(graph,dependency,null,Objects.requireNonNull(options),recorder,completion);
    }
    private Binding build(RenderGraph graph,String dependency,RenderGraph.PassExecutor prefix,UiAttachmentOptions options,
                          BiConsumer<PassResources,CommandBuffer> recorder,RenderGraph.FrameObserver completion) {
        validateGraph(graph,dependency);Objects.requireNonNull(recorder);
        Binding binding=new Binding(graph,prefix,options,recorder,completion);
        try {
            String last=dependency;
            if(options!=null) {
                binding.compositor=new UiCompositor(options);
                last=binding.compositor.registerPasses(graph,dependency,(r,c)->record(binding,r,c));
            }
            graph.observeFrames((sequence,failure)->{
                try {if(binding==active&&binding.completion!=null)binding.completion.completed(sequence,failure);}
                finally {binding.frameOpen=false;}
            });
            graph.addPass(overlayPassName).writeToBackbuffer().noClear().dependsOn(last).execute((r,c)->{
                if(binding.compositor!=null)binding.compositor.recordOverlay((resources,commands)->record(binding,resources,commands),r,c);
                else {if(binding.prefix!=null)binding.prefix.execute(r,c);record(binding,r,c);}
            });
            graph.sealTopology();return binding;
        } catch(RuntimeException|Error failure) {
            if(binding.compositor!=null)try{binding.compositor.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private void record(Binding binding,PassResources resources,CommandBuffer commands) {
        if(binding!=active||closed)throw new IllegalStateException("retired or uncommitted UI attachment cannot render");
        binding.frameOpen=true;binding.recorder.accept(resources,commands);
    }
    Candidate prepare(RenderGraph graph,String dependency,UiAttachmentOptions options,
                      BiConsumer<PassResources,CommandBuffer> recorder,RenderGraph.FrameObserver completion) {
        if(closed||active==null)throw new IllegalStateException("prepareAttachment requires an active UI");
        if(active.frameOpen)throw new IllegalStateException("UI attachment edits require a frame boundary");
        if(graph==active.graph)throw new IllegalArgumentException("UI topology needs a separate candidate graph");
        long oldPixels=active.graph.isClosed()||active.compositor==null?0:active.compositor.runtimeStatistics().bytes()/8;
        long newPixels=UiCompositor.initialPixels(graph,dependency,options);
        if(Math.addExact(oldPixels,newPixels)>options.maximumIntermediatePixels())
            throw new IllegalArgumentException("UI candidate peak budget includes retiring attachment: "+(oldPixels+newPixels));
        Binding candidate=build(graph,dependency,null,options,recorder,completion);
        try {
            graph.compile();if(candidate.compositor!=null)candidate.compositor.preparePrograms();
            return new Candidate(active,candidate);
        } catch(RuntimeException|Error failure) {
            if(candidate.compositor!=null)try{candidate.compositor.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    final class Candidate implements AutoCloseable {
        private final Binding expected,candidate;
        private boolean committed,disposed;
        Candidate(Binding expected,Binding candidate){this.expected=expected;this.candidate=candidate;}
        void commit() {
            if(disposed||committed||closed||active!=expected)throw new IllegalStateException("stale UI attachment candidate");
            if(active.frameOpen)throw new IllegalStateException("UI attachment edits require a frame boundary");
            active=candidate;committed=true;
            if(expected.compositor!=null)expected.compositor.close();
        }
        @Override public void close(){if(disposed)return;disposed=true;if(!committed&&candidate.compositor!=null)candidate.compositor.close();}
    }
    void rebind(RenderGraph graph,String dependency) {
        if(active!=null&&graph==active.graph)return;
        if(active==null||!active.graph.isClosed())throw new IllegalStateException("UI rebind requires a retired previous RenderGraph; use prepareAttachment for a transaction");
        Binding previous=active;
        Binding candidate=build(graph,dependency,previous.prefix,previous.options,previous.recorder,previous.completion);
        active=candidate;
        if(previous.compositor!=null)previous.compositor.close();
    }
    boolean isAttached(){return active!=null;}
    UiCompositor.Diagnostics diagnostics(){return active==null||active.compositor==null?UiCompositor.Diagnostics.EMPTY:active.compositor.diagnostics();}
    UiCompositor.RuntimeStatistics runtimeStatistics(){return active==null||active.compositor==null?new UiCompositor.RuntimeStatistics(0,0,0,0,0,Map.of()):active.compositor.runtimeStatistics();}
    boolean hasFrameObserver(){return active!=null&&active.completion!=null;}
    boolean beginsFrame(){return active==null||active.compositor==null||active.compositor.beginsFrame();}
    boolean recordCompositor(UiRenderSnapshot snapshot,UiRenderer renderer,PassResources resources,CommandBuffer commands) {
        return active!=null&&active.compositor!=null&&active.compositor.recordCurrentPass(snapshot,renderer,resources,commands);
    }
    @Override public void close(){if(closed)return;closed=true;Binding previous=active;active=null;if(previous!=null&&previous.compositor!=null)previous.compositor.close();}
    private void validateGraph(RenderGraph graph,String dependency) {
        if(closed)throw new IllegalStateException("UI attachment is closed");
        Objects.requireNonNull(graph);Objects.requireNonNull(dependency);
        if(graph.isTopologySealed())throw new IllegalStateException("RenderGraph topology is already sealed");
        if(!graph.hasPass(dependency)||!graph.passWritesToBackbuffer(dependency))throw new IllegalArgumentException("UI dependency must write the presentation target: "+dependency);
        if(graph.hasPass(overlayPassName))throw new IllegalStateException("RenderGraph already contains "+overlayPassName);
    }
}
