package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.*;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.*;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.ui.*;
import com.kaleblangley.haikalat.subsystems.ui.render.*;
import com.kaleblangley.haikalat.subsystems.ui.style.*;
import com.kaleblangley.haikalat.subsystems.ui.widget.*;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiVisualTransform;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class UiCompositorLifecycleGlTest {
    @Test void structuralRootLayerGroupsItsChildrenAndSharesItsTransformWithHitTesting() {
        try(var window=new GlfwWindow.Builder().dimensions(97,65).visible(false).title("UI root layer").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(97,65,RenderFormat.RGBA16F,false,0);
                var graph=background(new AtomicBoolean());var ui=UiSystem.create(window,config())) {
                var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,97,65)).opacity(.5f).build();
                ui.document().root().layerDescription(description);ui.document().root().visualTransform(UiVisualTransform.translation(10,0));
                var button=new Button("");button.style(style(16,16));button.addStyleClass("red");
                var clicks=new java.util.concurrent.atomic.AtomicInteger();button.onClick(clicks::incrementAndGet);ui.document().root().add(button);
                ui.attachTo(graph,"Display",options(description,20000));graph.compile();var input=input(97,65,97,65);
                render(ui,graph,device,output,input);pixel(output,18,5,.5,0,.5);pixel(output,3,5,0,0,1);
                click(ui,graph,device,output,input,18,5);assertEquals(1,clicks.get());
                GlDebug.assertNoError("UI structural root layer and transform");
            }
        }
    }
    @Test void unclippedSubtreeOverflowIsComposedAndAnExplicitLayerClipMatchesPointerHits() {
        try(var window=new GlfwWindow.Builder().dimensions(97,65).visible(false).title("UI overflow and clip").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(97,65,RenderFormat.RGBA16F,false,0);
                var graph=background(new AtomicBoolean());var ui=UiSystem.create(window,config())) {
                var bounds=new UiScreenRect(0,0,20,20);var description=UiLayerDescription.builder("panel",bounds).opacity(.5f).build();
                var group=new Panel();group.style(style(20,20));group.layerDescription(description);
                var button=new Button("");button.style(style(16,16));button.addStyleClass("red");button.visualTransform(UiVisualTransform.translation(15,0));
                var clicks=new java.util.concurrent.atomic.AtomicInteger();button.onClick(clicks::incrementAndGet);group.add(button);ui.document().root().add(group);
                ui.attachTo(graph,"Display",options(description,10000));graph.compile();var input=input(97,65,97,65);
                render(ui,graph,device,output,input);pixel(output,25,5,.5,0,.5);
                click(ui,graph,device,output,input,25,5);assertEquals(1,clicks.get());
                group.layerDescription(UiLayerDescription.builder("panel",bounds).opacity(.5f).mask(UiLayerDescription.MaskMode.CLIP).build());
                render(ui,graph,device,output,input);pixel(output,25,5,0,0,1);pixel(output,18,5,.5,0,.5);
                click(ui,graph,device,output,input,25,5);assertEquals(1,clicks.get(),"clipped overflow must not remain clickable");
                click(ui,graph,device,output,input,18,5);assertEquals(2,clicks.get());
                GlDebug.assertNoError("UI subtree overflow and layer clip");
            }
        }
    }
    @Test void scaledScrolledLayerControlsUseTheVisibleInverseGeometryAndPointerCapture() {
        try(var window=new GlfwWindow.Builder().dimensions(97,65).visible(false).title("UI transformed input").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(97,65,RenderFormat.RGBA16F,false,0);
                var graph=background(new AtomicBoolean());var ui=UiSystem.create(window,config())) {
                var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,40,24)).opacity(.5f).build();
                var scroll=new ScrollView();scroll.style(style(40,24));scroll.layerDescription(description);
                scroll.visualTransform(new UiVisualTransform(10,10,1.5,1.25,0,0,0));
                var content=new Panel();content.style(style(80,48));scroll.content(content);
                var slider=new Slider(0,1,0);slider.style(style(40,12));slider.visualTransform(UiVisualTransform.translation(20,0));content.add(slider);
                var button=new Button("");button.style(style(20,8));button.addStyleClass("red");button.visualTransform(UiVisualTransform.translation(20,16));content.add(button);
                var clicks=new java.util.concurrent.atomic.AtomicInteger();button.onClick(clicks::incrementAndGet);ui.document().root().add(scroll);
                ui.attachTo(graph,"Display",options(description,10000));graph.compile();var input=input(97,65,97,65);
                render(ui,graph,device,output,input);scroll.scrollTo(10,0);render(ui,graph,device,output,input);
                input.cursorPosition(55,17.5);input.pressMouse(MouseButton.LEFT,KeyModifiers.NONE);render(ui,graph,device,output,input);
                assertEquals(.5,slider.value(),1e-6,"scaled and scrolled pointer must map into slider-local coordinates");
                input.cursorPosition(100,17.5);render(ui,graph,device,output,input);assertEquals(1,slider.value(),1e-6,"capture keeps dragging outside the clip");
                input.releaseMouse(MouseButton.LEFT,KeyModifiers.NONE);render(ui,graph,device,output,input);assertFalse(slider.dragging());
                pixel(output,40,35,.5,0,.5);click(ui,graph,device,output,input,40,35);assertEquals(1,clicks.get());
                button.visibility(UiVisibility.HIDDEN);render(ui,graph,device,output,input);click(ui,graph,device,output,input,40,35);assertEquals(1,clicks.get());
                button.visibility(UiVisibility.VISIBLE);render(ui,graph,device,output,input);
                input.cursorPosition(40,35);input.pressMouse(MouseButton.LEFT,KeyModifiers.NONE);render(ui,graph,device,output,input);
                content.remove(button);input.releaseMouse(MouseButton.LEFT,KeyModifiers.NONE);render(ui,graph,device,output,input);
                assertEquals(1,clicks.get(),"detaching a captured control cancels its action");button.close();
                GlDebug.assertNoError("UI scaled scroll, slider, capture, hidden and detach");
            }
        }
    }
    private static void click(UiSystem ui,RenderGraph graph,GlRenderDevice device,OwnedPresentationTarget output,WindowInputCollector input,double x,double y) {
        input.cursorPosition(x,y);input.pressMouse(MouseButton.LEFT,KeyModifiers.NONE);render(ui,graph,device,output,input);
        input.releaseMouse(MouseButton.LEFT,KeyModifiers.NONE);render(ui,graph,device,output,input);
    }
    @Test void realThreeDimensionalPipelineComposesUiAndEmbeddedSuccessAndFailureRestoreHostState() {
        try (var window=new GlfwWindow.Builder().dimensions(97,65).visible(false).title("UI pipeline consumers").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();
            for(boolean embedded:List.of(false,true)) {
                try(var tracking=GlDebug.acquireResourceTracking()) {
                    var baseline=GlDebug.resources();var device=new GlRenderDevice();
                    try(var host=OwnedPresentationTarget.create(97,65,RenderFormat.RGBA16F,false,0);
                        var mesh=Mesh.from(BuiltinMeshData.coloredTriangle("UI consumer triangle"));
                        var shader=ShaderProgram.fromSources("""
                                #version 460 core
                                layout(location=0) in vec3 aPos;
                                layout(std140) uniform CameraBlock { mat4 uProjection; mat4 uView; };
                                uniform mat4 uModel;
                                void main(){gl_Position=uProjection*uView*uModel*vec4(aPos,1);}
                                ""","""
                                #version 460 core
                                out vec4 color;
                                void main(){color=vec4(0,0,1,1);}
                                """);var material=Material.builder(shader).build()) {
                        var scene=new Scene(new Camera(new Vector3f(0,0,3)));
                        scene.add(new SceneObject(mesh,material,(model,frame)->model.identity()));
                        var hostTarget=host.target();
                        var borrowed=PresentationTarget.borrowed(hostTarget.drawFramebufferId(),
                                ExternalAttachment.borrowedColor(hostTarget.color().orElseThrow().textureId(),RenderFormat.RGBA16F,97,65),
                                ExternalAttachment.borrowedDepth(hostTarget.depth().orElseThrow().textureId(),RenderFormat.DEPTH_COMPONENT24,97,65),97,65,0);
                        var settings=RenderSettings.builder().vsync(false).build();
                        var pipeline=embedded?new RenderPipeline(borrowed,scene,null,settings):new RenderPipeline(window,scene,null,settings);
                        try(var ui=UiSystem.create(window,config())) {
                            pipeline.build();
                            var camera=ExternalCamera.of(new Matrix4f().lookAt(0,0,3,0,0,0,0,1,0),
                                    new Matrix4f().perspective((float)Math.toRadians(45),97f/65,.1f,100),new Vector3f(0,0,3),0);
                            clearHost(hostTarget);
                            pipeline.execute(device,camera,borrowed);pixel(host,48,32,0,0,1);
                            var fail=new AtomicBoolean();
                            pipeline.graph().addPass("AfterUiFailure").computeOnly().dependsOn(UiSystem.OVERLAY_PASS_NAME)
                                    .execute((r,c)->{if(fail.get())throw new IllegalStateException("test failure after UI recording");});
                            var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,97,65)).opacity(.5f).build();
                            var panel=new Panel();panel.style(style(97,65));panel.addStyleClass("red");panel.layerDescription(description);ui.document().root().add(panel);
                            ui.attachTo(pipeline.graph(),pipeline.finalPassName(),options(description,30000));
                            var input=input(97,65,97,65);
                            for(int i=0;i<2;i++) {
                                ui.update(input.snapshot(),0);clearHost(hostTarget);hostState();
                                pipeline.execute(device,camera,borrowed);assertHostState();pixel(host,48,32,.5,0,.5);
                            }
                            fail.set(true);ui.update(input.snapshot(),0);hostState();
                            assertThrows(IllegalStateException.class,()->pipeline.execute(device,camera,borrowed));assertHostState();
                            fail.set(false);ui.update(input.snapshot(),0);clearHost(hostTarget);hostState();
                            pipeline.execute(device,camera,borrowed);assertHostState();pixel(host,48,32,.5,0,.5);
                            assertEquals("FAILED_FRAME",ui.compositorRuntimeStatistics().cache().get("panel"));
                        } finally {pipeline.close();}
                        assertTrue(glIsFramebuffer(hostTarget.drawFramebufferId()));
                        assertTrue(glIsTexture(hostTarget.color().orElseThrow().textureId()));
                        assertTrue(glIsTexture(hostTarget.depth().orElseThrow().textureId()));
                    }
                    assertEquals(baseline.liveResources(),GlDebug.resources().liveResources());
                    GlDebug.assertNoError("UI real 3D and embedded consumer");
                }
            }
        }
    }
    private static void hostState() {
        glBindFramebuffer(GL_FRAMEBUFFER,0);glViewport(5,6,7,8);glEnable(GL_SCISSOR_TEST);glScissor(3,2,11,12);glEnable(GL_CULL_FACE);
    }
    private static void clearHost(PresentationTarget target) {
        glBindFramebuffer(GL_FRAMEBUFFER,target.drawFramebufferId());glDisable(GL_SCISSOR_TEST);
        glClearColor(0,0,0,1);glClearDepth(1);glDepthMask(true);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    private static void assertHostState() {
        assertEquals(0,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));assertEquals(0,glGetInteger(GL_READ_FRAMEBUFFER_BINDING));
        int[] viewport=new int[4],scissor=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);glGetIntegerv(GL_SCISSOR_BOX,scissor);
        assertArrayEquals(new int[]{5,6,7,8},viewport);assertArrayEquals(new int[]{3,2,11,12},scissor);
        assertTrue(glIsEnabled(GL_SCISSOR_TEST));assertTrue(glIsEnabled(GL_CULL_FACE));
    }
    @Test void preparingAbandoningAndPublishingAnotherGraphPreservesTheLiveOutputAndOwnership() {
        try (var window=new GlfwWindow.Builder().dimensions(97,65).visible(false).title("UI attachment transaction").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();
            try (var tracking=GlDebug.acquireResourceTracking()) {
                var baseline=GlDebug.resources();var device=new GlRenderDevice();var green=new AtomicBoolean();
                try (var output=OwnedPresentationTarget.create(97,65,RenderFormat.RGBA16F,false,0);
                     var old=background(green);var ui=UiSystem.create(window,config())) {
                    var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,40,30)).opacity(.5f).build();
                    var panel=new Panel();panel.style(style(40,30));panel.addStyleClass("red");panel.layerDescription(description);
                    ui.document().root().add(panel);var options=options(description,10000);
                    ui.attachTo(old,"Display",options);old.compile();var input=input(97,65,97,65);
                    render(ui,old,device,output,input);render(ui,old,device,output,input);
                    pixel(output,8,8,.5,0,.5);assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("panel"));

                    // Keep the candidate alive: deleting it first would invalidate the device cache
                    // and hide creation-time FBO binding corruption in the still-active old graph.
                    try (var next=background(new AtomicBoolean());var candidate=ui.prepareAttachment(next,"Display",options)) {
                        assertEquals(2L*40*30*8,ui.compositorRuntimeStatistics().bytes(),"pending and active storage both count");
                        green.set(true);render(ui,old,device,output,input);
                        pixel(output,80,50,0,1,0);pixel(output,8,8,.5,.5,0);
                    }
                    green.set(false);render(ui,old,device,output,input);pixel(output,80,50,0,0,1);
                    try (var rejected=background(new AtomicBoolean())) {
                        assertThrows(IllegalArgumentException.class,()->ui.prepareAttachment(rejected,"Display",options(description,2000)));
                        assertFalse(rejected.hasPass("UiOverlay"),"peak-budget rejection must happen before graph mutation");
                    }
                    green.set(true);render(ui,old,device,output,input);pixel(output,80,50,0,1,0);
                    try (var next=background(new AtomicBoolean());var candidate=ui.prepareAttachment(next,"Display",options)) {
                        candidate.commit();candidate.close();
                        assertEquals(2L*40*30*8,ui.compositorRuntimeStatistics().bytes(),"publication does not transfer or delete caller-owned graph storage");
                        try(var third=background(new AtomicBoolean())) {
                            assertThrows(IllegalArgumentException.class,()->ui.prepareAttachment(third,"Display",options(description,3000)),
                                    "retiring storage remains in the next candidate's peak budget");
                        }
                        assertThrows(IllegalStateException.class,()->old.execute(device,output.target()),"retired attachment cannot submit UI work");
                        render(ui,next,device,output,input);pixel(output,8,8,.5,0,.5);
                        render(ui,next,device,output,input);assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("panel"));
                        old.close();old.close();
                        assertEquals(40L*30*8,ui.compositorRuntimeStatistics().bytes());
                    }
                    ui.close();ui.close();
                    assertTrue(glIsFramebuffer(output.target().drawFramebufferId()),"UI and graph never acquire presentation storage");
                }
                assertEquals(baseline.liveResources(),GlDebug.resources().liveResources(),"all attachment/program/ring/atlas/timer resources return to baseline");
                assertEquals(baseline.estimatedBytes(),GlDebug.resources().estimatedBytes());
                GlDebug.assertNoError("UI candidate publication and ownership");
            }
        }
    }

    @Test void oddHiDpiResizeZeroExtentAndPresentationReplacementKeepIndependentViewsCorrect() {
        try (var window=new GlfwWindow.Builder().dimensions(97,65).visible(false).title("UI odd extent lifecycle").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();
            try (var tracking=GlDebug.acquireResourceTracking()) {
                var baseline=GlDebug.resources();var device=new GlRenderDevice();
                try (var graph=background(new AtomicBoolean());var otherGraph=background(new AtomicBoolean(true));
                     var ui=UiSystem.create(window,config());var other=UiSystem.create(window,config());
                     var small=OwnedPresentationTarget.create(97,65,RenderFormat.RGBA16F,false,0);
                     var large=OwnedPresentationTarget.create(195,131,RenderFormat.RGBA16F,false,1);
                     var replacement=OwnedPresentationTarget.create(195,131,RenderFormat.RGBA16F,false,2)) {
                    var layer=UiLayerDescription.builder("panel",new UiScreenRect(0,0,41,31)).opacity(.5f).build();
                    for(var view:List.of(ui,other)) {
                        var panel=new Panel();panel.style(style(41,31));panel.addStyleClass("red");panel.layerDescription(layer);view.document().root().add(panel);
                    }
                    ui.attachTo(graph,"Display",options(layer,20000));other.attachTo(otherGraph,"Display",options(layer,20000));
                    graph.compile();otherGraph.compile();var input=input(97,65,97,65);
                    render(ui,graph,device,small,input);pixel(small,8,8,.5,0,.5);
                    for(int i=0;i<3;i++) {
                        input.framebufferSize(195,131);input.contentScale(2,2);
                        graph.resize(195,131);render(ui,graph,device,large,input);pixel(large,16,16,.5,0,.5);
                        long expectedWidth=(long)Math.ceil(41*195.0/97),expectedHeight=(long)Math.ceil(31*131.0/65);
                        assertEquals(expectedWidth*expectedHeight*8,ui.compositorRuntimeStatistics().bytes());
                        render(ui,graph,device,replacement,input);pixel(replacement,16,16,.5,0,.5);
                        assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("panel"));
                        long created=GlDebug.resources().createdCount();
                        assertEquals(PresentationResult.SKIPPED_ZERO_EXTENT,graph.execute(device,PresentationTarget.defaultFramebuffer(0,0)));
                        assertEquals(created,GlDebug.resources().createdCount(),"zero extent records and allocates nothing");
                        input.framebufferSize(97,65);input.contentScale(1,1);graph.resize(97,65);
                        render(ui,graph,device,small,input);pixel(small,8,8,.5,0,.5);
                        render(other,otherGraph,device,replacement,input);pixel(replacement,16,16,.5,.5,0);
                        render(ui,graph,device,small,input);pixel(small,8,8,.5,0,.5);
                    }
                    assertTrue(glIsFramebuffer(replacement.target().drawFramebufferId()));
                }
                assertEquals(baseline.liveResources(),GlDebug.resources().liveResources());
                assertEquals(baseline.estimatedBytes(),GlDebug.resources().estimatedBytes());
                GlDebug.assertNoError("UI resize, zero extent, presentation replacement and independent views");
            }
        }
    }

    private static RenderGraph background(AtomicBoolean green) {
        var graph=new RenderGraph(97,65);
        graph.addPass("Display").writeToBackbuffer().noClear().execute((r,c)->c.enableScissor(false)
                .clearColor(0,green.get()?1:0,green.get()?0:1,1).clear(true,false));
        return graph;
    }
    private static UiAttachmentOptions options(UiLayerDescription layer,long pixels) {
        return UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(layer)).maximumIntermediatePixels(pixels).build();
    }
    private static UiStyle style(float w,float h) {
        return UiStyle.builder().positionType(UiStyle.PositionType.ABSOLUTE).width(UiLength.points(w)).height(UiLength.points(h)).flexShrink(0).build();
    }
    private static UiConfig config() {
        return UiConfig.builder().styleResolver((widget,classes,states,inherited)->new ComputedStyle(
                classes.contains("red")?new UiColor(1,0,0,1):UiColor.TRANSPARENT,UiColor.WHITE,UiColor.TRANSPARENT,0,0,1,12,
                com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine.DEFAULT_FONT_FAMILY)).build();
    }
    private static WindowInputCollector input(int w,int h,int fw,int fh) {
        var input=new WindowInputCollector();input.windowSize(w,h);input.framebufferSize(fw,fh);input.focused(true);input.cursorInside(true);return input;
    }
    private static void render(UiSystem ui,RenderGraph graph,GlRenderDevice device,OwnedPresentationTarget output,WindowInputCollector input) {
        ui.update(input.snapshot(),0);graph.execute(device,output.target());
    }
    private static void pixel(OwnedPresentationTarget output,int x,int y,double r,double g,double b) {
        float[] value=new float[4];glBindFramebuffer(GL_READ_FRAMEBUFFER,output.target().readFramebufferId());
        glReadPixels(x,output.target().height()-1-y,1,1,GL_RGBA,GL_FLOAT,value);
        assertEquals(r,value[0],.002,"red at "+x+","+y);assertEquals(g,value[1],.002,"green at "+x+","+y);assertEquals(b,value[2],.002,"blue at "+x+","+y);
    }
}
