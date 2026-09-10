package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL42;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * GPU assignment proofs against an independent CPU oracle.
 *
 * <p>The oracle never calls {@link ClusterMath}; it re-derives slice edges,
 * cluster centers and sphere tests directly from the frozen conventions.  A
 * light whose sphere contains a cluster's view-space center or AABB must be in
 * that cluster's list, otherwise the cluster must be flagged as overflow.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glReadback", matches = "true")
class ClusterAssignmentGlTest {
    @Test
    void gpuListsContainEveryLightWhoseSphereTouchesAClusterCenter() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32).title("ClusterAssignmentGlTest").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            MutableWindow viewport = new MutableWindow(320, 180);
            ClusteredLightingSettings settings = ClusteredLightingSettings.builder()
                    .tileSize(64).zSlices(8).maxLocalLights(64)
                    .inlineIndicesPerCluster(8).build();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("cluster-oracle-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.6f, 0.6f, 0.6f, 1.0f),
                                0.0f, 0.8f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    Scene scene = oracleScene(quad, material, 1234L);
                    RenderPipeline pipeline = new RenderPipeline(viewport, scene, null,
                            renderSettings(), environment).clusteredLighting(settings);
                    try {
                        pipeline.build();
                        pipeline.execute(device);
                        PipelineGeneration generation = pipeline.activeGenerationForTest();
                        ClusterGrid grid = generation.clusteredLightingBinder.stagedGrid();
                        FrameLightTable table = generation.clusteredLightingBinder.stagedTable();
                        ClusteredLightingResources.ClusterStorage storage =
                                generation.clusteredResources.storage();
                        int[] headers = readInts(storage.clusterHeaders,
                                storage.clusterHeadersBytes);
                        int[] indices = readInts(storage.clusterIndices,
                                storage.clusterIndicesBytes);

                        verifyConsistentHeaders(headers, storage);
                        verifyNoFalseNegatives(grid, table, storage, headers, indices);
                        assertEquals(GL_NO_ERROR, glGetError());

                        int[] secondRun = readIntsAfterFrame(pipeline, device,
                                storage.clusterIndices, storage.clusterIndicesBytes);
                        assertTrue(java.util.Arrays.equals(indices, secondRun),
                                "identical frames must produce identical cluster lists");

                        viewport.resize(256, 144);
                        pipeline.resize(256, 144);
                        pipeline.execute(device);
                        ClusterGrid resizedGrid = generation.clusteredLightingBinder.stagedGrid();
                        FrameLightTable resizedTable =
                                generation.clusteredLightingBinder.stagedTable();
                        ClusteredLightingResources.ClusterStorage resizedStorage =
                                generation.clusteredResources.storage();
                        assertEquals(256, resizedGrid.width());
                        int[] resizedHeaders = readInts(resizedStorage.clusterHeaders,
                                resizedStorage.clusterHeadersBytes);
                        int[] resizedIndices = readInts(resizedStorage.clusterIndices,
                                resizedStorage.clusterIndicesBytes);
                        verifyConsistentHeaders(resizedHeaders, resizedStorage);
                        verifyNoFalseNegatives(resizedGrid, resizedTable, resizedStorage,
                                resizedHeaders, resizedIndices);
                        assertTrue(resizedStorage.clusterCount < storage.clusterCount,
                                "resize must allocate the smaller candidate grid");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
            }
        }
    }

    @Test
    void overflowClustersExposeTrueCountAndDeterministicPrefix() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32).title("ClusterOverflowGlTest").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            MutableWindow viewport = new MutableWindow(256, 128);
            ClusteredLightingSettings settings = ClusteredLightingSettings.builder()
                    .tileSize(64).zSlices(4).maxLocalLights(32)
                    .inlineIndicesPerCluster(4).build();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("cluster-overflow-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.6f, 0.6f, 0.6f, 1.0f),
                                0.0f, 0.8f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    Scene scene = overflowScene(quad, material);
                    RenderPipeline pipeline = new RenderPipeline(viewport, scene, null,
                            renderSettings(), environment).clusteredLighting(settings);
                    try {
                        pipeline.build();
                        pipeline.execute(device);
                        PipelineGeneration generation = pipeline.activeGenerationForTest();
                        ClusteredLightingResources.ClusterStorage storage =
                                generation.clusteredResources.storage();
                        int[] headers = readInts(storage.clusterHeaders,
                                storage.clusterHeadersBytes);
                        int[] indices = readInts(storage.clusterIndices,
                                storage.clusterIndicesBytes);
                        int overflowClusters = 0;
                        for (int cluster = 0; cluster < storage.clusterCount; cluster++) {
                            int offset = headers[cluster * 4];
                            int count = headers[cluster * 4 + 1];
                            int overflow = headers[cluster * 4 + 2];
                            int trueCount = headers[cluster * 4 + 3];
                            assertEquals(cluster * storage.inlineCapacity, offset);
                            assertTrue(count <= storage.inlineCapacity);
                            if (overflow != 0) {
                                overflowClusters++;
                                assertTrue(trueCount > storage.inlineCapacity,
                                        "overflow must report the true count");
                                assertEquals(storage.inlineCapacity, count,
                                        "overflow clusters keep the full inline prefix");
                                for (int k = 0; k < count; k++) {
                                    int index = indices[cluster * storage.inlineCapacity + k];
                                    assertTrue(index >= 0 && index < 12,
                                            "index must address a local light record");
                                }
                            }
                        }
                        int[] counters = generation.clusteredLightingBinder.readCountersBlocking();
                        assertEquals(overflowClusters, counters[0]);
                        assertTrue(overflowClusters > 0,
                                "12 lights at one point must overflow K=4");
                        assertEquals(GL_NO_ERROR, glGetError());
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
            }
        }
    }

    private static void verifyConsistentHeaders(int[] headers,
                                                ClusteredLightingResources.ClusterStorage storage) {
        for (int cluster = 0; cluster < storage.clusterCount; cluster++) {
            int offset = headers[cluster * 4];
            int count = headers[cluster * 4 + 1];
            int overflow = headers[cluster * 4 + 2];
            int trueCount = headers[cluster * 4 + 3];
            assertEquals(cluster * storage.inlineCapacity, offset);
            assertTrue(count <= storage.inlineCapacity);
            if (overflow != 0) {
                assertTrue(trueCount > storage.inlineCapacity);
            } else {
                assertEquals(count, trueCount,
                        "non-overflow clusters report the true list size");
            }
        }
    }

    private static void verifyNoFalseNegatives(ClusterGrid grid, FrameLightTable table,
                                               ClusteredLightingResources.ClusterStorage storage,
                                               int[] headers, int[] indices) {
        Map<Integer, FrameLightTable.Record> byFrameIndex = new HashMap<>();
        for (int index = 0; index < table.totalCount(); index++) {
            byFrameIndex.put(index, table.record(index));
        }
        for (int cluster = 0; cluster < storage.clusterCount; cluster++) {
            int x = cluster % storage.nx;
            int y = (cluster / storage.nx) % storage.ny;
            int z = cluster / (storage.nx * storage.ny);
            double[] center = independentCenter(grid, x, y, z);
            int overflow = headers[cluster * 4 + 2];
            if (overflow != 0) {
                continue;
            }
            int count = headers[cluster * 4 + 1];
            java.util.Set<Integer> present = new java.util.HashSet<>();
            for (int k = 0; k < count; k++) {
                present.add(indices[cluster * storage.inlineCapacity + k]);
            }
            for (int index = table.directionalCount(); index < table.totalCount(); index++) {
                FrameLightTable.Record light = byFrameIndex.get(index);
                double dx = light.viewPosition().x - center[0];
                double dy = light.viewPosition().y - center[1];
                double dz = light.viewPosition().z - center[2];
                double radius = light.range() - 1.0e-3;
                if (radius > 0.0
                        && dx * dx + dy * dy + dz * dz <= radius * radius) {
                    assertTrue(present.contains(index),
                            "cluster " + cluster + " must contain light " + index
                                    + " (stableId " + light.stableId() + ")");
                }
            }
        }
    }

    /** Independent double-precision cluster center derivation. */
    private static double[] independentCenter(ClusterGrid grid, int x, int y, int z) {
        double nx = grid.nx();
        double ny = grid.ny();
        double u = -1.0 + 2.0 * (x + 0.5) / nx;
        double v = -1.0 + 2.0 * (y + 0.5) / ny;
        double nearEdge = independentSliceEdge(grid.perspective(), grid.nearPlane(),
                grid.farPlane(), z, grid.zSlices());
        double farEdge = independentSliceEdge(grid.perspective(), grid.nearPlane(),
                grid.farPlane(), z + 1, grid.zSlices());
        double depth = 0.5 * (nearEdge + farEdge);
        Matrix4f projection = grid.stableProjection();
        if (grid.perspective()) {
            return new double[]{u * depth / projection.m00(), v * depth / projection.m11(),
                    -depth};
        }
        return new double[]{(u - projection.m30()) / projection.m00(),
                (v - projection.m31()) / projection.m11(), -depth};
    }

    private static double independentSliceEdge(boolean perspective, double near, double far,
                                               int slice, int slices) {
        double ratio = slice / (double) slices;
        return perspective ? near * Math.pow(far / near, ratio) : near + (far - near) * ratio;
    }

    private static Scene oracleScene(Mesh quad, Material material, long seed) {
        Camera camera = new Camera(new Vector3f(0.0f, 2.0f, 14.0f));
        Scene scene = new Scene(camera);
        scene.add(MeshRenderer.of(quad, material, Transform.identity().scale(20.0f)));
        scene.addLight(SceneLight.directional(new Vector3f(-0.4f, -1.0f, -0.3f),
                new Vector3f(0.4f), 0.6f));
        Random random = new Random(seed);
        for (int index = 0; index < 48; index++) {
            scene.addLight(SceneLight.point(
                    new Vector3f((random.nextFloat() - 0.5f) * 24.0f,
                            random.nextFloat() * 6.0f,
                            (random.nextFloat() - 0.5f) * 24.0f),
                    new Vector3f(random.nextFloat() * 0.8f + 0.2f,
                            random.nextFloat() * 0.8f + 0.2f,
                            random.nextFloat() * 0.8f + 0.2f),
                    random.nextFloat() * 6.0f + 1.0f,
                    random.nextFloat() * 5.0f + 2.0f));
        }
        return scene;
    }

    private static Scene overflowScene(Mesh quad, Material material) {
        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 10.0f));
        Scene scene = new Scene(camera);
        scene.add(MeshRenderer.of(quad, material, Transform.identity().scale(30.0f)));
        for (int index = 0; index < 12; index++) {
            scene.addLight(SceneLight.point(new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(1.0f), 1.0f, 25.0f));
        }
        return scene;
    }

    private static RenderSettings renderSettings() {
        return RenderSettings.builder().toneMappingMode(ToneMappingMode.ACES)
                .bloomSettings(BloomSettings.disabled()).vsync(false).build();
    }

    private static int[] readInts(GlBuffer buffer, long byteSize) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        ByteBuffer data = BufferUtils.createByteBuffer(Math.toIntExact(byteSize))
                .order(ByteOrder.nativeOrder());
        buffer.read(0L, data);
        IntBuffer values = data.asIntBuffer();
        int[] result = new int[values.remaining()];
        values.get(result);
        return result;
    }

    private static int[] readIntsAfterFrame(RenderPipeline pipeline, GlRenderDevice device,
                                            GlBuffer buffer, long byteSize) {
        pipeline.execute(device);
        return readInts(buffer, byteSize);
    }

    private static final class MutableWindow implements RenderWindow {
        private int width;
        private int height;

        private MutableWindow(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        private void resize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
