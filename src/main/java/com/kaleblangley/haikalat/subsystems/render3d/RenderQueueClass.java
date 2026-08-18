package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.UniformKey;
import com.kaleblangley.haikalat.core.material.UniformValue;

/** Internal forward-queue classification; public materials keep their existing blend model. */
enum RenderQueueClass {
    OPAQUE,
    MASKED,
    TRANSPARENT_ALPHA,
    TRANSPARENT_ADDITIVE;

    private static final UniformKey<UniformValue.FloatVal> ALPHA_CUTOFF =
            UniformKey.float1("uAlphaCutoff");
    private static final UniformKey<UniformValue.IntVal> ALPHA_MODE =
            UniformKey.int1("uAlphaMode");

    static RenderQueueClass classify(MaterialInstance instance) {
        BlendMode blendMode = instance.material().blendMode();
        if (blendMode == BlendMode.ALPHA) return TRANSPARENT_ALPHA;
        if (blendMode == BlendMode.ADDITIVE) return TRANSPARENT_ADDITIVE;
        UniformValue alphaMode = instance.uniformOverrides().get(ALPHA_MODE);
        if (alphaMode == null) alphaMode = instance.material().defaultUniforms().get(ALPHA_MODE);
        if (alphaMode instanceof UniformValue.IntVal mode && mode.value() == 1) return MASKED;
        UniformValue value = instance.uniformOverrides().get(ALPHA_CUTOFF);
        if (value == null) value = instance.material().defaultUniforms().get(ALPHA_CUTOFF);
        return value instanceof UniformValue.FloatVal cutoff && cutoff.value() > 0.0f
                ? MASKED : OPAQUE;
    }

    boolean castsOpaqueShadow() {
        return this == OPAQUE || this == MASKED;
    }
}
