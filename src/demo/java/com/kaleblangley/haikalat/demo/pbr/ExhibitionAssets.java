package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.material.AnisotropicSettings;
import com.kaleblangley.haikalat.core.material.ClearcoatSettings;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.RefractionSettings;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrTextureBinding;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ReflectionProbeSet;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ReflectionProbeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared GL resource owner for the exhibition: one PBR program set, one
 * environment, the neutral studio geometry and the material factories every
 * station draws from.
 *
 * <p>Materials created through the factory methods are owned here and closed
 * with the assets; the shared meshes are never closed by station views.</p>
 */
final class ExhibitionAssets implements AutoCloseable {
    private static final String ENVIRONMENT_HDR = "/environments/pbr/studio-small.hdr";
    private static final String REFLECTION_PROBE_HDR =
            "/environments/pbr/reflection-probe-gallery.hdr";

    private final PbrEnvironment environment;
    private final PbrFallbackTextures fallbacks;
    private final ShaderProgram pbrShader;
    private final ShaderProgram refractionShader;
    private final List<Material> materials = new ArrayList<>();
    private final VfxRenderer vfxRenderer = new VfxRenderer();
    private final RenderDevice device;
    private final List<Texture2D> textures = new ArrayList<>();
    private final List<Sampler> samplers = new ArrayList<>();
    private final List<com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSystem>
            particleSystems = new ArrayList<>();

    private Map<PbrTextureRole, PbrTextureBinding> brickParallaxBindings;
    private ReflectionProbeSet reflectionProbes;
    private Mesh subsurfaceSample;

    final Mesh sphere;
    final Mesh cube;
    final Mesh quad;
    final Mesh groundQuad;
    final Material groundMaterial;
    final Material wallMaterial;
    final Material neutralMaterial;
    final Material accentMaterial;
    final Material darkMaterial;

    private ExhibitionAssets(RenderDevice device, PbrEnvironment environment,
                             PbrFallbackTextures fallbacks,
                             ShaderProgram pbrShader, ShaderProgram refractionShader,
                             Mesh sphere, Mesh cube, Mesh quad, Mesh groundQuad) {
        this.device = device;
        this.environment = environment;
        this.fallbacks = fallbacks;
        this.pbrShader = pbrShader;
        this.refractionShader = refractionShader;
        this.sphere = sphere;
        this.cube = cube;
        this.quad = quad;
        this.groundQuad = groundQuad;
        this.groundMaterial = pbr(new PbrMaterialProperties(new Vector4f(0.30f, 0.31f, 0.33f, 1.0f),
                0.0f, 0.82f, 1.0f, 1.0f, new Vector3f(), Map.of()));
        this.wallMaterial = pbr(new PbrMaterialProperties(new Vector4f(0.42f, 0.43f, 0.46f, 1.0f),
                0.0f, 0.9f, 1.0f, 1.0f, new Vector3f(), Map.of()));
        this.neutralMaterial = pbr(new PbrMaterialProperties(new Vector4f(0.62f, 0.64f, 0.68f, 1.0f),
                0.05f, 0.42f, 1.0f, 1.0f, new Vector3f(), Map.of()));
        this.accentMaterial = pbr(new PbrMaterialProperties(new Vector4f(0.16f, 0.48f, 0.86f, 1.0f),
                0.15f, 0.3f, 1.0f, 1.0f, new Vector3f(), Map.of()));
        this.darkMaterial = pbr(new PbrMaterialProperties(new Vector4f(0.08f, 0.09f, 0.11f, 1.0f),
                0.0f, 0.55f, 1.0f, 1.0f, new Vector3f(), Map.of()));
    }

