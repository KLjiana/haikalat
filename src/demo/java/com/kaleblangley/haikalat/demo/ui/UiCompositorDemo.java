package com.kaleblangley.haikalat.demo.ui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.kaleblangley.haikalat.backend.*;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget;
import com.kaleblangley.haikalat.subsystems.ui.*;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiVisualTransform;
import com.kaleblangley.haikalat.subsystems.ui.render.*;
import com.kaleblangley.haikalat.subsystems.ui.style.*;
import com.kaleblangley.haikalat.subsystems.ui.widget.*;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.*;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.io.IOException;
import java.util.*;
import static org.lwjgl.opengl.GL45.*;

/** Four independent production-path examples; finite replay also writes linear pixels and evidence. */
public final class UiCompositorDemo {
    private static final List<String> CASES=List.of("layers","content-blur","backdrop-blur","cache-lifecycle");
    private UiCompositorDemo() { }
    public static void main(String[] args) throws Exception {
        String selected="layers";boolean verify=false,hidden=false;int frames=0,width=960,height=540;
        Path output=Path.of("build/reports/ui-compositor-v0260/demos/"+UUID.randomUUID());
        for(String arg:args) {
            if(arg.equals("--verify")){verify=true;hidden=true;frames=96;width=640;height=360;}
            else if(arg.equals("--hidden"))hidden=true;
            else if(arg.startsWith("--case="))selected=arg.substring(7);
            else if(arg.startsWith("--frames="))frames=Integer.parseInt(arg.substring(9));
            else if(arg.startsWith("--output="))output=Path.of(arg.substring(9));
            else if(arg.startsWith("--size=")){String[] size=arg.substring(7).split("x");width=Integer.parseInt(size[0]);height=Integer.parseInt(size[1]);}
            else throw new IllegalArgumentException("Unknown option: "+arg);
        }
        if(width<640||height<360||frames<0||verify&&frames!=96)throw new IllegalArgumentException("Demo needs >=640x360; verification needs exactly 96 frames");
        if(hidden&&frames==0)frames=96;
        List<String> cases=selected.equals("all")?CASES:List.of(selected);
        if(!CASES.containsAll(cases))throw new IllegalArgumentException("case: "+CASES);
        for(String name:cases)run(name,verify,hidden,frames,width,height,output.resolve(name));
        System.out.println("UI_COMPOSITOR_DEMOS="+output.toAbsolutePath());
    }
    private static void run(String name,boolean verify,boolean hidden,int frames,int width,int height,Path output) throws Exception {
        Files.createDirectories(output);
        try(var window=new GlfwWindow.Builder().dimensions(width,height).visible(false).title("UI compositor / "+name).build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();window.setVsync(!hidden);
            try(var tracking=GlDebug.acquireResourceTracking()) {
                var baseline=GlDebug.resources();
                try(var demo=new Example(name,window,output)) {
                    demo.replaying=verify;demo.updateInput(window.inputSnapshot(),false);demo.ui.update(demo.input.snapshot(),0);demo.render();
                    demo.rendered=0;
                    if(!hidden)window.show();
                    long previous=System.nanoTime();int attempted=0;
                    while(!window.shouldClose()&&(frames==0||attempted<frames)) {
                        window.pollEvents();var nativeInput=window.inputSnapshot();
                        if(nativeInput.keyPressed(Key.ESCAPE))window.requestClose();
                        if(window.consumeResize())demo.graph.resize(window.width(),window.height());
                        demo.ui.document().root().visualTransform(new UiVisualTransform(0,0,nativeInput.windowWidth()/640.0,nativeInput.windowHeight()/360.0,0,0,0));
                        demo.updateInput(nativeInput,hidden||demo.replaying);
                        if(demo.replaying)demo.script();
                        long now=System.nanoTime();double delta=hidden||demo.replaying?1.0/60:Math.min(.05,(now-previous)/1e9);previous=now;
                        demo.ui.update(demo.input.snapshot(),(float)delta);
                        if(!demo.paused||demo.step){demo.time+=demo.paused?1.0/60:delta;demo.step=false;}
                        boolean rendered=demo.render();
                        if(rendered) {
                            demo.observe();
                            if(verify)demo.assertFrame(attempted);
                            if(demo.capture){demo.save("capture-"+attempted,false);demo.capture=false;}
                            demo.present();if(verify&&attempted==52)demo.assertNativePresentation();window.swapBuffers();
                        }
                        GlDebug.assertNoError("UI compositor demo "+name+" frame "+attempted);
                        if(demo.replaying&&++demo.replayFrame>=96)demo.replaying=false;
                        demo.frame=++attempted;
                    }
                    if(verify)demo.assertReplay(attempted);
                    demo.save("final",true);demo.evidence(attempted,verify);
                    System.out.printf(Locale.ROOT,"UI_COMPOSITOR_CASE case=%s frames=%d rendered=%d markerHits=%d cacheHits=%d expectedFailures=%d%n",
                            name,attempted,demo.rendered,demo.markerHits,demo.cacheHits,demo.expectedFailures);
                }
                if(!baseline.liveResources().equals(GlDebug.resources().liveResources())||baseline.estimatedBytes()!=GlDebug.resources().estimatedBytes())
                    throw new IllegalStateException("demo did not return all GPU resources to baseline: "+name);
                GlDebug.assertNoError("UI compositor demo close "+name);
            }
        }
    }
    private static final class Example implements AutoCloseable {
        final String name;final GlfwWindow window;final Path output;final GlRenderDevice device=new GlRenderDevice();
        final WindowInputCollector input=new WindowInputCollector();final Map<String,UiNode> controls=new LinkedHashMap<>();
        final List<Map<String,Object>> events=new ArrayList<>();final List<Map<String,Object>> samples=new ArrayList<>();
        UiSystem ui;RenderGraph graph;ShaderProgram background;VertexArray quad;OwnedPresentationTarget linear,encoded;
        int image;long imageToken=-1,generation;Panel main,child,second;Label contentText,diagnostics;Slider parameter;
        boolean compositor=true,effect=true,source=true,cache=true,paused,step,capture,replaying,failOnce,recovering;
        boolean imageGreen;int frame,replayFrame,markerHits,revision,rendered,cacheHits,expectedFailures,downsample=2;
        boolean nativePresentationVerified;
        double time,move,scale=1;float opacity=.5f,childOpacity=.5f,radius=6;
        int minimumWarmCommands=Integer.MAX_VALUE,maximumColdCommands;
        Example(String name,GlfwWindow window,Path output) {
            this.name=name;this.window=window;this.output=output;
            try {
                image=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(image,1,GL_RGBA8,1,1);
                imageToken=GlDebug.trackResource("Texture",image,"UI demo logical image",4);uploadImage();
                background=ShaderProgram.fromResource(UiCompositorDemo.class,"/shaders/ui/composite.vert","/shaders/demo/ui-compositor-background.frag");quad=new VertexArray();
                UiImageResolver resolver=new UiImageResolver() {
                    public Optional<UiImageRegion> resolve(UiImageId id){return Optional.of(new UiImageRegion(image,0,16,16,new UiUvRect(0,0,1,1)));}
                    public long generation(UiImageId id){return generation;}
                };
                ui=UiSystem.create(window,config(),new UnavailableTextInputAdapter("compositor example"),resolver);
                buildUi();apply();graph=createGraph();ui.attachTo(graph,"Display",options());graph.compile();
            } catch(RuntimeException|Error failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        private void buildUi() {
            label(ui.document().root(),"UI COMPOSITOR / "+name,12,8,610,24,"title");
            label(ui.document().root(),"Linear premultiplied layers | pointer controls | deterministic replay",12,35,610,20,"");
            main=panel(ui.document().root(),40,70,260,160,"");
            if(name.equals("backdrop-blur")) {
                label(main,"Sharp UI / pre-UI backdrop",8,125,230,20,"");
                panel(main,40,40,1,30,"green");
                second=panel(ui.document().root(),230,95,220,130,"");label(second,"Same pre-UI source",8,75,200,22,"");panel(second,30,20,1,30,"green");
            } else if(name.equals("content-blur")) {
                panel(main,100,0,8,160,"red");panel(main,200,5,1,20,"green");label(main,"Content and text blur together",8,130,245,22,"");
            } else {
                panel(main,0,0,180,120,"red");child=panel(main,80,30,140,120,"green");
                if(name.equals("cache-lifecycle")) {
                    var picture=new Image(new UiImageId(1));picture.style(at(12,12,16,16));main.add(picture);
                    contentText=label(main,"Cached revision 0",8,135,220,20,"");
                } else label(main,"Red + half-opacity green child",8,135,245,20,"");
            }
            Button marker=button(main,"probe","X",name.equals("backdrop-blur")?160:230,name.equals("backdrop-blur")?90:120,22,22,()->{markerHits++;revision++;if(contentText!=null)contentText.text("Cached revision "+revision);});marker.addStyleClass("green");
            String help=switch(name){
                case "layers"->"Overlapping opaque children form one group. Toggle direct/offscreen, move, scale, and adjust group opacity.";
                case "content-blur"->"The red stripe has an independent Gaussian reference. Change radius and downsample; zero radius bypasses filtering.";
                case "backdrop-blur"->"Moving checkerboard is captured before UI. Two panels share that source. Missing source preserves sharp content.";
                default->"Warm layers omit subtree and filter commands. Update the image/text or child opacity, resize, then recover an injected failed submission.";
            };
            var helpLabel=label(ui.document().root(),help,name.equals("backdrop-blur")?460:330,75,name.equals("backdrop-blur")?165:285,180,"");helpLabel.wrap(Label.Wrap.WORD);
            String[] ids={"effect","reset","pause","step","replay","capture","resize","variant","secondary"};
            String[] titles={"Effect","Reset","Pause","Step","Replay","Capture","Resize",switch(name){case "layers"->"Move";case "content-blur"->"Down x";case "backdrop-blur"->"Source";default->"Image";},
                    switch(name){case "layers"->"Scale";case "content-blur"->"Radius 0";case "backdrop-blur"->"Phase";default->"Child";}};
            Runnable[] actions={this::toggleEffect,this::reset,()->paused=!paused,()->step=true,()->{reset();replaying=true;replayFrame=0;},()->capture=true,
                    ()->window.resize(window.width()==641?640:641,window.height()==361?360:361),this::variant,this::secondary};
            for(int i=0;i<ids.length;i++)button(ui.document().root(),ids[i],titles[i],8+i*69,280,64,24,actions[i]);
            parameter=new Slider(0,name.contains("blur")?20:1,name.contains("blur")?6:.5);parameter.style(at(12,315,160,20));
            parameter.onValueChanged(value->{if(name.contains("blur"))radius=(float)value;else opacity=(float)value;apply();events.add(event("parameter"));});
            ui.document().root().add(parameter);controls.put("parameter",parameter);
            label(ui.document().root(),name.contains("blur")?"Blur radius (logical pixels)":"Group opacity",185,315,285,20,"");
            if(name.equals("cache-lifecycle"))button(ui.document().root(),"failure","Fail one submission",480,315,150,22,()->failOnce=true);
            diagnostics=label(ui.document().root(),"Developer diagnostics: 0123456789 / FIRST_USE HIT FAILED_FRAME",12,343,620,17,"");
        }
        private Button button(Panel parent,String id,String title,float x,float y,float w,float h,Runnable action) {
            var button=new Button(title);button.style(atBuilder(x,y,w,h).justifyContent(UiStyle.JustifyContent.CENTER).alignItems(UiStyle.AlignItems.CENTER).build());button.onClick(()->{action.run();events.add(event(id));});parent.add(button);controls.put(id,button);return button;
        }
        private Map<String,Object> event(String id){return Map.of("frame",frame,"control",id,"time",time);}
        private void toggleEffect() {
            if(name.equals("layers")){compositor=!compositor;apply();rebuild();}
            else if(name.equals("cache-lifecycle")){cache=!cache;apply();}
            else {effect=!effect;apply();}
        }
        private void variant() {
            switch(name) {
                case "layers"->move=move==0?16:0;
                case "content-blur"->downsample=downsample==1?2:downsample==2?4:1;
                case "backdrop-blur"->{source=!source;rebuild();}
                default->{imageGreen=!imageGreen;generation++;uploadImage();}
            }
            apply();
        }
        private void secondary() {
            switch(name) {
                case "layers"->scale=scale==1?1.25:1;
                case "content-blur"->parameter.value(parameter.value()==0?6:0);
                case "backdrop-blur"->time+=1;
                default->childOpacity=childOpacity==.5f?1:.5f;
            }
            apply();
        }
        private void reset() {
            boolean reconfigure=!compositor||!source;
            compositor=true;source=true;effect=true;cache=true;paused=false;move=0;scale=1;opacity=.5f;childOpacity=.5f;radius=6;downsample=2;time=0;
            parameter.value(name.contains("blur")?6:.5);apply();if(reconfigure)rebuild();
        }
        private UiLayerDescription mainDescription() {
            var builder=UiLayerDescription.builder("main",new UiScreenRect(40,70,260,160)).opacity(name.equals("backdrop-blur")?1:opacity)
                    .cachePolicy(cache?UiLayerDescription.CachePolicy.WHEN_CLEAN:UiLayerDescription.CachePolicy.NONE);
            if(name.equals("content-blur"))builder.effect(UiLayerDescription.Effect.BLUR).blur(effect?radius:0,downsample);
            if(name.equals("backdrop-blur"))builder.backdropBlur(effect?radius:0,downsample);
            if(name.equals("cache-lifecycle"))builder.blur(3,2);
            return builder.build();
        }
        private UiLayerDescription childDescription(){return UiLayerDescription.builder("child",new UiScreenRect(120,100,140,120)).parent("main").opacity(childOpacity)
                .cachePolicy(cache?UiLayerDescription.CachePolicy.WHEN_CLEAN:UiLayerDescription.CachePolicy.NONE).build();}
        private UiLayerDescription secondDescription(){return UiLayerDescription.builder("second",new UiScreenRect(230,95,220,130)).backdropBlur(effect?radius:0,downsample).build();}
        private void apply() {
            main.layerDescription(mainDescription());main.visualTransform(new UiVisualTransform(move,0,scale,scale,0,0,0));
            if(child!=null)child.layerDescription(childDescription());if(second!=null)second.layerDescription(secondDescription());
        }
        private UiAttachmentOptions options() {
            var layers=new ArrayList<UiLayerDescription>();if(compositor){layers.add(mainDescription());if(child!=null)layers.add(childDescription());if(second!=null)layers.add(secondDescription());}
            return UiAttachmentOptions.builder().enableCompositor(compositor).layers(layers)
                    .backdropSource(source?UiBackdropSource.available("PreUiColor"):UiBackdropSource.unavailable()).build();
        }
        private RenderGraph createGraph() {
            var next=new RenderGraph(window.width(),window.height());
            next.addPass("PreUi").createColor("PreUiColor",RenderFormat.RGBA16F).relativeSize(1).noClear().execute((r,c)->c
                    .enableDepthTest(false).depthMask(false).enableCullFace(false).enableBlend(false).enableFramebufferSrgb(false)
                    .bindShader(background).setUniformVec2(background,"uViewport",r.currentTarget().width(),r.currentTarget().height())
                    .setUniformVec4(background,"uRect",new Vector4f(0,0,r.currentTarget().width(),r.currentTarget().height()))
                    .setUniformFloat(background,"uPhase",(float)Math.floor(time*8)).setUniformInt(background,"uChecker",name.equals("backdrop-blur")?1:0)
                    .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES,0,6));
            next.addPass("Display").writeToBackbuffer().dependsOn("PreUi").noClear().execute((r,c)->c.enableFramebufferSrgb(false)
                    .blitFramebuffer(r.framebufferOfPass("PreUi").id(),r.presentationTarget().drawFramebufferId(),r.framebufferOfPass("PreUi").width(),
                            r.framebufferOfPass("PreUi").height(),r.presentationTarget().width(),r.presentationTarget().height()));
            return next;
        }
        private void rebuild() {
            if(graph==null)return;var next=createGraph();
            try(var candidate=ui.prepareAttachment(next,"Display",options())) {
                candidate.commit();var previous=graph;graph=next;previous.close();
            } catch(RuntimeException|Error failure){if(graph!=next)next.close();throw failure;}
        }
        private void uploadImage(){glTextureSubImage2D(image,0,0,0,1,1,GL_RGBA,GL_FLOAT,new float[]{imageGreen?0:1,imageGreen?1:0,0,1});}
        private void updateInput(WindowInputSnapshot nativeInput,boolean scripted) {
            input.windowSize(nativeInput.windowWidth(),nativeInput.windowHeight());input.framebufferSize(nativeInput.framebufferWidth(),nativeInput.framebufferHeight());
            input.contentScale(nativeInput.contentScaleX(),nativeInput.contentScaleY());input.focused(scripted||nativeInput.focused());input.cursorInside(scripted||nativeInput.cursorInside());
            if(!scripted) {
                input.cursorPosition(nativeInput.cursorX(),nativeInput.cursorY());input.scroll(nativeInput.scrollX(),nativeInput.scrollY());
                for(var key:Key.values()){if(nativeInput.keyPressed(key))input.pressKey(key,nativeInput.modifiers());if(nativeInput.keyReleased(key))input.releaseKey(key,nativeInput.modifiers());}
                for(var button:MouseButton.values()){if(nativeInput.mousePressed(button))input.pressMouse(button,nativeInput.modifiers());if(nativeInput.mouseReleased(button))input.releaseMouse(button,nativeInput.modifiers());}
            }
        }
        private void script() {
            int f=replayFrame;
            String id=switch(f) {
                case 4,5,10,11->"effect";case 16,17->"variant";case 20,21->"parameter";case 22,23->"secondary";
                case 28,29,36,37->"pause";case 32,33->"step";case 42,43,60,61->"reset";case 48,49->"probe";
                case 54,55->"resize";case 66,67->name.equals("cache-lifecycle")?"failure":"probe";case 74,75->"capture";default->null;
            };
            if(id==null)return;var box=ui.document().visualLayoutBox(controls.get(id));
            input.cursorPosition(box.x()+box.width()*(id.equals("parameter")?.75:.5),box.y()+box.height()*.5);
            if((f&1)==0)input.pressMouse(MouseButton.LEFT,KeyModifiers.NONE);else input.releaseMouse(MouseButton.LEFT,KeyModifiers.NONE);
        }
        private boolean render() {
            surface();
            if(failOnce) {
                failOnce=false;System.setProperty("haikalat.test.failShadowPassOnce","true");
                try {graph.execute(device,linear.target());throw new IllegalStateException("failure probe did not fail");}
                catch(IllegalStateException expected) {
                    if(!expected.getMessage().contains("injected"))throw expected;
                    expectedFailures++;recovering=true;return false;
                } finally {System.clearProperty("haikalat.test.failShadowPassOnce");}
            }
            graph.execute(device,linear.target());rendered++;
            if(recovering){require("FAILED_FRAME".equals(ui.compositorRuntimeStatistics().cache().get("main")),"failed submission did not invalidate the cache");recovering=false;}
            return true;
        }
        private void surface() {
            if(linear!=null&&linear.target().width()==window.width()&&linear.target().height()==window.height())return;
            OwnedPresentationTarget next=null,nextEncoded=null;
            try {next=OwnedPresentationTarget.create(window.width(),window.height(),RenderFormat.RGBA16F,false,frame);
                nextEncoded=OwnedPresentationTarget.create(window.width(),window.height(),RenderFormat.SRGB8_ALPHA8,false,frame);
            } catch(RuntimeException|Error failure){if(next!=null)next.close();if(nextEncoded!=null)nextEncoded.close();throw failure;}
            var old=linear;var oldEncoded=encoded;linear=next;encoded=nextEncoded;if(old!=null)old.close();if(oldEncoded!=null)oldEncoded.close();
        }
        private void present() {
            int w=window.width(),h=window.height();device.execute(new CommandBuffer().enableScissor(false).enableFramebufferSrgb(true)
                    .blitFramebuffer(linear.target().drawFramebufferId(),encoded.target().drawFramebufferId(),w,h,w,h)
                    .enableFramebufferSrgb(false).blitFramebuffer(encoded.target().drawFramebufferId(),0,w,h,w,h));
        }
        private void observe() {
            var stats=ui.compositorRuntimeStatistics();boolean hit="HIT".equals(stats.cache().get("main"));
            if(hit){cacheHits++;minimumWarmCommands=Math.min(minimumWarmCommands,graph.lastRecordedCommandCount());}
            else maximumColdCommands=Math.max(maximumColdCommands,graph.lastRecordedCommandCount());
            if(frame%8==0)diagnostics.text("DEV layers="+ui.compositorDiagnostics().activeLayers()+" cache="+stats.cache().getOrDefault("main","direct")
                    +" draw="+stats.draws()+" blur="+stats.blurDraws()+" comp="+stats.composites()+" bytes="+stats.bytes()+" fallback="+ui.compositorDiagnostics().fallbacks().size());
            if(frame%8==0)samples.add(Map.of("frame",frame,"time",time,"statistics",statistics(stats),"commands",graph.lastRecordedCommandCount(),"fallbacks",
                    ui.compositorDiagnostics().fallbacks().stream().map(f->Map.of("layerId",f.layerId(),"reason",f.reason().name())).toList()));
        }
        private void assertFrame(int f) {
            if(f==5&&name.equals("layers")){expect(140,130,0,1,0);require(ui.compositorRuntimeStatistics().bytes()==0,"disabled compositor allocated layer storage");}
            if(f==11&&name.equals("layers"))expect(140,130,.25,.25,.5);
            if(f==21)require(Math.abs(parameter.value()-(name.contains("blur")?15:.75))<.05,"pointer slider did not reach its value");
            if(f==17&&name.equals("backdrop-blur")){expect(80,120,0,1,0);require(ui.compositorRuntimeStatistics().blurDraws()==0,"missing source still filtered");require(ui.compositorDiagnostics().fallbacks().size()==2,"missing source was not diagnosed");}
            if(f==52) {
                require(markerHits>0,"probe button never received a pointer click");
                if(name.equals("layers")||name.equals("cache-lifecycle"))expect(140,130,.25,.25,.5);
                if(name.equals("content-blur"))stripeOracle();
                if(name.equals("backdrop-blur")) {
                    expect(80,120,0,1,0);require(ui.compositorRuntimeStatistics().blurDraws()==4,"both glass panels must consume the dynamic pre-UI source");
                    int x=64-(int)Math.floor(time*8);float[] value=readPixel(x,88);
                    double original=checker(x+.5,88.5)[0];require(Math.abs(value[0]-original)>.03,"background blur changed no boundary pixels");
                }
            }
        }
        private void stripeOracle() {
            int width=272,filteredWidth=136;double[] weights=weights(3);double[] horizontal=new double[filteredWidth];
            for(int j=0;j<filteredWidth;j++)for(int i=-3;i<=3;i++)horizontal[j]+=weights[Math.abs(i)]*stripeSample((j+.5+i)*width/filteredWidth-.5,106,114);
            for(int x=88;x<=120;x++) {
                double source=(x+.5+6)*filteredWidth/width-.5;double expected=sample(horizontal,source);
                float[] pixel=readPixel(40+x,150);require(Math.abs(pixel[0]-expected*opacity)<.001,"independent downsampled Gaussian stripe mismatch at "+x);
            }
        }
        private void assertReplay(int attempted) {
            require(attempted==96,"finite replay frame count mismatch");
            for(String id:List.of("effect","variant","secondary","parameter","pause","step","reset","probe","resize","capture"))
                require(events.stream().anyMatch(e->e.get("control").equals(id)),"control missing from actual input callbacks: "+id);
            require(window.width()==641&&window.height()==361,"native odd-size resize did not arrive");
            require(nativePresentationVerified,"native framebuffer encoding was not verified");
            require(name.equals("cache-lifecycle")?expectedFailures==1:expectedFailures==0,"failure probe count mismatch");
            if(name.equals("cache-lifecycle")){require(cacheHits>10,"static layer never settled into cache reuse");require(minimumWarmCommands<maximumColdCommands,"cache reduced no recorded work");}
        }
        private void assertNativePresentation() {
            int x=name.equals("backdrop-blur")?55:140,y=name.equals("backdrop-blur")?88:130;
            float[] linearPixel=readPixel(x,y),presented=new float[4];
            glBindFramebuffer(GL_READ_FRAMEBUFFER,0);glReadPixels((int)Math.floor(x*window.width()/640.0),window.height()-1-(int)Math.floor(y*window.height()/360.0),1,1,GL_RGBA,GL_FLOAT,presented);
            for(int c=0;c<3;c++)require(Math.abs(presented[c]-srgb(linearPixel[c])/255.0)<.006,"native framebuffer did not encode linear UI once: "+name);
            nativePresentationVerified=true;
        }
        private double[] checker(double x,double y) {
            return (((int)Math.floor((x+Math.floor(time*8))/16)+(int)Math.floor(y/16))&1)==0?new double[]{.08,.14,.25}:new double[]{.45,.26,.08};
        }
        private void expect(int x,int y,double r,double g,double b){float[] v=readPixel(x,y);require(Math.abs(v[0]-r)<.003&&Math.abs(v[1]-g)<.003&&Math.abs(v[2]-b)<.003,"linear ROI mismatch: "+name+" at "+x+","+y+" = "+Arrays.toString(v));}
        private float[] readPixel(int x,int y) {
            int px=(int)Math.floor(x*linear.target().width()/640.0),py=(int)Math.floor(y*linear.target().height()/360.0);float[] values=new float[4];
            glBindFramebuffer(GL_READ_FRAMEBUFFER,linear.target().readFramebufferId());glReadPixels(px,linear.target().height()-1-py,1,1,GL_RGBA,GL_FLOAT,values);return values;
        }
        private void save(String stem,boolean raw) throws Exception {
            int w=linear.target().width(),h=linear.target().height();float[] pixels=new float[w*h*4];
            glBindFramebuffer(GL_READ_FRAMEBUFFER,linear.target().readFramebufferId());glReadPixels(0,0,w,h,GL_RGBA,GL_FLOAT,pixels);
            var png=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);ByteBuffer bytes=raw?ByteBuffer.allocate(pixels.length*4).order(ByteOrder.LITTLE_ENDIAN):null;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                int i=((h-1-y)*w+x)*4;png.setRGB(x,y,0xff000000|srgb(pixels[i])<<16|srgb(pixels[i+1])<<8|srgb(pixels[i+2]));
                if(bytes!=null)for(int c=0;c<4;c++)bytes.putFloat(pixels[i+c]);
            }
            ImageIO.write(png,"png",output.resolve(stem+".png").toFile());if(bytes!=null)Files.write(output.resolve(stem+".rgba32f"),bytes.array());
        }
        private void evidence(int attempts,boolean verified) throws Exception {
            Map<String,Object> result=new LinkedHashMap<>();result.put("case",name);result.put("status",verified?"PASS":"CAPTURE_ONLY");result.put("attemptedFrames",attempts);
            result.put("renderedFrames",rendered);result.put("expectedSubmissionFailures",expectedFailures);result.put("markerHits",markerHits);result.put("cacheHits",cacheHits);
            result.put("nativePresentationVerified",nativePresentationVerified);
            result.put("prewarmFrames",1);result.put("size",List.of(window.width(),window.height()));result.put("linearPixels","final.rgba32f: top-down little-endian float32 RGBA, linear premultiplied");
            result.put("pngEncoding","sRGB encoded once from linear readback");result.put("events",events);result.put("samples",samples);result.put("statistics",statistics(ui.compositorRuntimeStatistics()));
            result.put("linearSha256",hash(output.resolve("final.rgba32f")));result.put("pngSha256",hash(output.resolve("final.png")));
            result.put("performance","NOT_EVALUATED");result.put("humanVisualAcceptance","NOT_EVALUATED");result.put("releaseReady",false);
            try(var json=new JsonFactory().createGenerator(Files.newOutputStream(output.resolve("evidence.json")))) {
                json.useDefaultPrettyPrinter();writeJson(json,result);
            }
        }
        @Override public void close() {
            if(ui!=null)ui.close();if(graph!=null)graph.close();if(linear!=null)linear.close();if(encoded!=null)encoded.close();
            if(quad!=null)quad.close();if(background!=null)background.close();if(image!=0){glDeleteTextures(image);GlDebug.closeResource(imageToken);image=0;}
        }
    }
    private static Map<String,Object> statistics(UiCompositor.RuntimeStatistics s){return Map.of("bytes",s.bytes(),"peakBytes",s.peakBytes(),"draws",s.draws(),"blurDraws",s.blurDraws(),"composites",s.composites(),"cache",s.cache());}
    private static void writeJson(JsonGenerator json,Object value)throws IOException {
        if(value instanceof Map<?,?> map){json.writeStartObject();for(var entry:map.entrySet()){json.writeFieldName(entry.getKey().toString());writeJson(json,entry.getValue());}json.writeEndObject();}
        else if(value instanceof Iterable<?> list){json.writeStartArray();for(var item:list)writeJson(json,item);json.writeEndArray();}
        else if(value instanceof Number number)json.writeNumber(number.toString());
        else if(value instanceof Boolean flag)json.writeBoolean(flag);
        else if(value==null)json.writeNull();else json.writeString(value.toString());
    }
    private static double[] weights(double radius) {
        int half=(int)Math.ceil(radius);double[] weights=new double[half+1];double sum=0,sigma=Math.max(.5,radius/3);
        for(int i=0;i<=half;i++){weights[i]=Math.exp(-.5*i*i/(sigma*sigma));sum+=weights[i]*(i==0?1:2);}for(int i=0;i<=half;i++)weights[i]/=sum;return weights;
    }
    private static double stripeSample(double x,int start,int end){int i=(int)Math.floor(x);double f=x-i;return (i>=start&&i<end?1-f:0)+(i+1>=start&&i+1<end?f:0);}
    private static double sample(double[] values,double x){int i=(int)Math.floor(x);double f=x-i;return values[Math.max(0,Math.min(values.length-1,i))]*(1-f)+values[Math.max(0,Math.min(values.length-1,i+1))]*f;}
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static int srgb(float value){double v=Math.max(0,Math.min(1,value));return (int)Math.round((v<=.0031308?12.92*v:1.055*Math.pow(v,1/2.4)-.055)*255);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static UiStyle.Builder atBuilder(float x,float y,float w,float h){return UiStyle.builder().positionType(UiStyle.PositionType.ABSOLUTE).width(UiLength.points(w)).height(UiLength.points(h))
            .margin(new UiInsets(UiLength.points(x),UiLength.points(y),UiLength.points(0),UiLength.points(0))).flexShrink(0);}
    private static UiStyle at(float x,float y,float w,float h){return atBuilder(x,y,w,h).build();}
    private static Panel panel(Panel parent,float x,float y,float w,float h,String role){var panel=new Panel();panel.style(at(x,y,w,h));if(!role.isEmpty())panel.addStyleClass(role);parent.add(panel);return panel;}
    private static Label label(Panel parent,String text,float x,float y,float w,float h,String role){var label=new Label(text);label.style(at(x,y,w,h));if(!role.isEmpty())label.addStyleClass(role);parent.add(label);return label;}
    private static UiConfig config(){return UiConfig.builder().styleResolver((widget,classes,states,inherited)->new ComputedStyle(
            classes.contains("red")?new UiColor(1,0,0,1):classes.contains("green")?new UiColor(0,1,0,1):widget.equals("Button")?new UiColor(.12f,.17f,.24f,1):UiColor.TRANSPARENT,
            UiColor.WHITE,new UiColor(.25f,.35f,.45f,1),widget.equals("Button")?1:0,0,1,classes.contains("title")?18:12,
            com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine.DEFAULT_FONT_FAMILY)).build();}
}
