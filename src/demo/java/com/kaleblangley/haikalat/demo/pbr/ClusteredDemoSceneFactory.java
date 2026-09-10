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
                          StressMode stressMode) {
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
            return new Request(Kind.LAB, localLights, spotLights, seed, StressMode.SPARSE);
        }

        public static Request town() {
            return new Request(Kind.TOWN, 128, 24, 242L, StressMode.SPARSE);
        }

        public static Request stress(int localLights, StressMode mode, long seed) {
            return new Request(Kind.STRESS, localLights, 0, seed, mode);
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
                case TOWN -> createTown(pbrShader, fallbacks, materials, meshes);
                case STRESS -> createStress(request, pbrShader, fallbacks, materials, meshes);
            };
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
        scene.add(MeshRenderer.of(plane("lab-glass", 6.0f, 5.0f), glassMaterial,
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
                                     List<Material> materials, List<Mesh> meshes) {
        Random random = new Random(242L);
        Camera camera = new Camera(new Vector3f(7.5f, 2.8f, 7.5f),
                new Vector3f(0.0f, 1.0f, 0.0f), -135.0f, -2.0f);
        Scene scene = new Scene(camera);

        Mesh street = track(meshes, plane("town-street", 110.0f, 110.0f));
        Mesh box = track(meshes, box("town-box"));
        Mesh smallBox = track(meshes, box("town-small-box"));
        Mesh sphere = track(meshes, Mesh.from(PbrSphereMesh.create(24, 16)));

        Material streetMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.05f, 0.055f, 0.075f, 1.0f), 0.0f, 0.35f, 0.0f));
        Material plazaMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.12f, 0.12f, 0.125f, 1.0f), 0.0f, 0.6f, 0.0f));
        Material wallMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.22f, 0.19f, 0.16f, 1.0f), 0.0f, 0.8f, 0.0f));
        Material stoneMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.26f, 0.27f, 0.30f, 1.0f), 0.0f, 0.75f, 0.0f));
        Material woodMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.28f, 0.18f, 0.11f, 1.0f), 0.0f, 0.7f, 0.0f));
        Material foliageMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.10f, 0.22f, 0.12f, 1.0f), 0.0f, 0.9f, 0.0f));
        Material lampMaterial = track(materials, material(pbrShader, fallbacks,
                new Vector4f(0.09f, 0.09f, 0.10f, 1.0f), 0.0f, 0.45f, 0.0f));
        Material windowGlow = track(materials, emissiveMaterial(pbrShader, fallbacks,
                new Vector3f(1.0f, 0.72f, 0.38f), 2.0f, 0.5f));
        Material lampGlow = track(materials, emissiveMaterial(pbrShader, fallbacks,
                new Vector3f(1.0f, 0.86f, 0.62f), 3.0f, 0.4f));
        Material skillGlow = track(materials, emissiveMaterial(pbrShader, fallbacks,
                new Vector3f(0.35f, 0.65f, 1.0f), 1.2f, 0.25f));
        Material torchGlow = track(materials, emissiveMaterial(pbrShader, fallbacks,
                new Vector3f(1.0f, 0.45f, 0.12f), 2.4f, 0.4f));
        scene.add(MeshRenderer.of(street, streetMaterial,
                Transform.identity().rotationRadians((float) (-Math.PI * 0.5), 0.0f, 0.0f))
                .withoutShadows());
        scene.add(MeshRenderer.of(plane("town-plaza", 30.0f, 30.0f), plazaMaterial,
                Transform.identity().rotationRadians((float) (-Math.PI * 0.5), 0.0f, 0.0f)
                        .position(0.0f, 0.02f, 0.0f)).withoutShadows());

        // Four streets on an 80x80 grid with two-storey shops on each block.
        int windowIndex = 0;
        for (int blockX = -1; blockX <= 1; blockX++) {
            for (int blockZ = -1; blockZ <= 1; blockZ++) {
                if (blockX == 0 && blockZ == 0) continue;
                float centerX = blockX * 22.0f;
                float centerZ = blockZ * 22.0f;
                float height = 7.0f + (((blockX * 3 + blockZ) & 1) == 0 ? 0.0f : 3.5f);
                scene.add(MeshRenderer.of(box, wallMaterial,
                        Transform.identity().position(centerX, height * 0.5f, centerZ)
                                .scale(14.0f, height, 14.0f)));
                scene.add(MeshRenderer.of(box, stoneMaterial,
                        Transform.identity().position(centerX, height + 1.2f, centerZ)
                                .scale(12.6f, 1.2f, 12.6f)));
                // Warm windows on every facade, two floors.
                for (int column = -1; column <= 1; column++) {
                    for (int floor = 0; floor < 2; floor++) {
                        float y = floor == 0 ? 2.6f : height - 2.2f;
                        for (int side = 0; side < 4; side++) {
                            Transform transform = switch (side) {
                                case 0 -> Transform.identity()
                                        .position(centerX + column * 3.6f, y, centerZ - 7.05f)
                                        .scale(1.7f, 1.3f, 0.15f);
                                case 1 -> Transform.identity()
                                        .position(centerX + column * 3.6f, y, centerZ + 7.05f)
                                        .scale(1.7f, 1.3f, 0.15f);
                                case 2 -> Transform.identity()
                                        .position(centerX - 7.05f, y, centerZ + column * 3.6f)
                                        .scale(0.15f, 1.3f, 1.7f);
                                default -> Transform.identity()
                                        .position(centerX + 7.05f, y, centerZ + column * 3.6f)
                                        .scale(0.15f, 1.3f, 1.7f);
                            };
                            scene.add(MeshRenderer.of(smallBox, windowGlow, transform)
                                    .withoutShadows());
                            windowIndex++;
                        }
                    }
                }
            }
        }
        // Street lamps along both boulevard rows, with glowing heads.
        for (int row = -1; row <= 1; row += 2) {
            for (int index = 0; index < 8; index++) {
                float x = -16.8f + index * 4.8f;
                scene.add(MeshRenderer.of(box, lampMaterial,
                        Transform.identity().position(x, 2.9f, row * 9.0f)
                                .scale(0.28f, 5.8f, 0.28f))
                        .withoutShadows());
                scene.add(MeshRenderer.of(sphere, lampGlow,
                        Transform.identity().position(x, 5.9f, row * 9.0f)
                                .scale(0.42f, 0.5f, 0.42f))
                        .withoutShadows());
            }
        }
        // Trees with trunks around the plaza.
        for (int index = 0; index < 10; index++) {
            float angle = index / 10.0f * (float) Math.PI * 2.0f;
            float x = (float) Math.cos(angle) * 16.0f;
            float z = (float) Math.sin(angle) * 16.0f;
            scene.add(MeshRenderer.of(box, woodMaterial,
                    Transform.identity().position(x, 1.4f, z).scale(0.35f, 2.8f, 0.35f)));
            scene.add(MeshRenderer.of(sphere, foliageMaterial,
                    Transform.identity().position(x, 3.4f, z).scale(1.7f, 1.5f, 1.7f)));
            scene.add(MeshRenderer.of(sphere, foliageMaterial,
                    Transform.identity().position(x, 4.3f, z).scale(1.1f, 1.1f, 1.1f)));
        }
        // Fountain and benches in the plaza.
        scene.add(MeshRenderer.of(sphere, stoneMaterial,
                Transform.identity().position(0.0f, 0.7f, 0.0f).scale(3.0f, 0.9f, 3.0f)));
        scene.add(MeshRenderer.of(box, stoneMaterial,
                Transform.identity().position(0.0f, 1.1f, 0.0f).scale(1.4f, 0.5f, 1.4f)));
        scene.add(MeshRenderer.of(box, stoneMaterial,
                Transform.identity().position(0.0f, 1.9f, 0.0f).scale(0.4f, 1.4f, 0.4f)));
        scene.add(MeshRenderer.of(sphere, skillGlow,
                Transform.identity().position(0.0f, 2.9f, 0.0f).scale(0.32f))
                .withoutShadows());
        for (int index = 0; index < 6; index++) {
            float angle = index / 6.0f * (float) Math.PI * 2.0f;
            float x = (float) Math.cos(angle) * 7.5f;
            float z = (float) Math.sin(angle) * 7.5f;
            scene.add(MeshRenderer.of(box, woodMaterial,
                    Transform.identity().position(x, 0.45f, z)
                            .rotationRadians(0.0f, -angle, 0.0f)
                            .scale(2.2f, 0.25f, 0.6f)));
            scene.add(MeshRenderer.of(box, woodMaterial,
                    Transform.identity().position(x, 0.22f, z)
                            .rotationRadians(0.0f, -angle, 0.0f)
                            .scale(0.25f, 0.45f, 0.5f)));
        }
        // Arch over the south entrance.
        scene.add(MeshRenderer.of(box, stoneMaterial,
                Transform.identity().position(-5.5f, 3.5f, 18.0f).scale(1.2f, 7.0f, 1.2f)));
        scene.add(MeshRenderer.of(box, stoneMaterial,
                Transform.identity().position(5.5f, 3.5f, 18.0f).scale(1.2f, 7.0f, 1.2f)));
        scene.add(MeshRenderer.of(box, stoneMaterial,
                Transform.identity().position(0.0f, 7.4f, 18.0f).scale(12.2f, 1.1f, 1.4f)));

        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(0.25f, -1.0f, 0.3f),
                new Vector3f(0.16f, 0.2f, 0.38f), 0.5f));

        // 128 local lights: 48 window lights, 16 street lamps, 16 shop lights,
        // 16 torches, 32 coloured skill lights in the plaza.
        int windowLights = 48;
        int lampLights = 16;
        int shopLights = 16;
        int torchLights = 16;
        int shadowRequests = 0;
        for (int index = 0; index < windowLights; index++) {
            int block = index % 8;
            float x = -36.0f + block * 10.0f;
            float z = index / 8 % 2 == 0 ? -8.0f : 8.0f;
            scene.addLight(SceneLight.point(new Vector3f(x, 3.4f, z),
                    new Vector3f(1.0f, 0.72f, 0.4f), 6.5f, 7.5f));
        }
        for (int index = 0; index < lampLights; index++) {
            float x = -16.8f + (index % 8) * 4.8f;
            float z = index < 8 ? -9.0f : 9.0f;
            Vector3f position = new Vector3f(x, 5.9f, z);
            Vector3f direction = new Vector3f(0.0f, -1.0f, 0.0f);
            Vector3f color = new Vector3f(1.0f, 0.85f, 0.6f);
            if (index < 4) {
                // Only a subset wins a shadow slot; the rest still light the
                // street through the unified light table.
                scene.addLight(SceneLight.shadowedSpot(position, direction, color,
                        45.0f, 13.0f, 0.25f, 0.7f), ShadowLightHints.priority(8 - index));
                shadowRequests++;
            } else {
                scene.addLight(SceneLight.spot(position, direction, color,
                        45.0f, 13.0f, 0.25f, 0.7f));
            }
        }
        for (int index = 0; index < shopLights; index++) {
            float blockX = (index % 4 - 1.5f) * 22.0f;
            float blockZ = (index / 4 % 2 == 0 ? -1.0f : 1.0f) * 22.0f;
            scene.addLight(SceneLight.point(new Vector3f(blockX, 2.2f, blockZ),
                    new Vector3f(0.6f, 0.9f, 1.0f), 3.5f, 6.0f));
        }
        for (int index = 0; index < torchLights; index++) {
            float angle = index / (float) torchLights * (float) Math.PI * 2.0f;
            Vector3f position = new Vector3f((float) Math.cos(angle) * 24.0f, 1.8f,
                    (float) Math.sin(angle) * 24.0f);
            Vector3f color = new Vector3f(1.0f, 0.5f, 0.15f);
            if (index < 2) {
                scene.addLight(SceneLight.shadowedPoint(position, color, 5.0f, 5.5f),
                        ShadowLightHints.priority(6 - index));
                shadowRequests++;
            } else {
                scene.addLight(SceneLight.point(position, color, 5.0f, 5.5f));
            }
        }
        Vector3f[] skillColors = {
                new Vector3f(0.2f, 0.6f, 1.0f), new Vector3f(1.0f, 0.2f, 0.8f),
                new Vector3f(0.2f, 1.0f, 0.5f)
        };
        for (int index = 0; index < 32; index++) {
            scene.addLight(SceneLight.point(
                    new Vector3f((random.nextFloat() - 0.5f) * 10.0f,
                            1.2f + random.nextFloat() * 2.0f,
                            (random.nextFloat() - 0.5f) * 10.0f),
                    skillColors[index % skillColors.length], 6.0f, 6.0f));
        }
        return new Bundle(scene, materials, meshes, windowLights + lampLights + shopLights
                + torchLights + 32, lampLights, shadowRequests,
                1 + windowLights + lampLights + shopLights, torchLights,
                1 + windowLights + lampLights + shopLights + torchLights, 32);
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

    /** Dark albedo with an explicit emissive colour, used for windows and lamp heads. */
    private static Material emissiveMaterial(ShaderProgram shader, PbrFallbackTextures fallbacks,
                                             Vector3f color, float intensity, float roughness) {
        return PbrMaterials.create(shader,
                new PbrMaterialProperties(new Vector4f(0.03f, 0.03f, 0.03f, 1.0f),
                        0.0f, roughness, 1.0f, 1.0f,
                        new Vector3f(color).mul(intensity), Map.of()),
                Map.of(), fallbacks);
    }

    private static Mesh plane(String name, float width, float height) {
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

    private static Mesh box(String name) {
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
