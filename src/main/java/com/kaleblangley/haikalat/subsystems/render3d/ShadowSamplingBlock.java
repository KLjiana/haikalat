package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

/** std140 local-shadow mapping block, packed once from the immutable frame plan. */
final class ShadowSamplingBlock implements AutoCloseable {
    static final String BLOCK_NAME = "ShadowSamplingBlock";
    static final int BLOCK_BINDING = 5;
    static final int POINT_META_OFFSET = 0;
    static final int POINT_MATRIX_OFFSET = POINT_META_OFFSET
            + LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS * 16;
    static final int POINT_RECT_OFFSET = POINT_MATRIX_OFFSET
            + LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS
            * PointShadowAtlas.FACE_COUNT * 64;
    static final int SPOT_META_OFFSET = POINT_RECT_OFFSET
            + LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS
            * PointShadowAtlas.FACE_COUNT * 16;
    static final int SPOT_MATRIX_OFFSET = SPOT_META_OFFSET
            + LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS * 16;
    static final int SPOT_RECT_OFFSET = SPOT_MATRIX_OFFSET
            + LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS * 64;
    static final int QUALITY_OFFSET = SPOT_RECT_OFFSET
            + LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS * 16;
    static final int BLOCK_SIZE_BYTES = QUALITY_OFFSET + 16;

    private final UniformBlock block = new UniformBlock(BLOCK_SIZE_BYTES);
    private final Matrix4f identity = new Matrix4f();
    private long lastSignature = Long.MIN_VALUE;

    void update(ShadowFramePlan plan, LocalShadowPipelineSettings settings) {
        long signature = signature(plan, settings);
        if (signature == lastSignature) return;
        for (int slot = 0; slot < LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS; slot++) {
            setMeta(POINT_META_OFFSET + slot * 16, -1, 0, slot, 0);
            for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                int matrixIndex = slot * PointShadowAtlas.FACE_COUNT + face;
                block.setMat4(POINT_MATRIX_OFFSET + matrixIndex * 64, identity);
                block.setVec4(POINT_RECT_OFFSET + matrixIndex * 16, 0, 0, 0, 0);
            }
        }
        for (PointShadowSlotPlan point : plan.points()) {
            setMeta(POINT_META_OFFSET + point.slot() * 16,
                    point.shaderIndex(), 1, point.slot(), 0);
            for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                int matrixIndex = point.slot() * PointShadowAtlas.FACE_COUNT + face;
                block.setMat4(POINT_MATRIX_OFFSET + matrixIndex * 64,
                        point.faceMatrices().get(face));
                ShadowTileRect rect = point.faceTiles().get(face);
                block.setVec4(POINT_RECT_OFFSET + matrixIndex * 16,
                        rect.minU(), rect.minV(), rect.maxU(), rect.maxV());
            }
        }
        for (int slot = 0; slot < LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS; slot++) {
            setMeta(SPOT_META_OFFSET + slot * 16, -1, 0, slot, 0);
            block.setMat4(SPOT_MATRIX_OFFSET + slot * 64, identity);
            block.setVec4(SPOT_RECT_OFFSET + slot * 16, 0, 0, 0, 0);
        }
        for (SpotShadowSlotPlan spot : plan.spots()) {
            setMeta(SPOT_META_OFFSET + spot.slot() * 16,
                    spot.shaderIndex(), 1, spot.slot(), 0);
            block.setMat4(SPOT_MATRIX_OFFSET + spot.slot() * 64,
                    spot.lightSpaceMatrix());
            ShadowTileRect rect = spot.tile();
            block.setVec4(SPOT_RECT_OFFSET + spot.slot() * 16,
                    rect.minU(), rect.minV(), rect.maxU(), rect.maxV());
        }
        block.setInt(QUALITY_OFFSET, settings.filterMode().kernelRadius())
                .setFloat(QUALITY_OFFSET + 4, settings.normalBias())
                .setFloat(QUALITY_OFFSET + 8, settings.point().bias())
                .setFloat(QUALITY_OFFSET + 12, settings.spot().bias());
        lastSignature = signature;
    }

    void bind(CommandBuffer commands) {
        commands.bindUniformBlock(BLOCK_BINDING, block);
    }

    private void setMeta(int offset, int shaderIndex, int valid, int slot, int reserved) {
        block.setInt(offset, shaderIndex)
                .setInt(offset + 4, valid)
                .setInt(offset + 8, slot)
                .setInt(offset + 12, reserved);
    }

    private static long signature(ShadowFramePlan plan,
                                  LocalShadowPipelineSettings settings) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, settings.point().hashCode());
        hash = mix(hash, settings.spot().hashCode());
        hash = mix(hash, settings.filterMode().ordinal());
        hash = mix(hash, Float.floatToIntBits(settings.normalBias()));
        hash = mix(hash, plan.points().size());
        for (PointShadowSlotPlan point : plan.points()) {
            hash = mix(hash, point.entry().stableId());
            hash = mix(hash, point.entry().revision());
            hash = mix(hash, point.shaderIndex());
            hash = mix(hash, point.slot());
        }
        hash = mix(hash, plan.spots().size());
        for (SpotShadowSlotPlan spot : plan.spots()) {
            hash = mix(hash, spot.entry().stableId());
            hash = mix(hash, spot.entry().revision());
            hash = mix(hash, spot.shaderIndex());
            hash = mix(hash, spot.slot());
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    @Override
    public void close() {
        block.close();
    }
}
