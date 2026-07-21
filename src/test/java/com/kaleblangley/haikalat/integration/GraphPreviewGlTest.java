package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewController;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.preview.PreviewAspect;
import com.kaleblangley.haikalat.subsystems.render3d.preview.PreviewSourceKey;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBatcher;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBlendMode;
import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiRenderSnapshot;
import com.kaleblangley.haikalat.subsystems.ui.render.UiRenderer;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.render.UiUvRect;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.glGetTextureImage;

/** 预览 conversion、MRT read attachment 与精确 depth resolve 的真实 GL 回归。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GraphPreviewGlTest {
    @Test
    void staleAsyncLogicalImageIsSkippedBeforeBindingReleasedTexture() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            UiDisplayList display = new UiDisplayList().addLogicalImageQuad(
                    new UiScreenRect(0, 0, 16, 16), UiUvRect.FULL,
                    GraphPreviewController.IMAGE_ID, 999_999, 0,
                    0xffffffff, UiBlendMode.PREMULTIPLIED_ALPHA);
            UiRenderSnapshot snapshot = UiRenderSnapshot.capture(1L,
                    32, 32, 32, 32, 1.0, 1.0, display, new UiBatcher());
            try (UiRenderer renderer = new UiRenderer(16, 64, 64, 1,
                    imageId -> java.util.Optional.empty())) {
                device.execute(device.createCommandBuffer().clearColor(0, 0, 0, 1).clear(true, false));
                var commands = device.createCommandBuffer();
                renderer.record(snapshot, commands);
                device.execute(commands);
                assertEquals(0, renderer.lastDrawCalls());
                GlDebug.assertNoError("stale async preview image");
            }
        }
    }

    @Test
    void knownColorGraphAttachmentProducesPreviewOwnedPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            RenderGraph graph = new RenderGraph(32, 32);
            graph.addPass("KnownColor")
                    .createColor("known", RenderFormat.RGBA8)
                    .fixedSize(8, 4)
                    .clearColor(1.0f, 0.0f, 0.0f, 1.0f)
                    .execute((resources, commands) -> { });
            GraphPreviewController controller = new GraphPreviewController();
            GraphPreviewRenderer renderer = new GraphPreviewRenderer(controller, graph, null, 1L);
            graph.addPass("PreviewOverlay")
                    .writeToBackbuffer().noClear().dependsOn("KnownColor")
                    .execute(renderer.overlayRecorder());
            graph.sealTopology();
            controller.detailedDiagnostics(true);
            controller.panelVisible(true);
            controller.select(PreviewSourceKey.graph(1L, "KnownColor", "known",
                    PreviewAspect.COLOR));

            try (renderer; graph) {
                renderer.prepare(device, 0L);
                graph.execute(device);
                renderer.frameSucceeded(0L);

                GraphPreviewController.PreviewOutput output = controller.output().orElseThrow();
                ByteBuffer pixel = BufferUtils.createByteBuffer(output.width() * output.height() * 4);
                glGetTextureImage(output.textureId(), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                assertTrue(Byte.toUnsignedInt(pixel.get(0)) > 240);
                assertTrue(Byte.toUnsignedInt(pixel.get(1)) < 8);
                assertEquals(1, controller.summary().frameDraws());
                assertEquals(0, controller.summary().frameBlits());
                GlDebug.assertNoError("graph preview known color");
            }
        }
    }

    @Test
    void typedMsaaBlitSelectsRequestedMrtAttachmentAndMatchesDepthStencilFormat() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            FramebufferDescriptor.Builder sourceBuilder = FramebufferDescriptor.builder(8, 8).samples(4)
                    .colorRenderbuffer(RenderFormat.RGBA8)
                    .colorRenderbuffer(RenderFormat.RGBA8)
                    .depthStencilRenderbuffer();
            try (Framebuffer source = Framebuffer.fromDescriptor(sourceBuilder.build());
                 Framebuffer colorTarget = Framebuffer.fromDescriptor(
                         FramebufferDescriptor.colorOnly(8, 8, GL_RGBA8));
                 Framebuffer depthTarget = Framebuffer.fromDescriptor(
                         FramebufferDescriptor.builder(8, 8).depthStencilTexture().build())) {
                glBindFramebuffer(GL_FRAMEBUFFER, source.id());
                glClearBufferfv(GL_COLOR, 0, new float[]{1.0f, 0.0f, 0.0f, 1.0f});
                glClearBufferfv(GL_COLOR, 1, new float[]{0.0f, 1.0f, 0.0f, 1.0f});
                glClearDepth(0.25);
                glClear(GL_DEPTH_BUFFER_BIT);
                device.invalidateState();

                device.execute(device.createCommandBuffer()
                        .blitFramebuffer(source.id(), colorTarget.id(), 8, 8, 8, 8,
                                GL_COLOR_BUFFER_BIT, GL_NEAREST, 1)
                        .blitDepth(source, depthTarget));

                ByteBuffer color = BufferUtils.createByteBuffer(8 * 8 * 4);
                glGetTextureImage(colorTarget.colorAttachment(), 0, GL_RGBA, GL_UNSIGNED_BYTE, color);
                assertTrue(Byte.toUnsignedInt(color.get(1)) > 240);
                glBindFramebuffer(GL_FRAMEBUFFER, depthTarget.id());
                FloatBuffer depth = BufferUtils.createFloatBuffer(1);
                glReadPixels(4, 4, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
                assertEquals(0.25f, depth.get(0), 0.002f);
                GlDebug.assertNoError("typed MRT and depth-stencil resolve");
            }
        }
    }
}
