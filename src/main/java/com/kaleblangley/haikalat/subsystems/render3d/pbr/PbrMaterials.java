package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.material.AnisotropicSettings;
import com.kaleblangley.haikalat.core.material.ClearcoatSettings;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.ParallaxOcclusionSettings;
import com.kaleblangley.haikalat.core.material.RefractionSettings;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.BlendMode;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS;

/** 将不可变 PBR 定义装配为通用 runtime Material 的便利入口。 */
public final class PbrMaterials {
    /**
     * Frozen fragment texture unit of the optional clearcoat normal map. Units
     * 0-4 are the metallic-roughness roles and 8-10 are borrowed from the
     * per-frame IBL binder.
     */
    public static final int CLEARCOAT_NORMAL_UNIT = 5;
    /**
     * Frozen fragment texture unit of the optional linear height map used by
     * parallax occlusion mapping. Unit 6 is free of the PBR roles (0-4), the
     * clearcoat normal (5) and the borrowed IBL units (8-10).
     */
    public static final int HEIGHT_UNIT = 6;
    /**
     * Frozen fragment texture units of the optional local reflection probes.
     * Units 0-6 are the material roles, 7/11/12/13 the frame shadow/GTAO
     * samplers, 8-10 the global IBL and 14-21 the volumetric-fog prelude
     * samplers; 22/23 are the first units free in both the ordinary and the
     * volume-fog PBR variants. Using them therefore requires a context with at
     * least 24 fragment texture units (the target desktop GL 4.6 context
     * exposes 32). The probe sampler uniforms are always pointed at these units
     * because the shader statically declares both samplerCubes; otherwise they
     * would default to unit 0 and alias the sampler2D base-colour binding.
     */
    public static final int REFLECTION_PROBE_UNIT_0 = 22;
    public static final int REFLECTION_PROBE_UNIT_1 = 23;

    private PbrMaterials() {
    }

