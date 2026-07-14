package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.state.PipelineStateSink;
import com.kaleblangley.haikalat.core.BlendMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

class PendingPipelineStateTest {
    @Test
    void repeatedSettersCollapseToOneFinalValuePerBoundary() {
        PendingPipelineState pending = new PendingPipelineState();
        RecordingSink sink = new RecordingSink();

        pending.enableBlend(false);
        pending.enableBlend(true);
        pending.enableBlend(false);
        pending.depthMask(false);
        pending.depthMask(true);
        pending.blendFunc(GL_ONE, GL_ONE);
        pending.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        pending.flush(sink);

        assertEquals(List.of(
                "blendFunc:" + GL_SRC_ALPHA + ":" + GL_ONE_MINUS_SRC_ALPHA,
                "blend:false",
                "depthMask:true"), sink.events);
        assertFalse(pending.isDirty());

        pending.flush(sink);
        assertEquals(3, sink.events.size(), "A clean flush must emit no backend checks");
    }

    @Test
    void drawLikeBoundariesKeepOpaqueAndTransparentPacketsOrdered() {
        PendingPipelineState pending = new PendingPipelineState();
        RecordingSink sink = new RecordingSink();

        pending.materialState(BlendMode.OPAQUE, true);
        pending.flush(sink); // first draw boundary
        pending.materialState(BlendMode.ALPHA, true);
        pending.flush(sink); // second draw boundary

        assertEquals(List.of(
                "blend:false", "depthMask:true", "depthTest:true",
                "blendFunc:" + GL_SRC_ALPHA + ":" + GL_ONE_MINUS_SRC_ALPHA,
                "blend:true", "depthMask:false", "depthTest:true"), sink.events);
    }

    @Test
    void primitivePipelineCommandsDoNotAllocateObjectPayloads() {
        CommandBuffer commands = new CommandBuffer()
                .viewport(0, 0, 1280, 720)
                .enableBlend(false)
                .enableBlend(true)
                .depthMask(true)
                .enableDepthTest(true)
                .enableCullFace(true)
                .blendFunc(GL_ONE, GL_ONE)
                .clearColor(0, 0, 0, 1)
                .materialState(BlendMode.OPAQUE, true);

        assertEquals(1, commands.commandCount(),
                "Nine state setters must collapse into one pending state packet");
        assertEquals(0, commands.objectPayloadCount());
        commands.reset();
        assertEquals(0, commands.commandCount());
        assertEquals(0, commands.objectPayloadCount());
    }

    @Test
    void recordingCreatesSeparateStatePacketsAtDrawAndClearBoundaries() {
        CommandBuffer commands = new CommandBuffer()
                .enableBlend(false)
                .enableBlend(true)
                .drawArrays(GL_TRIANGLES, 0, 3)
                .enableBlend(false)
                .drawArrays(GL_TRIANGLES, 0, 3)
                .depthMask(false)
                .clear(false, true);

        assertEquals(6, commands.commandCount(),
                "Three state packets must remain separated from two draws and one clear");
        assertEquals(0, commands.objectPayloadCount());
    }

    private static final class RecordingSink implements PipelineStateSink {
        private final List<String> events = new ArrayList<>();

        @Override public void viewport(int x, int y, int width, int height) {
            events.add("viewport:" + x + ":" + y + ":" + width + ":" + height);
        }

        @Override public void enableBlend(boolean enable) { events.add("blend:" + enable); }

        @Override public void blendFunc(int sourceRgb, int destinationRgb) {
            events.add("blendFunc:" + sourceRgb + ":" + destinationRgb);
        }

        @Override public void depthMask(boolean write) { events.add("depthMask:" + write); }

        @Override public void enableDepthTest(boolean enable) {
            events.add("depthTest:" + enable);
        }

        @Override public void enableCullFace(boolean enable) { events.add("cull:" + enable); }

        @Override public void clearColor(float red, float green, float blue, float alpha) {
            events.add("clearColor:" + red + ":" + green + ":" + blue + ":" + alpha);
        }
    }
}
