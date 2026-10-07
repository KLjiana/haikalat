package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowLightHints;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

/**
 * Deterministic procedural scenes shared by the clustered demo, benchmark and
 * quality runners.  One factory means every runner measures the same geometry,
 * materials and light placement.
 */
public final class ClusteredDemoSceneFactory {
    public enum Kind {
        LAB,
        TOWN,
        STRESS
    }

    /** Light distribution for the stress scene. */
    public enum StressMode {
        SPARSE,
        OVERLAP,
        CHURN,
        MOVING,
        COVERAGE
    }

    public record Request(Kind kind, int localLights, int spotLights, long seed,
                          StressMode stressMode, boolean grayModel) {
        public Request {
            kind = Objects.requireNonNull(kind, "kind");
            stressMode = Objects.requireNonNull(stressMode, "stressMode");
            if (localLights < 0 || localLights > 4096) {
                throw new IllegalArgumentException("localLights must be in [0, 4096]");
            }
            if (spotLights < 0 || spotLights > localLights) {
                throw new IllegalArgumentException("spotLights must be in [0, localLights]");
            }
        }

        public static Request lab(int localLights, int spotLights, long seed) {
            return new Request(Kind.LAB, localLights, spotLights, seed, StressMode.SPARSE, false);
        }

        public static Request town() {
            return town(false);
        }

        public static Request town(boolean grayModel) {
            return new Request(Kind.TOWN, 128, 24, 242L, StressMode.SPARSE, grayModel);
        }

        public static Request stress(int localLights, StressMode mode, long seed) {
            return new Request(Kind.STRESS, localLights, 0, seed, mode, false);
        }
    }

    /** Owns every generated material and mesh; close after the pipeline. */
    public static final class Bundle implements AutoCloseable {
        public final Scene scene;
        public final List<Material> materials;
        public final List<Mesh> meshes;
        public final int localLightCount;
        public final int spotLightCount;
        public final int shadowRequestCount;
        /** Scene light indices that the demo animates; -1 / 0 when none. */
        public final int torchLightStart;
        public final int torchLightCount;
        public final int skillLightStart;
        public final int skillLightCount;

        private Bundle(Scene scene, List<Material> materials, List<Mesh> meshes,
                       int localLightCount, int spotLightCount, int shadowRequestCount) {
            this(scene, materials, meshes, localLightCount, spotLightCount, shadowRequestCount,
                    -1, 0, -1, 0);
        }