    public static Material create(ShaderProgram shader, PbrMaterialProperties properties,
                                  Map<PbrTextureRole, Texture2D> supplied,
                                  PbrFallbackTextures fallbacks) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(properties, "properties");
        supplied = Map.copyOf(Objects.requireNonNull(supplied, "supplied"));
        Objects.requireNonNull(fallbacks, "fallbacks");
        requireReflectionProbeTextureUnits();
        Material.Builder builder = Material.builder(shader)
                .model(MaterialModel.METALLIC_ROUGHNESS)
                .setVec4("uBaseColorFactor", properties.baseColorFactor())
                .setFloat("uMetallicFactor", properties.metallicFactor())
                .setFloat("uRoughnessFactor", properties.roughnessFactor())
                .setFloat("uNormalScale", properties.normalScale())
                .setFloat("uOcclusionStrength", properties.occlusionStrength())
                .setVec3("uEmissiveFactor", properties.emissiveFactor())
                .setFloat("uThicknessFactor", properties.thicknessFactor())
                .setFloat("uTransmissionFactor", properties.transmissionFactor())
                .setFloat("uBacklightFactor", properties.backlightFactor())
                .setInt("uEnableDirect", 1)
                .setInt("uEnableDiffuseIbl", 1)
                .setInt("uEnableSpecularIbl", 1)
                .setInt("uEnableNormalMap", 1)
                .setInt("uHasVertexColor", 0)
                .setInt("uDoubleSided", 0)
                .setFloat("uAlphaCutoff", 0.0f);
        builder.setInt("uAlphaMode", 0);
        applyReflectionProbeDefaults(builder);
        applyClearcoat(builder, ClearcoatSettings.disabled());
        applyAnisotropic(builder, AnisotropicSettings.disabled());
        applyParallax(builder, ParallaxOcclusionSettings.disabled());
        applySubsurface(builder, SubsurfaceScatteringSettings.disabled());
        bind(builder, 0, "uBaseColorMap", PbrTextureRole.BASE_COLOR, supplied, fallbacks);
        bind(builder, 1, "uNormalMap", PbrTextureRole.NORMAL, supplied, fallbacks);
        bind(builder, 2, "uMetallicRoughnessMap", PbrTextureRole.METALLIC_ROUGHNESS, supplied, fallbacks);
        bind(builder, 3, "uOcclusionMap", PbrTextureRole.OCCLUSION, supplied, fallbacks);
        bind(builder, 4, "uEmissiveMap", PbrTextureRole.EMISSIVE, supplied, fallbacks);
        bind(builder, HEIGHT_UNIT, "uHeightMap", PbrTextureRole.HEIGHT, supplied, fallbacks);
        bindClearcoatNormal(builder, null, fallbacks);
        return builder.build();
    }

    /**
     * Binds the local reflection probes selected for one world position to a
     * material instance.
     *
     * <p>The GL-free {@link ReflectionProbeSelector} chooses at most
     * {@link ReflectionProbeSettings#MAX_PROBE_COUNT} probes and computes their
     * normalised weights plus the residual global environment weight. This
     * method uploads the selected prefiltered cubemaps, per-probe position /
     * box-or-sphere extents / weight / intensity / rotation / maximum mip and
     * the global weight to the frozen {@code uReflectionProbe*} contract. The
     * fragment shader performs the per-pixel local-correction direction and
     * mixes the probe radiance with the existing global IBL sample.</p>
     *
     * <p>Callers pass the receiver's representative world position (for
     * example its bounds centre) on the render thread before the draw. The
     * method allocates no GPU resources and never reads pixels back. When the
     * selection is empty the count is set to zero, so the shader executes the
     * unchanged global IBL path and no probe texture is sampled.</p>
     *
     * <p>This is a specular-only, per-draw selection: an object that spans a
     * probe boundary uses the selection of its representative point. The
     * shader still applies the local-correction direction per pixel.</p>
     */
    public static void bindReflectionProbes(MaterialInstance instance,
                                             ReflectionProbeSet probes,
                                             Vector3fc worldPosition) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(probes, "probes");
        Objects.requireNonNull(worldPosition, "worldPosition");
        ReflectionProbeSelector.Selection selection = probes.select(worldPosition);
        int slot = 0;
        for (ReflectionProbeSelector.SelectedProbe selected : selection.probes()) {
            if (slot >= ReflectionProbeSettings.MAX_PROBE_COUNT) break;
            ReflectionProbe probe = probes.probe(selected.probeIndex());
            if (probe == null) continue;
            ReflectionProbeSettings settings = probe.settings();
            int unit = slot == 0 ? REFLECTION_PROBE_UNIT_0 : REFLECTION_PROBE_UNIT_1;
            String prefix = "uReflectionProbe" + slot;
            instance.textureCube(unit, prefix, probe.prefilteredSpecular(),
                            probes.cubeSampler())
                    .setVec3(prefix + "Position", settings.position())
                    .setVec3(prefix + "Extents", probeExtents(settings))
                    .setInt(prefix + "Shape",
                            settings.shape() == ReflectionProbeSettings.Shape.BOX ? 0 : 1)
                    .setFloat(prefix + "Weight", selected.weight())
                    .setFloat(prefix + "Intensity", settings.intensity())
                    .setFloat(prefix + "Rotation", settings.rotationRadians())
                    .setFloat(prefix + "MaxLod", probe.maximumPrefilteredLod());
            slot++;
        }
        instance.setInt("uReflectionProbeCount", slot)
                .setFloat("uReflectionProbeGlobalWeight", selection.globalWeight())
                // Raw Material.builder(pbr-forward.frag) callers do not pass
                // through applyReflectionProbeDefaults; write the dedicated
                // sampler units unconditionally so even a count-zero bind
                // cannot alias the sampler2D base-colour unit 0.
                .setInt("uReflectionProbe0", REFLECTION_PROBE_UNIT_0)
                .setInt("uReflectionProbe1", REFLECTION_PROBE_UNIT_1);
    }

    private static Vector3f probeExtents(ReflectionProbeSettings settings) {
        return settings.shape() == ReflectionProbeSettings.Shape.SPHERE
                ? new Vector3f(settings.radius(), settings.radius(), settings.radius())
                : settings.halfExtents();
    }

    /** 使用每个纹理角色自己的 glTF sampler 创建 PBR material。 */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks) {
        return createWithBindings(shader, properties, supplied, fallbacks, CullMode.NONE, false, false);
    }

    /** 创建带 glTF raster/vertex-color 合同的 PBR material。 */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor) {
        return createWithBindings(shader, properties, supplied, fallbacks, cullMode,
                doubleSided, hasVertexColor, 0.0f);
    }

    /** 创建带 glTF MASK cutoff 的 PBR material；零表示 OPAQUE。 */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor,
                                               float alphaCutoff) {
        return createWithBindings(shader, properties, supplied, fallbacks, cullMode,
                doubleSided, hasVertexColor, alphaCutoff, BlendMode.OPAQUE);
    }

    /** Creates a glTF PBR material with an explicit raster alpha policy. */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor,
                                               float alphaCutoff, BlendMode blendMode) {
        return createWithBindings(shader, properties, supplied, fallbacks, cullMode, doubleSided,
                hasVertexColor, alphaCutoff, blendMode, ClearcoatSettings.disabled(), null);
    }

    /**
     * Creates a glTF-style PBR material with a bounded clearcoat overlay.
     *
     * <p>The clearcoat settings are stored on the material and uploaded as the
     * {@code uClearcoat*} uniforms. {@code clearcoatNormal} is an optional
     * tangent-space normal map bound to {@link #CLEARCOAT_NORMAL_UNIT}; the
     * fallback is the flat PBR normal, so omitting it (or a normal strength of
     * zero) leaves the coat on the geometric normal. The map is never borrowed
     * implicitly from the base normal role. Disabled settings are the exact
     * pre-clearcoat output.</p>
     */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor,
                                               float alphaCutoff, BlendMode blendMode,
                                               ClearcoatSettings clearcoat,
                                               PbrTextureBinding clearcoatNormal) {
        return createWithBindings(shader, properties, supplied, fallbacks, cullMode, doubleSided,
                hasVertexColor, alphaCutoff, blendMode, clearcoat, clearcoatNormal,
                AnisotropicSettings.disabled());
    }

    /**
     * Creates a glTF-style PBR material with the bounded clearcoat overlay and
     * the bounded anisotropic base-specular input.
     *
     * <p>{@code anisotropic} is stored on the material and uploaded as the
     * {@code uAnisotropicEnabled}/{@code uAnisotropicStrength}/
     * {@code uAnisotropicRotation} uniforms. It stretches only the base
     * metallic-roughness specular lobe; the clearcoat layer, diffuse term and
     * Fresnel split stay isotropic. Disabled settings are the exact
     * pre-anisotropy output, including the original prefiltered-IBL sample
     * sequence.</p>
     */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor,
                                               float alphaCutoff, BlendMode blendMode,
                                               ClearcoatSettings clearcoat,
                                               PbrTextureBinding clearcoatNormal,
                                               AnisotropicSettings anisotropic) {
        return createWithBindings(shader, properties, supplied, fallbacks, cullMode, doubleSided,
                hasVertexColor, alphaCutoff, blendMode, clearcoat, clearcoatNormal, anisotropic,
                ParallaxOcclusionSettings.disabled());
    }

    /**
     * Creates a glTF-style PBR material with every bounded material input,
     * including tangent-space parallax occlusion mapping.
     *
     * <p>{@code parallax} is stored on the material and uploaded as the
     * {@code uParallax*} uniforms. Its height map is the {@link PbrTextureRole#HEIGHT}
     * entry of {@code supplied} (linear colour space) bound to the frozen
     * {@link #HEIGHT_UNIT}; a missing entry uses the shared black fallback,
     * whose zero height is a flat surface and therefore no UV offset. The
     * settings carry the channel selector and optional inversion because they
     * are material values, not texture properties. Disabled settings or a zero
     * height scale keep the exact original {@code vTexCoord} sampling
     * sequence.</p>
     */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor,
                                               float alphaCutoff, BlendMode blendMode,
                                               ClearcoatSettings clearcoat,
                                               PbrTextureBinding clearcoatNormal,
                                               AnisotropicSettings anisotropic,
                                               ParallaxOcclusionSettings parallax) {
        return createWithBindings(shader, properties, supplied, fallbacks, cullMode, doubleSided,
                hasVertexColor, alphaCutoff, blendMode, clearcoat, clearcoatNormal, anisotropic,
                parallax, SubsurfaceScatteringSettings.disabled());
    }

    /**
     * Creates a glTF-style PBR material with every bounded material input,
     * including the bounded subsurface-scattering term and its
     * screen-space surface mask.
     *
     * <p>{@code subsurface} is stored on the material and uploaded as the
     * {@code uSubsurface*} uniforms. Its thickness source may read the existing
     * {@link PbrTextureRole#OCCLUSION} map or the base-colour alpha; no extra
     * texture role is created. {@link SubsurfaceScatteringSettings#disabled()}
     * is the exact pre-subsurface output and writes a zero mask, so the
     * screen-space pass contributes nothing for legacy materials.</p>
     */
    public static Material createWithBindings(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks, CullMode cullMode,
                                               boolean doubleSided, boolean hasVertexColor,
                                               float alphaCutoff, BlendMode blendMode,
                                               ClearcoatSettings clearcoat,
                                               PbrTextureBinding clearcoatNormal,
                                               AnisotropicSettings anisotropic,
                                               ParallaxOcclusionSettings parallax,
                                               SubsurfaceScatteringSettings subsurface) {
        if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
            throw new IllegalArgumentException("alphaCutoff must be finite and in [0, 1]");
        }
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(properties, "properties");
        supplied = Map.copyOf(Objects.requireNonNull(supplied, "supplied"));
        Objects.requireNonNull(fallbacks, "fallbacks");
        Objects.requireNonNull(clearcoat, "clearcoat");
        Objects.requireNonNull(anisotropic, "anisotropic");
        Objects.requireNonNull(parallax, "parallax");
        Objects.requireNonNull(subsurface, "subsurface");
        Objects.requireNonNull(blendMode, "blendMode");
        if (subsurface.active() && blendMode != BlendMode.OPAQUE) {
            throw new IllegalArgumentException(
                    "subsurface scattering requires an opaque alpha policy");
        }
        Material.Builder builder = baseBuilder(shader, properties, clearcoat, anisotropic, parallax,
                subsurface)
                .cullMode(cullMode)
                .blendMode(blendMode)
                .setInt("uDoubleSided", doubleSided ? 1 : 0)
                .setInt("uHasVertexColor", hasVertexColor ? 1 : 0)
                .setFloat("uAlphaCutoff", alphaCutoff)
                .setInt("uAlphaMode", blendMode == BlendMode.ALPHA ? 2
                        : alphaCutoff > 0.0f ? 1 : 0);
        bindWithSampler(builder, 0, "uBaseColorMap", PbrTextureRole.BASE_COLOR, supplied, fallbacks);
        bindWithSampler(builder, 1, "uNormalMap", PbrTextureRole.NORMAL, supplied, fallbacks);
        bindWithSampler(builder, 2, "uMetallicRoughnessMap", PbrTextureRole.METALLIC_ROUGHNESS, supplied, fallbacks);
        bindWithSampler(builder, 3, "uOcclusionMap", PbrTextureRole.OCCLUSION, supplied, fallbacks);
        bindWithSampler(builder, 4, "uEmissiveMap", PbrTextureRole.EMISSIVE, supplied, fallbacks);
        bindWithSampler(builder, HEIGHT_UNIT, "uHeightMap", PbrTextureRole.HEIGHT,
                supplied, fallbacks);
        bindClearcoatNormal(builder, clearcoatNormal, fallbacks);
        return builder.build();
    }

    /**
     * Hand-built PBR material with a clearcoat overlay and the default raster
     * state (no culling, single-sided, no vertex colour, OPAQUE).
     */
    public static Material createWithClearcoat(ShaderProgram shader, PbrMaterialProperties properties,
                                               Map<PbrTextureRole, PbrTextureBinding> supplied,
                                               PbrFallbackTextures fallbacks,
                                               ClearcoatSettings clearcoat,
                                               PbrTextureBinding clearcoatNormal) {
        return createWithBindings(shader, properties, supplied, fallbacks, CullMode.NONE,
                false, false, 0.0f, BlendMode.OPAQUE, clearcoat, clearcoatNormal);
    }

    /**
     * Hand-built PBR material with a bounded anisotropic highlight and the
     * default raster state (no culling, single-sided, no vertex colour,
     * OPAQUE). Anisotropy is available together with clearcoat through the
     * full {@code createWithBindings(...)} overload.
     */
    public static Material createWithAnisotropy(ShaderProgram shader,
                                                PbrMaterialProperties properties,
                                                Map<PbrTextureRole, PbrTextureBinding> supplied,
                                                PbrFallbackTextures fallbacks,
                                                AnisotropicSettings anisotropic) {
        return createWithBindings(shader, properties, supplied, fallbacks, CullMode.NONE,
                false, false, 0.0f, BlendMode.OPAQUE, ClearcoatSettings.disabled(), null,
                anisotropic);
    }

    /**
     * Hand-built PBR material with bounded tangent-space parallax occlusion and
     * the default raster state (no culling, single-sided, no vertex colour,
     * OPAQUE). The height map is the {@link PbrTextureRole#HEIGHT} entry of
     * {@code supplied}; omitting it uses the shared black fallback (flat, no
     * offset). A caller that also needs clearcoat or anisotropy uses the full
     * {@code createWithBindings(...)} overload with the trailing settings.
     */
    public static Material createWithParallax(ShaderProgram shader,
                                              PbrMaterialProperties properties,
                                              Map<PbrTextureRole, PbrTextureBinding> supplied,
                                              PbrFallbackTextures fallbacks,
                                              ParallaxOcclusionSettings parallax) {
        return createWithBindings(shader, properties, supplied, fallbacks, CullMode.NONE,
                false, false, 0.0f, BlendMode.OPAQUE, ClearcoatSettings.disabled(), null,
                AnisotropicSettings.disabled(), parallax);
    }

    /**
     * Hand-built PBR material with the bounded subsurface-scattering input and
     * the default raster state (no culling, single-sided, no vertex colour,
     * OPAQUE). The thickness source may read the existing occlusion role or the
     * base-colour alpha, so omitting those textures keeps the forwarded
     * fallback values. A caller that also needs clearcoat, anisotropy or
     * parallax uses the full {@code createWithBindings(...)} overload with the
     * trailing settings.
     */
    public static Material createWithSubsurface(ShaderProgram shader,
                                                PbrMaterialProperties properties,
                                                Map<PbrTextureRole, PbrTextureBinding> supplied,
                                                PbrFallbackTextures fallbacks,
                                                SubsurfaceScatteringSettings subsurface) {
        return createWithBindings(shader, properties, supplied, fallbacks, CullMode.NONE,
                false, false, 0.0f, BlendMode.OPAQUE, ClearcoatSettings.disabled(), null,
                AnisotropicSettings.disabled(), ParallaxOcclusionSettings.disabled(), subsurface);
    }

    /**
     * Creates a bounded single-layer transmissive material for the refraction
     * fragment stage.  The shader is supplied by the caller (the same lifecycle
     * contract as the ordinary PBR factory); it must be the paired
     * {@code pbr-forward.vert} and {@code refraction-forward.frag} program.
     * The material is always alpha blended so the forward queue sorts it
     * back-to-front, while the pipeline records it in the refraction pass
     * instead of the ordinary transparent stream.
     */
    public static Material createGlass(ShaderProgram shader, PbrMaterialProperties properties,
                                       Map<PbrTextureRole, PbrTextureBinding> supplied,
                                       PbrFallbackTextures fallbacks,
                                       RefractionSettings refraction) {
        return createGlass(shader, properties, supplied, fallbacks, refraction,
                CullMode.NONE, false, false);
    }

    public static Material createGlass(ShaderProgram shader, PbrMaterialProperties properties,
                                       Map<PbrTextureRole, PbrTextureBinding> supplied,
                                       PbrFallbackTextures fallbacks,
                                       RefractionSettings refraction, CullMode cullMode,
                                       boolean doubleSided, boolean hasVertexColor) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(fallbacks, "fallbacks");
        Objects.requireNonNull(cullMode, "cullMode");
        Objects.requireNonNull(refraction, "refraction");
        if (!refraction.active()) {
            throw new IllegalArgumentException(
                    "glass material requires enabled refraction settings");
        }
        supplied = Map.copyOf(Objects.requireNonNull(supplied, "supplied"));
        Material.Builder builder = Material.builder(shader)
                .model(MaterialModel.METALLIC_ROUGHNESS)
                .blendMode(BlendMode.ALPHA)
                .cullMode(cullMode)
                .refraction(refraction)
                .setVec4("uBaseColorFactor", properties.baseColorFactor())
                .setFloat("uNormalScale", properties.normalScale())
                .setInt("uEnableNormalMap", 1)
                .setInt("uHasVertexColor", hasVertexColor ? 1 : 0)
                .setInt("uDoubleSided", doubleSided ? 1 : 0)
                .setFloat("uRefractionIor", refraction.ior())
                .setFloat("uRefractionThickness", refraction.thickness())
                .setFloat("uRefractionRoughness", refraction.roughness())
                .setFloat("uRefractionTransmission", refraction.transmission())
                .setFloat("uRefractionReflection", refraction.reflection());
        bindWithSampler(builder, 0, "uBaseColorMap", PbrTextureRole.BASE_COLOR,
                supplied, fallbacks);
        bindWithSampler(builder, 1, "uNormalMap", PbrTextureRole.NORMAL,
                supplied, fallbacks);
        return builder.build();
    }

    private static void bind(Material.Builder builder, int unit, String samplerName,
                             PbrTextureRole role, Map<PbrTextureRole, Texture2D> supplied,
                             PbrFallbackTextures fallbacks) {
        Texture2D texture = supplied.getOrDefault(role, fallbacks.texture(role));
        if (texture.colorSpace() != role.requiredColorSpace()) {
            throw new IllegalArgumentException(role + " requires " + role.requiredColorSpace()
                    + " texture, got " + texture.colorSpace());
        }
        builder.texture(unit, samplerName, texture, fallbacks.sampler());
    }

    private static Material.Builder baseBuilder(ShaderProgram shader, PbrMaterialProperties properties,
                                                ClearcoatSettings clearcoat,
                                                AnisotropicSettings anisotropic,
                                                ParallaxOcclusionSettings parallax,
                                                SubsurfaceScatteringSettings subsurface) {
        requireReflectionProbeTextureUnits();
        Material.Builder builder = Material.builder(shader)
                .model(MaterialModel.METALLIC_ROUGHNESS)
                .setVec4("uBaseColorFactor", properties.baseColorFactor())
                .setFloat("uMetallicFactor", properties.metallicFactor())
                .setFloat("uRoughnessFactor", properties.roughnessFactor())
                .setFloat("uNormalScale", properties.normalScale())
                .setFloat("uOcclusionStrength", properties.occlusionStrength())
                .setVec3("uEmissiveFactor", properties.emissiveFactor())
                .setFloat("uThicknessFactor", properties.thicknessFactor())
                .setFloat("uTransmissionFactor", properties.transmissionFactor())
                .setFloat("uBacklightFactor", properties.backlightFactor())
                .setInt("uEnableDirect", 1)
                .setInt("uEnableDiffuseIbl", 1)
                .setInt("uEnableSpecularIbl", 1)
                .setInt("uEnableNormalMap", 1)
                .setInt("uHasVertexColor", 0)
                .setInt("uDoubleSided", 0)
                .setFloat("uAlphaCutoff", 0.0f);
        return applyReflectionProbeDefaults(applySubsurface(applyParallax(
                applyAnisotropic(applyClearcoat(builder, clearcoat), anisotropic), parallax),
                subsurface));
    }

    /**
     * Uploads the default disabled probe contract. The sampler uniforms are
     * pointed at the dedicated probe units even while the feature is off; this
     * is required because the fragment shader statically declares both probe
     * samplerCubes. Leaving them at their GL default of unit 0 would alias the
     * sampler2D base-colour unit and make the PBR draw invalid even when the
     * count-zero branch never samples a probe. The count-zero gate keeps the
     * original global path bit-identical.
     */
    /**
     * Hard capability requirement of the probe-aware PBR forward shader. Its
     * two samplerCubes occupy 22/23 in both the ordinary and volume-fog
     * variants; a smaller context cannot create an ordinary PBR forward
     * material with this shader/revision. Fail at creation instead of letting
     * the draw alias or reject texture units silently.
     */
    private static void requireReflectionProbeTextureUnits() {
        int units = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        if (units <= REFLECTION_PROBE_UNIT_1) {
            throw new GlException("PBR forward requires at least "
                    + (REFLECTION_PROBE_UNIT_1 + 1)
                    + " fragment texture units for the local reflection-probe contract, but "
                    + "the context exposes " + units);
        }
    }

    private static Material.Builder applyReflectionProbeDefaults(Material.Builder builder) {
        // The shader statically declares both probe samplerCubes. Their sampler
        // uniforms must never be left at GL's default unit 0, where the
        // sampler2D base-colour binding would make the PBR draw invalid even
        // though the count-zero branch never samples a probe. Units 22/23 are
        // the first pair free in both the ordinary and the volume-fog variants;
        // this makes the probe-aware PBR shader require the documented 24
        // fragment texture units even when the feature is disabled.
        return builder
                .setInt("uReflectionProbeCount", 0)
                .setFloat("uReflectionProbeGlobalWeight", 1.0f)
                .setInt("uReflectionProbe0", REFLECTION_PROBE_UNIT_0)
                .setInt("uReflectionProbe1", REFLECTION_PROBE_UNIT_1);
    }

    /** Uploads the bounded clearcoat settings as the frozen material uniforms. */
    private static Material.Builder applyClearcoat(Material.Builder builder,
                                                   ClearcoatSettings clearcoat) {
        Objects.requireNonNull(clearcoat, "clearcoat");
        return builder
                .clearcoat(clearcoat)
                .setInt("uClearcoatEnabled", clearcoat.active() ? 1 : 0)
                .setFloat("uClearcoatRoughness", clearcoat.roughness())
                .setFloat("uClearcoatNormalStrength", clearcoat.normalStrength());
    }

    /** Uploads the bounded anisotropic settings as the frozen material uniforms. */
    private static Material.Builder applyAnisotropic(Material.Builder builder,
                                                     AnisotropicSettings anisotropic) {
        Objects.requireNonNull(anisotropic, "anisotropic");
        return builder
                .anisotropic(anisotropic)
                .setInt("uAnisotropicEnabled", anisotropic.active() ? 1 : 0)
                .setFloat("uAnisotropicStrength", anisotropic.strength())
                .setFloat("uAnisotropicRotation", anisotropic.rotation());
    }

    /**
     * Uploads the bounded parallax occlusion settings as the frozen material
     * uniforms. The height texture itself is bound through the
     * {@link PbrTextureRole#HEIGHT} role at {@link #HEIGHT_UNIT}.
     */
    private static Material.Builder applyParallax(Material.Builder builder,
                                                  ParallaxOcclusionSettings parallax) {
        Objects.requireNonNull(parallax, "parallax");
        return builder
                .parallaxOcclusion(parallax)
                .setInt("uParallaxEnabled", parallax.active() ? 1 : 0)
                .setFloat("uParallaxHeightScale", parallax.heightScale())
                .setInt("uParallaxMinSteps", parallax.minSteps())
                .setInt("uParallaxMaxSteps", parallax.maxSteps())
                .setFloat("uParallaxFadeStart", parallax.fadeStart())
                .setFloat("uParallaxFadeEnd", parallax.fadeEnd())
                .setFloat("uParallaxFadeStartDistance", parallax.fadeStartDistance())
                .setFloat("uParallaxMaxDistance", parallax.maxDistance())
                .setInt("uParallaxHeightChannel", parallax.heightChannel().shaderIndex())
                .setInt("uParallaxFlipHeight", parallax.flipHeight() ? 1 : 0);
    }

    /**
     * Uploads the bounded subsurface-scattering settings as the frozen
     * material uniforms. The values are metadata for the forward shader and
     * the shared surface mask; no texture, pass or GPU owner is created here.
     */
    private static Material.Builder applySubsurface(Material.Builder builder,
                                                    SubsurfaceScatteringSettings subsurface) {
        Objects.requireNonNull(subsurface, "subsurface");
        return builder
                .subsurfaceScattering(subsurface)
                .setInt("uSubsurfaceEnabled", subsurface.active() ? 1 : 0)
                .setVec3("uSubsurfaceScatterColor", subsurface.scatterColor())
                .setFloat("uSubsurfaceIntensity", subsurface.intensity())
                .setInt("uSubsurfaceProfile", subsurface.profile().shaderIndex())
                .setFloat("uSubsurfaceProfileScale", subsurface.profileScaleMetres())
                .setInt("uSubsurfaceThicknessSource", subsurface.thicknessSource().shaderIndex())
                .setFloat("uSubsurfaceThickness", subsurface.thicknessMetres());
    }

    /**
     * Binds the optional clearcoat normal map, or the flat PBR normal fallback.
     * A supplied map must be linear data, like the base normal role.
     */
    private static void bindClearcoatNormal(Material.Builder builder,
                                            PbrTextureBinding binding,
                                            PbrFallbackTextures fallbacks) {
        Texture2D texture = binding == null
                ? fallbacks.texture(PbrTextureRole.NORMAL) : binding.texture();
        Sampler sampler = binding == null ? fallbacks.sampler() : binding.sampler();
        if (texture.colorSpace() != PbrTextureRole.NORMAL.requiredColorSpace()) {
            throw new IllegalArgumentException(PbrTextureRole.NORMAL
                    + " requires " + PbrTextureRole.NORMAL.requiredColorSpace()
                    + " texture, got " + texture.colorSpace());
        }
        builder.texture(CLEARCOAT_NORMAL_UNIT, "uClearcoatNormalMap", texture, sampler);
    }

    private static void bindWithSampler(Material.Builder builder, int unit, String samplerName,
                                        PbrTextureRole role,
                                        Map<PbrTextureRole, PbrTextureBinding> supplied,
                                        PbrFallbackTextures fallbacks) {
        PbrTextureBinding binding = supplied.get(role);
        Texture2D texture = binding == null ? fallbacks.texture(role) : binding.texture();
        Sampler sampler = binding == null ? fallbacks.sampler() : binding.sampler();
        if (texture.colorSpace() != role.requiredColorSpace()) {
            throw new IllegalArgumentException(role + " requires " + role.requiredColorSpace()
                    + " texture, got " + texture.colorSpace());
        }
        builder.texture(unit, samplerName, texture, sampler);
    }
}
