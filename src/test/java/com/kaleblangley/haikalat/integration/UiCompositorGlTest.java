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
import com.kaleblangley.haikalat.subsystems.windowing.input.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class UiCompositorGlTest {
    @Test void recycledCpuAtlasPageWithTheSameLogicalPageAndUvInvalidatesRealCachedGlyphPixels() {
        try(var window=new GlfwWindow.Builder().dimensions(32,32).visible(false).title("UI glyph generation cache").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(32,32,RenderFormat.RGBA16F,false,0);
                var atlas=new com.kaleblangley.haikalat.subsystems.text.GlyphAtlas(8,8,1,1);
                var renderer=new UiRenderer(16,8,8,1);var graph=new RenderGraph(32,32);
                var compositor=new UiCompositor(UiAttachmentOptions.builder().enableCompositor(true)
                        .layers(List.of(UiLayerDescription.builder("panel",new UiScreenRect(0,0,16,16)).build())).build())) {
                var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,16,16)).build();
                var list=new UiDisplayList().beginLayer(description);
                list.beginGlyphRun(0,0,UiBlendMode.PREMULTIPLIED_ALPHA);list.addGlyph(new UiScreenRect(0,0,16,16),new UiUvRect(.125f,.125f,.875f,.875f),0xffffffff);
                list.endGlyphRun();list.endLayer();var snapshot=UiRenderSnapshot.capture(1,32,32,32,32,1,1,list,new UiBatcher());
                graph.addPass("Display").writeToBackbuffer().noClear().execute((r,c)->c.clearColor(0,0,1,1).clear(true,false));
                java.util.function.BiConsumer<com.kaleblangley.haikalat.core.graph.PassResources,com.kaleblangley.haikalat.core.command.CommandBuffer> record=
                        (r,c)->compositor.recordCurrentPass(snapshot,renderer,r,c);
                String last=compositor.registerPasses(graph,"Display",record);
                graph.addPass("Overlay").writeToBackbuffer().dependsOn(last).noClear().execute((r,c)->compositor.recordOverlay(record,r,c));graph.compile();
                for(int glyph=1;glyph<=2;glyph++) {
                    var key=new com.kaleblangley.haikalat.subsystems.text.GlyphKey(new com.kaleblangley.haikalat.subsystems.text.FontFaceId(1),glyph,16);
                    byte[] coverage=new byte[36];Arrays.fill(coverage,glyph==1?(byte)255:0);
                    var request=atlas.lookup(key,k->new com.kaleblangley.haikalat.subsystems.text.GlyphBitmap(k,6,6,0,6,6,0,coverage)).uploadRequest().orElseThrow();
                    var commands=device.createCommandBuffer();renderer.recordGlyphUploads(List.of(request),commands);device.execute(commands);glFinish();
                    graph.execute(device,output.target());var results=renderer.drainCompletedGlyphUploads();assertEquals(1,results.size());assertTrue(results.getFirst().succeeded());atlas.publishUpload(request);
                    assertPixel(read(output,32,32),32,8,8,glyph==1?1:0,glyph==1?1:0,1);
                    if(glyph==2){assertTrue(atlas.statistics().evictions()>0);assertEquals("GLYPH_ATLAS",compositor.runtimeStatistics().cache().get("panel"));}
                    graph.execute(device,output.target());assertEquals("HIT",compositor.runtimeStatistics().cache().get("panel"));assertEquals(0,compositor.runtimeStatistics().draws());
                }
                GlDebug.assertNoError("UI atlas recycle through typed upload, snapshot, cache and real glyph draw");
            }
        }
    }
    @Test void growingContentDropsOptionalFilteringWithinTheCandidatePeakAndRestoresItAfterShrinking() {
        try(var window=new GlfwWindow.Builder().dimensions(64,64).visible(false).title("UI runtime budget").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(64,64,RenderFormat.RGBA16F,false,0);
                var graph=new RenderGraph(64,64);var ui=UiSystem.create(window,colorConfig())) {
                graph.addPass("Display").writeToBackbuffer().noClear().execute((r,c)->c.enableScissor(false).clearColor(0,0,1,1).clear(true,false));
                var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,20,20)).opacity(.5f).blur(4,2).build();
                var group=new Panel();group.style(style(20,20));group.layerDescription(description);
                var red=new Panel();red.style(style(20,20));red.addStyleClass("red");group.add(red);ui.document().root().add(group);
                ui.attachTo(graph,"Display",UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(description)).maximumIntermediatePixels(3000).build());graph.compile();
                var input=input(64,64);ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals(2,ui.compositorRuntimeStatistics().blurDraws());
                group.style(style(40,40));red.style(style(40,40));ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals(1,ui.compositorDiagnostics().activeLayers());assertEquals(0,ui.compositorDiagnostics().directFallbacks());
                assertEquals(0,ui.compositorRuntimeStatistics().blurDraws());assertEquals((40L*40+2)*8,ui.compositorRuntimeStatistics().bytes());
                assertTrue(ui.compositorRuntimeStatistics().peakBytes()<=3000L*8);assertPixel(read(output,64,64),64,30,30,.5,0,.5);
                assertTrue(ui.compositorDiagnostics().fallbacks().stream().anyMatch(f->f.reason()==UiCompositor.FallbackReason.OPTIONAL_EFFECT_BUDGET));
                assertTrue(graph.description().passes().stream().filter(p->p.name().startsWith("UiBlur/")).allMatch(p->p.width()==1&&p.height()==1));
                ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("panel"));
                group.style(style(20,20));red.style(style(20,20));ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals(2,ui.compositorRuntimeStatistics().blurDraws());assertTrue(ui.compositorDiagnostics().fallbacks().isEmpty());
                assertPixel(read(output,64,64),64,30,30,0,0,1);GlDebug.assertNoError("UI runtime optional effect budget and restoration");
            }
        }
    }
    @Test void largeRadiusPreservesTheGaussianSupportBeyondThirtyTwoFilterPixels() {
        try(var window=new GlfwWindow.Builder().dimensions(129,129).visible(false).title("UI wide Gaussian support").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(129,129,RenderFormat.RGBA16F,false,0);
                var graph=new RenderGraph(129,129);var ui=UiSystem.create(window,colorConfig())) {
                graph.addPass("Display").writeToBackbuffer().noClear().execute((r,c)->c.enableScissor(false).clearColor(0,0,1,1).clear(true,false));
                var description=UiLayerDescription.builder("stripe",new UiScreenRect(0,0,129,129)).blur(40,1).build();
                var group=new Panel();group.style(style(129,129));group.layerDescription(description);
                var stripe=new Panel();stripe.style(style(1,129));stripe.addStyleClass("red");stripe.visualTransform(UiVisualTransform.translation(64,0));group.add(stripe);
                ui.document().root().add(group);ui.attachTo(graph,"Display",UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(description)).build());graph.compile();
                var input=input(129,129);ui.update(input.snapshot(),0);graph.execute(device,output.target());var image=read(output,129,129);
                double sum=0;for(int i=-40;i<=40;i++)sum+=Math.exp(-.5*Math.pow(i/(40.0/3),2));
                for(int x=-42;x<=42;x++) {
                    double expected=Math.abs(x)>40?0:Math.exp(-.5*Math.pow(x/(40.0/3),2))/sum;
                    assertEquals(expected,image[(64*129+64+x)*4],.00005,"independent full-support Gaussian at offset "+x);
                }
                GlDebug.assertNoError("UI Gaussian radius above 32");
            }
        }
    }
    @Test void limitedBudgetDropsOnlyOptionalBlurAndKeepsTheCorrectGroupAlpha() {
        try(var window=new GlfwWindow.Builder().dimensions(96,64).visible(false).title("UI budget fallback").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(96,64,RenderFormat.RGBA16F,false,0);
                var graph=new RenderGraph(96,64);var ui=UiSystem.create(window,colorConfig())) {
                graph.addPass("Background").writeToBackbuffer().noClear().execute((r,c)->c.enableScissor(false).clearColor(0,0,1,1).clear(true,false));
                var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,52,40)).opacity(.5f).blur(8,2).build();
                var group=new Panel();group.style(style(52,40));group.layerDescription(description);
                var red=new Panel();red.style(style(40,30));red.addStyleClass("red");group.add(red);ui.document().root().add(group);
                ui.attachTo(graph,"Background",UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(description)).maximumIntermediatePixels(3000).build());
                graph.compile();var input=input(96,64);ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertPixel(read(output,96,64),96,8,8,.5,0,.5);
                assertEquals(0,ui.compositorDiagnostics().directFallbacks());
                assertTrue(ui.compositorDiagnostics().fallbacks().stream().anyMatch(f->f.reason()==UiCompositor.FallbackReason.OPTIONAL_EFFECT_BUDGET));
                assertTrue(graph.description().passes().stream().noneMatch(p->p.name().startsWith("UiBlur/")));
                assertEquals(52L*40*8,ui.compositorRuntimeStatistics().bytes());GlDebug.assertNoError("UI optional-effect budget fallback");
            }
            try(var output=OwnedPresentationTarget.create(96,64,RenderFormat.RGBA16F,false,0);
                var graph=new RenderGraph(96,64);var ui=UiSystem.create(window,colorConfig())) {
                graph.addPass("Background").writeToBackbuffer().noClear().execute((r,c)->c.enableScissor(false).clearColor(0,0,1,1).clear(true,false));
                var first=UiLayerDescription.builder("first",new UiScreenRect(0,0,20,20)).opacity(.5f).blur(4,2).build();
                var second=UiLayerDescription.builder("second",new UiScreenRect(0,0,20,20)).opacity(.5f).build();
                var red=new Panel();red.style(style(20,20));red.addStyleClass("red");red.layerDescription(first);
                var green=new Panel();green.style(style(20,20));green.addStyleClass("green");green.layerDescription(second);
                green.visualTransform(UiVisualTransform.translation(24,0));ui.document().root().add(red);ui.document().root().add(green);
                ui.attachTo(graph,"Background",UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(first,second)).maximumIntermediatePixels(1400).build());
                graph.compile();ui.update(input(96,64).snapshot(),0);graph.execute(device,output.target());
                var pixels=read(output,96,64);assertPixel(pixels,96,5,5,.5,0,.5);assertPixel(pixels,96,29,5,0,.5,.5);
                assertEquals(2,ui.compositorDiagnostics().activeLayers());assertEquals(0,ui.compositorDiagnostics().directFallbacks());
                assertEquals(800L*8,ui.compositorRuntimeStatistics().bytes());assertEquals(0,ui.compositorRuntimeStatistics().blurDraws());
                GlDebug.assertNoError("UI all group allocations precede optional filtering");
            }
        }
    }
    @Test void staticCacheSkipsRecordedDrawsAndTracksChildrenOpacityTranslationAndFailedSubmission() {
        try(var window=new GlfwWindow.Builder().dimensions(96,64).visible(false).title("UI cache reference").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(96,64,RenderFormat.RGBA16F,false,0);
                var graph=new RenderGraph(96,64);var ui=UiSystem.create(window,colorConfig())) {
                graph.addPass("Background").writeToBackbuffer().noClear().execute((r,c)->c.enableScissor(false).clearColor(0,0,1,1).clear(true,false));
                var parentDescription=UiLayerDescription.builder("parent",new UiScreenRect(0,0,52,40)).opacity(.5f).blur(2,1).build();
                var childDescription=UiLayerDescription.builder("child",new UiScreenRect(0,0,30,30)).parent("parent").opacity(.5f).build();
                var parent=new Panel();parent.style(style(52,40));parent.layerDescription(parentDescription);
                var red=new Panel();red.style(style(40,30));red.addStyleClass("red");parent.add(red);
                var child=new Panel();child.style(style(30,30));child.visualTransform(UiVisualTransform.translation(20,0));
                child.addStyleClass("green");child.layerDescription(childDescription);parent.add(child);ui.document().root().add(parent);
                ui.attachTo(graph,"Background",UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(parentDescription,childDescription)).build());
                graph.compile();var input=input(96,64);ui.update(input.snapshot(),0);graph.execute(device,output.target());
                int coldCommands=graph.lastRecordedCommandCount();float[] cold=read(output,96,64);
                ui.update(input.snapshot(),0);graph.execute(device,output.target());var warm=ui.compositorRuntimeStatistics();
                assertEquals("HIT",warm.cache().get("parent"));assertEquals("HIT",warm.cache().get("child"));
                assertEquals(0,warm.draws());assertEquals(0,warm.blurDraws());
                assertTrue(graph.lastRecordedCommandCount()<coldCommands,"the actual command stream must omit subtree and filter draws");
                assertArrayEquals(cold,read(output,96,64));
                parent.layerDescription(UiLayerDescription.builder("parent",parentDescription.bounds()).opacity(.25f).blur(2,1).build());
                ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("parent"));
                assertPixel(read(output,96,64),96,35,10,.125,.125,.75);
                parent.visualTransform(UiVisualTransform.translation(4,0));ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("parent"),"integer translation preserves the layer-local raster");
                assertPixel(read(output,96,64),96,39,10,.125,.125,.75);
                child.layerDescription(UiLayerDescription.builder("child",childDescription.bounds()).parent("parent").opacity(1).build());
                ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals("CHILD_CHANGED",ui.compositorRuntimeStatistics().cache().get("parent"));
                assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("child"));
                assertPixel(read(output,96,64),96,39,10,0,.25,.75);
                child.removeStyleClass("green");child.addStyleClass("red");ui.update(input.snapshot(),0);
                System.setProperty("haikalat.test.failShadowPassOnce","true");
                try {assertThrows(IllegalStateException.class,()->graph.execute(device,output.target()));}
                finally {System.clearProperty("haikalat.test.failShadowPassOnce");}
                ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals("FAILED_FRAME",ui.compositorRuntimeStatistics().cache().get("parent"));
                assertPixel(read(output,96,64),96,39,10,.25,0,.75);
                parent.layerDescription(UiLayerDescription.builder("parent",parentDescription.bounds()).opacity(.25f).blur(2,1)
                        .cachePolicy(UiLayerDescription.CachePolicy.ALWAYS).build());
                child.layerDescription(UiLayerDescription.builder("child",childDescription.bounds()).parent("parent").opacity(1)
                        .cachePolicy(UiLayerDescription.CachePolicy.ALWAYS).build());
                ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("parent"));
                child.removeStyleClass("red");child.addStyleClass("green");ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals("CHILD_CHANGED",ui.compositorRuntimeStatistics().cache().get("parent"),"ALWAYS must still invalidate changed content");
                assertPixel(read(output,96,64),96,39,10,0,.25,.75);
                child.layerDescription(UiLayerDescription.builder("child",childDescription.bounds()).parent("parent").opacity(1)
                        .cachePolicy(UiLayerDescription.CachePolicy.NONE).build());
                ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals("CACHE_DISABLED",ui.compositorRuntimeStatistics().cache().get("child"));
                assertEquals("UNTRACKED_DEPENDENCY",ui.compositorRuntimeStatistics().cache().get("parent"));
                assertTrue(ui.compositorRuntimeStatistics().draws()>0);
                assertPixel(read(output,96,64),96,39,10,0,.25,.75);
                child.layerDescription(UiLayerDescription.builder("child",childDescription.bounds()).parent("parent").opacity(1).build());
                ui.update(input.snapshot(),0);graph.execute(device,output.target());
                parent.close();
                var replacement=new Panel();replacement.style(style(52,40));replacement.addStyleClass("green");
                replacement.layerDescription(UiLayerDescription.builder("parent",parentDescription.bounds()).opacity(.25f).blur(2,1).build());
                ui.document().root().add(replacement);ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals("INSTANCE",ui.compositorRuntimeStatistics().cache().get("parent"),"stable layer ID does not preserve a detached node instance");
                assertPixel(read(output,96,64),96,10,10,0,.25,.75);
                GlDebug.assertNoError("UI cache success-frame and partial-write recovery");
            }
        }
    }

    @Test void inPlaceImageGenerationInvalidatesCachedColorWithoutChangingTheLogicalImageId() {
        try(var window=new GlfwWindow.Builder().dimensions(64,64).visible(false).title("UI image generation").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            int texture=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(texture,1,GL_RGBA8,1,1);
            var generation=new java.util.concurrent.atomic.AtomicLong();
            UiImageResolver resolver=new UiImageResolver() {
                public Optional<UiImageRegion> resolve(UiImageId id){return Optional.of(new UiImageRegion(texture,0,16,16,new UiUvRect(0,0,1,1)));}
                public long generation(UiImageId id){return generation.get();}
            };
            try(var output=OwnedPresentationTarget.create(64,64,RenderFormat.RGBA16F,false,0);
                var graph=new RenderGraph(64,64);var ui=UiSystem.create(window,colorConfig(),resolver)) {
                graph.addPass("Background").writeToBackbuffer().noClear().execute((r,c)->c.enableScissor(false).clearColor(0,0,0,1).clear(true,false));
                var description=UiLayerDescription.builder("image",new UiScreenRect(0,0,32,32)).build();
                var group=new Panel();group.style(style(32,32));group.layerDescription(description);
                var image=new Image(new UiImageId(1));image.style(style(16,16));group.add(image);ui.document().root().add(group);
                ui.attachTo(graph,"Background",UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(description)).build());graph.compile();
                glTextureSubImage2D(texture,0,0,0,1,1,GL_RGBA,GL_FLOAT,new float[]{1,0,0,1});var input=input(64,64);
                ui.update(input.snapshot(),0);graph.execute(device,output.target());assertPixel(read(output,64,64),64,8,8,1,0,0);
                ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("image"));
                glTextureSubImage2D(texture,0,0,0,1,1,GL_RGBA,GL_FLOAT,new float[]{0,1,0,1});generation.incrementAndGet();
                ui.update(input.snapshot(),0);graph.execute(device,output.target());
                assertEquals("IMAGE_DEPENDENCY",ui.compositorRuntimeStatistics().cache().get("image"));
                assertPixel(read(output,64,64),64,8,8,0,1,0);GlDebug.assertNoError("UI in-place image generation");
            } finally {glDeleteTextures(texture);}
        }
    }
    @Test void contentBlurMatchesAnIndependentGaussianImpulseAndZeroRadiusBypassesFiltering() {
        try(var window=new GlfwWindow.Builder().dimensions(64,64).visible(false).title("UI Gaussian oracle").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            try(var output=OwnedPresentationTarget.create(64,64,RenderFormat.RGBA16F,false,0);
                var graph=new RenderGraph(64,64);var ui=UiSystem.create(window,colorConfig())) {
                graph.addPass("Background").writeToBackbuffer().noClear().execute((r,c)->c
                        .enableScissor(false).clearColor(0,0,1,1).clear(true,false));
                var description=UiLayerDescription.builder("pulse",new UiScreenRect(0,0,64,64)).blur(2,1).build();
                var group=new Panel();group.style(style(64,64));group.layerDescription(description);
                var pulse=new Panel();pulse.style(style(1,1));pulse.addStyleClass("red");pulse.visualTransform(UiVisualTransform.translation(16,16));
                group.add(pulse);ui.document().root().add(group);
                ui.attachTo(graph,"Background",UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(description)).build());graph.compile();
                var input=input(64,64);ui.update(input.snapshot(),0);graph.execute(device,output.target());
                double[] kernel=new double[5];double sum=0;
                for(int i=-2;i<=2;i++){kernel[i+2]=Math.exp(-(i*i)/(2*Math.pow(2.0/3,2)));sum+=kernel[i+2];}
                for(int i=0;i<5;i++)kernel[i]/=sum;
                float[] image=read(output,64,64);
                for(int y=-3;y<=3;y++)for(int x=-3;x<=3;x++) {
                    double expected=Math.abs(x)>2||Math.abs(y)>2?0:kernel[x+2]*kernel[y+2];int index=((16+y)*64+16+x)*4;
                    assertEquals(expected,image[index],.002,"independent normalized Gaussian impulse");
                    assertEquals(1-expected,image[index+2],.002,"premultiplied transparent edge must preserve source-over");
                }
                group.layerDescription(UiLayerDescription.builder("pulse",description.bounds()).effect(UiLayerDescription.Effect.BLUR).blur(0,1).build());
                ui.update(input.snapshot(),0);graph.execute(device,output.target());image=read(output,64,64);
                assertEquals(1,image[(16*64+16)*4],.002);assertEquals(0,image[(16*64+15)*4],.002);
                GlDebug.assertNoError("UI Gaussian impulse and bypass");
            }
        }
    }

    @Test void backdropUsesTheDynamicPreUiProducerAndKeepsActualGlyphsAndMarkersSharp() {
        try(var window=new GlfwWindow.Builder().dimensions(64,64).visible(false).title("UI backdrop oracle").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();var device=new GlRenderDevice();
            var blue=new java.util.concurrent.atomic.AtomicBoolean();
            try(var output=OwnedPresentationTarget.create(64,64,RenderFormat.RGBA16F,false,0);
                var graph=new RenderGraph(64,64);var ui=UiSystem.create(window,colorConfig())) {
                graph.addPass("PreUiSource").createColor("PreUi",RenderFormat.RGBA16F).fixedSize(64,64).noClear().execute((r,c)->c
                        .enableScissor(false).clearColor(blue.get()?0:1,0,blue.get()?1:0,1).clear(true,false));
                graph.addPass("Display").writeToBackbuffer().noClear().dependsOn("PreUiSource").execute((r,c)->c
                        .enableFramebufferSrgb(false).blitFramebuffer(r.framebufferOfPass("PreUiSource").id(),r.presentationTarget().drawFramebufferId(),64,64,64,64));
                var description=UiLayerDescription.builder("glass",new UiScreenRect(0,0,52,40)).backdropBlur(0,1).build();
                var group=new Panel();group.style(style(52,40));group.layerDescription(description);
                var marker=new Panel();marker.style(style(1,1));marker.addStyleClass("green");marker.visualTransform(UiVisualTransform.translation(16,16));group.add(marker);
                var label=new Label("UI");label.style(style(30,18));label.visualTransform(UiVisualTransform.translation(8,20));group.add(label);
                ui.document().root().add(group);
                ui.attachTo(graph,"Display",UiAttachmentOptions.builder().enableCompositor(true).backdropSource(UiBackdropSource.available("PreUi")).layers(List.of(description)).build());
                graph.compile();var input=input(64,64);
                // Let the real glyph upload protocol settle before freezing the sharp reference.
                for(int i=0;i<5;i++){ui.update(input.snapshot(),0);graph.execute(device,output.target());}
                float[] sharp=read(output,64,64);
                group.layerDescription(UiLayerDescription.builder("glass",description.bounds()).backdropBlur(2,1).build());
                ui.update(input.snapshot(),0);graph.execute(device,output.target());float[] blurred=read(output,64,64);
                for(int i=0;i<sharp.length;i++)assertEquals(sharp[i],blurred[i],.002,"constant backdrop blur must leave all real glyph coverage and markers sharp");
                blue.set(true);ui.update(input.snapshot(),0);graph.execute(device,output.target());float[] moved=read(output,64,64);
                assertEquals(0,moved[(8*64+8)*4],.002);assertEquals(1,moved[(8*64+8)*4+2],.002,"a static UI must consume the current background");
                for(int i=1;i<sharp.length;i+=4)assertEquals(sharp[i],moved[i],.002,"green glyph coverage must not be spatially blurred");
                assertTrue(java.util.stream.IntStream.range(0,64*64).anyMatch(i->sharp[i*4+1]>.05&&i/64>20),"the comparison must include actual rendered text");
                GlDebug.assertNoError("UI backdrop source and sharp text");
            }
        }
    }
    @Test void productionSubtreeGroupOpacityAndNestedOrderUseLinearPremultipliedPixels() {
        try(var window=new GlfwWindow.Builder().dimensions(96,64).visible(false).title("UI compositor reference").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();
            var device=new GlRenderDevice();
            try(var tracking=GlDebug.acquireResourceTracking();var output=OwnedPresentationTarget.create(96,64,RenderFormat.RGBA16F,false,0)) {
                for(boolean nested:new boolean[]{false,true}) {
                    var config=UiConfig.builder().styleResolver((widget,classes,states,inherited)->new ComputedStyle(
                            classes.contains("red")?new UiColor(1,0,0,1):classes.contains("green")?new UiColor(0,1,0,1):UiColor.TRANSPARENT,
                            UiColor.TRANSPARENT,UiColor.TRANSPARENT,0,0,1,12,com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine.DEFAULT_FONT_FAMILY)).build();
                    try(var graph=new RenderGraph(96,64);var ui=UiSystem.create(window,config)) {
                        graph.addPass("Background").writeToBackbuffer().noClear().execute((r,c)->c
                                .enableScissor(false).clearColor(0,0,1,1).clear(true,false));
                        var description=UiLayerDescription.builder("group",new UiScreenRect(0,0,52,40)).opacity(.5f).build();
                        var childDescription=UiLayerDescription.builder("child",new UiScreenRect(0,0,30,30)).parent("group").opacity(.5f).build();
                        var group=new Panel();group.style(style(52,40));group.layerDescription(description);
                        var red=new Panel();red.addStyleClass("red");red.style(style(40,30));group.add(red);
                        var green=new Button("");green.addStyleClass("green");green.style(style(30,30));green.visualTransform(UiVisualTransform.translation(20,0));
                        var clicks=new AtomicInteger();green.onClick(clicks::incrementAndGet);
                        if(nested)green.layerDescription(childDescription);group.add(green);ui.document().root().add(group);
                        ui.attachTo(graph,"Background",UiAttachmentOptions.builder().enableCompositor(true)
                                .layers(nested?List.of(description,childDescription):List.of(description)).build());graph.compile();
                        var input=new WindowInputCollector();input.windowSize(96,64);input.framebufferSize(96,64);input.focused(true);input.cursorInside(true);
                        ui.update(input.snapshot(),0);graph.execute(device,output.target());
                        int x=(int)green.layoutBox().x()+35,y=(int)green.layoutBox().y()+10;
                        float[] pixel=new float[4];glBindFramebuffer(GL_READ_FRAMEBUFFER,output.target().readFramebufferId());
                        glReadPixels(x,63-y,1,1,GL_RGBA,GL_FLOAT,pixel);
                        assertEquals(nested?.25:0,pixel[0],.002,"overlap must not multiply opacity per primitive or draw the subtree twice");
                        assertEquals(nested?.25:.5,pixel[1],.002);assertEquals(.5,pixel[2],.002);assertEquals(1,pixel[3],.002);
                        input.cursorPosition(x,y);input.pressMouse(MouseButton.LEFT,KeyModifiers.NONE);ui.update(input.snapshot(),0);graph.execute(device,output.target());
                        input.releaseMouse(MouseButton.LEFT,KeyModifiers.NONE);ui.update(input.snapshot(),0);graph.execute(device,output.target());
                        assertEquals(1,clicks.get(),"the transformed button must receive the same visible hit");
                        assertEquals(nested?2:1,ui.compositorDiagnostics().activeLayers());
                        GlDebug.assertNoError("UI compositor production subtree reference");
                    }
                }
            }
        }
    }
    private static UiStyle style(float width,float height) {
        return UiStyle.builder().width(UiLength.points(width)).height(UiLength.points(height))
                .positionType(UiStyle.PositionType.ABSOLUTE).flexShrink(0).build();
    }
    private static UiConfig colorConfig() {
        return UiConfig.builder().styleResolver((widget,classes,states,inherited)->new ComputedStyle(
                classes.contains("red")?new UiColor(1,0,0,1):classes.contains("green")?new UiColor(0,1,0,1):UiColor.TRANSPARENT,
                UiColor.WHITE,UiColor.TRANSPARENT,0,0,1,12,com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine.DEFAULT_FONT_FAMILY)).build();
    }
    private static WindowInputCollector input(int width,int height) {
        var input=new WindowInputCollector();input.windowSize(width,height);input.framebufferSize(width,height);input.focused(true);input.cursorInside(true);return input;
    }
    private static float[] read(OwnedPresentationTarget output,int width,int height) {
        float[] bottomUp=new float[width*height*4],topDown=new float[bottomUp.length];
        glBindFramebuffer(GL_READ_FRAMEBUFFER,output.target().readFramebufferId());glReadPixels(0,0,width,height,GL_RGBA,GL_FLOAT,bottomUp);
        for(int y=0;y<height;y++)System.arraycopy(bottomUp,(height-1-y)*width*4,topDown,y*width*4,width*4);
        return topDown;
    }
    private static void assertPixel(float[] image,int width,int x,int y,double red,double green,double blue) {
        int index=(y*width+x)*4;assertEquals(red,image[index],.002);assertEquals(green,image[index+1],.002);assertEquals(blue,image[index+2],.002);
    }
}
