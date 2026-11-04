package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.*;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget;
import com.kaleblangley.haikalat.subsystems.ui.*;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiVisualTransform;
import com.kaleblangley.haikalat.subsystems.ui.render.*;
import com.kaleblangley.haikalat.subsystems.ui.style.*;
import com.kaleblangley.haikalat.subsystems.ui.widget.*;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45.*;

/** Separate first-delivery lifecycle run; never included in ordinary UI acceptance. */
@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class UiCompositorCacheSoakGlTest {
    @Test void threeThousandSixHundredFramesReuseStorageAndRecoverRealContentAndSubmissionChanges() {
        try(var window=new GlfwWindow.Builder().dimensions(97,65).visible(false).title("UI cache lifecycle soak").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();
            try(var tracking=GlDebug.acquireResourceTracking()) {
                var baseline=GlDebug.resources();var device=new GlRenderDevice();var generation=new AtomicLong();
                int texture=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(texture,1,GL_RGBA8,1,1);
                UiImageResolver resolver=new UiImageResolver() {
                    public Optional<UiImageRegion> resolve(UiImageId id){return Optional.of(new UiImageRegion(texture,0,16,16,new UiUvRect(0,0,1,1)));}
                    public long generation(UiImageId id){return generation.get();}
                };
                try(var small=OwnedPresentationTarget.create(97,65,RenderFormat.RGBA16F,false,0);
                    var large=OwnedPresentationTarget.create(195,131,RenderFormat.RGBA16F,false,1);
                    var graph=new RenderGraph(97,65);var ui=UiSystem.create(window,config(),resolver)) {
                    graph.addPass("Display").writeToBackbuffer().noClear().execute((r,c)->c.enableScissor(false).clearColor(0,0,1,1).clear(true,false));
                    var storage=new AtomicReference<List<Integer>>();
                    graph.addPass("ObserveStorage").computeOnly().dependsOn(UiSystem.OVERLAY_PASS_NAME).execute((r,c)->storage.set(List.of(
                            r.framebufferOfPass("UiLayer/panel").colorAttachment(),r.framebufferOfPass("UiBlur/panel/Horizontal").colorAttachment(),
                            r.framebufferOfPass("UiBlur/panel/Vertical").colorAttachment())));
                    var bounds=new UiScreenRect(0,0,52,40);
                    var description=UiLayerDescription.builder("panel",bounds).opacity(.5f).blur(4,2).build();
                    var panel=new Panel();panel.style(style(52,40));panel.addStyleClass("red");panel.layerDescription(description);
                    var image=new Image(new UiImageId(1));image.style(style(16,16));panel.add(image);
                    var text=new Label("cached 0");text.style(style(50,14));text.visualTransform(UiVisualTransform.translation(0,25));panel.add(text);
                    var child=new Panel();child.style(style(20,20));child.addStyleClass("green");child.visualTransform(UiVisualTransform.translation(30,0));panel.add(child);
                    ui.document().root().add(panel);
                    ui.attachTo(graph,"Display",UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(description)).maximumIntermediatePixels(100000).build());graph.compile();
                    var input=new WindowInputCollector();input.windowSize(97,65);input.focused(true);input.cursorInside(true);
                    List<Integer> previousStorage=null;int changes=0,hits=0,redraws=0,coldCommands=0,warmCommands=0;long peak=0;
                    boolean largeExtent=false,greenImage=false;float opacity=.5f;double translation=0;
                    for(int frame=0;frame<3600;frame++) {
                        if(frame%300==0) {
                            greenImage=!greenImage;generation.incrementAndGet();
                            glTextureSubImage2D(texture,0,0,0,1,1,GL_RGBA,GL_FLOAT,new float[]{greenImage?0:1,greenImage?1:0,0,1});
                        }
                        if(frame%600==0)text.text("cached "+frame/600);
                        if(frame%450==0) {
                            opacity=opacity==.5f?.25f:.5f;translation=translation==0?4:0;
                            panel.layerDescription(UiLayerDescription.builder("panel",bounds).opacity(opacity).blur(4,2).build());
                            panel.visualTransform(UiVisualTransform.translation(translation,0));
                        }
                        if(frame>0&&frame%900==0){largeExtent=!largeExtent;graph.resize(largeExtent?195:97,largeExtent?131:65);}
                        input.framebufferSize(largeExtent?195:97,largeExtent?131:65);input.contentScale(largeExtent?2:1,largeExtent?2:1);
                        ui.update(input.snapshot(),1f/60);var output=largeExtent?large:small;
                        if(frame==1800) {
                            System.setProperty("haikalat.test.failShadowPassOnce","true");
                            try{assertThrows(IllegalStateException.class,()->graph.execute(device,output.target()));}
                            finally{System.clearProperty("haikalat.test.failShadowPassOnce");}
                            assertNotEquals(previousStorage,storage.get());changes++;previousStorage=storage.get();
                            continue;
                        }
                        graph.execute(device,output.target());var stats=ui.compositorRuntimeStatistics();
                        peak=Math.max(peak,stats.peakBytes());
                        if(stats.cache().get("panel").equals("HIT")) {
                            hits++;assertEquals(0,stats.draws());assertEquals(0,stats.blurDraws());warmCommands=graph.lastRecordedCommandCount();
                        } else {redraws++;if(frame==0)coldCommands=graph.lastRecordedCommandCount();}
                        if(frame==1801){assertEquals("FAILED_FRAME",stats.cache().get("panel"));assertTrue(stats.draws()>0);}
                        if(previousStorage!=null&&!previousStorage.equals(storage.get())) {
                            changes++;assertEquals(0,frame%900,"managed storage may change only at the three intentional resizes");
                        }
                        previousStorage=storage.get();
                        if(frame%150==0||frame==3599) {
                            int x=(int)Math.round((translation+7)*output.target().width()/97.0),y=(int)Math.round(7*output.target().height()/65.0);
                            float[] pixel=new float[4];glBindFramebuffer(GL_READ_FRAMEBUFFER,output.target().readFramebufferId());
                            glReadPixels(x,output.target().height()-1-y,1,1,GL_RGBA,GL_FLOAT,pixel);
                            assertEquals(greenImage?0:opacity,pixel[0],.002,"image generation must reach actual cached output");
                            assertEquals(greenImage?opacity:0,pixel[1],.002);assertEquals(1-opacity,pixel[2],.002);
                        }
                    }
                    assertEquals(3,changes);assertTrue(hits>3400,"static intervals must actually reuse the cache");
                    assertTrue(redraws>=12,"all image revisions and failed submission must redraw");assertTrue(warmCommands<coldCommands);
                    assertTrue(peak<=100000L*8);assertTrue(ui.compositorDiagnostics().fallbacks().isEmpty());
                    System.out.printf(Locale.ROOT,"UI_CACHE_SOAK frames=3600 hits=%d redraws=%d storageChanges=%d coldCommands=%d warmCommands=%d peakBytes=%d%n",
                            hits,redraws,changes,coldCommands,warmCommands,peak);
                } finally {glDeleteTextures(texture);}
                assertEquals(baseline.liveResources(),GlDebug.resources().liveResources());
                assertEquals(baseline.estimatedBytes(),GlDebug.resources().estimatedBytes());GlDebug.assertNoError("UI cache lifecycle soak");
            }
        }
    }
    private static UiStyle style(float w,float h) {
        return UiStyle.builder().positionType(UiStyle.PositionType.ABSOLUTE).width(UiLength.points(w)).height(UiLength.points(h)).flexShrink(0).build();
    }
    private static UiConfig config() {
        return UiConfig.builder().styleResolver((widget,classes,states,inherited)->new ComputedStyle(
                classes.contains("red")?new UiColor(1,0,0,1):classes.contains("green")?new UiColor(0,1,0,1):UiColor.TRANSPARENT,
                UiColor.WHITE,UiColor.TRANSPARENT,0,0,1,12,com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine.DEFAULT_FONT_FAMILY)).build();
    }
}
