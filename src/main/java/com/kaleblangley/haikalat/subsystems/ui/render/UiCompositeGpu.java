package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Vector4f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/** Internal shaders only; attachment storage remains exclusively owned by RenderGraph. */
final class UiCompositeGpu implements AutoCloseable {
    private final ShaderProgram composite;
    private final ShaderProgram blur;
    private final VertexArray quad;
    private final Sampler sampler;
    UiCompositeGpu() {
        ShaderProgram program=null,filter=null;VertexArray array=null;Sampler sampling=null;
        try {
            program=ShaderProgram.fromResource(getClass(),"/shaders/ui/composite.vert","/shaders/ui/composite.frag");
            filter=ShaderProgram.fromResource(getClass(),"/shaders/ui/composite.vert","/shaders/ui/blur.frag");
            array=new VertexArray();
            sampling=Sampler.create(new Sampler.Descriptor(GL_LINEAR,GL_LINEAR,GL_CLAMP_TO_EDGE,GL_CLAMP_TO_EDGE));
        } catch(RuntimeException|Error failure) {
            for(AutoCloseable c:new AutoCloseable[]{sampling,array,filter,program})if(c!=null)try{c.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
        composite=program;blur=filter;quad=array;sampler=sampling;
    }
    void blur(CommandBuffer commands,int texture,float radius,boolean horizontal,int framebuffer,
              int width,int height,UiScreenRect inputView,UiScreenRect outputView) {
        float ux=(float)((outputView.x()-inputView.x())/inputView.width());
        float uy=(float)(1-(outputView.bottom()-inputView.y())/inputView.height());
        float uw=(float)(outputView.width()/inputView.width()),uh=(float)(outputView.height()/inputView.height());
        float pixelsPerLogical=(float)(horizontal?width/outputView.width():height/outputView.height());
        float[] kernel=UiBlurKernel.gaussian(radius*pixelsPerLogical);
        commands.bindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,framebuffer).viewport(0,0,width,height)
                .enableFramebufferSrgb(false).enableDepthTest(false).depthMask(false).enableCullFace(false)
                .enableBlend(false).enableScissor(false).bindShader(blur)
                .setUniformVec2(blur,"uViewport",width,height).setUniformVec4(blur,"uRect",new Vector4f(0,0,width,height))
                .setUniformVec4(blur,"uUvRect",new Vector4f(ux,uy,uw,uh))
                .setUniformVec2(blur,"uStep",horizontal?uw/width:0,horizontal?0:uh/height)
                .setUniformInt(blur,"uSource",0).setUniformInt(blur,"uHalfWidth",kernel.length-1)
                .bindTexture(0,texture).bindSampler(0,sampler).bindVertexArray(quad.id());
        for(int i=0;i<kernel.length;i++)commands.setUniformFloat(blur,"uWeights["+i+"]",kernel[i]);
        commands.drawArrays(GL_TRIANGLES,0,6);
    }
    void composite(CommandBuffer commands,int texture,float opacity,int framebuffer,int width,int height,
                   boolean srgb,UiScreenRect view,UiScreenRect bounds,UiScreenRect clip) {
        commands.bindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,framebuffer).viewport(0,0,width,height)
                .enableFramebufferSrgb(srgb).enableDepthTest(false).depthMask(false).enableCullFace(false)
                .enableBlend(true).blendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA).enableScissor(false)
                .bindShader(composite).setUniformVec2(composite,"uViewport",(float)view.width(),(float)view.height())
                .setUniformVec4(composite,"uRect",new Vector4f((float)(bounds.x()-view.x()),(float)(bounds.y()-view.y()),(float)bounds.width(),(float)bounds.height()))
                .setUniformInt(composite,"uSource",0).setUniformFloat(composite,"uOpacity",opacity)
                .bindTexture(0,texture).bindSampler(0,sampler).bindVertexArray(quad.id());
        if(clip!=null) {
            var c=clip.intersect(view);
            var local=new UiScreenRect(c.x()-view.x(),c.y()-view.y(),c.width(),c.height());
            var scissor=local.toGlScissor(width/view.width(),height/view.height(),width,height);
            commands.enableScissor(true).scissor(scissor.x(),scissor.y(),scissor.width(),scissor.height());
        }
        commands.drawArrays(GL_TRIANGLES,0,6).enableScissor(false);
    }
    @Override public void close() {
        RuntimeException failure=null;
        for(AutoCloseable c:new AutoCloseable[]{sampler,quad,blur,composite})try{c.close();}catch(Exception e){
            RuntimeException next=e instanceof RuntimeException r?r:new IllegalStateException(e);
            if(failure==null)failure=next;else failure.addSuppressed(next);
        }
        if(failure!=null)throw failure;
    }
}
