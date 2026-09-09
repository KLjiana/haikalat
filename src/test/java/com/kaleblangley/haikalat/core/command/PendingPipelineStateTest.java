package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.state.PipelineStateSink;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.FrontFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                "blend:true", "depthMask:false"), sink.events);
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
                .frontFace(FrontFace.CW)
                .scissor(2, 3, 1276, 714)
                .enableScissor(true)
                .enableFramebufferSrgb(false)
                .blendFunc(GL_ONE, GL_ONE)
                .clearColor(0, 0, 0, 1)
                .materialState(BlendMode.OPAQUE, true);

        assertEquals(1, commands.commandCount(),
                "Pipeline state setters must collapse into one pending state packet");
        assertEquals(0, commands.objectPayloadCount());
        commands.reset();
        assertEquals(0, commands.commandCount());
        assertEquals(0, commands.objectPayloadCount());
    }

    @Test
    void frontFaceUsesLastValueWithoutCrossingDrawBoundary() {
        PendingPipelineState pending = new PendingPipelineState();
        RecordingSink sink = new RecordingSink();
        pending.frontFace(FrontFace.CCW.glValue());
        pending.frontFace(FrontFace.CW.glValue());
        pending.flush(sink);
        pending.frontFace(FrontFace.CCW.glValue());
        pending.flush(sink);
        assertEquals(List.of("front:" + FrontFace.CW.glValue(),
                "front:" + FrontFace.CCW.glValue()), sink.events);
    }

    @Test
    void identicalStateAcrossDrawBoundariesIsEncodedOnlyOnce() {
        CommandBuffer commands = new CommandBuffer()
                .frontFace(FrontFace.CCW)
                .drawArrays(GL_TRIANGLES, 0, 3)
                .frontFace(FrontFace.CCW)
                .drawArrays(GL_TRIANGLES, 0, 3);

        assertEquals(3, commands.commandCount(),
                "The retained front-face value does not need a second state packet");
    }

    @Test
    void customBarrierInvalidatesRecorderStateKnowledge() {
        CommandBuffer commands = new CommandBuffer()
                .frontFace(FrontFace.CCW)
                .drawArrays(GL_TRIANGLES, 0, 3)
                .custom(() -> { })
                .frontFace(FrontFace.CCW);

        assertEquals(4, commands.commandCount(),
                "A custom callback may change GL state, so the same value must be encoded again");
    }

    @Test
    void recordingCreatesSeparateStatePacketsAtDrawAndClearBoundaries() {
        CommandBuffer commands = new CommandBuffer()
                .enableBlend(false)
                .enableBlend(true)
                .scissor(0, 0, 16, 16)
                .enableScissor(true)
                .drawArrays(GL_TRIANGLES, 0, 3)
                .enableBlend(false)
                .enableScissor(false)
                .drawArrays(GL_TRIANGLES, 0, 3)
                .depthMask(false)
                .clear(false, true);

        assertEquals(6, commands.commandCount(),
                "Three state packets must remain separated from two draws and one clear");
        assertEquals(0, commands.objectPayloadCount());
    }

    @Test
    void scissorUsesTheLastRectangleAndEnableValueAtEachBoundary() {
        PendingPipelineState pending = new PendingPipelineState();
        RecordingSink sink = new RecordingSink();

        pending.scissor(0, 0, 100, 100);
        pending.scissor(3, 4, 20, 30);
        pending.enableScissor(false);
        pending.enableScissor(true);
        pending.flush(sink);

        assertEquals(List.of("scissor:3:4:20:30", "scissorEnable:true"), sink.events);
        assertThrows(IllegalArgumentException.class, () -> pending.scissor(-1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandBuffer().scissor(0, 0, 1, -1));
        new CommandBuffer().scissor(0, 0, 0, 0);
    }

    @Test
    void customBoundarySeparatesScissorPacketsAndResetDropsPendingState() {
        CommandBuffer commands = new CommandBuffer()
                .scissor(1, 2, 3, 4)
                .enableScissor(true)
                .custom(() -> { })
                .enableScissor(false);

        assertEquals(3, commands.commandCount(),
                "state packet, custom barrier and following pending packet remain ordered");
        assertEquals(1, commands.objectPayloadCount());
        commands.reset();
        assertEquals(0, commands.commandCount());
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

        @Override public void depthFunc(int function) { events.add("depthFunc:" + function); }

        @Override public void enableDepthTest(boolean enable) {
            events.add("depthTest:" + enable);
        }

        @Override public void enableCullFace(boolean enable) { events.add("cull:" + enable); }
        @Override public void frontFace(int winding) { events.add("front:" + winding); }

        @Override public void enableScissor(boolean enable) {
            events.add("scissorEnable:" + enable);
        }

        @Override public void scissor(int x, int y, int width, int height) {
            events.add("scissor:" + x + ":" + y + ":" + width + ":" + height);
        }

        @Override public void enableFramebufferSrgb(boolean enable) {
            events.add("framebufferSrgb:" + enable);
        }

        @Override public void clearColor(float red, float green, float blue, float alpha) {
            events.add("clearColor:" + red + ":" + green + ":" + blue + ":" + alpha);
        }
    }
}