        private Bundle(Scene scene, List<Material> materials, List<Mesh> meshes,
                       int localLightCount, int spotLightCount, int shadowRequestCount,
                       int torchLightStart, int torchLightCount,
                       int skillLightStart, int skillLightCount) {
            this.scene = scene;
            this.materials = List.copyOf(materials);
            this.meshes = List.copyOf(meshes);
            this.localLightCount = localLightCount;
            this.spotLightCount = spotLightCount;
            this.shadowRequestCount = shadowRequestCount;
            this.torchLightStart = torchLightStart;
            this.torchLightCount = torchLightCount;
            this.skillLightStart = skillLightStart;
            this.skillLightCount = skillLightCount;
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (Material material : materials) {
                try {
                    material.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            for (Mesh mesh : meshes) {
                try {
                    mesh.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private ClusteredDemoSceneFactory() {
    }

    public static Bundle create(Request request, ShaderProgram pbrShader,
                                PbrFallbackTextures fallbacks) {
        List<Material> materials = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        try {
            return switch (request.kind()) {
                case LAB -> createLab(request, pbrShader, fallbacks, materials, meshes);
                case TOWN -> createTown(pbrShader, fallbacks, materials, meshes,
                        request.grayModel(), false, false);
                case STRESS -> createStress(request, pbrShader, fallbacks, materials, meshes);
            };
        } catch (RuntimeException | Error failure) {
            closeQuietly(materials, meshes, failure);
            throw failure;
        }
    }

    /** Same town lights/materials, with only the first western shop opened as a tavern. */
    static Bundle createTavern(ShaderProgram pbrShader, PbrFallbackTextures fallbacks) {
        return createTownVariant(pbrShader, fallbacks, true, false);
    }

    /** Downward-facing street fixtures with an open aperture and an offset support. */
    static Bundle createVolumetricTown(ShaderProgram pbrShader, PbrFallbackTextures fallbacks) {
        return createTownVariant(pbrShader, fallbacks, false, true);
    }

    private static Bundle createTownVariant(ShaderProgram pbrShader, PbrFallbackTextures fallbacks,
                                            boolean openTavern, boolean openStreetLanterns) {
        List<Material> materials = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        try {
            return createTown(pbrShader, fallbacks, materials, meshes, false, openTavern, openStreetLanterns);
        } catch (RuntimeException | Error failure) {
            closeQuietly(materials, meshes, failure);
            throw failure;
        }
    }

    private static Bundle createLab(Request request, ShaderProgram pbrShader,
                                    PbrFallbackTextures fallbacks,
                                    List<Material> materials, List<Mesh> meshes) {
        Random random = new Random(request.seed());
        Camera camera = new Camera(new Vector3f(0.0f, 4.5f, 16.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -12.0f);
        Scene scene = new Scene(camera);

        Mesh ground = track(meshes, plane("lab-ground", 40.0f, 24.0f));
        Mesh wall = track(meshes, plane("lab-wall", 24.0f, 10.0f));
        Mesh box = track(meshes, box("lab-box"));
        Mesh sphere = track(meshes, Mesh.from(PbrSphereMesh.create(40, 24)));

        Material floorMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.45f, 0.45f, 0.48f, 1.0f), 0.0f, 0.75f, 0.0f));
        Material wallMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.85f, 0.85f, 0.88f, 1.0f), 0.0f, 0.85f, 0.0f));
        Material markerMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.02f, 0.02f, 0.02f, 1.0f), 0.0f, 0.5f, 0.0f));
        Material glassMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.4f, 0.6f, 0.8f, 1.0f), 0.0f, 0.08f, 0.0f));

        scene.add(MeshRenderer.of(ground, floorMaterial,
                Transform.identity().rotationRadians((float) (-Math.PI * 0.5), 0.0f, 0.0f)
                        .position(0.0f, 0.0f, -2.0f))
                .withoutShadows());
        scene.add(MeshRenderer.of(wall, wallMaterial,
                Transform.identity().position(0.0f, 5.0f, -10.0f))
                .withoutShadows());

        float[] roughness = {0.15f, 0.5f, 0.9f};
        float[] metallic = {0.0f, 1.0f};
        int object = 0;
        for (float metal : metallic) {
            for (float rough : roughness) {
                Material material = track(materials, material(pbrShader, fallbacks,
                        new Vector4f(0.7f, 0.68f, 0.62f, 1.0f), metal, rough, 0.0f));
                float x = -5.0f + object * 2.0f;
                scene.add(MeshRenderer.of(sphere, material,
                        Transform.identity().position(x, 1.0f, -1.0f)));
                object++;
            }
        }
        scene.add(MeshRenderer.of(box, track(materials, material(pbrShader, fallbacks,
                        new Vector4f(0.75f, 0.45f, 0.25f, 1.0f), 0.0f, 0.35f, 0.0f)),
                Transform.identity().position(6.0f, 1.0f, -1.0f)));

        // Front alpha-blended panel with an opaque wall behind it: transparent
        // pixels must resolve their own cluster Z slice.
        scene.add(MeshRenderer.of(track(meshes, plane("lab-glass", 6.0f, 5.0f)), glassMaterial,
                Transform.identity().position(0.0f, 2.5f, 2.0f))
                .withoutShadows());

        scene.addLight(SceneLight.directional(new Vector3f(-0.4f, -1.0f, -0.3f),
                new Vector3f(0.12f, 0.14f, 0.2f), 0.35f));

        int spotCount = Math.min(request.spotLights(), request.localLights());
        int pointCount = request.localLights() - spotCount;
        float[] pointX = new float[pointCount];
        float[] pointZ = new float[pointCount];
        for (int index = 0; index < pointCount; index++) {
            // The 9th point light gets an isolated object so >8 lights are
            // observable, not just reported.
            if (index == 8) {
                pointX[index] = -2.0f;
                pointZ[index] = 4.0f;
            } else {
                pointX[index] = -6.0f + (index % 12) * 1.1f;
                pointZ[index] = -0.5f - (index / 12) * 1.4f;
            }
            Vector3f color = markerColor(random, index);
            scene.addLight(SceneLight.point(
                    new Vector3f(pointX[index], 1.6f, pointZ[index]),
                    color, 6.0f, 4.5f));
            scene.add(MeshRenderer.of(sphere, markerMaterial,
                    Transform.identity().position(pointX[index], 1.6f, pointZ[index])
                            .scale(0.09f))
                    .withoutShadows());
        }
        for (int index = 0; index < spotCount; index++) {
            float x = -6.0f + (index % 6) * 2.4f;
            float z = 4.0f + (index / 6) * 2.0f;
            Vector3f color = markerColor(random,index + 97);
            scene.addLight(SceneLight.spot(new Vector3f(x, 6.0f, z),
                    new Vector3f(0.0f, -1.0f, -0.25f), color, 25.0f, 12.0f, 0.35f, 0.75f));
        }
        return new Bundle(scene, materials, meshes, request.localLights(), spotCount, 0);
    }

    private static Bundle createTown(ShaderProgram pbrShader, PbrFallbackTextures fallbacks,
                                     List<Material> materials, List<Mesh> meshes,
                                     boolean grayModel, boolean openTavern, boolean openStreetLanterns) {
        Camera camera = new Camera(new Vector3f(30.0f, 24.0f, 42.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), -125.0f, -25.0f);
        Scene scene = new Scene(camera);
        ClusteredTownModel.Anchors anchors = ClusteredTownModel.build(
                scene, pbrShader, fallbacks, materials, meshes, grayModel, openTavern, openStreetLanterns);
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.45f, -0.8f, -0.3f),
                new Vector3f(0.46f, 0.61f, 0.88f), 1.1f));
        // Anchors come from the same transforms as the geometry: lamps cannot
        // drift inside walls when a building moves or rotates.
        for (Vector3f p : anchors.windows()) {
            scene.addLight(SceneLight.point(p, new Vector3f(1.0f, .64f, .29f), 2.8f, 4.0f));
        }
        for (int i = 0; i < anchors.lamps().size(); i++) {
            Vector3f p = anchors.lamps().get(i);
            Vector3f color = new Vector3f(1.0f, .72f, .38f);
            if (i < 4) {
                scene.addLight(SceneLight.shadowedSpot(p, new Vector3f(0,-1,0), color,
                        32f, 9f, .4f, .95f), ShadowLightHints.priority(8 - i));
            } else {
                scene.addLight(SceneLight.spot(p, new Vector3f(0,-1,0), color, 32f, 9f, .4f, .95f));
            }
        }
        for (Vector3f p : anchors.shops()) {
            scene.addLight(SceneLight.point(p, new Vector3f(1f, .66f, .35f), 2f, 4f));
        }
        int torchStart = scene.lights().size();
        for (int i = 0; i < anchors.torches().size(); i++) {
            Vector3f p = anchors.torches().get(i);
            Vector3f color = new Vector3f(1f, .5f, .17f);
            float intensity = openTavern && i < 2 ? 18.0f : 3.0f;
            float range = openTavern && i < 2 ? 6.0f : 4.0f;
            if (i < 2) scene.addLight(SceneLight.shadowedPoint(p, color, intensity, range),
                    ShadowLightHints.priority(6 - i));
            else scene.addLight(SceneLight.point(p, color, intensity, range));
        }
        int accentStart = scene.lights().size();
        for (Vector3f p : anchors.accents()) {
            scene.addLight(SceneLight.point(p, new Vector3f(.18f, .55f, .85f), .32f, 2.5f));
        }
        return new Bundle(scene, materials, meshes, scene.lights().size() - 1,
                anchors.lamps().size(), 6, torchStart, anchors.torches().size(),
                accentStart, anchors.accents().size());
    }
    private static Bundle createStress(Request request, ShaderProgram pbrShader,
                                       PbrFallbackTextures fallbacks,
                                       List<Material> materials, List<Mesh> meshes) {
        Random random = new Random(request.seed());
        Camera camera = new Camera(new Vector3f(0.0f, 18.0f, 42.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -20.0f);
        Scene scene = new Scene(camera);
        Mesh ground = track(meshes, plane("stress-ground", 110.0f, 110.0f));
        Mesh box = track(meshes, box("stress-box"));
        Material groundMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.35f, 0.36f, 0.4f, 1.0f), 0.0f, 0.8f, 0.0f));
        Material blockMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.6f, 0.6f, 0.65f, 1.0f), 0.0f, 0.6f, 0.0f));
        scene.add(MeshRenderer.of(ground, groundMaterial,
                Transform.identity().rotationRadians((float) (-Math.PI * 0.5), 0.0f, 0.0f))
                .withoutShadows());
        for (int index = 0; index < 24; index++) {
            float x = (random.nextFloat() - 0.5f) * 70.0f;
            float z = (random.nextFloat() - 0.5f) * 70.0f;
            scene.add(MeshRenderer.of(box, blockMaterial,
                    Transform.identity().position(x, 3.0f, z)
                            .scale(2.0f + random.nextFloat() * 4.0f, 6.0f, 2.0f)));
        }
        scene.addLight(SceneLight.directional(new Vector3f(0.2f, -1.0f, 0.2f),
                new Vector3f(0.2f, 0.25f, 0.35f), 0.6f));

        int count = request.localLights();
        for (int index = 0; index < count; index++) {
            Vector3f position;
            float range;
            if (request.stressMode() == StressMode.OVERLAP) {
                position = new Vector3f(0.0f, 2.0f, 0.0f);
                range = 30.0f;
            } else if (request.stressMode() == StressMode.COVERAGE) {
                position = new Vector3f((index % 16 - 7.5f) * 3.0f, 2.0f,
                        (index / 16 - 1.5f) * 12.0f);
                range = index % 32 == 0 ? 40.0f : 2.5f;
            } else {
                position = new Vector3f((random.nextFloat() - 0.5f) * 60.0f,
                        2.0f + random.nextFloat() * 3.0f,
                        (random.nextFloat() - 0.5f) * 60.0f);
                range = StressMode.MOVING == request.stressMode()
                        ? 8.0f : 4.0f + random.nextFloat() * 5.0f;
            }
            Vector3f color = markerColor(random, index);
            scene.addLight(SceneLight.point(position, color, 5.0f, range));
        }
        return new Bundle(scene, materials, meshes, count, 0, 0);
    }

    private static Vector3f markerColor(Random random, int index) {
        float hue = (index * 0.618034f) % 1.0f;
        float r = 0.5f + 0.5f * (float) Math.sin(hue * Math.PI * 2.0f);
        float g = 0.5f + 0.5f * (float) Math.sin((hue + 0.33f) * Math.PI * 2.0f);
        float b = 0.5f + 0.5f * (float) Math.sin((hue + 0.66f) * Math.PI * 2.0f);
        return new Vector3f(0.35f + 0.65f * r, 0.35f + 0.65f * g, 0.35f + 0.65f * b);
    }

    private static Material material(ShaderProgram shader, PbrFallbackTextures fallbacks,
                                     Vector4f baseColor, float metallic, float roughness,
                                     float emissive) {
        Vector3f emissiveFactor = emissive <= 0.0f ? new Vector3f()
                : new Vector3f(baseColor.x, baseColor.y, baseColor.z).mul(emissive);
        return PbrMaterials.create(shader,
                new PbrMaterialProperties(baseColor, metallic, roughness, 1.0f, 1.0f,
                        emissiveFactor, Map.of()),
                Map.of(), fallbacks);
    }

    static Mesh plane(String name, float width, float height) {
        MeshData data = BuiltinMeshData.texturedQuad(name);
        float[] source = data.vertices().clone();
        int stride = 8;
        for (int index = 0; index < source.length; index += stride) {
            source[index] *= width * 0.5f;
            source[index + 2] *= height * 0.5f;
        }
        return Mesh.from(TangentGenerator.generate(
                MeshData.indexed(name, source, data.indices(), data.layout())).mesh());
    }

    private static final VertexLayout BOX_LAYOUT = VertexLayout.interleaved(
            8 * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0)
                    .semantic(VertexSemantic.POSITION).build(),
            VertexAttribute.builder().index(1).size(2).type(GL_FLOAT)
                    .offsetBytes(3L * Float.BYTES).semantic(VertexSemantic.TEXCOORD_0).build(),
            VertexAttribute.builder().index(2).size(3).type(GL_FLOAT)
                    .offsetBytes(5L * Float.BYTES).semantic(VertexSemantic.NORMAL).build());

    static Mesh box(String name) {
        float[][] normals = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}
        };
        float[][][] corners = {
                {{-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}},
                {{1, -1, -1}, {-1, -1, -1}, {-1, 1, -1}, {1, 1, -1}},
                {{1, -1, 1}, {1, -1, -1}, {1, 1, -1}, {1, 1, 1}},
                {{-1, -1, -1}, {-1, -1, 1}, {-1, 1, 1}, {-1, 1, -1}},
                {{-1, 1, 1}, {1, 1, 1}, {1, 1, -1}, {-1, 1, -1}},
                {{-1, -1, -1}, {1, -1, -1}, {1, -1, 1}, {-1, -1, 1}}
        };
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        float[] vertices = new float[6 * 6 * 8];
        int cursor = 0;
        for (int face = 0; face < 6; face++) {
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int vertex : order) {
                vertices[cursor++] = corners[face][vertex][0];
                vertices[cursor++] = corners[face][vertex][1];
                vertices[cursor++] = corners[face][vertex][2];
                vertices[cursor++] = uv[vertex * 2];
                vertices[cursor++] = uv[vertex * 2 + 1];
                vertices[cursor++] = normals[face][0];
                vertices[cursor++] = normals[face][1];
                vertices[cursor++] = normals[face][2];
            }
        }
        return Mesh.from(TangentGenerator.generate(
                MeshData.of(name, vertices, BOX_LAYOUT)).mesh());
    }

    private static Material track(List<Material> materials, Material material) {
        materials.add(material);
        return material;
    }

    private static Mesh track(List<Mesh> meshes, Mesh mesh) {
        meshes.add(mesh);
        return mesh;
    }

    private static void closeQuietly(List<Material> materials, List<Mesh> meshes,
                                     Throwable failure) {
        for (Material material : materials) {
            try {
                material.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        for (Mesh mesh : meshes) {
            try {
                mesh.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }
}
