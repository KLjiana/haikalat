package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Bundle;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Request;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared offscreen renderer for clustered lighting image-comparison tests. */
final class ClusteredLightingTestSupport {
    private ClusteredLightingTestSupport() {
    }

    static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder()
                .dimensions(320, 180).title("ClusteredLightingGlTest").visible(false).build();
    }

    static PbrEnvironment environment(GlRenderDevice device) {
        return PbrEnvironmentLoader.load(device, ClusteredLightingTestSupport.class,
                "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
    }

    static byte[] render(GlfwWindow window, GlRenderDevice device, ShaderProgram shader,
                         PbrFallbackTextures fallbacks, PbrEnvironment environment,
                         Request request, ClusteredLightingSettings settings,
                         Consumer<Scene> sceneSetup) {
        Bundle bundle = ClusteredDemoSceneFactory.create(request, shader, fallbacks);
        try {
            Objects.requireNonNull(sceneSetup, "sceneSetup").accept(bundle.scene);
            RenderSettings renderSettings = RenderSettings.builder()
                    .vsync(false)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .bloomSettings(BloomSettings.disabled())
                    .build();
            RenderPipeline pipeline = new RenderPipeline(window, bundle.scene, null,
                    renderSettings, environment).clusteredLighting(settings);
            try {
                pipeline.build();
                pipeline.execute(device, 1.0f / 60.0f);
                ByteBuffer pixels = BufferUtils.createByteBuffer(window.width()
                        * window.height() * 4);
                GL11.glReadPixels(0, 0, window.width(), window.height(), GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE, pixels);
                byte[] image = new byte[pixels.remaining()];
                pixels.get(image);
                return image;
            } finally {
                pipeline.close();
            }
        } finally {
            bundle.close();
        }
    }

    static int maxDifference(byte[] first, byte[] second) {
        assertEquals(first.length, second.length);
        int maximum = 0;
        for (int index = 0; index < first.length; index++) {
            maximum = Math.max(maximum, Math.abs((first[index] & 0xFF) - (second[index] & 0xFF)));
        }
        return maximum;
    }
}
