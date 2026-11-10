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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void preparationIsWarmAfterFirstPassAndFailureLeavesOldEntriesUsable() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(32, 32)
                .title("VFX Texture Prepare GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            VfxMaterial valid = material("valid", VfxMaskMode.LUMINANCE,
                    "/vfx/particles/kenney/particle_pack/fire_01.png");
            VfxMaterial missing = material("missing", VfxMaskMode.LUMINANCE,
                    "/vfx/does-not-exist.png");
            try (VfxTextureCache cache = new VfxTextureCache()) {
                VfxTextureCache.Preparation first = cache.prepare(java.util.List.of(valid));
                assertEquals(1, first.loaded());
                assertEquals(0, first.evicted());
                VfxTextureCache.Preparation warm = cache.prepare(java.util.List.of(valid));
                assertEquals(0, warm.loaded());
                assertEquals(1, cache.size());

                assertThrows(RuntimeException.class,
                        () -> cache.prepare(java.util.List.of(valid, missing)));
                assertEquals(1, cache.size(),
                        "a failed candidate must not publish or evict textures");
                assertEquals(cache.get(valid), cache.get(valid));
                assertEquals(32, cache.maximumEntries());
            }
        }
    }

    @Test
    void cacheEvictsLeastRecentlyUsedEntriesBeyondItsBound() throws Exception {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(32, 32)
                .title("VFX Texture Cache Bound GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            java.util.List<java.nio.file.Path> files;
            try (var stream = java.nio.file.Files.list(java.nio.file.Path.of(
                    "src", "main", "resources", "vfx", "particles", "kenney", "particle_pack"))) {
                files = stream.filter(path -> path.toString().endsWith(".png"))
                        .sorted().limit(40).toList();
            }
            assertEquals(40, files.size(), "fixture must provide more textures than the cache bound");
            try (VfxTextureCache cache = new VfxTextureCache()) {
                int evicted = 0;
                for (java.nio.file.Path file : files) {
                    VfxMaterial material = material("bound-" + file.getFileName(),
                            VfxMaskMode.LUMINANCE,
                            "/vfx/particles/kenney/particle_pack/" + file.getFileName());
                    evicted += cache.prepare(java.util.List.of(material)).evicted();
                }
                assertEquals(cache.maximumEntries(), cache.size());
                assertTrue(evicted > 0, "repeated resource switching must evict old entries");
            }
        }
    }

    private static VfxMaterial material(String name, VfxMaskMode mode, String path) {
        return VfxMaterial.builder(name).texture(AssetRef.of(path)).maskMode(mode).build();
    }
}
