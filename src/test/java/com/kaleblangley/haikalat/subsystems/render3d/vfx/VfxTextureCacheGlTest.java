package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaskMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30.GL_R8;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VfxTextureCacheGlTest {
    @Test
    void importsMasksAsR8ColorsAsSrgbAndSeparatesProfiles() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(32, 32)
                .title("VFX Texture Cache GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            VfxMaterial luminance = material("luminance", VfxMaskMode.LUMINANCE,
                    "/vfx/particles/kenney/particle_pack/fire_01.png");
            VfxMaterial alpha = material("alpha", VfxMaskMode.ALPHA,
                    "/vfx/masks/kenney/light_masks/transparent/ring_a_streaks.png");
            VfxMaterial color = material("color", VfxMaskMode.RGBA_COLOR,
                    "/vfx/masks/kenney/light_masks/transparent/ring_a_streaks.png");
            try (VfxTextureCache cache = new VfxTextureCache()) {
                Texture2D luminanceTexture = cache.get(luminance);
                Texture2D alphaTexture = cache.get(alpha);
                Texture2D colorTexture = cache.get(color);
                assertEquals(GL_R8, luminanceTexture.format());
                assertEquals(GL_R8, alphaTexture.format());
                assertEquals(GL_SRGB8_ALPHA8, colorTexture.format());
                assertEquals(3, cache.size());
                assertEquals(luminanceTexture, cache.get(luminance));
            }
        }
    }

    private static VfxMaterial material(String name, VfxMaskMode mode, String path) {
        return VfxMaterial.builder(name).texture(AssetRef.of(path)).maskMode(mode).build();
    }
}