    static ExhibitionAssets create(RenderDevice device, boolean testQuality) {
        PbrFallbackTextures fallbacks = new PbrFallbackTextures();
        ShaderProgram pbrShader = null;
        ShaderProgram refractionShader = null;
        PbrEnvironment environment = null;
        Mesh sphere = null;
        Mesh cube = null;
        Mesh quad = null;
        Mesh ground = null;
        try {
            environment = PbrEnvironmentLoader.load(device, ExhibitionAssets.class, ENVIRONMENT_HDR,
                    testQuality ? PbrEnvironmentSettings.testQuality()
                            : PbrEnvironmentSettings.defaultQuality());
            pbrShader = ShaderProgram.fromResource(ExhibitionAssets.class,
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
            refractionShader = ShaderProgram.fromResource(ExhibitionAssets.class,
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/refraction-forward.frag");
            sphere = Mesh.from(PbrSphereMesh.create(32, 20));
            cube = ClusteredDemoSceneFactory.box("exhibition-cube");
            quad = ClusteredDemoSceneFactory.plane("exhibition-quad", 2.0f, 2.0f);
            ground = ClusteredDemoSceneFactory.plane("exhibition-ground", 30.0f, 30.0f);
            return new ExhibitionAssets(device, environment, fallbacks, pbrShader, refractionShader,
                    sphere, cube, quad, ground);
        } catch (RuntimeException | Error failure) {
            RuntimeException cleanupFailure = null;
            cleanupFailure = closeQuietly(ground, cleanupFailure);
            cleanupFailure = closeQuietly(quad, cleanupFailure);
            cleanupFailure = closeQuietly(cube, cleanupFailure);
            cleanupFailure = closeQuietly(sphere, cleanupFailure);
            cleanupFailure = closeQuietly(refractionShader, cleanupFailure);
            cleanupFailure = closeQuietly(pbrShader, cleanupFailure);
            cleanupFailure = closeQuietly(environment, cleanupFailure);
            cleanupFailure = closeQuietly(fallbacks, cleanupFailure);
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    PbrEnvironment environment() {
        return environment;
    }

    Mesh subsurfaceSample() {
        if (subsurfaceSample == null) {
            subsurfaceSample = Mesh.from(PbrSphereMesh.create(96, 64));
        }
        return subsurfaceSample;
    }

    ShaderProgram pbrShader() {
        return pbrShader;
    }

    ShaderProgram refractionShader() {
        return refractionShader;
    }

    PbrFallbackTextures fallbacks() {
        return fallbacks;
    }

    /** Shared VFX draw/texture owner used by the VFX stations and the decal pass. */
    VfxRenderer vfxRenderer() {
        return vfxRenderer;
    }

    /**
     * Bounded POM/parallax texture set: a procedural brick height map authored
     * in code plus the existing licensed wall colour map.  Created once and
     * owned by the assets.
     */
    Map<PbrTextureRole, PbrTextureBinding> brickParallaxBindings() {
        if (brickParallaxBindings == null) {
            Texture2D height = trackTexture(proceduralBrickHeight(256, 256));
            Texture2D baseColor = trackTexture(Texture2D.fromResource(
                    ExhibitionAssets.class, "/textures/learnopengl/wall.png",
                    com.kaleblangley.haikalat.backend.texture.TextureColorSpace.SRGB));
            Sampler sampler = trackSampler(Sampler.linearRepeat());
            brickParallaxBindings = Map.of(
                    PbrTextureRole.HEIGHT, new PbrTextureBinding(height, sampler),
                    PbrTextureRole.BASE_COLOR, new PbrTextureBinding(baseColor, sampler));
        }
        return brickParallaxBindings;
    }

    /**
     * Two static prefiltered local reflection probes (left warm, right cool)
     * loaded from a directional gallery HDR. Created once; no per-frame capture.
     */
    ReflectionProbeSet reflectionProbes() {
        if (reflectionProbes == null) {
            List<ReflectionProbeSettings> probes = List.of(
                    ReflectionProbeSettings.box("exhibition-warm",
                            new Vector3f(-2.1f, 1.2f, -1.8f), new Vector3f(2.2f, 2.0f, 2.2f),
                            1.2f, ReflectionProbeSettings.Quality.MEDIUM, 1.35f,
                            0.0f, REFLECTION_PROBE_HDR),
                    ReflectionProbeSettings.box("exhibition-cool",
                            new Vector3f(2.1f, 1.2f, -1.8f), new Vector3f(2.2f, 2.0f, 2.2f),
                            1.2f, ReflectionProbeSettings.Quality.MEDIUM, 1.35f,
                            (float) Math.PI, REFLECTION_PROBE_HDR));
            reflectionProbes = ReflectionProbeSet.load(device, ExhibitionAssets.class, probes);
        }
        return reflectionProbes;
    }

    /**
     * Creates and owns one GPU particle system for the given sorting/collision
     * generation.  Distinct stations request distinct owners; assets close them
     * with the rest of the shared resources.
     */
    com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSystem gpuParticles(
            com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSettings settings,
            com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings sorting,
            com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleCollisionSettings collision) {
        var system = new com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSystem(
                settings, sorting, collision);
        particleSystems.add(system);
        return system;
    }

    private static Texture2D proceduralBrickHeight(int width, int height) {        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height);
        int brickWidth = width / 4;
        int brickHeight = height / 8;
        int mortar = Math.max(2, width / 48);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int row = y / brickHeight;
                int offset = (row % 2 == 0) ? 0 : brickWidth / 2;
                int localX = Math.floorMod(x + offset, brickWidth);
                int localY = y % brickHeight;
                boolean groove = localX < mortar || localY < mortar;
                int value;
                if (groove) {
                    value = 18;
                } else {
                    // Slight per-brick height variation plus a shallow crown.
                    int brickId = Math.floorMod(x + offset, width) / Math.max(1, brickWidth)
                            + row * 5;
                    int jitter = Math.floorMod(brickId * 37, 40);
                    int crownX = Math.min(localX - mortar, brickWidth - localX) * 4;
                    int crownY = Math.min(localY - mortar, brickHeight - localY) * 4;
                    value = Math.min(255, 140 + jitter + crownX + crownY);
                }
                pixels.put((byte) value);
            }
        }
        pixels.flip();
        return Texture2D.fromR8(width, height, pixels);
    }

    private Texture2D trackTexture(Texture2D texture) {
        textures.add(texture);
        return texture;
    }

    private Sampler trackSampler(Sampler sampler) {
        samplers.add(sampler);
        return sampler;
    }

    /** Registers a demo-owned texture with this asset bag (closed with it). */
    Texture2D ownTexture(Texture2D texture) {
        return trackTexture(texture);
    }

    Material pbr(PbrMaterialProperties properties) {
        return track(PbrMaterials.create(pbrShader, properties, Map.of(), fallbacks));
    }

    Material alphaPbr(PbrMaterialProperties properties) {
        return track(PbrMaterials.createWithBindings(pbrShader, properties, Map.of(), fallbacks,
                CullMode.BACK, false, false, 0f, BlendMode.ALPHA));
    }

    Material pbr(PbrMaterialProperties properties,
                 Map<PbrTextureRole, Texture2D> supplied) {
        return track(PbrMaterials.create(pbrShader, properties, supplied, fallbacks));
    }

    Material clearcoat(PbrMaterialProperties properties, Map<PbrTextureRole, PbrTextureBinding> supplied,
                       ClearcoatSettings clearcoat) {
        return track(PbrMaterials.createWithClearcoat(pbrShader, properties, supplied, fallbacks,
                clearcoat, null));
    }

    Material anisotropic(PbrMaterialProperties properties,
                         Map<PbrTextureRole, PbrTextureBinding> supplied,
                         AnisotropicSettings anisotropic) {
        return track(PbrMaterials.createWithAnisotropy(pbrShader, properties, supplied, fallbacks,
                anisotropic));
    }

    Material glass(PbrMaterialProperties properties, RefractionSettings refraction) {
        return track(PbrMaterials.createGlass(refractionShader, properties, Map.of(), fallbacks,
                refraction));
    }

    private Material track(Material material) {
        materials.add(material);
        return material;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (int index = materials.size() - 1; index >= 0; index--) {
            try {
                materials.get(index).close();
            } catch (RuntimeException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        materials.clear();
        for (int index = particleSystems.size() - 1; index >= 0; index--) {
            failure = closeQuietly(particleSystems.get(index), failure);
        }
        failure = closeQuietly(reflectionProbes, failure);
        for (int index = textures.size() - 1; index >= 0; index--) {
            failure = closeQuietly(textures.get(index), failure);
        }
        for (int index = samplers.size() - 1; index >= 0; index--) {
            failure = closeQuietly(samplers.get(index), failure);
        }
        failure = closeQuietly(vfxRenderer, failure);
        failure = closeQuietly(groundQuad, failure);
        failure = closeQuietly(quad, failure);
        failure = closeQuietly(cube, failure);
        failure = closeQuietly(subsurfaceSample, failure);
        failure = closeQuietly(sphere, failure);
        failure = closeQuietly(refractionShader, failure);
        failure = closeQuietly(pbrShader, failure);
        failure = closeQuietly(environment, failure);
        failure = closeQuietly(fallbacks, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException append(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static RuntimeException closeQuietly(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
            return failure;
        } catch (RuntimeException closeFailure) {
            return append(failure, closeFailure);
        } catch (Exception closeFailure) {
            return append(failure, new IllegalStateException(closeFailure));
        }
    }

}
