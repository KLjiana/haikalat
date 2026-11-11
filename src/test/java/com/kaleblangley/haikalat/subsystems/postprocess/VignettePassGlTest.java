package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.vignette.VignettePass;
import com.kaleblangley.haikalat.subsystems.postprocess.film.FilmGrainPass;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VignettePassGlTest {
    @Test
    void vignetteDarkensCornersAndKeepsTheCenter() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(32, 32)
                .title("Vignette Pass GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (Framebuffer target = Framebuffer.singleSampled(32, 32);
                 Texture2D source = grayTexture()) {
                VignetteSettings settings = VignetteSettings.builder().enabled(true)
                        .intensity(0.8f).smoothness(0.5f).roundness(0.0f).build();
                try (VignettePass pass = new VignettePass(settings)) {
                    target.bind();
                    GlRenderDevice device = new GlRenderDevice();
                    var commands = device.createCommandBuffer();
                    pass.recordIntoCurrentTarget(commands, source.id(), 32, 32);
                    device.execute(commands);
                    int center = channel(16, 16);
                    int corner = channel(1, 1);
                    assertTrue(center > corner + 20,
                            "vignette must darken corners while preserving the center");
                    assertTrue(center >= 100, "center must remain close to the source");
                }
            }
            assertTrue(glGetError() == org.lwjgl.opengl.GL11.GL_NO_ERROR);
        }
    }

    @Test
    void renderPipelineRecordsVignetteAfterToneMapping() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(64, 64)
                .title("Vignette Pipeline Order GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 4.0f)));
            RenderSettings settings = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .vsync(false).build();
            RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings)
                    .postProcessSettings(PostProcessSettings.builder()
                            .vignette(VignetteSettings.builder().enabled(true)
                                    .intensity(0.4f).build())
                            .filmGrain(FilmGrainSettings.builder().enabled(true)
                                    .intensity(0.04f).seed(3).build())
                            .chromaticAberration(ChromaticAberrationSettings.builder()
                                    .enabled(true).intensity(0.002f).build())
                            .build());
            try {
                pipeline.build();
                pipeline.execute(new GlRenderDevice());
                var passes = pipeline.graph().lastFrameProfile().passes();
                int tone = -1;
                int vignette = -1;
                int grain = -1;
                int aberration = -1;
                for (int index = 0; index < passes.size(); index++) {
                    String name = passes.get(index).passName();
                    if (PostProcessTargets.TONE_MAPPING_PASS.equals(name)) tone = index;
                    if (PostProcessTargets.VIGNETTE_PASS.equals(name)) vignette = index;
                    if (PostProcessTargets.FILM_GRAIN_PASS.equals(name)) grain = index;
                    if (PostProcessTargets.CHROMATIC_ABERRATION_PASS.equals(name)) aberration = index;
                }
                assertTrue(tone >= 0 && vignette > tone
                                && grain > vignette && aberration > grain,
                        "screen effects must run after tone mapping in vignette/grain/aberration order");
            } finally {
                pipeline.close();
            }
        }
    }

    @Test
    void filmGrainPerturbsAFlatImage() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(32, 32)
                .title("Film Grain GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (Framebuffer target = Framebuffer.singleSampled(32, 32);
                 Texture2D source = grayTexture()) {
                FilmGrainSettings settings = FilmGrainSettings.builder().enabled(true)
                        .intensity(0.2f).response(0.0f).seed(5).build();
                try (FilmGrainPass pass = new FilmGrainPass(settings)) {
                    target.bind();
                    GlRenderDevice device = new GlRenderDevice();
                    var commands = device.createCommandBuffer();
                    pass.recordIntoCurrentTarget(commands, source.id(), 32, 32);
                    device.execute(commands);
                    ByteBuffer pixels = BufferUtils.createByteBuffer(32 * 32 * 4);
                    glReadPixels(0, 0, 32, 32, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    int changed = 0;
                    for (int offset = 0; offset < pixels.capacity(); offset += 4) {
                        if (Byte.toUnsignedInt(pixels.get(offset)) != 160) changed++;
                    }
                    assertTrue(changed > 32, "film grain must perturb a flat image");
                }
            }
            assertTrue(glGetError() == org.lwjgl.opengl.GL11.GL_NO_ERROR);
        }
    }

    private static Texture2D grayTexture() {
        byte[] pixels = new byte[32 * 32 * 4];
        for (int index = 0; index < pixels.length; index += 4) {
            pixels[index] = (byte) 160;
            pixels[index + 1] = (byte) 160;
            pixels[index + 2] = (byte) 160;
            pixels[index + 3] = (byte) 255;
        }
        return Texture2D.fromRgba8(32, 32, pixels, TextureColorSpace.LINEAR);
    }

    private static int channel(int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return Byte.toUnsignedInt(pixel.get(0));
    }
}
