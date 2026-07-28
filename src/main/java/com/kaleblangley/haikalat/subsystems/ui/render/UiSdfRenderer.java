package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Vector4f;

/**
 * Package-private SDF uniform encoder.
 *
 * <p>The scratch vectors are render-thread owned and reused across batches. CommandBuffer
 * copies scalar components while recording, so no per-batch vector allocation is needed.</p>
 */
final class UiSdfRenderer {
    private final Vector4f radii = new Vector4f();
    private final Vector4f params = new Vector4f();
    private final Vector4f fillColor = new Vector4f();
    private final Vector4f borderColor = new Vector4f();
    private final Vector4f gradientStart = new Vector4f();
    private final Vector4f gradientEnd = new Vector4f();

    void recordUniforms(ShaderProgram shader, CommandBuffer commands,
                        UiDisplayList list, UiBatcher.Result batches, int batch) {
        int primitive = batches.firstPrimitive(batch);
        int quad = list.primitiveFirstQuad(primitive);
        radii.set(list.sdfRadius(primitive, 0), list.sdfRadius(primitive, 1),
                list.sdfRadius(primitive, 2), list.sdfRadius(primitive, 3));
        params.set(list.sdfParameter(primitive, 0), list.sdfParameter(primitive, 1),
                list.sdfParameter(primitive, 2), list.sdfParameter(primitive, 3));
        unpackColor(list.sdfFillColor(primitive), fillColor);
        unpackColor(list.sdfBorderColor(primitive), borderColor);
        unpackColor(list.sdfGradientStartColor(primitive), gradientStart);
        unpackColor(list.sdfGradientEndColor(primitive), gradientEnd);
        commands.setUniformInt(shader, "uSdfShape", list.sdfKind(primitive).ordinal())
                .setUniformVec2(shader, "uSdfSize",
                        (float) list.quadWidth(quad), (float) list.quadHeight(quad))
                .setUniformVec4(shader, "uSdfRadii", radii)
                .setUniformVec4(shader, "uSdfParams", params)
                .setUniformFloat(shader, "uSdfGradientAngle",
                        list.sdfGradientAngle(primitive))
                .setUniformVec4(shader, "uSdfFillColor", fillColor)
                .setUniformVec4(shader, "uSdfBorderColor", borderColor)
                .setUniformVec4(shader, "uSdfGradientStart", gradientStart)
                .setUniformVec4(shader, "uSdfGradientEnd", gradientEnd);
    }

    private static void unpackColor(int rgba, Vector4f target) {
        target.set(
                ((rgba >>> 24) & 0xff) / 255.0f,
                ((rgba >>> 16) & 0xff) / 255.0f,
                ((rgba >>> 8) & 0xff) / 255.0f,
                (rgba & 0xff) / 255.0f);
    }
}
