package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import com.kaleblangley.haikalat.testing.SkinnedGltfFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class GltfAssetLoaderTest {
    @TempDir Path temporaryDirectory;
    @Test
    void decodesDataUriTriangleAndGeneratesCanonicalNormalsAndTangents() {
        LoadedGltfScene scene = loader().load(AssetRef.of("/gltf/minimal.gltf"));

        assertEquals(0, scene.selectedSceneIndex());
        assertEquals("default", scene.selectedSceneName());
        assertEquals(1, scene.nodes().size());
        assertEquals(1, scene.primitives().size());
        LoadedGltfScene.Primitive primitive = scene.primitives().getFirst();
        assertEquals(3, primitive.mesh().vertexCount());
        assertTrue(primitive.mesh().layout().attribute(VertexSemantic.NORMAL).isPresent());
        assertTrue(primitive.mesh().layout().attribute(VertexSemantic.TANGENT).isPresent());
        assertEquals(Bounds3f.of(0, 0, 0, 1, 1, 0), primitive.mesh().localBounds());
        assertFalse(primitive.hasVertexColor());
        for (float value : primitive.mesh().vertices()) assertTrue(Float.isFinite(value));
    }

    @Test
    void radioFixturePreservesMaskAndAlphaCutoff() {
        LoadedGltfScene scene = new GltfAssetLoader(
                ResourceLocator.classpath(GltfAssetLoaderTest.class))
                .load(AssetRef.of("/radio.gltf"));

        assertEquals(GltfAlphaMode.MASK, scene.materials().getFirst().alphaMode());
        assertEquals(0.05f, scene.materials().getFirst().alphaCutoff());
        assertEquals(8, scene.primitives().size());
        assertEquals(1, scene.images().size());
    }

    @Test
    void scalabilityFixtureProvidesSharedMultiMaterialTopology() {
        LoadedGltfScene scene = new GltfAssetLoader(
                ResourceLocator.classpath(GltfAssetLoaderTest.class))
                .load(AssetRef.of("/gltf/scalability.gltf"));

        assertEquals(2, scene.nodes().size());
        assertEquals(8, scene.primitives().size());
        assertEquals(9, scene.materials().size()); // 8 authored + loader fallback material
        assertEquals(4, scene.textures().size());
        assertEquals(1, scene.images().size());
        assertTrue(scene.nodes().stream().allMatch(node -> node.meshIndex() == 0));
        assertEquals(GltfAlphaMode.MASK, scene.materials().get(7).alphaMode());
    }

    @Test
    void blendMaterialStillFailsWithPreciseDiagnostic() throws Exception {
        ResourceLocator classpath = ResourceLocator.classpath(getClass());
        String blend = classpath.readString(AssetRef.of("/radio.gltf"))
                .replace("\"alphaMode\":\"MASK\"", "\"alphaMode\":\"BLEND\"");
        Files.writeString(temporaryDirectory.resolve("radio-blend.gltf"), blend);

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> new GltfAssetLoader(classpath.addRoot(temporaryDirectory))
                        .load(AssetRef.of("radio-blend.gltf")));
        assertTrue(failure.getMessage().contains("materials[0].alphaMode"));
        assertTrue(failure.getMessage().contains("BLEND"));
    }

    @Test
    void opaqueContractCopyOfRadioDecodesAllEmbeddedGeometryAndImage() throws Exception {
        ResourceLocator classpath = ResourceLocator.classpath(getClass());
        String opaque = classpath.readString(AssetRef.of("/radio.gltf"))
                .replace("\"alphaMode\":\"MASK\"", "\"alphaMode\":\"OPAQUE\"");
        Files.writeString(temporaryDirectory.resolve("radio-opaque.gltf"), opaque);
        LoadedGltfScene scene = new GltfAssetLoader(classpath.addRoot(temporaryDirectory))
                .load(AssetRef.of("radio-opaque.gltf"));
        assertEquals(8, scene.primitives().size());
        assertEquals(8, scene.nodes().size());
        assertEquals(1, scene.images().size());
        assertTrue(scene.statistics().tangentFallbackTriangles() >= 0);
    }

    @Test
    void duplicateJsonKeyFailsDuringParse() {
        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> loader().load(AssetRef.of("/gltf/duplicate.gltf")));
        assertEquals(GltfAssetException.Phase.PARSE, failure.phase());
        assertTrue(failure.getMessage().contains("Duplicate field"));
    }

    @Test
    void sceneCanBeSelectedByName() {
        LoadedGltfScene scene = loader().load(AssetRef.of("/gltf/minimal.gltf"),
                new GltfLoadOptions(new SceneSelection.ByName("default"), true,
                        GltfAssetLimits.defaults()));
        assertEquals("default", scene.selectedSceneName());
    }

    @Test
    void decodesMinimalGlbBinChunk() throws Exception {
        byte[] positions = trianglePositions();
        String json = """
                {"asset":{"version":"2.0"},"scene":0,
                 "scenes":[{"name":"glb","nodes":[0]}],"nodes":[{"mesh":0}],
                 "buffers":[{"byteLength":36}],
                 "bufferViews":[{"buffer":0,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}
                """;
        Files.write(temporaryDirectory.resolve("triangle.glb"), glb(json, positions));
        LoadedGltfScene scene = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("triangle.glb"));
        assertEquals("glb", scene.selectedSceneName());
        assertEquals(3, scene.primitives().getFirst().mesh().vertexCount());
    }

    @Test
    void sparseAccessorWithoutBaseBufferViewOverlaysZeroStorage() throws Exception {
        ByteBuffer payload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        payload.put((byte) 0).put((byte) 1).put((byte) 2).put((byte) 0);
        payload.put(trianglePositions());
        String encoded = Base64.getEncoder().encodeToString(payload.array());
        String json = """
                {"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "buffers":[{"byteLength":40,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":3},
                                {"buffer":0,"byteOffset":4,"byteLength":36}],
                 "accessors":[{"componentType":5126,"count":3,"type":"VEC3",
                   "sparse":{"count":3,"indices":{"bufferView":0,"componentType":5121},
                             "values":{"bufferView":1}}}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}
                """.formatted(encoded);
        Files.writeString(temporaryDirectory.resolve("sparse.gltf"), json);
        LoadedGltfScene scene = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("sparse.gltf"));
        float[] vertices = scene.primitives().getFirst().mesh().vertices();
        assertEquals(0.0f, vertices[0]);
        assertEquals(1.0f, vertices[12]);
        assertEquals(1.0f, vertices[25]);
    }

    @Test
    void millionElementSparseAccessorReusesZeroStorage() throws Exception {
        int indexCount = 1_000_002;
        ByteBuffer payload = ByteBuffer.allocate(152).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(trianglePositions());
        for (int vertex = 0; vertex < 3; vertex++) {
            payload.putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        }
        for (int vertex = 0; vertex < 3; vertex++) payload.putFloat(0.0f).putFloat(0.0f);
        for (int vertex = 0; vertex < 3; vertex++) {
            payload.putFloat(1.0f).putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        }
        payload.putInt(indexCount - 1).putShort((short) 1);
        String encoded = Base64.getEncoder().encodeToString(payload.array());
        Files.writeString(temporaryDirectory.resolve("large-sparse.gltf"), """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":[{"mesh":0}],
                 "buffers":[{"byteLength":152,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[
                   {"buffer":0,"byteOffset":0,"byteLength":36},
                   {"buffer":0,"byteOffset":36,"byteLength":36},
                   {"buffer":0,"byteOffset":72,"byteLength":24},
                   {"buffer":0,"byteOffset":96,"byteLength":48},
                   {"buffer":0,"byteOffset":144,"byteLength":4},
                   {"buffer":0,"byteOffset":148,"byteLength":2}],
                 "accessors":[
                   {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                   {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                   {"bufferView":2,"componentType":5126,"count":3,"type":"VEC2"},
                   {"bufferView":3,"componentType":5126,"count":3,"type":"VEC4"},
                   {"componentType":5123,"count":%d,"type":"SCALAR",
                    "sparse":{"count":1,
                     "indices":{"bufferView":4,"componentType":5125},
                     "values":{"bufferView":5}}}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,
                   "TEXCOORD_0":2,"TANGENT":3},"indices":4}]}]}
                """.formatted(encoded, indexCount));

        LoadedGltfScene scene = assertTimeout(Duration.ofSeconds(10),
                () -> new GltfAssetLoader(ResourceLocator.classpath(getClass())
                        .addRoot(temporaryDirectory)).load(AssetRef.of("large-sparse.gltf")));
        int[] indices = scene.primitives().getFirst().mesh().indices();
        assertEquals(indexCount, indices.length);
        assertEquals(0, indices[0]);
        assertEquals(1, indices[indexCount - 1]);
    }

    @Test
    void sparseRangesFailBeforeByteBufferReadsWithPreciseLocations() throws Exception {
        assertSparseRangeFailure("negative-index", 1, 5121, 4, 24, -1, 0,
                "accessors[0].sparse.indices");
        assertSparseRangeFailure("misaligned-values", 1, 5121, 4, 24, 0, 2,
                "accessors[0].sparse.values");
        assertSparseRangeFailure("truncated-indices", 2, 5121, 1, 24, 0, 0,
                "accessors[0].sparse.indices");
        assertSparseRangeFailure("values-out-of-range", 1, 5121, 4, 12, 0, 4,
                "accessors[0].sparse.values");
        assertSparseRangeFailure("overflow-sized-index-offset", 1, 5125, 4, 24,
                Integer.MAX_VALUE - 3, 0, "accessors[0].sparse.indices");
    }

    @Test
    void sharedMeshNodesPreserveWorldAndMirroredMetadata() {
        LoadedGltfScene scene = new GltfAssetLoader(ResourceLocator.classpath(getClass()))
                .load(AssetRef.of("/gltf/showcase.gltf"),
                        new GltfLoadOptions(new SceneSelection.ByName("showcase"), true,
                                GltfAssetLimits.defaults()));
        assertEquals(1, scene.primitives().size());
        assertEquals(2, scene.nodes().size());
        assertFalse(scene.nodes().getFirst().mirrored());
        assertTrue(scene.nodes().get(1).mirrored());
    }

    @Test
    void externalResourcesDecodeInterleavedAttributesAndNormalizedVertexColors() throws Exception {
        Path sceneDirectory = Files.createDirectories(temporaryDirectory.resolve("external"));
        ByteBuffer vertices = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        putInterleavedVertex(vertices, 0.0f, 0.0f, 0.0f, 0, 0, 255, 0, 0, 255);
        putInterleavedVertex(vertices, 1.0f, 0.0f, 0.0f, 65535, 0, 0, 255, 0, 255);
        putInterleavedVertex(vertices, 0.0f, 1.0f, 0.0f, 0, 65535, 0, 0, 255, 255);
        Files.write(sceneDirectory.resolve("triangle.bin"), vertices.array());
        byte[] png = tinyPng();
        Files.write(sceneDirectory.resolve("albedo.png"), png);
        Files.writeString(sceneDirectory.resolve("scene.gltf"), """
                {"asset":{"version":"2.0"},"scene":0,
                 "scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "buffers":[{"byteLength":60,"uri":"triangle.bin"}],
                 "bufferViews":[{"buffer":0,"byteLength":60,"byteStride":20}],
                 "accessors":[
                   {"bufferView":0,"byteOffset":0,"componentType":5126,"count":3,"type":"VEC3"},
                   {"bufferView":0,"byteOffset":12,"componentType":5123,"normalized":true,"count":3,"type":"VEC2"},
                   {"bufferView":0,"byteOffset":16,"componentType":5121,"normalized":true,"count":3,"type":"VEC4"}],
                 "images":[{"uri":"albedo.png"}],"textures":[{"source":0}],
                 "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1,"COLOR_0":2},"material":0}]}]}
                """);

        LoadedGltfScene scene = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("external/scene.gltf"));
        LoadedGltfScene.Primitive primitive = scene.primitives().getFirst();
        float[] canonical = primitive.mesh().vertices();
        assertTrue(primitive.hasVertexColor());
        assertEquals("image/png", scene.images().getFirst().mimeType());
        assertEquals(1.0f, canonical[12], 1.0e-6f);
        assertEquals(0.0f, canonical[13], 1.0e-6f);
        assertEquals(1.0f, canonical[19], 1.0e-6f);
        assertEquals(1.0f, canonical[29], 1.0e-6f);
        assertEquals(1.0f, canonical[46], 1.0e-6f);
        assertEquals(1.0f, canonical[47], 1.0e-6f);
    }

    @Test
    void glbBufferViewImageIsDecodedFromBinChunk() throws Exception {
        byte[] positions = trianglePositions();
        byte[] png = tinyPng();
        byte[] bin = new byte[positions.length + png.length];
        System.arraycopy(positions, 0, bin, 0, positions.length);
        System.arraycopy(png, 0, bin, positions.length, png.length);
        String json = """
                {"asset":{"version":"2.0"},"scene":0,
                 "scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "buffers":[{"byteLength":%d}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},
                                {"buffer":0,"byteOffset":36,"byteLength":%d}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],
                 "images":[{"bufferView":1,"mimeType":"image/png"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}
                """.formatted(bin.length, png.length);
        Files.write(temporaryDirectory.resolve("image.glb"), glb(json, bin));

        LoadedGltfScene scene = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("image.glb"));
        assertEquals("bufferView[1]", scene.images().getFirst().sourceUri());
        assertEquals(png.length, scene.images().getFirst().encoded().length);
    }

    @Test
    void rejectsOutOfOrderSparseIndices() throws Exception {
        ByteBuffer payload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        payload.put((byte) 1).put((byte) 0).put((byte) 2).put((byte) 0);
        payload.put(trianglePositions());
        String json = """
                {"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "buffers":[{"byteLength":40,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[{"buffer":0,"byteLength":3},{"buffer":0,"byteOffset":4,"byteLength":36}],
                 "accessors":[{"componentType":5126,"count":3,"type":"VEC3",
                   "sparse":{"count":3,"indices":{"bufferView":0,"componentType":5121},
                             "values":{"bufferView":1}}}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}
                """.formatted(Base64.getEncoder().encodeToString(payload.array()));
        Files.writeString(temporaryDirectory.resolve("bad-sparse.gltf"), json);

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> new GltfAssetLoader(ResourceLocator.classpath(getClass()).addRoot(temporaryDirectory))
                        .load(AssetRef.of("bad-sparse.gltf")));
        assertTrue(failure.getMessage().contains("strictly increasing"));
    }

    @Test
    void rejectsCyclesAndNodesWithMultipleSelectedParents() throws Exception {
        Files.writeString(temporaryDirectory.resolve("cycle.gltf"), """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":[{"children":[1]},{"children":[0]}]}
                """);
        Files.writeString(temporaryDirectory.resolve("parents.gltf"), """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
                 "nodes":[{"children":[2]},{"children":[2]},{}]}
                """);
        GltfAssetLoader loader = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory));

        assertTrue(assertThrows(GltfAssetException.class,
                () -> loader.load(AssetRef.of("cycle.gltf"))).getMessage().contains("cycle"));
        assertTrue(assertThrows(GltfAssetException.class,
                () -> loader.load(AssetRef.of("parents.gltf"))).getMessage().contains("multiple parents"));
    }

    @Test
    void malformedVersionImageAndDependentUriKeepStructuredLocations() throws Exception {
        Files.writeString(temporaryDirectory.resolve("version.gltf"),
                "{\"asset\":{\"version\":\"2.x\"},\"scenes\":[{}]}");
        Files.write(temporaryDirectory.resolve("not-an-image.png"), new byte[]{1, 2, 3, 4});
        Files.writeString(temporaryDirectory.resolve("image.gltf"), """
                {"asset":{"version":"2.0"},"scenes":[{}],
                 "images":[{"uri":"not-an-image.png"}]}
                """);
        Files.writeString(temporaryDirectory.resolve("missing.gltf"), """
                {"asset":{"version":"2.0"},"scenes":[{}],
                 "buffers":[{"byteLength":12,"uri":"missing.bin"}]}
                """);
        GltfAssetLoader loader = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory));

        GltfAssetException version = assertThrows(GltfAssetException.class,
                () -> loader.load(AssetRef.of("version.gltf")));
        assertEquals("asset.version", version.location());
        GltfAssetException image = assertThrows(GltfAssetException.class,
                () -> loader.load(AssetRef.of("image.gltf")));
        assertEquals("images[0]", image.location());
        GltfAssetException missing = assertThrows(GltfAssetException.class,
                () -> loader.load(AssetRef.of("missing.gltf")));
        assertEquals(GltfAssetException.Phase.RESOLVE, missing.phase());
        assertEquals("buffers[0].uri", missing.location());
        assertEquals("missing.bin", missing.dependentUri());
    }

    @Test
    void extensionPayloadRequiresExplicitLenientPolicyAndStillProducesWarning() throws Exception {
        Files.writeString(temporaryDirectory.resolve("extension.gltf"), """
                {"asset":{"version":"2.0"},"extensionsUsed":["VENDOR_metadata"],
                 "extensions":{"VENDOR_metadata":{"label":"test"}},"scenes":[{"name":"only"}]}
                """);
        GltfAssetLoader loader = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory));

        GltfAssetException strict = assertThrows(GltfAssetException.class,
                () -> loader.load(AssetRef.of("extension.gltf")));
        assertEquals("$.extensions", strict.location());
        LoadedGltfScene lenient = loader.load(AssetRef.of("extension.gltf"),
                new GltfLoadOptions(new SceneSelection.Default(), false,
                        GltfAssetLimits.defaults()));
        assertTrue(lenient.warnings().stream().anyMatch(warning -> warning.contains("VENDOR_metadata")));
    }

    @Test
    void decodesSkinFourInfluencesInverseBindsAndCubicAnimation() throws Exception {
        Files.writeString(temporaryDirectory.resolve("skinned.gltf"),
                SkinnedGltfFixture.document());

        LoadedGltfScene scene = new GltfAssetLoader(ResourceLocator.classpath(getClass())
                .addRoot(temporaryDirectory)).load(AssetRef.of("skinned.gltf"));

        assertEquals(1, scene.skins().size());
        assertEquals(2, scene.skins().getFirst().joints().size());
        assertEquals(1, scene.skins().getFirst().skeletonRootNode());
        assertEquals(1, scene.animations().size());
        LoadedGltfScene.AnimationChannelDef channel = scene.animations().getFirst()
                .channels().getFirst();
        assertEquals(2, channel.nodeIndex());
        assertEquals(LoadedGltfScene.AnimationTargetPath.TRANSLATION, channel.path());
        assertEquals(LoadedGltfScene.AnimationInterpolation.CUBIC_SPLINE,
                channel.interpolation());
        assertEquals(2, channel.timesSeconds().length);
        assertEquals(18, channel.values().length);
        assertEquals(0, scene.nodeRigs().getFirst().skinIndex());
        assertEquals(0, scene.nodeRigs().get(1).parentIndex());
        assertEquals(1, scene.nodeRigs().get(2).parentIndex());

        LoadedGltfScene.Primitive primitive = scene.primitives().getFirst();
        assertTrue(primitive.mesh().layout().attribute(VertexSemantic.JOINTS_0).isPresent());
        assertTrue(primitive.mesh().layout().attribute(VertexSemantic.WEIGHTS_0).isPresent());
        assertEquals(20 * Float.BYTES, primitive.mesh().layout().strideBytes());
        assertEquals(1, scene.primitiveSkinning(primitive.index()).orElseThrow().maxJointIndex());
        float[] vertices = primitive.mesh().vertices();
        assertEquals(0.25f, vertices[2 * 20 + 16], 1.0e-6f);
        assertEquals(0.75f, vertices[2 * 20 + 17], 1.0e-6f);
        assertEquals(1, scene.statistics().skinCount());
        assertEquals(1, scene.statistics().animationCount());
        assertEquals(1, scene.statistics().animationChannelCount());
    }

    @Test
    void rejectsJointIndexOutsideReferencedSkin() throws Exception {
        Files.writeString(temporaryDirectory.resolve("bad-joint.gltf"),
                SkinnedGltfFixture.document(2, 6));

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> new GltfAssetLoader(ResourceLocator.classpath(getClass())
                        .addRoot(temporaryDirectory)).load(AssetRef.of("bad-joint.gltf")));
        assertTrue(failure.getMessage().contains("JOINTS_0 index 2"));
        assertTrue(failure.getMessage().contains("joint count 2"));
    }

    @Test
    void rejectsCubicOutputCountThatDoesNotMatchInputTriples() throws Exception {
        Files.writeString(temporaryDirectory.resolve("bad-cubic.gltf"),
                SkinnedGltfFixture.document(1, 5));

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> new GltfAssetLoader(ResourceLocator.classpath(getClass())
                        .addRoot(temporaryDirectory)).load(AssetRef.of("bad-cubic.gltf")));
        assertTrue(failure.getMessage().contains("output count"));
        assertTrue(failure.location().contains("samplers[0].output"));
    }

    private static void putInterleavedVertex(ByteBuffer output, float x, float y, float z,
                                             int u, int v, int r, int g, int b, int a) {
        output.putFloat(x).putFloat(y).putFloat(z).putShort((short) u).putShort((short) v)
                .put((byte) r).put((byte) g).put((byte) b).put((byte) a);
    }

    private void assertSparseRangeFailure(String name, int sparseCount, int indexType,
                                          int indexViewLength, int valueViewLength,
                                          int indexOffset, int valueOffset,
                                          String expectedLocation) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(new byte[64]);
        String json = """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":[{"mesh":0}],
                 "buffers":[{"byteLength":64,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":%d},
                                {"buffer":0,"byteOffset":16,"byteLength":%d}],
                 "accessors":[{"componentType":5126,"count":3,"type":"VEC3",
                   "sparse":{"count":%d,
                     "indices":{"bufferView":0,"byteOffset":%d,"componentType":%d},
                     "values":{"bufferView":1,"byteOffset":%d}}}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}
                """.formatted(encoded, indexViewLength, valueViewLength, sparseCount,
                indexOffset, indexType, valueOffset);
        Path file = temporaryDirectory.resolve(name + ".gltf");
        Files.writeString(file, json);

        GltfAssetException failure = assertThrows(GltfAssetException.class,
                () -> new GltfAssetLoader(ResourceLocator.classpath(getClass())
                        .addRoot(temporaryDirectory)).load(AssetRef.of(file.getFileName().toString())));
        assertEquals(expectedLocation, failure.location());
        assertFalse(failure.getCause() instanceof java.nio.BufferUnderflowException);
        assertFalse(failure.getCause() instanceof IndexOutOfBoundsException);
    }

    private static byte[] tinyPng() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nYsAAAAASUVORK5CYII=");
    }


    private static byte[] trianglePositions() {
        return ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0).putFloat(0).putFloat(0)
                .putFloat(1).putFloat(0).putFloat(0)
                .putFloat(0).putFloat(1).putFloat(0).array();
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] rawJson = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int jsonLength = (rawJson.length + 3) & ~3;
        int binLength = (bin.length + 3) & ~3;
        ByteBuffer output = ByteBuffer.allocate(12 + 8 + jsonLength + 8 + binLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67).putInt(2).putInt(output.capacity());
        output.putInt(jsonLength).putInt(0x4E4F534A).put(rawJson);
        while (output.position() < 20 + jsonLength) output.put((byte) 0x20);
        output.putInt(binLength).putInt(0x004E4942).put(bin);
        while (output.hasRemaining()) output.put((byte) 0);
        return output.array();
    }

    private static GltfAssetLoader loader() {
        ResourceLocator locator = ResourceLocator.classpath(GltfAssetLoaderTest.class)
                .addRoot(java.nio.file.Path.of("src/test/resources"));
        return new GltfAssetLoader(locator);
    }
}
