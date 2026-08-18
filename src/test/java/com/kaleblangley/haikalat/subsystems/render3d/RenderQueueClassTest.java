package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.material.Material;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderQueueClassTest {
    @Test
    void classifiesOpaqueMaskedAlphaAndAdditiveWithoutNewPublicAlphaModel() throws Exception {
        Constructor<ShaderProgram> constructor = ShaderProgram.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        ShaderProgram shader = constructor.newInstance(41);
        Material opaque = Material.builder(shader).build();
        Material masked = Material.builder(shader).setFloat("uAlphaCutoff", 0.5f).build();
        Material alpha = Material.builder(shader).blendMode(BlendMode.ALPHA).build();
        Material additive = Material.builder(shader).blendMode(BlendMode.ADDITIVE).build();

        assertEquals(RenderQueueClass.OPAQUE, RenderQueueClass.classify(opaque.createInstance()));
        assertEquals(RenderQueueClass.MASKED, RenderQueueClass.classify(masked.createInstance()));
        assertEquals(RenderQueueClass.TRANSPARENT_ALPHA,
                RenderQueueClass.classify(alpha.createInstance()));
        assertEquals(RenderQueueClass.TRANSPARENT_ADDITIVE,
                RenderQueueClass.classify(additive.createInstance()));

        var override = opaque.createInstance().setFloat("uAlphaCutoff", 0.25f);
        assertEquals(RenderQueueClass.MASKED, RenderQueueClass.classify(override));
        override.setFloat("uAlphaCutoff", 0.0f);
        assertEquals(RenderQueueClass.OPAQUE, RenderQueueClass.classify(override));
    }
}
