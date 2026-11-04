package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.*;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.*;
import com.kaleblangley.haikalat.subsystems.ui.*;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiVisualTransform;
import com.kaleblangley.haikalat.subsystems.ui.render.*;
import com.kaleblangley.haikalat.subsystems.ui.style.*;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class UiCompositorSourceGlTest {
    @Test void oddDownsampledBlurMatchesIndependentResamplingAndGaussianAtTransparentEdges() {
        try(var window=window(97,65)) {
            init(window);var device=new GlRenderDevice();boolean roundToZero=calibrateHalfStore(device);
            for(int downsample:List.of(2,4)) {
                try(var output=OwnedPresentationTarget.create(97,65,RenderFormat.RGBA16F,false,0);
                    var graph=display(97,65);var ui=UiSystem.create(window,config())) {
                    var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,97,65)).blur(8,downsample).build();
                    var group=group(ui,description,97,65);var stripe=new Panel();stripe.style(style(8,65));stripe.addStyleClass("red");stripe.visualTransform(UiVisualTransform.translation(32,0));group.add(stripe);
                    ui.attachTo(graph,"Display",options(description,UiBackdropSource.unavailable(),100000));graph.compile();var input=input(97,65);
                    ui.update(input.snapshot(),0);graph.execute(device,output.target());
                    int fw=(113+downsample-1)/downsample,fh=(81+downsample-1)/downsample;
                    double[] xWeights=weights(8.0*fw/113),yWeights=weights(8.0*fh/81),horizontal=new double[fw],vertical=new double[fh],ySource=new double[fh];
                    for(int j=0;j<fw;j++)for(int i=1-xWeights.length;i<xWeights.length;i++)horizontal[j]+=xWeights[Math.abs(i)]*stripe((j+.5+i)*113/fw-.5,40,48);
                    for(int k=0;k<fh;k++)ySource[k]=stripe((k+.5)*81/fh-.5,8,73);
                    double[][] first=new double[fh][fw],second=new double[fh][fw];
                    for(int k=0;k<fh;k++)for(int j=0;j<fw;j++)first[k][j]=half(horizontal[j]*ySource[k],roundToZero);
                    for(int k=0;k<fh;k++)for(int j=0;j<fw;j++) {
                        double sum=0;for(int i=1-yWeights.length;i<yWeights.length;i++)sum+=yWeights[Math.abs(i)]*first[Math.max(0,Math.min(fh-1,k+i))][j];
                        second[k][j]=half(sum,roundToZero);
                    }
                    for(int y:List.of(0,1,8,32,64))for(int x=20;x<53;x++) {
                        double expected=half(sample(second,(x+.5+8)*fw/113-.5,(y+.5+8)*fh/81-.5),roundToZero);
                        float[] pixel=pixel(output,x,y);assertEquals(expected,pixel[0],.001,"independent ceil/downsample/edge reference d="+downsample+" at "+x+","+y);
                        assertEquals(1-expected,pixel[2],.002,"transparent filtering must preserve premultiplied source-over");
                    }
                    GlDebug.assertNoError("UI odd downsample Gaussian reference");
                }
            }
        }
    }
    @Test void finalSrgbEncodingOccursOnceAndGroupOpacityZeroHalfAndOneReuseTheSameRaster() {
        try(var window=window(64,64)) {
            init(window);var device=new GlRenderDevice();
            for(var format:List.of(RenderFormat.RGBA16F,RenderFormat.SRGB8_ALPHA8)) {
                try(var output=OwnedPresentationTarget.create(64,64,format,false,0);var graph=display(64,64);var ui=UiSystem.create(window,config())) {
                    var bounds=new UiScreenRect(0,0,41,31);var description=UiLayerDescription.builder("panel",bounds).framebufferScale(.5f).build();
                    var group=group(ui,description,41,31);group.addStyleClass("red");
                    ui.attachTo(graph,"Display",options(description,UiBackdropSource.unavailable(),100000));graph.compile();var input=input(64,64);
                    ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals(21L*16*8,ui.compositorRuntimeStatistics().bytes());
                    for(float opacity:new float[]{0,.5f,1}) {
                        group.layerDescription(UiLayerDescription.builder("panel",bounds).framebufferScale(.5f).opacity(opacity).build());
                        ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals("HIT",ui.compositorRuntimeStatistics().cache().get("panel"));
                        float[] value=pixel(output,10,10);assertEquals(format==RenderFormat.SRGB8_ALPHA8?encode(opacity):opacity,value[0],.003);
                        assertEquals(format==RenderFormat.SRGB8_ALPHA8?encode(1-opacity):1-opacity,value[2],.003);
                    }
                    group.layerDescription(UiLayerDescription.builder("panel",bounds).dirtyRevision(1).build());
                    ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals("TARGET_REPLACED",ui.compositorRuntimeStatistics().cache().get("panel"));
                    assertEquals(41L*31*8,ui.compositorRuntimeStatistics().bytes());
                    group.layerDescription(UiLayerDescription.builder("panel",bounds).dirtyRevision(2).build());
                    ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals("AUTHOR_DIRTY",ui.compositorRuntimeStatistics().cache().get("panel"));
                    GlDebug.assertNoError("UI linear/sRGB final targets and raster scale");
                }
            }
        }
    }
    @Test void missingOptionalSourcePreservesContentAndStrictOrLateSourcesFailBeforeUiMutation() {
        try(var window=window(64,64)) {
            init(window);var device=new GlRenderDevice();var bounds=new UiScreenRect(0,0,30,30);
            var description=UiLayerDescription.builder("panel",bounds).opacity(.5f).backdropBlur(2,1).build();
            try(var output=OwnedPresentationTarget.create(64,64,RenderFormat.RGBA16F,false,0);var graph=display(64,64);var ui=UiSystem.create(window,config())) {
                group(ui,description,30,30).addStyleClass("red");ui.attachTo(graph,"Display",options(description,UiBackdropSource.unavailable(),100000));graph.compile();
                ui.update(input(64,64).snapshot(),0);graph.execute(device,output.target());float[] p=pixel(output,10,10);
                assertEquals(.5,p[0],.002);assertEquals(.5,p[2],.002);assertEquals(0,ui.compositorRuntimeStatistics().blurDraws());
                assertTrue(ui.compositorDiagnostics().fallbacks().stream().anyMatch(f->f.reason()==UiCompositor.FallbackReason.BACKDROP_SOURCE_UNAVAILABLE));
            }
            try(var graph=display(64,64);var ui=UiSystem.create(window,config())) {
                var strict=UiLayerDescription.builder("panel",bounds).backdropBlur(2,1).backdropRequired(true).build();
                assertThrows(IllegalArgumentException.class,()->ui.attachTo(graph,"Display",options(strict,UiBackdropSource.unavailable(),100000)));
                assertFalse(graph.isTopologySealed());assertFalse(graph.hasPass("UiLayer/panel"));
            }
            try(var graph=display(64,64);var ui=UiSystem.create(window,config())) {
                graph.addPass("Late").createColor("LateColor",RenderFormat.RGBA16F).fixedSize(64,64).dependsOn("Display").execute((r,c)->{});
                assertThrows(IllegalArgumentException.class,()->ui.attachTo(graph,"Display",options(description,UiBackdropSource.available("LateColor"),100000)));
                assertFalse(graph.hasPass("UiLayer/panel"));
            }
            GlDebug.assertNoError("UI missing/strict/late backdrop contracts");
        }
    }
    @Test void srgbTextureAndMsaaResolveSupplyLinearBackdropAndPresentationAliasesAreRejected() {
        try(var window=window(64,64)) {
            init(window);var device=new GlRenderDevice();
            for(boolean msaa:List.of(false,true)) {
                try(var output=OwnedPresentationTarget.create(64,64,RenderFormat.RGBA16F,false,0);var graph=new RenderGraph(64,64);var ui=UiSystem.create(window,config())) {
                    var sourceFbo=new AtomicInteger();var sourceTexture=new AtomicInteger();var source=graph.addPass("Source");
                    if(msaa)source.createColorMS("SourceColor",RenderFormat.RGBA16F,4);else source.createColor("SourceColor",RenderFormat.SRGB8_ALPHA8);
                    source.fixedSize(64,64).noClear().execute((r,c)->{sourceFbo.set(r.currentTarget().id());sourceTexture.set(r.currentTarget().colorAttachment());c.clearColor(.25f,.125f,.5f,1).clear(true,false);});
                    graph.addPass("Display").writeToBackbuffer().dependsOn("Source").noClear().execute((r,c)->c.clearColor(0,0,1,1).clear(true,false));
                    var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,52,40)).backdropBlur(2,1).build();
                    var group=group(ui,description,52,40);var marker=new Panel();marker.style(style(1,1));marker.addStyleClass("green");marker.visualTransform(UiVisualTransform.translation(16,16));group.add(marker);
                    ui.attachTo(graph,"Display",options(description,UiBackdropSource.available("SourceColor"),100000));graph.compile();var input=input(64,64);
                    ui.update(input.snapshot(),0);graph.execute(device,output.target());float[] p=pixel(output,8,8);
                    assertEquals(msaa?.25:srgbRoundTrip(.25),p[0],.003);
                    assertEquals(msaa?.125:srgbRoundTrip(.125),p[1],.003);
                    assertEquals(msaa?.5:srgbRoundTrip(.5),p[2],.003);
                    assertEquals(1,pixel(output,16,16)[1],.002,"a real sharp marker must overlay the filtered background");
                    assertEquals(msaa,graph.hasPass("UiCompositor/BackdropResolve"));
                    assertEquals((56L*44*3+(msaa?64L*64:0))*8,ui.compositorRuntimeStatistics().bytes());
                    if(!msaa) {
                        var aliased=PresentationTarget.borrowed(sourceFbo.get(),ExternalAttachment.borrowedColor(sourceTexture.get(),RenderFormat.SRGB8_ALPHA8,64,64),null,64,64,1);
                        ui.update(input.snapshot(),0);assertThrows(IllegalArgumentException.class,()->graph.execute(device,aliased));
                        ui.update(input.snapshot(),0);graph.execute(device,output.target());assertEquals(.25,pixel(output,8,8)[0],.003);
                    }
                    GlDebug.assertNoError("UI sRGB source, MSAA resolve and feedback rejection");
                }
            }
        }
    }
    @Test void optionalMsaaResolveBudgetDropsOnlyBackdropFilteringWhileStrictRequestsKeepTheGraphUntouched() {
        try(var window=window(64,64)) {
            init(window);var device=new GlRenderDevice();
            for(boolean strict:List.of(false,true)) {
                try(var output=OwnedPresentationTarget.create(64,64,RenderFormat.RGBA16F,false,0);var graph=new RenderGraph(64,64);var ui=UiSystem.create(window,config())) {
                    graph.addPass("Source").createColorMS("SourceColor",RenderFormat.RGBA16F,4).fixedSize(64,64).execute((r,c)->{});
                    graph.addPass("Display").writeToBackbuffer().dependsOn("Source").noClear().execute((r,c)->c.clearColor(0,0,1,1).clear(true,false));
                    var description=UiLayerDescription.builder("panel",new UiScreenRect(0,0,20,20)).opacity(.5f).backdropBlur(4,2).backdropRequired(strict).build();
                    group(ui,description,20,20).addStyleClass("red");var options=options(description,UiBackdropSource.available("SourceColor"),5000);
                    if(strict){assertThrows(IllegalArgumentException.class,()->ui.attachTo(graph,"Display",options));assertFalse(graph.hasPass("UiLayer/panel"));continue;}
                    ui.attachTo(graph,"Display",options);graph.compile();ui.update(input(64,64).snapshot(),0);graph.execute(device,output.target());
                    assertFalse(graph.hasPass("UiCompositor/BackdropResolve"));assertEquals(20L*20*8,ui.compositorRuntimeStatistics().bytes());
                    assertEquals(.5,pixel(output,10,10)[0],.002);assertEquals(.5,pixel(output,10,10)[2],.002);
                    assertTrue(ui.compositorDiagnostics().fallbacks().stream().anyMatch(f->f.reason()==UiCompositor.FallbackReason.OPTIONAL_EFFECT_BUDGET));
                    GlDebug.assertNoError("UI MSAA resolve budget");
                }
            }
        }
    }
    private static GlfwWindow window(int w,int h){return new GlfwWindow.Builder().dimensions(w,h).visible(false).title("UI source and encoding").build();}
    private static void init(GlfwWindow window){window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();}
    private static RenderGraph display(int w,int h){var graph=new RenderGraph(w,h);graph.addPass("Display").writeToBackbuffer().noClear().execute((r,c)->c.clearColor(0,0,1,1).clear(true,false));return graph;}
    private static Panel group(UiSystem ui,UiLayerDescription description,int w,int h){var panel=new Panel();panel.style(style(w,h));panel.layerDescription(description);ui.document().root().add(panel);return panel;}
    private static UiAttachmentOptions options(UiLayerDescription layer,UiBackdropSource source,long budget){return UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(layer)).backdropSource(source).maximumIntermediatePixels(budget).build();}
    private static UiStyle style(float w,float h){return UiStyle.builder().positionType(UiStyle.PositionType.ABSOLUTE).width(UiLength.points(w)).height(UiLength.points(h)).flexShrink(0).build();}
    private static UiConfig config(){return UiConfig.builder().styleResolver((widget,classes,states,inherited)->new ComputedStyle(classes.contains("red")?new UiColor(1,0,0,1):classes.contains("green")?new UiColor(0,1,0,1):UiColor.TRANSPARENT,UiColor.WHITE,UiColor.TRANSPARENT,0,0,1,12,com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine.DEFAULT_FONT_FAMILY)).build();}
    private static WindowInputCollector input(int w,int h){var input=new WindowInputCollector();input.windowSize(w,h);input.framebufferSize(w,h);input.focused(true);input.cursorInside(true);return input;}
    private static float[] pixel(OwnedPresentationTarget output,int x,int y){float[] value=new float[4];glBindFramebuffer(GL_READ_FRAMEBUFFER,output.target().readFramebufferId());glReadPixels(x,output.target().height()-1-y,1,1,GL_RGBA,GL_FLOAT,value);return value;}
    private static double encode(double v){return v<=.0031308?12.92*v:1.055*Math.pow(v,1/2.4)-.055;}
    private static double srgbRoundTrip(double linear){double encoded=Math.round(encode(linear)*255)/255.0;return encoded<=.04045?encoded/12.92:Math.pow((encoded+.055)/1.055,2.4);}
    private static double[] weights(double radius){int half=(int)Math.ceil(radius);double[] w=new double[half+1];double sum=0,sigma=Math.max(.5,radius/3);for(int i=0;i<=half;i++){w[i]=Math.exp(-.5*i*i/(sigma*sigma));sum+=w[i]*(i==0?1:2);}for(int i=0;i<=half;i++)w[i]/=sum;return w;}
    private static double stripe(double x,int start,int end){int i=(int)Math.floor(x);double f=x-i;return (i>=start&&i<end?1-f:0)+(i+1>=start&&i+1<end?f:0);}
    private static double sample(double[] v,double x){int i=(int)Math.floor(x);double f=x-i;return v[Math.max(0,Math.min(v.length-1,i))]*(1-f)+v[Math.max(0,Math.min(v.length-1,i+1))]*f;}
    private static double sample(double[][] v,double x,double y){int i=(int)Math.floor(y);double f=y-i;return sample(v[Math.max(0,Math.min(v.length-1,i))],x)*(1-f)+sample(v[Math.max(0,Math.min(v.length-1,i+1))],x)*f;}
    private static double half(double value,boolean towardZero){float f=(float)value;short bits=Float.floatToFloat16(f);float rounded=Float.float16ToFloat(bits);if(towardZero&&rounded>f&&f>0)bits--;return Float.float16ToFloat(bits);}
    /** Calibrate storage alone, without using the filter or its output as a reference. */
    private static boolean calibrateHalfStore(GlRenderDevice device) {
        try(var output=OwnedPresentationTarget.create(1,1,RenderFormat.RGBA16F,false,0);
            var vao=new com.kaleblangley.haikalat.backend.vertex.VertexArray();
            var shader=com.kaleblangley.haikalat.backend.shader.ShaderProgram.fromSources("""
                    #version 460 core
                    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}
                    ""","""
                    #version 460 core
                    uniform float uValue;
                    out vec4 color;
                    void main(){color=vec4(uValue,0,0,1);}
                    """)) {
            boolean nearest=true,truncated=true;
            for(float value:new float[]{.6234f,.12347f,.5f}) {
                device.execute(device.createCommandBuffer().bindFramebuffer(GL_FRAMEBUFFER,output.target().drawFramebufferId()).viewport(0,0,1,1)
                        .enableBlend(false).enableDepthTest(false).enableCullFace(false).enableScissor(false).enableFramebufferSrgb(false)
                        .bindShader(shader).setUniformFloat(shader,"uValue",value).bindVertexArray(vao.id()).drawArrays(GL_TRIANGLES,0,3));
                float observed=pixel(output,0,0)[0];nearest&=observed==half(value,false);truncated&=observed==half(value,true);
            }
            assertTrue(nearest||truncated,"unknown FP16 framebuffer storage conversion; reference needs an explicit profile");
            System.out.println("UI_FP16_STORE="+(nearest?"NEAREST_EVEN":"TOWARD_ZERO"));return !nearest;
        }
    }
}
