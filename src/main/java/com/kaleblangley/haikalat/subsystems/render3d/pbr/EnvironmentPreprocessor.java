package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureCube;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_RG16F;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT;

/** 固定的 GPU environment preprocessing 实现，不参与逐帧 RenderGraph。 */
final class EnvironmentPreprocessor {
    private static final String SHADER_ROOT =
            "/com/kaleblangley/haikalat/subsystems/render3d/pbr/shaders/";
    private static final int VISIBILITY_BARRIER =
            GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT;

    private EnvironmentPreprocessor() {
    }

    static PbrEnvironment preprocess(Texture2D source, PbrEnvironmentSettings settings) {
        TextureCube environment = null;
        TextureCube irradiance = null;
        TextureCube prefiltered = null;
        Texture2D brdfLut = null;
        try (ShaderProgram equirect = compute("equirect_to_cube.comp");
             ShaderProgram irradianceProgram = compute("irradiance.comp");
             ShaderProgram prefilterProgram = compute("prefilter.comp");
             ShaderProgram brdfProgram = compute("brdf_lut.comp");
             Sampler sourceSampler = sampler(GL_LINEAR, GL_LINEAR);
             Sampler cubeSampler = sampler(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR)) {
            environment = TextureCube.create(settings.environmentSize(),
                    TextureCube.maximumMipLevels(settings.environmentSize()), RenderFormat.RGBA16F);
            irradiance = TextureCube.create(settings.irradianceSize(), 1, RenderFormat.RGBA16F);
            prefiltered = TextureCube.create(settings.prefilteredSize(),
                    TextureCube.maximumMipLevels(settings.prefilteredSize()), RenderFormat.RGBA16F);
            brdfLut = Texture2D.createEmpty(settings.brdfLutSize(), settings.brdfLutSize(), GL_RG16F);

            StateCache cache = new StateCache();
            runEquirect(source, sourceSampler, environment, equirect, cache);
            runIrradiance(environment, cubeSampler, irradiance, irradianceProgram,
                    settings.irradianceSamples(), cache);
            runPrefilter(environment, cubeSampler, prefiltered, prefilterProgram,
                    settings.prefilterSamples(), cache);
            runBrdf(brdfLut, brdfProgram, settings.brdfSamples(), cache);

            PbrEnvironment result = new PbrEnvironment(environment, irradiance, prefiltered,
                    brdfLut, 1.0f, 0.0f);
            environment = null;
            irradiance = null;
            prefiltered = null;
            brdfLut = null;
            return result;
        } finally {
            close(brdfLut);
            close(prefiltered);
            close(irradiance);
            close(environment);
        }
    }

    private static void runEquirect(Texture2D source, Sampler sampler, TextureCube output,
                                    ShaderProgram shader, StateCache cache) {
        CommandBuffer cmd = new CommandBuffer();
        cmd.bindShader(shader)
                .bindTexture(0, source, sampler)
                .setUniformInt(shader, "uEquirectangular", 0)
                .bindImage(output, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .dispatchCompute(groups(output.size()), groups(output.size()), 6)
                .memoryBarrier(VISIBILITY_BARRIER)
                .generateMipmaps(output)
                .memoryBarrier(VISIBILITY_BARRIER);
        cmd.execute(cache);
    }

    private static void runIrradiance(TextureCube source, Sampler sampler, TextureCube output,
                                      ShaderProgram shader, int samples, StateCache cache) {
        CommandBuffer cmd = new CommandBuffer();
        cmd.bindShader(shader)
                .bindTextureCube(0, source, sampler)
                .setUniformInt(shader, "uEnvironment", 0)
                .setUniformInt(shader, "uSampleCount", samples)
                .bindImage(output, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .dispatchCompute(groups(output.size()), groups(output.size()), 6)
                .memoryBarrier(VISIBILITY_BARRIER);
        cmd.execute(cache);
    }

    private static void runPrefilter(TextureCube source, Sampler sampler, TextureCube output,
                                     ShaderProgram shader, int samples, StateCache cache) {
        for (int mip = 0; mip < output.mipLevels(); mip++) {
            int size = Math.max(1, output.size() >> mip);
            float roughness = output.mipLevels() == 1 ? 0.0f
                    : (float) mip / (output.mipLevels() - 1);
            CommandBuffer cmd = new CommandBuffer();
            cmd.bindShader(shader)
                    .bindTextureCube(0, source, sampler)
                    .setUniformInt(shader, "uEnvironment", 0)
                    .setUniformInt(shader, "uSampleCount", samples)
                    .setUniformFloat(shader, "uRoughness", roughness)
                    .bindImage(output, 0, mip, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                    .dispatchCompute(groups(size), groups(size), 6)
                    .memoryBarrier(VISIBILITY_BARRIER);
            cmd.execute(cache);
        }
    }

    private static void runBrdf(Texture2D output, ShaderProgram shader,
                                int samples, StateCache cache) {
        CommandBuffer cmd = new CommandBuffer();
        cmd.bindShader(shader)
                .setUniformInt(shader, "uSampleCount", samples)
                .bindImage(output, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RG16F)
                .dispatchCompute(groups(output.width()), groups(output.height()), 1)
                .memoryBarrier(VISIBILITY_BARRIER);
        cmd.execute(cache);
    }

    private static ShaderProgram compute(String name) {
        return ShaderProgram.fromComputeResource(EnvironmentPreprocessor.class, SHADER_ROOT + name);
    }

    private static Sampler sampler(int min, int mag) {
        return Sampler.create(new Sampler.Descriptor(min, mag,
                GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
    }

    private static int groups(int size) {
        return (size + 7) / 8;
    }

    private static void close(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to release partial PBR environment", error);
        }
    }
}
