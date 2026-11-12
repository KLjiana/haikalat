package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.FlipbookConfig;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaskMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.subsystems.vfx.VfxVisualSet;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL46.*;

/**
 * Freezes the production VFX sprite sampler/minification contract with real-GL
 * evidence.
 *
 * <p>The fixtures live under {@code src/test/resources/vfx/test}: a 2x1
 * black/white pair for the bilinear midpoint and clamp-to-edge wrap, a 8x8
 * single-white-row pair for the mip-chain average, a uniform mid-gray sRGB
 * texel for the transfer-function decode, and a 12x4 two-cell red/green atlas
 * whose 3x1 mip straddles the cell boundary. {@link VfxSamplerReferenceTest}
 * independently derives the numeric references from the same files.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VfxSamplerContractGlTest {
    private static final int FRAME_SIZE = 32;
    private static final int PROBE_SIZE = 8;
    private static final int PROBE_CENTER = PROBE_SIZE / 2;
    private static final float PROBE_PIXEL_CENTER = PROBE_SIZE * 0.5f;

    private static final String PROBE_VERTEX = """
            #version 460 core
            layout (location = 0) in vec3 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 1.0);
            }
            """;

    /**
     * Textures are sampled with implicit derivatives. {@code uStepPerPixel}
     * controls the texel footprint: zero keeps the base level, one UV unit per
     * pixel forces LOD log2(textureSize) so the 1x1 mip average is read.
     */
    private static final String PROBE_FRAGMENT = """
            #version 460 core
            uniform sampler2D uTexture;
            uniform vec2 uSampleUv;
            uniform vec2 uStepPerPixel;
            uniform vec2 uPixelCenter;
            layout (location = 0) out vec4 fragColor;
            void main() {
                vec2 uv = uSampleUv + (gl_FragCoord.xy - uPixelCenter) * uStepPerPixel;
                fragColor = texture(uTexture, uv);
            }
            """;

    @Test
    void productionTexturesPinFilteringWrapMipChainAndSrgbDecode() {
        try (GlfwWindow window = hiddenWindow()) {
            initialize(window);
            GlDebug.resourceTracking(true);
            try {
                try (VfxTextureCache cache = new VfxTextureCache();
                     Probe probe = new Probe()) {
                    Texture2D color = cache.get(material("sampler-color-8",
                            VfxMaskMode.RGBA_COLOR, "/vfx/test/sampler-color-8x8.png"));
                    Texture2D mask = cache.get(material("sampler-mask-8",
                            VfxMaskMode.ALPHA, "/vfx/test/sampler-mask-8x8.png"));
                    Texture2D gray = cache.get(material("sampler-color-gray",
                            VfxMaskMode.RGBA_COLOR, "/vfx/test/sampler-color-1x1.png"));

                    assertEquals(org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8, color.format(),
                            "VFX colour sprites must be imported as sRGB8 alpha8");
                    assertEquals(TextureColorSpace.SRGB, color.colorSpace());
                    assertEquals(org.lwjgl.opengl.GL30.GL_R8, mask.format(),
                            "VFX masks must stay linear R8");
                    assertEquals(TextureColorSpace.LINEAR, mask.colorSpace());
                    assertFrozenSamplerState(color, 4);
                    assertFrozenSamplerState(mask, 4);

                    // sRGB 128/255 >= 0.04045 is decoded to 0.2158605 before any
                    // filtering. A linear upload or an encoded-space sample
                    // would be ~0.502.
                    assertEquals(0.2158605f, probe.sample(gray, 0.5f, 0.5f, 0.0f, 0.0f)[0],
                            0.002f, "sRGB decode of encoded 0.5 must be linear 0.21586");
                    // Implicit derivatives at one UV unit per pixel force LOD 3
                    // on the 8x8 texture, i.e. the 1x1 mip. GL generates sRGB
                    // mips in linear light: 8/64 -> sRGB 99/255 -> linear
                    // 0.124772. A missing or base-level-only mip chain returns 0.
                    assertEquals(0.124772f, probe.sample(color, 0.5f, 0.5f, 1.0f, 1.0f)[0],
                            0.02f, "sRGB mip must be generated and filtered in linear space");
                    assertEquals(32.0f / 255.0f, probe.sample(mask, 0.5f, 0.5f, 1.0f, 1.0f)[0],
                            0.01f, "linear R8 mip must contain the 8/64 average");
                }
                assertNoGlError("VFX sampler state");
            } finally {
                assertTrackedResourcesReleased();
                GlDebug.resourceTracking(false);
            }
        }
    }

    @Test
    void magnifiedMidpointUsesBilinearFilteringAndClampToEdgeWrap() {
        try (GlfwWindow window = hiddenWindow()) {
            initialize(window);
            GlDebug.resourceTracking(true);
            try {
                try (VfxTextureCache cache = new VfxTextureCache();
                     Probe probe = new Probe()) {
                    Texture2D color = cache.get(material("sampler-color-2",
                            VfxMaskMode.RGBA_COLOR, "/vfx/test/sampler-color-2x1.png"));
                    Texture2D mask = cache.get(material("sampler-mask-2",
                            VfxMaskMode.ALPHA, "/vfx/test/sampler-mask-2x1.png"));

                    // Magnified LOD 0 at exactly half a texel. The sRGB path
                    // decodes black/white first and then filters, so 0.5 is the
                    // linear midpoint. NEAREST would return 1.0; filtering in
                    // encoded space would return 0.216.
                    assertEquals(0.5f, probe.sample(color, 0.5f, 0.5f, 0.0f, 0.0f)[0],
                            0.01f, "sRGB bilinear midpoint");
                    assertEquals(0.5f, probe.sample(mask, 0.5f, 0.5f, 0.0f, 0.0f)[0],
                            0.01f, "linear R8 bilinear midpoint");

                    // U=2.0 is outside the image. CLAMP_TO_EDGE repeats the
                    // white rightmost texel; REPEAT would wrap to the black
                    // left texel.
                    assertEquals(1.0f, probe.sample(color, 2.0f, 0.5f, 0.0f, 0.0f)[0],
                            0.01f, "sRGB clamp-to-edge must use the rightmost texel");
                    assertEquals(1.0f, probe.sample(mask, 2.0f, 0.5f, 0.0f, 0.0f)[0],
                            0.01f, "R8 clamp-to-edge must use the rightmost texel");
                }
                assertNoGlError("VFX bilinear and clamp contract");
            } finally {
                assertTrackedResourcesReleased();
                GlDebug.resourceTracking(false);
            }
        }
    }

    @Test
    void minifiedSampleUsesTheMipmapLinearAverageInsteadOfANearestTexel() {
        try (GlfwWindow window = hiddenWindow()) {
            initialize(window);
            GlDebug.resourceTracking(true);
            try {
                try (VfxTextureCache cache = new VfxTextureCache();
                     Probe probe = new Probe()) {
                    Texture2D mask = cache.get(material("sampler-mask-8",
                            VfxMaskMode.ALPHA, "/vfx/test/sampler-mask-8x8.png"));

                    // One white row out of eight: LOD 0 at the centre is black,
                    // while the 1x1 mip is 32/255. A NEAREST sampler (or a
                    // silently dropped mip chain) stays at 0.
                    float base = probe.sample(mask, 0.5f, 0.5f, 0.0f, 0.0f)[0];
                    assertEquals(0.0f, base, 1.0e-4f);
                    float minified = probe.sample(mask, 0.5f, 0.5f, 1.0f, 1.0f)[0];
                    assertEquals(32.0f / 255.0f, minified, 0.01f,
                            "minified UV footprint must select the mipmap-average level");
                    assertTrue(minified - base > 0.1f,
                            "mip filtering must be distinguishable from base/nearest sampling: base="
                                    + base + " minified=" + minified);
                }
                assertNoGlError("VFX minification contract");
            } finally {
                assertTrackedResourcesReleased();
                GlDebug.resourceTracking(false);
            }
        }
    }

    @Test
    void minifiedProductionSpriteUsesMipFilteringInsteadOfTheBaseLevel() {
        try (GlfwWindow window = hiddenWindow()) {
            initialize(window);
            GlDebug.resourceTracking(true);
            try {
                GlRenderDevice device = new GlRenderDevice();
                VfxMaterial material = material("sampler-color-8",
                        VfxMaskMode.RGBA_COLOR, "/vfx/test/sampler-color-8x8.png");
                try (VfxRenderer renderer = new VfxRenderer()) {
                    ByteBuffer magnified = render(renderer, device, sprite(material, 32.0f));
                    assertEquals(0, centerChannel(magnified, 0), 2,
                            "the magnified centre must read the black base level");
                    ByteBuffer minified = render(renderer, device, sprite(material, 0.6f));
                    int center = centerChannel(minified, 0);
                    assertTrue(center > 20 && center < 160,
                            "the minified sprite must read the mip average, not a black base texel "
                                    + "or a white edge texel: center=" + center);
                }
                assertNoGlError("minified production VFX sprite");
            } finally {
                assertTrackedResourcesReleased();
                GlDebug.resourceTracking(false);
            }
        }
    }

    @Test
    void flipbookStaysInsideItsCellWhileTheUnderlyingMipCrossesTheCellBoundary() {
        try (GlfwWindow window = hiddenWindow()) {
            initialize(window);
            GlDebug.resourceTracking(true);
            try {
                GlRenderDevice device = new GlRenderDevice();
                VfxMaterial flipbook = VfxMaterial.builder("sampler-atlas-flipbook")
                        .texture(AssetRef.of("/vfx/test/sampler-atlas-12x4.png"))
                        .maskMode(VfxMaskMode.RGBA_COLOR)
                        .blendMode(BlendMode.ALPHA)
                        .flipbook(new FlipbookConfig(2, 1, 2, 1.0f, false, false))
                        .build();
                try (VfxRenderer renderer = new VfxRenderer()) {
                    // The half-texel inset keeps the magnified edge on the last
                    // red texel; without it bilinear would mix the green cell.
                    ByteBuffer magnified = render(renderer, device, sprite(flipbook, 32.0f));
                    assertEquals(0, maximumChannel(magnified, 1),
                            "magnified cell 0 must not sample the adjacent green cell");
                    assertTrue(centerChannel(magnified, 0) > 100);

                    // The 12x4 atlas has a 3x1 mip whose middle texel averages
                    // both cells. Flipbook sampling is frozen to LOD 0, so the
                    // minified sprite must remain pure red.
                    ByteBuffer minified = render(renderer, device, sprite(flipbook, 0.6f));
                    assertEquals(0, maximumChannel(minified, 1),
                            "flipbook LOD 0 must not let a coarse mip average in the green cell");
                    assertTrue(maximumChannel(minified, 0) > 100,
                            "the minified flipbook sprite must still draw its red cell");
                    assertTrue(nonBlackPixels(minified) > 0);
                }
                // Same production texture object, but sampled at the exact
                // half-texel inset edge. LOD 0 is pure red; forcing the 3x1
                // level (four texels per pixel) returns the mixed texel. This
                // is the evidence for why the flipbook branch must use LOD 0.
                try (VfxTextureCache cache = new VfxTextureCache();
                     Probe probe = new Probe()) {
                    Texture2D atlas = cache.get(material("sampler-atlas-probe",
                            VfxMaskMode.RGBA_COLOR, "/vfx/test/sampler-atlas-12x4.png"));
                    float edgeU = 0.5f - 0.5f / 12.0f;
                    assertEquals(0.0f, probe.sample(atlas, edgeU, 0.5f, 0.0f, 0.0f)[1],
                            1.0e-4f, "LOD 0 at the inset edge must be pure red");
                    float mipGreen = probe.sample(atlas, edgeU, 0.5f,
                            4.0f / 12.0f, 4.0f / 12.0f)[1];
                    assertTrue(mipGreen > 0.2f,
                            "the 3x1 mip must average the green cell into the edge texel: "
                                    + mipGreen);
                }
                assertNoGlError("flipbook atlas boundary contract");
            } finally {
                assertTrackedResourcesReleased();
                GlDebug.resourceTracking(false);
            }
        }
    }

    private static void initialize(GlfwWindow window) {
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(FRAME_SIZE, FRAME_SIZE)
                .title("VFX sampler contract GL test").visible(false).build();
    }

    private static VfxMaterial material(String name, VfxMaskMode mode, String path) {
        return VfxMaterial.builder(name)
                .texture(AssetRef.of(path))
                .maskMode(mode)
                .blendMode(BlendMode.ALPHA)
                .build();
    }

    private static void assertFrozenSamplerState(Texture2D texture, int expectedLevels) {
        assertEquals(GL_LINEAR_MIPMAP_LINEAR,
                glGetTextureParameteri(texture.id(), GL_TEXTURE_MIN_FILTER),
                "VFX min filter must stay GL_LINEAR_MIPMAP_LINEAR");
        assertEquals(GL_LINEAR, glGetTextureParameteri(texture.id(), GL_TEXTURE_MAG_FILTER),
                "VFX mag filter must stay GL_LINEAR");
        assertEquals(GL_CLAMP_TO_EDGE,
                glGetTextureParameteri(texture.id(), GL_TEXTURE_WRAP_S),
                "VFX wrap S must stay GL_CLAMP_TO_EDGE");
        assertEquals(GL_CLAMP_TO_EDGE,
                glGetTextureParameteri(texture.id(), GL_TEXTURE_WRAP_T),
                "VFX wrap T must stay GL_CLAMP_TO_EDGE");
        assertEquals(0, glGetTextureParameteri(texture.id(), GL_TEXTURE_BASE_LEVEL),
                "sampling must start at the base level");
        assertEquals(expectedLevels,
                glGetTextureParameteri(texture.id(), GL_TEXTURE_IMMUTABLE_LEVELS),
                "the full immutable mip chain is part of the contract");
        assertEquals(1.0f, glGetTextureParameterf(texture.id(), GL_TEXTURE_MAX_ANISOTROPY),
                "VFX sprites must not use anisotropic filtering");
    }

    private static ByteBuffer render(VfxRenderer renderer, GlRenderDevice device,
                                     EffectSnapshot snapshot) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                1.0f, 0.1f, 20.0f);
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 4.0f),
                new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f));
        var commands = device.createCommandBuffer();
        commands.bindDefaultFramebuffer().viewport(0, 0, FRAME_SIZE, FRAME_SIZE)
                .enableFramebufferSrgb(false).enableDepthTest(true).depthMask(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f).clear(true, true);
        renderer.record(commands, snapshot, projection, view);
        device.execute(commands);
        ByteBuffer pixels = BufferUtils.createByteBuffer(FRAME_SIZE * FRAME_SIZE * 4);
        glReadPixels(0, 0, FRAME_SIZE, FRAME_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static EffectSnapshot sprite(VfxMaterial material, float size) {
        EffectSnapshot.ParticleSprite particle = new EffectSnapshot.ParticleSprite(
                1L, new Vector3f(0.0f, 0.0f, -3.0f), size, 0.0f,
                new Vector4f(1.0f), 49.0f, new Vector3f(), 0.0f, 0.0f, 0);
        return new EffectSnapshot(List.of(particle), List.of(), List.of(), List.of(particle),
                new VfxVisualSet(material, material, material, material));
    }

    private static int centerChannel(ByteBuffer pixels, int channel) {
        return Byte.toUnsignedInt(pixels.get(
                ((FRAME_SIZE / 2) * FRAME_SIZE + FRAME_SIZE / 2) * 4 + channel));
    }

    private static int maximumChannel(ByteBuffer pixels, int channel) {
        int maximum = 0;
        for (int offset = channel; offset < pixels.capacity(); offset += 4) {
            maximum = Math.max(maximum, Byte.toUnsignedInt(pixels.get(offset)));
        }
        return maximum;
    }

    private static int nonBlackPixels(ByteBuffer pixels) {
        int count = 0;
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            int energy = Byte.toUnsignedInt(pixels.get(offset))
                    + Byte.toUnsignedInt(pixels.get(offset + 1))
                    + Byte.toUnsignedInt(pixels.get(offset + 2));
            if (energy > 12) count++;
        }
        return count;
    }

    private static void assertNoGlError(String context) {
        assertEquals(GL_NO_ERROR, glGetError(), context);
        GlDebug.assertNoError(context);
    }

    private static void assertTrackedResourcesReleased() {
        var resources = GlDebug.resources();
        assertTrue(resources.liveResources().isEmpty(),
                "VFX sampler fixtures must release every tracked GL resource: "
                        + resources.liveResources());
        assertEquals(resources.createdCount(), resources.closedCount(),
                "every tracked VFX sampler resource must be closed exactly once");
    }

    /** Fullscreen probe target and shader, owned by one test case. */
    private static final class Probe implements AutoCloseable {
        private final GlRenderDevice device = new GlRenderDevice();
        private final Framebuffer target = Framebuffer.fromDescriptor(
                FramebufferDescriptor.colorOnly(PROBE_SIZE, PROBE_SIZE, GL_RGBA32F));
        private final ShaderProgram shader = ShaderProgram.fromSources(
                PROBE_VERTEX, PROBE_FRAGMENT);
        private final Mesh quad = fullscreenQuad();

        private float[] sample(Texture2D texture, float u, float v, float stepU, float stepV) {
            var commands = device.createCommandBuffer();
            commands.bindFramebuffer(target).viewport(0, 0, PROBE_SIZE, PROBE_SIZE)
                    .enableDepthTest(false).enableBlend(false).enableFramebufferSrgb(false)
                    .clearColor(0.0f, 0.0f, 0.0f, 0.0f).clear(true, false)
                    .bindShader(shader).bindMesh(quad)
                    .bindTexture(0, texture)
                    .setUniformInt(shader, "uTexture", 0)
                    .setUniformVec2(shader, "uSampleUv", u, v)
                    .setUniformVec2(shader, "uStepPerPixel", stepU, stepV)
                    .setUniformVec2(shader, "uPixelCenter", PROBE_PIXEL_CENTER,
                            PROBE_PIXEL_CENTER)
                    .drawMesh(quad);
            device.execute(commands);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, target.id());
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
            glReadPixels(PROBE_CENTER, PROBE_CENTER, 1, 1, GL_RGBA, GL_FLOAT, pixel);
            return new float[]{pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3)};
        }

        private static Mesh fullscreenQuad() {
            VertexAttribute position = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            return Mesh.builder()
                    .vertices(new float[]{
                            -1.0f, -1.0f, 0.0f,
                            1.0f, -1.0f, 0.0f,
                            1.0f, 1.0f, 0.0f,
                            -1.0f, 1.0f, 0.0f
                    }, 3 * Float.BYTES, position)
                    .indices(new int[]{0, 1, 2, 0, 2, 3})
                    .build();
        }

        @Override
        public void close() {
            quad.close();
            shader.close();
            target.close();
        }
    }
}
