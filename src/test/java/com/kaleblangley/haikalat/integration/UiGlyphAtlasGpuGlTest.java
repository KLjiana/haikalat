package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.ui.render.UiGlyphAtlasGpu;
import com.kaleblangley.haikalat.subsystems.ui.render.UiGlyphUploadResult;
import com.kaleblangley.haikalat.subsystems.text.FontFaceId;
import com.kaleblangley.haikalat.subsystems.text.GlyphAtlas;
import com.kaleblangley.haikalat.subsystems.text.GlyphBitmap;
import com.kaleblangley.haikalat.subsystems.text.GlyphKey;
import com.kaleblangley.haikalat.subsystems.text.GlyphUploadRequest;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_INTERNAL_FORMAT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL45.glGetTextureImage;
import static org.lwjgl.opengl.GL45.glGetTextureLevelParameteri;

/** 验证 glyph atlas 的 R8 typed upload、GPU fence 发布和失败重试闭环。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class UiGlyphAtlasGpuGlTest {
    @Test
    void glyphRegionBecomesVisibleOnlyAfterFenceAndOtherPixelsRemainZero() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1);
                 UiGlyphAtlasGpu gpuAtlas = new UiGlyphAtlasGpu(8, 8, 1)) {
                GlyphUploadRequest request = request(atlas, 1,
                        new byte[]{17, 34, 51, 68});
                CommandBuffer commands = device.createCommandBuffer();

                UiGlyphAtlasGpu.UploadSubmission submission =
                        gpuAtlas.recordUploads(List.of(request), commands);

                assertEquals(UiGlyphAtlasGpu.SubmissionStatus.RECORDED, submission.status());
                assertTrue(gpuAtlas.drainCompletedResults().isEmpty());
                assertEquals(3, commands.commandCount(),
                        "new page must record typed zero-fill, glyph upload and typed fence");

                device.execute(commands);
                assertEquals(UiGlyphAtlasGpu.SubmissionStatus.GPU_PENDING, submission.status());
                glFinish();
                assertEquals(1, gpuAtlas.pollGpuCompletions());

                UiGlyphUploadResult result = gpuAtlas.pollCompletedResult().orElseThrow();
                assertTrue(result.succeeded());
                assertEquals(List.of(request), result.requests());
                int texture = result.pageTextureId(request.pageIndex());
                assertEquals(GL_R8,
                        glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_INTERNAL_FORMAT));

                ByteBuffer pixels = readR8(texture, 8, 8);
                assertAll(
                        () -> assertEquals(0, pixel(pixels, 8, 0, 0)),
                        () -> assertEquals(17, pixel(pixels, 8, 1, 1)),
                        () -> assertEquals(34, pixel(pixels, 8, 2, 1)),
                        () -> assertEquals(51, pixel(pixels, 8, 1, 2)),
                        () -> assertEquals(68, pixel(pixels, 8, 2, 2)),
                        () -> assertEquals(0, pixel(pixels, 8, 3, 3)),
                        () -> assertEquals(0, pixel(pixels, 8, 7, 7)));

                atlas.publishUpload(request);
                assertTrue(atlas.lookup(request.key(), ignored -> null).ready());
                assertTrue(gpuAtlas.pollCompletedResult().isEmpty());
                GlDebug.assertNoError("UI glyph atlas typed R8 upload");
            }
        }
    }

    @Test
    void abandonedBatchPublishesOnlyFailureAndCanRetryWholeGeneration() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1);
                 UiGlyphAtlasGpu gpuAtlas = new UiGlyphAtlasGpu(8, 8, 1)) {
                GlyphUploadRequest first = request(atlas, 1,
                        new byte[]{11, 12, 13, 14});
                GlyphUploadRequest second = request(atlas, 2,
                        new byte[]{21, 22, 23, 24});
                List<GlyphUploadRequest> batch = List.of(first, second);

                CommandBuffer abandoned = device.createCommandBuffer();
                UiGlyphAtlasGpu.UploadSubmission failed =
                        gpuAtlas.recordUploads(batch, abandoned);
                IllegalStateException cause = new IllegalStateException("synthetic executor failure");

                assertTrue(failed.executionFailed(cause));
                UiGlyphUploadResult failedResult = gpuAtlas.pollCompletedResult().orElseThrow();
                assertFalse(failedResult.succeeded());
                assertEquals(batch, failedResult.requests());
                assertSame(cause, failedResult.failure().orElseThrow().getCause());
                assertTrue(failedResult.pageTextureIds().isEmpty());
                assertEquals(0, atlas.statistics().readyGlyphs());
                atlas.uploadFailed(first);
                atlas.uploadFailed(second);

                CommandBuffer retry = device.createCommandBuffer();
                UiGlyphAtlasGpu.UploadSubmission retried = gpuAtlas.recordUploads(batch, retry);
                assertEquals(4, retry.commandCount(),
                        "uninitialized page retry must repeat zero-fill and both glyph uploads");
                device.execute(retry);
                glFinish();
                gpuAtlas.pollGpuCompletions();

                UiGlyphUploadResult success = gpuAtlas.pollCompletedResult().orElseThrow();
                assertTrue(success.succeeded());
                assertEquals(UiGlyphAtlasGpu.SubmissionStatus.SUCCEEDED, retried.status());
                atlas.publishUpload(first);
                atlas.publishUpload(second);
                assertEquals(2, atlas.statistics().readyGlyphs());
                GlDebug.assertNoError("UI glyph atlas failed-batch retry");
            }
        }
    }

    private static GlyphUploadRequest request(GlyphAtlas atlas, int glyphId, byte[] coverage) {
        GlyphKey key = new GlyphKey(new FontFaceId(1), glyphId, 16);
        return atlas.lookup(key, ignored -> new GlyphBitmap(
                key, 2, 2, 0, 2, 2.0f, 0.0f, coverage))
                .uploadRequest().orElseThrow();
    }

    private static ByteBuffer readR8(int texture, int width, int height) {
        int previousPack = glGetInteger(GL_PACK_ALIGNMENT);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glGetTextureImage(texture, 0, GL_RED, GL_UNSIGNED_BYTE, pixels);
            return pixels;
        } finally {
            glPixelStorei(GL_PACK_ALIGNMENT, previousPack);
        }
    }

    private static int pixel(ByteBuffer pixels, int width, int x, int y) {
        return Byte.toUnsignedInt(pixels.get(y * width + x));
    }
}
