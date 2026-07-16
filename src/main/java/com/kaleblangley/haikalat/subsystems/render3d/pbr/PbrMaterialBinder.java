package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL11.glGetInteger;

/** 将 borrowed environment 绑定到冻结的逐帧 PBR unit contract。 */
public final class PbrMaterialBinder implements AutoCloseable {
    public static final int IRRADIANCE_UNIT = 8;
    public static final int PREFILTERED_SPECULAR_UNIT = 9;
    public static final int BRDF_LUT_UNIT = 10;
    private final PbrEnvironment environment;
    private final Sampler cubeSampler;
    private final Sampler lutSampler;

    public PbrMaterialBinder(PbrEnvironment environment) {
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
        int units = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        if (units <= BRDF_LUT_UNIT) {
            throw new GlException("PBR requires at least " + (BRDF_LUT_UNIT + 1)
                    + " fragment texture units, but the context exposes " + units);
        }
        Sampler createdCube = Sampler.create(new Sampler.Descriptor(
                GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
        try {
            lutSampler = Sampler.create(new Sampler.Descriptor(GL_LINEAR, GL_LINEAR,
                    GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
            cubeSampler = createdCube;
        } catch (RuntimeException failure) {
            createdCube.close();
            throw failure;
        }
    }

    public void bind(ShaderProgram shader, CommandBuffer cmd) {
        cmd.bindTextureCube(IRRADIANCE_UNIT, environment.irradiance(), cubeSampler)
                .bindTextureCube(PREFILTERED_SPECULAR_UNIT,
                        environment.prefilteredSpecular(), cubeSampler)
                .bindTexture(BRDF_LUT_UNIT, environment.brdfLut(), lutSampler)
                .trySetUniformInt(shader, "uIrradianceMap", IRRADIANCE_UNIT)
                .trySetUniformInt(shader, "uPrefilteredMap", PREFILTERED_SPECULAR_UNIT)
                .trySetUniformInt(shader, "uBrdfLut", BRDF_LUT_UNIT)
                .trySetUniformFloat(shader, "uEnvironmentIntensity", environment.intensity())
                .trySetUniformFloat(shader, "uEnvironmentRotation", environment.rotationRadians())
                .trySetUniformFloat(shader, "uPrefilterMaxLod",
                        environment.prefilteredSpecular().mipLevels() - 1.0f);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try { lutSampler.close(); } catch (RuntimeException error) { failure = error; }
        try { cubeSampler.close(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }
}
