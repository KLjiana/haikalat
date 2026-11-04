package com.kaleblangley.haikalat.backend.framebuffer;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45.*;

@EnabledIfSystemProperty(named="haikalat.glSmoke",matches="true")
class RenderTargetManagerGlTest {
    @Test void partialNativeReplacementFailurePreservesAllOldTargetsAndReleasesOnlyCandidates() {
        try(var window=new GlfwWindow.Builder().dimensions(97,65).visible(false).title("UI target allocation rollback").build()) {
            window.bindContext();GL.createCapabilities();GlDebug.enableDebugCallback();
            try(var tracking=GlDebug.acquireResourceTracking()) {
                var baseline=GlDebug.resources();
                try(var manager=new RenderTargetManager()) {
                    var descriptor=FramebufferDescriptor.colorOnly(8,8,GL_RGBA16F);
                    var first=manager.create("first",descriptor);var second=manager.create("second",descriptor);
                    var unrelated=manager.create("unrelated",descriptor);
                    clear(first,1,0);clear(second,0,1);
                    var stable=GlDebug.resources();var replacements=new LinkedHashMap<String,FramebufferDescriptor>();
                    replacements.put("first",descriptor.resized(16,12));replacements.put("second",descriptor.resized(16,12));
                    var allocations=new AtomicInteger();int[] candidateId={0};
                    assertThrows(IllegalStateException.class,()->manager.replaceSelected(replacements,d->{
                        if(allocations.incrementAndGet()==2)throw new IllegalStateException("injected second attachment allocation failure");
                        var allocated=Framebuffer.fromDescriptor(d);candidateId[0]=allocated.id();return allocated;
                    }));
                    assertEquals(2,allocations.get());assertFalse(glIsFramebuffer(candidateId[0]));
                    assertSame(first,manager.get("first"));assertSame(second,manager.get("second"));
                    assertSame(unrelated,manager.get("unrelated"));assertEquals(descriptor,manager.descriptor("first"));
                    assertEquals(stable.liveResources(),GlDebug.resources().liveResources());
                    assertEquals(stable.estimatedBytes(),GlDebug.resources().estimatedBytes());
                    pixel(first,1,0);pixel(second,0,1);
                    manager.replaceSelected(replacements);
                    assertFalse(glIsFramebuffer(first.id()));assertFalse(glIsFramebuffer(second.id()));
                    assertSame(unrelated,manager.get("unrelated"));
                    assertEquals(16,manager.get("first").width());assertEquals(12,manager.get("second").height());
                    clear(manager.get("first"),0,1);pixel(manager.get("first"),0,1);
                    assertThrows(IllegalArgumentException.class,()->manager.replaceSelected(Map.of("unknown",descriptor)));
                }
                assertEquals(baseline.liveResources(),GlDebug.resources().liveResources());
                assertEquals(baseline.estimatedBytes(),GlDebug.resources().estimatedBytes());
                GlDebug.assertNoError("partial target allocation rollback");
            }
        }
    }
    private static void clear(Framebuffer target,float r,float g) {
        glBindFramebuffer(GL_FRAMEBUFFER,target.id());glDisable(GL_SCISSOR_TEST);glClearColor(r,g,0,1);glClear(GL_COLOR_BUFFER_BIT);
    }
    private static void pixel(Framebuffer target,float r,float g) {
        float[] value=new float[4];glBindFramebuffer(GL_READ_FRAMEBUFFER,target.id());glReadPixels(3,3,1,1,GL_RGBA,GL_FLOAT,value);
        assertEquals(r,value[0],.001);assertEquals(g,value[1],.001);assertEquals(1,value[3],.001);
    }
}
