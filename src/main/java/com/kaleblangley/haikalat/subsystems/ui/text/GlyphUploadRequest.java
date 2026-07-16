package com.kaleblangley.haikalat.subsystems.ui.text;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * update 线程生成、render 线程消费的稳定 R8 atlas region upload。
 *
 * <p>{@code x/y/width/height} 表示包含零值 padding 的完整上传区域；
 * {@link #glyphPlacement()} 表示其中真正采样的 glyph coverage。</p>
 */
public final class GlyphUploadRequest {
    private final long requestId;
    private final GlyphKey key;
    private final long generation;
    private final GlyphAtlasPlacement glyphPlacement;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int bearingX;
    private final int bearingY;
    private final float advanceX;
    private final float advanceY;
    private final ByteBuffer payload;

    GlyphUploadRequest(long requestId, GlyphBitmap bitmap, long generation,
                       GlyphAtlasPlacement glyphPlacement, ByteBuffer payload) {
        if (requestId <= 0L) {
            throw new IllegalArgumentException("Upload request id must be positive");
        }
        this.requestId = requestId;
        GlyphBitmap sourceBitmap = Objects.requireNonNull(bitmap, "bitmap");
        this.key = sourceBitmap.key();
        if (generation <= 0L) {
            throw new IllegalArgumentException("Atlas generation must be positive");
        }
        this.generation = generation;
        this.glyphPlacement = Objects.requireNonNull(glyphPlacement, "glyphPlacement");
        this.x = glyphPlacement.allocatedX();
        this.y = glyphPlacement.allocatedY();
        this.width = glyphPlacement.allocatedWidth();
        this.height = glyphPlacement.allocatedHeight();
        this.bearingX = sourceBitmap.bearingX();
        this.bearingY = sourceBitmap.bearingY();
        this.advanceX = sourceBitmap.advanceX();
        this.advanceY = sourceBitmap.advanceY();
        ByteBuffer input = Objects.requireNonNull(payload, "payload").duplicate();
        int requiredBytes = Math.multiplyExact(width, height);
        if (input.remaining() != requiredBytes) {
            throw new IllegalArgumentException("Upload payload contains " + input.remaining()
                    + " bytes but region requires " + requiredBytes);
        }
        ByteBuffer stable = ByteBuffer.allocateDirect(requiredBytes);
        stable.put(input).flip();
        this.payload = stable.asReadOnlyBuffer();
    }

    public long requestId() {
        return requestId;
    }

    public GlyphKey key() {
        return key;
    }

    public int pageIndex() {
        return glyphPlacement.pageIndex();
    }

    /** {@link #pageIndex()} 的简洁别名。 */
    public int page() {
        return pageIndex();
    }

    public long generation() {
        return generation;
    }

    public GlyphAtlasPlacement glyphPlacement() {
        return glyphPlacement;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int bearingX() {
        return bearingX;
    }

    public int bearingY() {
        return bearingY;
    }

    public float advanceX() {
        return advanceX;
    }

    public float advanceY() {
        return advanceY;
    }

    /** 返回 position 为 0 的独立只读 direct view。 */
    public ByteBuffer payload() {
        return payload.asReadOnlyBuffer();
    }

    public int uploadByteCount() {
        return payload.remaining();
    }

    @Override
    public String toString() {
        return "GlyphUploadRequest[id=" + requestId + ", key=" + key + ", page=" + pageIndex()
                + ", generation=" + generation + ", region=" + x + ',' + y + ' ' + width + 'x' + height + ']';
    }
}
