package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.ColorGradingLut;
import com.kaleblangley.haikalat.subsystems.postprocess.ColorGradingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class PostProcessEffectsGlTest {
    @Test
    void colorGradingAndDepthFogChangePixelsAndSurviveResize() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
            GltfSceneAsset asset = GltfSceneAsset.upload(
                    new GltfAssetLoader(ResourceLocator.classpath(getClass()))
                            .load(AssetRef.of("/fixtures/gltf/minimal.gltf")), library);
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality())) {
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 3.0f)));
                asset.instantiate(new Matrix4f().translation(-0.4f, -0.4f, 0.0f), false)
                        .forEach(scene::add);
                scene.addLight(SceneLight.directional(new Vector3f(0.0f, 0.0f, -1.0f),
                        new Vector3f(1.0f), 3.0f));
                RenderSettings renderSettings = RenderSettings.builder()
                        .toneMappingMode(ToneMappingMode.ACES)
                        .bloomSettings(BloomSettings.disabled())
                        .vsync(false)
                        .build();

                ByteBuffer baseline = renderOneFrame(window, scene, renderSettings, environment,
                        PostProcessSettings.defaults(), device);
                ColorGradingLut warmLut = ColorGradingLut.generate(16,
                        (red, green, blue) -> new ColorGradingLut.Rgb(
                                Math.min(1.0f, red * 1.15f), green * 0.45f, blue * 0.20f));
                ByteBuffer graded = renderOneFrame(window, scene, renderSettings, environment,
                        PostProcessSettings.builder()
                                .colorGrading(ColorGradingSettings.of(warmLut, 1.0f))
                                .build(), device);
                assertTrue(changedRgbPixels(baseline, graded) > 64,
                        "the LUT must visibly alter the rendered frame");

                PostProcessSettings fog = PostProcessSettings.builder()
                        .fog(FogSettings.builder()
                                .color(0.75f, 0.18f, 0.08f)
                                .distanceDensity(0.35f)
                                .heightDensity(0.08f)
                                .heightFalloff(0.2f)
                                .maximumOpacity(0.95f)
                                .build())
                        .build();
                RenderPipeline fogPipeline = new RenderPipeline(window, scene, null,
                        renderSettings, environment).postProcessSettings(fog);
                try {
                    fogPipeline.build();
                    assertTrue(fogPipeline.graph().hasPass(PostProcessTargets.FOG_PASS));
                    fogPipeline.execute(device);
                    ByteBuffer fogged = readFrame(window);
                    assertTrue(changedRgbPixels(baseline, fogged) > 64,
                            "depth-based fog must visibly alter the rendered frame");

                    window.resize(80, 48);
                    window.pollEvents();
                    fogPipeline.resize(window.width(), window.height());
                    fogPipeline.execute(device);
                    assertEquals(window.width() * window.height() * 4,
                            readFrame(window).capacity());
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally {
                    fogPipeline.close();
                }
            } finally {
                asset.close();
                library.close();
            }
        }
    }

    private static ByteBuffer renderOneFrame(GlfwWindow window, Scene scene,
                                             RenderSettings renderSettings,
                                             PbrEnvironment environment,
                                             PostProcessSettings effects,
                                             GlRenderDevice device) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                renderSettings, environment).postProcessSettings(effects);
        try {
            pipeline.build();
            pipeline.execute(device);
            return readFrame(window);
        } finally {
            pipeline.close();
        }
    }

    private static ByteBuffer readFrame(GlfwWindow window) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(window.width() * window.height() * 4);
        glReadPixels(0, 0, window.width(), window.height(), GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static int changedRgbPixels(ByteBuffer left, ByteBuffer right) {
        int pixels = Math.min(left.capacity(), right.capacity()) / 4;
        int changed = 0;
        for (int pixel = 0; pixel < pixels; pixel++) {
            int offset = pixel * 4;
            int delta = Math.abs(Byte.toUnsignedInt(left.get(offset))
                    - Byte.toUnsignedInt(right.get(offset)))
                    + Math.abs(Byte.toUnsignedInt(left.get(offset + 1))
                    - Byte.toUnsignedInt(right.get(offset + 1)))
                    + Math.abs(Byte.toUnsignedInt(left.get(offset + 2))
                    - Byte.toUnsignedInt(right.get(offset + 2)));
            if (delta > 8) changed++;
        }
        return changed;
    }
}
