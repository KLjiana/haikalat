package com.kaleblangley.haikalat.core.assets.gltf;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.core.mesh.NormalGenerator;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_REPEAT;

/** glTF 2.0 静态场景的纯 JVM 加载入口。 */
public final class GltfAssetLoader {
    private static final ByteBuffer ZERO_ACCESSOR_BUFFER = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;
    private static final Set<Integer> NO_NORMALIZED_COMPONENTS = Set.of();
    private static final Set<Integer> SIGNED_NORMALIZED_COMPONENTS = Set.of(5120, 5122);
    private static final Set<Integer> UNSIGNED_NORMALIZED_COMPONENTS = Set.of(5121, 5123);
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ResourceLocator locator;

    public GltfAssetLoader(ResourceLocator locator) {
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    public LoadedGltfScene load(AssetRef ref) {
        return load(ref, GltfLoadOptions.defaults());
    }

    public LoadedGltfScene load(AssetRef ref, GltfLoadOptions options) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(options, "options");
        if (!ref.extension().equals("gltf") && !ref.extension().equals("glb")) {
            throw error(ref, GltfAssetException.Phase.READ, "source", "expected .gltf or .glb asset");
        }
        byte[] document;
        try {
            document = locator.readBytes(ref, options.limits().documentBytes());
        } catch (RuntimeException failure) {
            throw new GltfAssetException(ref, GltfAssetException.Phase.READ, "source", null,
                    "could not read document", failure);
        }
        limit(ref, "documentBytes", document.length, options.limits().documentBytes(), "source");
        Document parsed = ref.extension().equals("glb") ? readGlb(ref, document) : new Document(document, null, List.of());
        Map<String, Object> root = parseJson(ref, parsed.json());
        try {
            return new Decoder(ref, root, parsed.bin(), parsed.warnings(), options).decode();
        } catch (GltfAssetException failure) {
            throw failure;
        } catch (DecodeFailure failure) {
            throw new GltfAssetException(ref, GltfAssetException.Phase.DECODE,
                    failure.location, null, failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            throw new GltfAssetException(ref, GltfAssetException.Phase.DECODE, "$", null,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), failure);
        }
    }

    private static Document readGlb(AssetRef source, byte[] bytes) {
        if (bytes.length < 20) throw error(source, GltfAssetException.Phase.PARSE, "byte 0", "GLB is truncated");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != GLB_MAGIC) throw error(source, GltfAssetException.Phase.PARSE, "byte 0", "invalid GLB magic");
        int version = input.getInt();
        if (version != 2) throw error(source, GltfAssetException.Phase.PARSE, "byte 4", "unsupported GLB version " + version);
        long declared = Integer.toUnsignedLong(input.getInt());
        if (declared != bytes.length) throw error(source, GltfAssetException.Phase.PARSE, "byte 8",
                "declared GLB length " + declared + " differs from actual " + bytes.length);
        byte[] json = null;
        byte[] bin = null;
        List<String> warnings = new ArrayList<>();
        int chunk = 0;
        while (input.hasRemaining()) {
            int headerOffset = input.position();
            if (input.remaining() < 8) throw error(source, GltfAssetException.Phase.PARSE,
                    "chunk[" + chunk + "] byte " + headerOffset, "truncated chunk header");
            long length = Integer.toUnsignedLong(input.getInt());
            int type = input.getInt();
            if ((length & 3L) != 0L || length > input.remaining()) throw error(source,
                    GltfAssetException.Phase.PARSE, "chunk[" + chunk + "] byte " + headerOffset,
                    "invalid or out-of-range aligned chunk length " + length);
            byte[] payload = new byte[Math.toIntExact(length)];
            input.get(payload);
            if (chunk == 0 && type != JSON_CHUNK) throw error(source, GltfAssetException.Phase.PARSE,
                    "chunk[0]", "first GLB chunk must be JSON");
            if (type == JSON_CHUNK) {
                if (json != null) throw error(source, GltfAssetException.Phase.PARSE, "chunk[" + chunk + "]", "duplicate JSON chunk");
                json = trimJsonPadding(source, payload, chunk);
            } else if (type == BIN_CHUNK) {
                if (bin != null) throw error(source, GltfAssetException.Phase.PARSE, "chunk[" + chunk + "]", "duplicate BIN chunk");
                bin = payload;
            } else warnings.add("ignored unknown GLB chunk type 0x" + Integer.toHexString(type));
            chunk++;
        }
        if (json == null) throw error(source, GltfAssetException.Phase.PARSE, "chunk[0]", "missing JSON chunk");
        return new Document(json, bin, warnings);
    }

    private static byte[] trimJsonPadding(AssetRef source, byte[] bytes, int chunk) {
        int end = bytes.length;
        while (end > 0 && (bytes[end - 1] == 0 || bytes[end - 1] == 0x20)) end--;
        for (int i = end; i < bytes.length; i++) if (bytes[i] != 0 && bytes[i] != 0x20) {
            throw error(source, GltfAssetException.Phase.PARSE, "chunk[" + chunk + "] byte " + i, "invalid JSON padding");
        }
        return java.util.Arrays.copyOf(bytes, end);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(AssetRef source, byte[] bytes) {
        try (JsonParser parser = JSON.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw error(source, GltfAssetException.Phase.PARSE, "$", "root must be an object");
            Object value = readValue(parser);
            if (parser.nextToken() != null) throw error(source, GltfAssetException.Phase.PARSE, "$", "trailing JSON content");
            return (Map<String, Object>) value;
        } catch (GltfAssetException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new GltfAssetException(source, GltfAssetException.Phase.PARSE, "$", null,
                    "invalid JSON: " + failure.getMessage(), failure);
        }
    }

    private static Object readValue(JsonParser parser) throws IOException {
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                Map<String, Object> object = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String field = parser.currentName();
                    parser.nextToken();
                    object.put(field, readValue(parser));
                }
                yield object;
            }
            case START_ARRAY -> {
                List<Object> array = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) array.add(readValue(parser));
                yield List.copyOf(array);
            }
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getLongValue();
            case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new IOException("unexpected token " + parser.currentToken());
        };
    }

    private final class Decoder {
        private final AssetRef source;
        private final Map<String, Object> root;
        private final byte[] glbBin;
        private final List<String> warnings;
        private final GltfLoadOptions options;
        private List<byte[]> buffers;
        private List<Map<String, Object>> views;
        private List<Map<String, Object>> accessors;
        private int normalFallbacks;
        private int tangentFallbackTriangles;
        private int tangentFallbackVertices;
        private long decodedBufferBytes;
        private long encodedImageBytes;
        private long decodedDataUriBytes;
        private long vertexBytes;
        private long indexBytes;

        Decoder(AssetRef source, Map<String, Object> root, byte[] glbBin,
                List<String> warnings, GltfLoadOptions options) {
            this.source = source;
            this.root = root;
            this.glbBin = glbBin;
            this.warnings = new ArrayList<>(warnings);
            this.options = options;
        }

        LoadedGltfScene decode() {
            validateAsset();
            validateExtensions();
            List<Map<String, Object>> nodeDtos = objects(root, "nodes");
            List<Map<String, Object>> meshDtos = objects(root, "meshes");
            List<Map<String, Object>> materialDtos = objects(root, "materials");
            List<Map<String, Object>> textureDtos = objects(root, "textures");
            List<Map<String, Object>> imageDtos = objects(root, "images");
            List<Map<String, Object>> samplerDtos = objects(root, "samplers");
            accessors = objects(root, "accessors");
            views = objects(root, "bufferViews");
            checkCounts(nodeDtos, meshDtos, materialDtos, textureDtos, imageDtos, samplerDtos);
            buffers = resolveBuffers();
            validateBufferViews();
            List<LoadedGltfScene.SamplerDef> samplers = decodeSamplers(samplerDtos);
            List<LoadedGltfScene.TextureDef> textures = decodeTextures(textureDtos, imageDtos.size(), samplers.size());
            List<LoadedGltfScene.ImageDef> images = decodeImages(imageDtos);
            List<LoadedGltfScene.MaterialDef> materials = decodeMaterials(materialDtos, textures.size());
            int defaultMaterialIndex = materials.size();
            List<LoadedGltfScene.MaterialDef> allMaterials = new ArrayList<>(materials);
            allMaterials.add(defaultMaterial(defaultMaterialIndex));
            List<LoadedGltfScene.Primitive> primitives = decodeMeshes(meshDtos, allMaterials, defaultMaterialIndex);
            SceneChoice scene = selectScene();
            List<LoadedGltfScene.Node> nodes = decodeNodes(nodeDtos, meshDtos.size(), scene.roots());
            int reachable = (int) nodes.stream().filter(LoadedGltfScene.Node::reachable).count();
            GltfSceneStatistics stats = new GltfSceneStatistics(nodeDtos.size(), reachable, meshDtos.size(),
                    primitives.size(), allMaterials.size(), textures.size(), images.size(), samplers.size(),
                    normalFallbacks, tangentFallbackTriangles, tangentFallbackVertices,
                    decodedBufferBytes, encodedImageBytes, vertexBytes, indexBytes);
            return new LoadedGltfScene(source, scene.index(), scene.name(), scene.roots(), nodes,
                    primitives, allMaterials, textures, images, samplers, warnings, stats);
        }

        private void validateAsset() {
            Map<String, Object> asset = object(root, "asset", true, "asset");
            String version = string(asset, "version", true, "asset.version");
            if (!validVersion(version) || !version.startsWith("2.")) {
                throw fail("asset.version", "only numeric glTF 2.x versions are supported, got " + version);
            }
            String min = string(asset, "minVersion", false, "asset.minVersion");
            if (min != null && (!validVersion(min) || compareVersion(min, "2.0") > 0)) {
                throw fail("asset.minVersion", "requires unsupported glTF " + min);
            }
        }

        private void validateExtensions() {
            Set<String> required = new HashSet<>(strings(root.get("extensionsRequired"), "extensionsRequired"));
            if (!required.isEmpty()) throw fail("extensionsRequired", "unsupported required extensions " + required);
            List<String> used = strings(root.get("extensionsUsed"), "extensionsUsed");
            if (!used.isEmpty()) warnings.add("ignored optional extensions: " + used);
            List<String> payloads = new ArrayList<>();
            collectExtensionPayloads(root, "$", payloads);
            if (!payloads.isEmpty()) {
                if (options.strictExtensions()) {
                    String first = payloads.getFirst();
                    int separator = first.indexOf(':');
                    throw fail(first.substring(0, separator),
                            "unsupported extension payload " + first.substring(separator + 1));
                }
                warnings.add("ignored extension payloads by explicit lenient policy: " + payloads);
            }
        }

        private void collectExtensionPayloads(Object value, String path, List<String> output) {
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = Objects.toString(entry.getKey());
                    String childPath = path + "." + key;
                    if (key.equals("extensions")) {
                        Map<String, Object> extensions = GltfAssetLoader.map(entry.getValue(), childPath);
                        if (extensions != null) {
                            for (String extension : extensions.keySet()) {
                                output.add(childPath + ":" + extension);
                            }
                        }
                    } else {
                        collectExtensionPayloads(entry.getValue(), childPath, output);
                    }
                }
            } else if (value instanceof List<?> list) {
                for (int index = 0; index < list.size(); index++) {
                    collectExtensionPayloads(list.get(index), path + "[" + index + "]", output);
                }
            }
        }

        private void checkCounts(List<?> nodes, List<?> meshes, List<?> materials, List<?> textures,
                                 List<?> images, List<?> samplers) {
            GltfAssetLimits l = options.limits();
            limit(source, "nodes", nodes.size(), l.nodes(), "nodes");
            limit(source, "meshes", meshes.size(), l.meshes(), "meshes");
            limit(source, "materials", materials.size(), l.materials(), "materials");
            limit(source, "textures", textures.size(), l.textures(), "textures");
            limit(source, "images", images.size(), l.images(), "images");
            limit(source, "samplers", samplers.size(), l.samplers(), "samplers");
            limit(source, "accessors", accessors.size(), l.accessors(), "accessors");
        }

        private List<byte[]> resolveBuffers() {
            List<Map<String, Object>> dtos = objects(root, "buffers");
            List<byte[]> result = new ArrayList<>();
            Map<String, byte[]> uriCache = new HashMap<>();
            long total = 0;
            for (int i = 0; i < dtos.size(); i++) {
                Map<String, Object> dto = dtos.get(i);
                int declared = integer(dto, "byteLength", true, "buffers[" + i + "].byteLength");
                if (declared < 0) throw fail("buffers[" + i + "].byteLength", "must not be negative");
                long nextTotal = Math.addExact(total, declared);
                limit(source, "decodedBufferBytes", nextTotal,
                        options.limits().decodedBufferBytes(), "buffers");
                String uri = string(dto, "uri", false, "buffers[" + i + "].uri");
                byte[] bytes;
                if (uri == null) {
                    if (i != 0 || glbBin == null) throw fail("buffers[" + i + "]", "buffer without URI requires GLB BIN chunk");
                    bytes = glbBin;
                } else {
                    int bufferIndex = i;
                    bytes = uriCache.computeIfAbsent(uri,
                            key -> resolveUri(key, false, "buffers[" + bufferIndex + "].uri"));
                }
                if (declared < 0 || declared > bytes.length) throw fail("buffers[" + i + "].byteLength",
                        "declared " + declared + " exceeds payload " + bytes.length);
                total = nextTotal;
                result.add(java.util.Arrays.copyOf(bytes, declared));
            }
            decodedBufferBytes = total;
            return List.copyOf(result);
        }

        private byte[] resolveUri(String uri, boolean image, String path) {
            if (uri.startsWith("data:")) return decodeDataUri(uri, image, path);
            final AssetRef dependent;
            try { dependent = locator.resolveRelative(source, uri); }
            catch (RuntimeException failure) {
                throw new GltfAssetException(source, GltfAssetException.Phase.RESOLVE, path, uri,
                        failure.getMessage(), failure);
            }
            long byteLimit = image ? options.limits().imageBytes()
                    : options.limits().decodedBufferBytes();
            try { return locator.readBytes(dependent, byteLimit); }
            catch (RuntimeException failure) {
                throw new GltfAssetException(source, GltfAssetException.Phase.RESOLVE, path, uri,
                        "could not read dependent resource", failure);
            }
        }

        private byte[] decodeDataUri(String uri, boolean image, String path) {
            int comma = uri.indexOf(',');
            if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) throw fail(path, "only base64 data URIs are supported");
            String mime = uri.substring(5, comma - 7).toLowerCase(Locale.ROOT);
            Set<String> allowed = image ? Set.of("image/png", "image/jpeg")
                    : Set.of("application/octet-stream", "application/gltf-buffer");
            if (!allowed.contains(mime)) throw fail(path, "unsupported data URI mime " + mime);
            long encodedLength = uri.length() - comma - 1L;
            int padding = uri.endsWith("==") ? 2 : uri.endsWith("=") ? 1 : 0;
            long estimate = Math.max(0L, Math.multiplyExact(encodedLength, 6L) / 8L - padding);
            limit(source, "dataUriBytes", Math.addExact(decodedDataUriBytes, estimate),
                    options.limits().dataUriBytes(), path);
            try {
                byte[] decoded = Base64.getDecoder().decode(uri.substring(comma + 1));
                decodedDataUriBytes = Math.addExact(decodedDataUriBytes, decoded.length);
                limit(source, "dataUriBytes", decodedDataUriBytes, options.limits().dataUriBytes(), path);
                return decoded;
            } catch (IllegalArgumentException failure) {
                throw fail(path, "invalid base64 payload", failure);
            }
        }

        private List<LoadedGltfScene.SamplerDef> decodeSamplers(List<Map<String, Object>> dtos) {
            List<LoadedGltfScene.SamplerDef> result = new ArrayList<>();
            for (int i = 0; i < dtos.size(); i++) {
                Map<String, Object> dto = dtos.get(i);
                int min = integer(dto, "minFilter", false, "samplers[" + i + "].minFilter", GL_LINEAR_MIPMAP_LINEAR);
                int mag = integer(dto, "magFilter", false, "samplers[" + i + "].magFilter", GL_LINEAR);
                int s = integer(dto, "wrapS", false, "samplers[" + i + "].wrapS", GL_REPEAT);
                int t = integer(dto, "wrapT", false, "samplers[" + i + "].wrapT", GL_REPEAT);
                if (!Set.of(9728, 9729, 9984, 9985, 9986, 9987).contains(min)
                        || !Set.of(9728, 9729).contains(mag)
                        || !Set.of(33071, 33648, 10497).contains(s)
                        || !Set.of(33071, 33648, 10497).contains(t)) throw fail("samplers[" + i + "]", "invalid sampler enum");
                result.add(new LoadedGltfScene.SamplerDef(i, min, mag, s, t));
            }
            return List.copyOf(result);
        }

        private List<LoadedGltfScene.TextureDef> decodeTextures(List<Map<String, Object>> dtos, int imageCount, int samplerCount) {
            List<LoadedGltfScene.TextureDef> result = new ArrayList<>();
            for (int i = 0; i < dtos.size(); i++) {
                Map<String, Object> dto = dtos.get(i);
                if (dto.containsKey("extensions")) throw fail("textures[" + i + "].extensions", "texture extensions are not supported");
                int image = integer(dto, "source", true, "textures[" + i + "].source");
                int sampler = integer(dto, "sampler", false, "textures[" + i + "].sampler", -1);
                index(image, imageCount, "textures[" + i + "].source");
                if (sampler >= 0) index(sampler, samplerCount, "textures[" + i + "].sampler");
                result.add(new LoadedGltfScene.TextureDef(i, image, sampler));
            }
            return List.copyOf(result);
        }

        private List<LoadedGltfScene.ImageDef> decodeImages(List<Map<String, Object>> dtos) {
            List<LoadedGltfScene.ImageDef> result = new ArrayList<>();
            for (int i = 0; i < dtos.size(); i++) {
                Map<String, Object> dto = dtos.get(i);
                String uri = string(dto, "uri", false, "images[" + i + "].uri");
                String mime = string(dto, "mimeType", false, "images[" + i + "].mimeType");
                int view = integer(dto, "bufferView", false, "images[" + i + "].bufferView", -1);
                if ((uri == null) == (view < 0)) throw fail("images[" + i + "]", "exactly one of uri or bufferView is required");
                byte[] encoded;
                String sourceUri = uri == null ? "bufferView[" + view + "]" : uri;
                if (uri != null) {
                    encoded = resolveUri(uri, true, "images[" + i + "].uri");
                    if (mime == null && uri.startsWith("data:")) mime = uri.substring(5, uri.indexOf(';'));
                    if (mime == null) mime = inferImageMime(uri, encoded);
                } else {
                    if (mime == null) throw fail("images[" + i + "].mimeType", "bufferView image requires mimeType");
                    encoded = viewBytes(view, "images[" + i + "].bufferView");
                }
                if (!Set.of("image/png", "image/jpeg").contains(mime)) throw fail("images[" + i + "].mimeType", "only PNG/JPEG images are supported");
                if (!imageSignatureMatches(encoded, mime)) {
                    throw fail("images[" + i + "]", "mimeType " + mime + " does not match image signature");
                }
                limit(source, "imageBytes", encoded.length, options.limits().imageBytes(), "images[" + i + "]");
                encodedImageBytes = Math.addExact(encodedImageBytes, encoded.length);
                result.add(new LoadedGltfScene.ImageDef(i, string(dto, "name", false, "images[" + i + "].name"), mime, sourceUri, encoded));
            }
            return List.copyOf(result);
        }

        private List<LoadedGltfScene.MaterialDef> decodeMaterials(List<Map<String, Object>> dtos, int textureCount) {
            List<LoadedGltfScene.MaterialDef> result = new ArrayList<>();
            for (int i = 0; i < dtos.size(); i++) {
                Map<String, Object> dto = dtos.get(i);
                String path = "materials[" + i + "]";
                String alpha = string(dto, "alphaMode", false, path + ".alphaMode");
                GltfAlphaMode alphaMode = switch (alpha == null ? "OPAQUE" : alpha) {
                    case "OPAQUE" -> GltfAlphaMode.OPAQUE;
                    case "MASK" -> GltfAlphaMode.MASK;
                    case "BLEND" -> throw fail(path + ".alphaMode",
                            "BLEND is not supported by the v0.13 static glTF path");
                    default -> throw fail(path + ".alphaMode", "unknown alpha mode " + alpha);
                };
                float alphaCutoff = alphaMode == GltfAlphaMode.MASK
                        ? decimal(dto, "alphaCutoff", 0.5f, path) : 0.0f;
                if (alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
                    throw fail(path + ".alphaCutoff", "must be in [0, 1]");
                }
                if (dto.containsKey("extensions")) throw fail(path + ".extensions", "material extensions are not supported");
                Map<String, Object> pbr = object(dto, "pbrMetallicRoughness", false, path + ".pbrMetallicRoughness");
                if (pbr == null) pbr = Map.of();
                String pbrPath = path + ".pbrMetallicRoughness";
                Vector4f base = vec4(pbr.get("baseColorFactor"), new Vector4f(1.0f), pbrPath + ".baseColorFactor");
                float metallic = decimal(pbr, "metallicFactor", 1.0f, pbrPath);
                float roughness = decimal(pbr, "roughnessFactor", 1.0f, pbrPath);
                float normalScale = 1.0f;
                float occlusionStrength = 1.0f;
                Vector3f emissive = vec3(dto.get("emissiveFactor"), new Vector3f(), path + ".emissiveFactor");
                EnumMap<PbrTextureRole, Integer> refs = new EnumMap<>(PbrTextureRole.class);
                textureInfo(pbr.get("baseColorTexture"), PbrTextureRole.BASE_COLOR, refs,
                        textureCount, pbrPath + ".baseColorTexture");
                textureInfo(pbr.get("metallicRoughnessTexture"), PbrTextureRole.METALLIC_ROUGHNESS,
                        refs, textureCount, pbrPath + ".metallicRoughnessTexture");
                Map<String, Object> normal = map(dto.get("normalTexture"), path + ".normalTexture");
                if (normal != null) { textureInfo(normal, PbrTextureRole.NORMAL, refs, textureCount, path + ".normalTexture"); normalScale = decimal(normal, "scale", 1.0f, path + ".normalTexture"); }
                Map<String, Object> occ = map(dto.get("occlusionTexture"), path + ".occlusionTexture");
                if (occ != null) { textureInfo(occ, PbrTextureRole.OCCLUSION, refs, textureCount, path + ".occlusionTexture"); occlusionStrength = decimal(occ, "strength", 1.0f, path + ".occlusionTexture"); }
                textureInfo(dto.get("emissiveTexture"), PbrTextureRole.EMISSIVE, refs,
                        textureCount, path + ".emissiveTexture");
                PbrMaterialProperties properties;
                try { properties = new PbrMaterialProperties(base, metallic, roughness, normalScale, occlusionStrength, emissive, Map.of()); }
                catch (IllegalArgumentException failure) { throw fail(path, failure.getMessage(), failure); }
                result.add(new LoadedGltfScene.MaterialDef(i, string(dto, "name", false, path + ".name"), properties,
                        refs, bool(dto, "doubleSided", false, path + ".doubleSided"),
                        alphaMode, alphaCutoff));
            }
            return List.copyOf(result);
        }

        private LoadedGltfScene.MaterialDef defaultMaterial(int index) {
            PbrMaterialProperties properties = new PbrMaterialProperties(new Vector4f(1.0f), 1.0f, 1.0f,
                    1.0f, 1.0f, new Vector3f(), Map.of());
            return new LoadedGltfScene.MaterialDef(index, "glTF default", properties, Map.of(), false,
                    GltfAlphaMode.OPAQUE, 0.0f);
        }

        private List<LoadedGltfScene.Primitive> decodeMeshes(List<Map<String, Object>> meshes,
                                                              List<LoadedGltfScene.MaterialDef> materials,
                                                              int defaultMaterial) {
            List<LoadedGltfScene.Primitive> result = new ArrayList<>();
            for (int meshIndex = 0; meshIndex < meshes.size(); meshIndex++) {
                Map<String, Object> mesh = meshes.get(meshIndex);
                List<Map<String, Object>> primitiveDtos = objects(mesh, "primitives");
                limit(source, "primitives", result.size() + primitiveDtos.size(), options.limits().primitives(), "meshes");
                for (int primitiveIndex = 0; primitiveIndex < primitiveDtos.size(); primitiveIndex++) {
                    Map<String, Object> dto = primitiveDtos.get(primitiveIndex);
                    String path = "meshes[" + meshIndex + "].primitives[" + primitiveIndex + "]";
                    if (integer(dto, "mode", false, path + ".mode", 4) != 4) throw fail(path + ".mode", "only TRIANGLES mode 4 is supported");
                    if (dto.containsKey("targets") || dto.containsKey("extensions")) throw fail(path, "morph targets and primitive extensions are not supported");
                    Map<String, Object> attributes = object(dto, "attributes", true, path + ".attributes");
                    int posAccessor = integer(attributes, "POSITION", true, path + ".attributes.POSITION");
                    float[] positions = accessorFloats(posAccessor, 3, NO_NORMALIZED_COMPONENTS,
                            path + ".attributes.POSITION");
                    validatePositionBounds(accessors.get(posAccessor), positions,
                            path + ".attributes.POSITION");
                    int vertexCount = positions.length / 3;
                    limit(source, "primitiveVertices", vertexCount, options.limits().primitiveVertices(), path);
                    int[] indices = dto.containsKey("indices")
                            ? accessorIndices(integer(dto, "indices", true, path + ".indices"), vertexCount, path + ".indices")
                            : new int[0];
                    int elementCount = indices.length == 0 ? vertexCount : indices.length;
                    if (elementCount % 3 != 0) throw fail(path, "triangle element count must be divisible by 3");
                    limit(source, "primitiveIndices", elementCount, options.limits().primitiveIndices(), path);
                    float[] normals;
                    if (attributes.containsKey("NORMAL")) {
                        normals = accessorFloats(integer(attributes, "NORMAL", true, path), 3,
                                SIGNED_NORMALIZED_COMPONENTS, path + ".attributes.NORMAL");
                        normalizeVectors(normals, 3, path + ".attributes.NORMAL");
                    } else {
                        NormalGenerator.Result generated = NormalGenerator.generate(positions, indices);
                        normals = generated.normals(); normalFallbacks += generated.fallbackVertexCount();
                    }
                    requireCount(normals.length / 3, vertexCount, path + ".attributes.NORMAL");
                    float[] uv = attributes.containsKey("TEXCOORD_0")
                            ? accessorFloats(integer(attributes, "TEXCOORD_0", true, path), 2,
                            UNSIGNED_NORMALIZED_COMPONENTS, path + ".attributes.TEXCOORD_0")
                            : new float[vertexCount * 2];
                    requireCount(uv.length / 2, vertexCount, path + ".attributes.TEXCOORD_0");
                    int material = integer(dto, "material", false, path + ".material", defaultMaterial);
                    index(material, materials.size(), path + ".material");
                    if (!materials.get(material).textureIndices().isEmpty() && !attributes.containsKey("TEXCOORD_0")) {
                        throw fail(path + ".attributes.TEXCOORD_0", "textured material requires TEXCOORD_0");
                    }
                    float[] colors = attributes.containsKey("COLOR_0")
                            ? colorAccessor(integer(attributes, "COLOR_0", true, path), vertexCount, path + ".attributes.COLOR_0") : null;
                    float[] baseVertices = interleaveBase(positions, uv, normals);
                    String meshName = source.path() + "/mesh[" + meshIndex + "]:"
                            + Objects.toString(mesh.get("name"), "") + "/primitive[" + primitiveIndex + "]";
                    MeshData canonical;
                    if (attributes.containsKey("TANGENT")) {
                        float[] tangents = accessorFloats(integer(attributes, "TANGENT", true, path), 4,
                                SIGNED_NORMALIZED_COMPONENTS, path + ".attributes.TANGENT");
                        requireCount(tangents.length / 4, vertexCount, path + ".attributes.TANGENT");
                        validateTangents(tangents, path + ".attributes.TANGENT");
                        canonical = canonicalWithTangent(meshName, positions, uv, normals, tangents, colors, indices);
                    } else {
                        MeshData base = new MeshData(meshName, baseVertices, indices, baseLayout(), org.lwjgl.opengl.GL11.GL_TRIANGLES);
                        TangentGenerator.Result generated = TangentGenerator.generate(base);
                        tangentFallbackTriangles += generated.fallbackTriangleCount();
                        tangentFallbackVertices += generated.fallbackVertexCount();
                        canonical = colors == null ? generated.mesh() : appendColors(generated.mesh(), colors);
                    }
                    vertexBytes += (long) canonical.vertices().length * Float.BYTES;
                    indexBytes += (long) canonical.indices().length * Integer.BYTES;
                    result.add(new LoadedGltfScene.Primitive(result.size(), meshIndex, primitiveIndex,
                            meshName, canonical, material, colors != null));
                }
            }
            return List.copyOf(result);
        }

        private SceneChoice selectScene() {
            List<Map<String, Object>> scenes = objects(root, "scenes");
            if (scenes.isEmpty()) throw fail("scenes", "asset has no scenes");
            int selected;
            if (options.scene() instanceof SceneSelection.ByIndex byIndex) selected = byIndex.index();
            else if (options.scene() instanceof SceneSelection.ByName byName) {
                List<Integer> matches = new ArrayList<>();
                for (int i = 0; i < scenes.size(); i++) if (byName.name().equals(scenes.get(i).get("name"))) matches.add(i);
                if (matches.size() != 1) throw fail("scenes", "scene name '" + byName.name() + "' matched " + matches.size() + " scenes");
                selected = matches.getFirst();
            } else selected = integer(root, "scene", false, "scene", 0);
            index(selected, scenes.size(), "scene");
            Map<String, Object> scene = scenes.get(selected);
            return new SceneChoice(selected, Objects.toString(scene.get("name"), ""), integers(scene.get("nodes"), "scenes[" + selected + "].nodes"));
        }

        private List<LoadedGltfScene.Node> decodeNodes(List<Map<String, Object>> dtos, int meshCount, List<Integer> roots) {
            int count = dtos.size();
            for (int rootIndex : roots) index(rootIndex, count, "scene.nodes");
            Matrix4f[] locals = new Matrix4f[count];
            List<List<Integer>> children = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Map<String, Object> dto = dtos.get(i);
                if (dto.containsKey("skin") || dto.containsKey("weights")) throw fail("nodes[" + i + "]", "skins and morph weights are not supported");
                int mesh = integer(dto, "mesh", false, "nodes[" + i + "].mesh", -1);
                if (mesh >= 0) index(mesh, meshCount, "nodes[" + i + "].mesh");
                List<Integer> childList = integers(dto.get("children"), "nodes[" + i + "].children");
                if (new HashSet<>(childList).size() != childList.size()) throw fail("nodes[" + i + "].children", "duplicate child");
                for (int child : childList) {
                    index(child, count, "nodes[" + i + "].children");
                }
                children.add(childList);
                locals[i] = localMatrix(dto, i);
            }
            Matrix4f[] worlds = new Matrix4f[count];
            byte[] visiting = new byte[count];
            int[] selectedParents = new int[count];
            java.util.Arrays.fill(selectedParents, -2);
            for (int rootIndex : roots) {
                if (selectedParents[rootIndex] != -2) throw fail("scene.nodes", "duplicate root node " + rootIndex);
                selectedParents[rootIndex] = -1;
                expandNode(rootIndex, -1, new Matrix4f(), 0, locals, children,
                        worlds, visiting, selectedParents);
            }
            List<LoadedGltfScene.Node> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Map<String, Object> dto = dtos.get(i);
                int mesh = integer(dto, "mesh", false, "nodes[" + i + "].mesh", -1);
                boolean reachable = worlds[i] != null;
                Matrix4f world = reachable ? worlds[i] : new Matrix4f(locals[i]);
                result.add(new LoadedGltfScene.Node(i, Objects.toString(dto.get("name"), ""), mesh,
                        children.get(i), locals[i], world, reachable, world.determinant3x3() < 0.0f));
            }
            return List.copyOf(result);
        }

        private void expandNode(int node, int parentIndex, Matrix4f parent, int depth,
                                Matrix4f[] locals, List<List<Integer>> children,
                                Matrix4f[] worlds, byte[] visiting, int[] parents) {
            if (depth > options.limits().hierarchyDepth()) throw fail("nodes[" + node + "]", "hierarchy depth exceeds limit");
            if (visiting[node] == 1) throw fail("nodes[" + node + "]", "node hierarchy cycle");
            if (visiting[node] == 2) return;
            visiting[node] = 1;
            worlds[node] = new Matrix4f(parent).mul(locals[node]);
            for (int child : children.get(node)) {
                if (parents[child] != -2 && parents[child] != node) {
                    throw fail("nodes[" + child + "]", "node has multiple parents in selected scene");
                }
                parents[child] = node;
                expandNode(child, node, worlds[node], depth + 1, locals, children,
                        worlds, visiting, parents);
            }
            visiting[node] = 2;
        }

        private Matrix4f localMatrix(Map<String, Object> dto, int index) {
            if (dto.containsKey("matrix") && (dto.containsKey("translation") || dto.containsKey("rotation") || dto.containsKey("scale"))) {
                throw fail("nodes[" + index + "]", "matrix cannot be combined with TRS");
            }
            if (dto.containsKey("matrix")) {
                float[] values = floatArray(dto.get("matrix"), 16, "nodes[" + index + "].matrix");
                return new Matrix4f().set(values);
            }
            Vector3f translation = vec3(dto.get("translation"), new Vector3f(), "nodes[" + index + "].translation");
            Vector3f scale = vec3(dto.get("scale"), new Vector3f(1.0f), "nodes[" + index + "].scale");
            float[] q = dto.containsKey("rotation") ? floatArray(dto.get("rotation"), 4, "nodes[" + index + "].rotation") : new float[]{0,0,0,1};
            Quaternionf rotation = new Quaternionf(q[0], q[1], q[2], q[3]);
            if (!Float.isFinite(rotation.lengthSquared()) || rotation.lengthSquared() <= 1.0e-12f) throw fail("nodes[" + index + "].rotation", "quaternion is zero or non-finite");
            rotation.normalize();
            Matrix4f result = new Matrix4f().translationRotateScale(translation, rotation, scale);
            if (!finite(result)) throw fail("nodes[" + index + "]", "transform is non-finite");
            return result;
        }

        private float[] accessorFloats(int accessorIndex, int components,
                                       Set<Integer> normalizedTypes, String path) {
            index(accessorIndex, accessors.size(), path);
            Map<String, Object> accessor = accessors.get(accessorIndex);
            int actualComponents = componentCount(string(accessor, "type", true,
                    "accessors[" + accessorIndex + "].type"), "accessors[" + accessorIndex + "].type");
            if (actualComponents != components) throw fail(path, "expected " + components + " components, got " + actualComponents);
            int componentType = integer(accessor, "componentType", true, "accessors[" + accessorIndex + "].componentType");
            boolean normalized = bool(accessor, "normalized", false, "accessors[" + accessorIndex + "].normalized");
            if (componentType != 5126 && !(normalized && normalizedTypes.contains(componentType))) {
                throw fail(path, "unsupported componentType " + componentType + (normalized ? " normalized" : ""));
            }
            int count = integer(accessor, "count", true, "accessors[" + accessorIndex + "].count");
            float[] result = new float[Math.multiplyExact(count, components)];
            readAccessor(accessorIndex, components, (out, component, buffer, offset, type, norm) ->
                    result[out] = readFloat(buffer, offset, type, norm));
            finite(result, path);
            return result;
        }

        private void validateBufferViews() {
            for (int i = 0; i < views.size(); i++) {
                Map<String, Object> view = views.get(i);
                int buffer = integer(view, "buffer", true, "bufferViews[" + i + "].buffer");
                index(buffer, buffers.size(), "bufferViews[" + i + "].buffer");
                int offset = integer(view, "byteOffset", false, "bufferViews[" + i + "].byteOffset", 0);
                int length = integer(view, "byteLength", true, "bufferViews[" + i + "].byteLength");
                if (offset < 0 || length < 0 || (long) offset + length > buffers.get(buffer).length) {
                    throw fail("bufferViews[" + i + "]", "range " + offset + ".."
                            + ((long) offset + length) + " exceeds buffer length " + buffers.get(buffer).length);
                }
                if (view.containsKey("byteStride")) {
                    int stride = integer(view, "byteStride", true, "bufferViews[" + i + "].byteStride");
                    if (stride < 4 || stride > 252 || stride % 4 != 0) {
                        throw fail("bufferViews[" + i + "].byteStride", "must be a 4-byte multiple in 4..252");
                    }
                }
            }
        }

        private void validatePositionBounds(Map<String, Object> accessor, float[] values, String path) {
            float[] min = accessor.containsKey("min") ? floatArray(accessor.get("min"), 3, path + ".min") : null;
            float[] max = accessor.containsKey("max") ? floatArray(accessor.get("max"), 3, path + ".max") : null;
            if ((min == null) != (max == null)) throw fail(path, "POSITION min and max must be provided together");
            if (min == null) return;
            for (int component = 0; component < 3; component++) {
                if (min[component] > max[component]) throw fail(path, "POSITION min exceeds max");
            }
            for (int i = 0; i < values.length; i++) {
                int component = i % 3;
                if (values[i] < min[component] || values[i] > max[component]) {
                    throw fail(path, "POSITION bounds do not enclose decoded vertex data");
                }
            }
        }

        private void validateTangents(float[] tangents, String path) {
            for (int i = 0; i < tangents.length; i += 4) {
                float lengthSquared = tangents[i] * tangents[i]
                        + tangents[i + 1] * tangents[i + 1]
                        + tangents[i + 2] * tangents[i + 2];
                if (!Float.isFinite(lengthSquared) || lengthSquared <= 1.0e-12f) {
                    throw fail(path, "tangent xyz must be finite and non-zero");
                }
                float w = tangents[i + 3];
                if (!Float.isFinite(w) || Math.abs(Math.abs(w) - 1.0f) > 1.0e-3f) {
                    throw fail(path, "tangent w must be -1 or +1");
                }
            }
        }

        private int[] accessorIndices(int accessorIndex, int vertexCount, String path) {
            index(accessorIndex, accessors.size(), path);
            Map<String, Object> accessor = accessors.get(accessorIndex);
            if (!"SCALAR".equals(accessor.get("type"))) throw fail(path, "indices accessor must be SCALAR");
            int type = integer(accessor, "componentType", true, path);
            if (!Set.of(5121, 5123, 5125).contains(type)) throw fail(path, "indices must use unsigned byte/short/int");
            int count = integer(accessor, "count", true, path);
            int[] result = new int[count];
            readAccessor(accessorIndex, 1, (out, component, buffer, offset, ignored, normalized) -> {
                long value = readUnsigned(buffer, offset, type);
                if (value > Integer.MAX_VALUE) throw fail(path, "index exceeds signed framework range: " + value);
                result[out] = (int) value;
            });
            for (int value : result) if (value < 0 || value >= vertexCount) throw fail(path, "index " + value + " outside vertex count " + vertexCount);
            return result;
        }

        private float[] colorAccessor(int accessorIndex, int vertexCount, String path) {
            index(accessorIndex, accessors.size(), path);
            Map<String, Object> accessor = accessors.get(accessorIndex);
            String type = string(accessor, "type", true, path + ".type");
            int components = componentCount(type, path + ".type");
            if (components != 3 && components != 4) throw fail(path, "COLOR_0 must be VEC3 or VEC4");
            int componentType = integer(accessor, "componentType", true, path + ".componentType");
            boolean normalized = bool(accessor, "normalized", false, path + ".normalized");
            if (componentType != 5126 && !(normalized && (componentType == 5121 || componentType == 5123))) {
                throw fail(path, "COLOR_0 must use FLOAT or normalized unsigned byte/short");
            }
            float[] raw = accessorFloats(accessorIndex, components,
                    UNSIGNED_NORMALIZED_COMPONENTS, path);
            requireCount(raw.length / components, vertexCount, path);
            for (int i = 0; i < raw.length; i++) raw[i] = Math.max(0.0f, Math.min(1.0f, raw[i]));
            return colorAccessorResult(raw, components);
        }

        private void readAccessor(int accessorIndex, int components, AccessorConsumer consumer) {
            Map<String, Object> accessor = accessors.get(accessorIndex);
            int type = integer(accessor, "componentType", true, "accessors[" + accessorIndex + "]");
            int bytes = componentBytes(type);
            int count = integer(accessor, "count", true, "accessors[" + accessorIndex + "].count");
            boolean normalized = bool(accessor, "normalized", false, "accessors[" + accessorIndex + "].normalized");
            int accessorOffset = integer(accessor, "byteOffset", false, "accessors[" + accessorIndex + "].byteOffset", 0);
            int viewIndex = integer(accessor, "bufferView", false, "accessors[" + accessorIndex + "].bufferView", -1);
            if (viewIndex >= 0) {
                index(viewIndex, views.size(), "accessors[" + accessorIndex + "].bufferView");
                Map<String, Object> view = views.get(viewIndex);
                int bufferIndex = integer(view, "buffer", true, "bufferViews[" + viewIndex + "].buffer");
                index(bufferIndex, buffers.size(), "bufferViews[" + viewIndex + "].buffer");
                int viewOffset = integer(view, "byteOffset", false, "bufferViews[" + viewIndex + "].byteOffset", 0);
                int viewLength = integer(view, "byteLength", true, "bufferViews[" + viewIndex + "].byteLength");
                int element = Math.multiplyExact(components, bytes);
                int stride = integer(view, "byteStride", false, "bufferViews[" + viewIndex + "].byteStride", element);
                if (stride < element || (view.containsKey("byteStride") && (stride < 4 || stride > 252 || stride % 4 != 0))) throw fail("bufferViews[" + viewIndex + "].byteStride", "invalid stride " + stride);
                long last = count == 0 ? accessorOffset : Math.addExact(accessorOffset, Math.addExact(Math.multiplyExact((long) (count - 1), stride), element));
                if (accessorOffset < 0 || last > viewLength) throw fail("accessors[" + accessorIndex + "]", "range exceeds bufferView[" + viewIndex + "]");
                if ((viewOffset + accessorOffset) % bytes != 0) throw fail(
                        "accessors[" + accessorIndex + "].byteOffset", "is not aligned to component size " + bytes);
                ByteBuffer data = ByteBuffer.wrap(buffers.get(bufferIndex)).order(ByteOrder.LITTLE_ENDIAN);
                for (int row = 0; row < count; row++) for (int component = 0; component < components; component++) {
                    int offset = Math.addExact(viewOffset, Math.addExact(accessorOffset, Math.addExact(Math.multiplyExact(row, stride), component * bytes)));
                    consumer.accept(row * components + component, component, data, offset, type, normalized);
                }
            } else {
                for (int row = 0; row < count; row++) {
                    int outputBase = Math.multiplyExact(row, components);
                    for (int component = 0; component < components; component++) {
                        int output = Math.addExact(outputBase, component);
                        consumer.accept(output, component, ZERO_ACCESSOR_BUFFER, 0, type, normalized);
                    }
                }
            }
            Map<String, Object> sparse = map(accessor.get("sparse"), "accessors[" + accessorIndex + "].sparse");
            if (sparse != null) applySparse(accessorIndex, sparse, components, consumer, type, normalized, count);
        }

        private void applySparse(int accessorIndex, Map<String, Object> sparse, int components,
                                 AccessorConsumer consumer, int valueType, boolean normalized, int count) {
            int sparseCount = integer(sparse, "count", true, "accessors[" + accessorIndex + "].sparse.count");
            if (sparseCount < 0 || sparseCount > count) throw fail("accessors[" + accessorIndex + "].sparse.count", "out of range");
            String sparsePath = "accessors[" + accessorIndex + "].sparse";
            String indicesPath = sparsePath + ".indices";
            String valuesPath = sparsePath + ".values";
            Map<String, Object> indicesDto = object(sparse, "indices", true, indicesPath);
            Map<String, Object> valuesDto = object(sparse, "values", true, valuesPath);
            int indexView = integer(indicesDto, "bufferView", true, indicesPath + ".bufferView");
            int indexType = integer(indicesDto, "componentType", true, indicesPath + ".componentType");
            if (!Set.of(5121, 5123, 5125).contains(indexType)) {
                throw fail(indicesPath, "componentType must be unsigned integer");
            }
            ByteBuffer indexData = viewBuffer(indexView, indicesPath + ".bufferView");
            int indexOffset = integer(indicesDto, "byteOffset", false, indicesPath + ".byteOffset", 0);
            int valueView = integer(valuesDto, "bufferView", true, valuesPath + ".bufferView");
            ByteBuffer valueData = viewBuffer(valueView, valuesPath + ".bufferView");
            int valueOffset = integer(valuesDto, "byteOffset", false, valuesPath + ".byteOffset", 0);
            int previous = -1;
            int indexBytes = componentBytes(indexType);
            int valueBytes = componentBytes(valueType);
            int indexViewOffset = integer(views.get(indexView), "byteOffset", false,
                    indicesPath + ".bufferView.byteOffset", 0);
            int valueViewOffset = integer(views.get(valueView), "byteOffset", false,
                    valuesPath + ".bufferView.byteOffset", 0);
            int valueElementBytes;
            try {
                valueElementBytes = Math.multiplyExact(components, valueBytes);
            } catch (ArithmeticException overflow) {
                throw fail(valuesPath, "element size overflow", overflow);
            }
            validateSparseRange(indicesPath, indexOffset, sparseCount, indexBytes,
                    indexBytes, indexData.remaining(), indexViewOffset);
            validateSparseRange(valuesPath, valueOffset, sparseCount, valueElementBytes,
                    valueBytes, valueData.remaining(), valueViewOffset);
            for (int row = 0; row < sparseCount; row++) {
                int sparseIndexOffset = Math.toIntExact((long) indexOffset + (long) row * indexBytes);
                long raw = readUnsigned(indexData, sparseIndexOffset, indexType);
                if (raw > Integer.MAX_VALUE) throw fail(indicesPath, "index too large");
                int target = (int) raw;
                if (target <= previous || target >= count) {
                    throw fail(indicesPath, "indices must be strictly increasing and in range");
                }
                previous = target;
                for (int component = 0; component < components; component++) {
                    int offset = Math.toIntExact((long) valueOffset
                            + (long) row * valueElementBytes + (long) component * valueBytes);
                    consumer.accept(target * components + component, component, valueData, offset, valueType, normalized);
                }
            }
        }

        private ByteBuffer viewBuffer(int viewIndex) {
            return viewBuffer(viewIndex, "bufferView");
        }

        private ByteBuffer viewBuffer(int viewIndex, String path) {
            index(viewIndex, views.size(), path);
            Map<String, Object> view = views.get(viewIndex);
            int bufferIndex = integer(view, "buffer", true, path + ".buffer");
            index(bufferIndex, buffers.size(), path + ".buffer");
            int offset = integer(view, "byteOffset", false, path + ".byteOffset", 0);
            int length = integer(view, "byteLength", true, path + ".byteLength");
            if (offset < 0 || length < 0 || (long) offset + length > buffers.get(bufferIndex).length) {
                throw fail(path, "bufferView range exceeds buffer");
            }
            return ByteBuffer.wrap(buffers.get(bufferIndex), offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
        }

        private void validateSparseRange(String path, int offset, int count, int elementBytes,
                                         int componentAlignment, int viewLength, int viewOffset) {
            if (offset < 0) throw fail(path, "byteOffset must be non-negative");
            if (((long) viewOffset + offset) % componentAlignment != 0) {
                throw fail(path, "byteOffset " + offset
                        + " plus bufferView offset " + viewOffset
                        + " is not aligned to component size " + componentAlignment);
            }
            final long byteCount;
            final long end;
            try {
                byteCount = Math.multiplyExact((long) count, elementBytes);
                end = Math.addExact((long) offset, byteCount);
            } catch (ArithmeticException overflow) {
                throw fail(path, "byte range overflow", overflow);
            }
            if (end > viewLength) {
                throw fail(path, "byte range " + offset + ".." + end
                        + " exceeds bufferView length " + viewLength);
            }
        }

        private byte[] viewBytes(int viewIndex, String path) {
            ByteBuffer buffer = viewBuffer(viewIndex, path);
            byte[] result = new byte[buffer.remaining()]; buffer.get(result); return result;
        }

        private GltfAssetException fail(String location, String message) { return error(source, GltfAssetException.Phase.DECODE, location, message); }
        private GltfAssetException fail(String location, String message, Throwable cause) {
            return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location, null, message, cause);
        }
    }

    private static MeshData canonicalWithTangent(String name, float[] p, float[] uv, float[] n, float[] t, float[] c, int[] indices) {
        int count = p.length / 3;
        int stride = c == null ? 12 : 16;
        float[] values = new float[count * stride];
        for (int i = 0; i < count; i++) {
            int out = i * stride;
            System.arraycopy(p, i * 3, values, out, 3);
            System.arraycopy(uv, i * 2, values, out + 3, 2);
            Vector3f normal = new Vector3f(n[i * 3], n[i * 3 + 1], n[i * 3 + 2]).normalize();
            Vector3f tangent = new Vector3f(t[i * 4], t[i * 4 + 1], t[i * 4 + 2]);
            tangent.sub(new Vector3f(normal).mul(normal.dot(tangent)));
            if (!Float.isFinite(tangent.lengthSquared()) || tangent.lengthSquared() <= 1e-12f) tangent.set(1, 0, 0);
            else tangent.normalize();
            values[out + 5] = normal.x; values[out + 6] = normal.y; values[out + 7] = normal.z;
            values[out + 8] = tangent.x; values[out + 9] = tangent.y; values[out + 10] = tangent.z;
            values[out + 11] = t[i * 4 + 3] < 0 ? -1 : 1;
            if (c != null) System.arraycopy(c, i * 4, values, out + 12, 4);
        }
        return new MeshData(name, values, indices, c == null ? TangentGenerator.pbrLayout() : colorLayout(), org.lwjgl.opengl.GL11.GL_TRIANGLES);
    }

    private static MeshData appendColors(MeshData source, float[] colors) {
        float[] old = source.vertices();
        int count = source.vertexCount();
        float[] values = new float[count * 16];
        for (int i = 0; i < count; i++) {
            System.arraycopy(old, i * 12, values, i * 16, 12);
            System.arraycopy(colors, i * 4, values, i * 16 + 12, 4);
        }
        return new MeshData(source.name(), values, source.indices(), colorLayout(), source.primitiveMode());
    }

    private static VertexLayout baseLayout() {
        return VertexLayout.interleaved(8 * Float.BYTES,
                attribute(0, 3, 0, VertexSemantic.POSITION), attribute(1, 2, 3, VertexSemantic.TEXCOORD_0),
                attribute(2, 3, 5, VertexSemantic.NORMAL));
    }
    private static VertexLayout colorLayout() {
        return VertexLayout.interleaved(16 * Float.BYTES,
                attribute(0, 3, 0, VertexSemantic.POSITION), attribute(1, 2, 3, VertexSemantic.TEXCOORD_0),
                attribute(2, 3, 5, VertexSemantic.NORMAL), attribute(3, 4, 8, VertexSemantic.TANGENT),
                attribute(4, 4, 12, VertexSemantic.COLOR_0));
    }
    private static VertexAttribute attribute(int index, int size, int offset, VertexSemantic semantic) {
        return VertexAttribute.builder().index(index).size(size).type(GL_FLOAT).offsetBytes((long) offset * Float.BYTES).semantic(semantic).build();
    }
    private static float[] interleaveBase(float[] p, float[] uv, float[] n) {
        int count = p.length / 3; float[] result = new float[count * 8];
        for (int i = 0; i < count; i++) { System.arraycopy(p, i*3, result, i*8, 3); System.arraycopy(uv, i*2, result, i*8+3, 2); System.arraycopy(n, i*3, result, i*8+5, 3); }
        return result;
    }

    private static boolean imageSignatureMatches(byte[] bytes, String mime) {
        boolean png = bytes.length >= 8 && (bytes[0]&255)==137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71;
        boolean jpeg = bytes.length >= 3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255;
        return mime.equals("image/png") ? png : mime.equals("image/jpeg") && jpeg;
    }
    private static String inferImageMime(String uri, byte[] bytes) {
        String lower = uri.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (imageSignatureMatches(bytes, "image/png")) return "image/png";
        if (imageSignatureMatches(bytes, "image/jpeg")) return "image/jpeg";
        return null;
    }
    private static float[] colorAccessorResult(float[] raw, int components) {
        if (components == 4) return raw;
        float[] result = new float[raw.length / 3 * 4];
        for (int i=0;i<raw.length/3;i++){System.arraycopy(raw,i*3,result,i*4,3);result[i*4+3]=1;}
        return result;
    }
    private static void normalizeVectors(float[] values, int components, String path) {
        for(int i=0;i<values.length;i+=components){float length=0;for(int j=0;j<3;j++)length+=values[i+j]*values[i+j];if(!Float.isFinite(length)||length<=1e-12f)throw new DecodeFailure(path,"contains zero/non-finite vector");float inv=(float)(1/Math.sqrt(length));for(int j=0;j<3;j++)values[i+j]*=inv;}
    }
    private static float readFloat(ByteBuffer buffer,int offset,int type,boolean normalized){return switch(type){case 5126->buffer.getFloat(offset);case 5120->normalized?Math.max(-1f,buffer.get(offset)/127f):buffer.get(offset);case 5121->normalized?(buffer.get(offset)&255)/255f:(buffer.get(offset)&255);case 5122->normalized?Math.max(-1f,buffer.getShort(offset)/32767f):buffer.getShort(offset);case 5123->normalized?(buffer.getShort(offset)&65535)/65535f:(buffer.getShort(offset)&65535);default->throw new IllegalArgumentException("unsupported component type "+type);};}
    private static long readUnsigned(ByteBuffer buffer,int offset,int type){return switch(type){case 5121->buffer.get(offset)&255L;case 5123->buffer.getShort(offset)&65535L;case 5125->Integer.toUnsignedLong(buffer.getInt(offset));default->throw new IllegalArgumentException("not an unsigned component type");};}
    private static int componentBytes(int type){return switch(type){case 5120,5121->1;case 5122,5123->2;case 5125,5126->4;default->throw new IllegalArgumentException("unsupported component type "+type);};}
    private static int componentCount(String type,String path){return switch(type){case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;case "MAT4"->16;default->throw new DecodeFailure(path,"unsupported accessor type "+type);};}
    private static boolean finite(Matrix4f value){for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(!Float.isFinite(value.get(c,r)))return false;return true;}
    private static void finite(float[] values,String path){for(float value:values)if(!Float.isFinite(value))throw new DecodeFailure(path,"contains non-finite value");}
    private static boolean validVersion(String value){return value.matches("[0-9]+(?:\\.[0-9]+)*");}
    private static int compareVersion(String a,String b){String[] aa=a.split("\\.");String[] bb=b.split("\\.");for(int i=0;i<Math.max(aa.length,bb.length);i++){java.math.BigInteger av=i<aa.length?new java.math.BigInteger(aa[i]):java.math.BigInteger.ZERO;java.math.BigInteger bv=i<bb.length?new java.math.BigInteger(bb[i]):java.math.BigInteger.ZERO;int comparison=av.compareTo(bv);if(comparison!=0)return comparison;}return 0;}
    private static void limit(AssetRef source,String name,long actual,long max,String path){if(actual>max)throw error(source,GltfAssetException.Phase.DECODE,path,"limit "+name+" exceeded: "+actual+" > "+max);}
    private static void index(int index,int size,String path){if(index<0||index>=size)throw new DecodeFailure(path,"index "+index+" outside 0.."+(size-1));}
    private static void requireCount(int actual,int expected,String path){if(actual!=expected)throw new DecodeFailure(path,"count "+actual+" differs from POSITION count "+expected);}
    private static GltfAssetException error(AssetRef source,GltfAssetException.Phase phase,String location,String message){return new GltfAssetException(source,phase,location,message);}
    private static GltfAssetException error(AssetRef source,GltfAssetException.Phase phase,String location,String message,Throwable cause){return new GltfAssetException(source,phase,location,null,message,cause);}

    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value,String path){if(value==null)return null;if(!(value instanceof Map<?,?>))throw new DecodeFailure(path,"must be an object");return (Map<String,Object>)value;}
    private static Map<String,Object> object(Map<String,Object> owner,String key,boolean required,String path){Map<String,Object> value=map(owner.get(key),path);if(required&&value==null)throw new DecodeFailure(path,"is required");return value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> objects(Map<String,Object> owner,String key){Object value=owner.get(key);if(value==null)return List.of();if(!(value instanceof List<?> list))throw new DecodeFailure(key,"must be an array");List<Map<String,Object>> result=new ArrayList<>();for(int i=0;i<list.size();i++)result.add(map(list.get(i),key+"["+i+"]"));return List.copyOf(result);}
    private static String string(Map<String,Object> owner,String key,boolean required,String path){Object value=owner.get(key);if(value==null){if(required)throw new DecodeFailure(path,"is required");return null;}if(!(value instanceof String text))throw new DecodeFailure(path,"must be a string");return text;}
    private static int integer(Map<String,Object> owner,String key,boolean required,String path){return integer(owner,key,required,path,0);}
    private static int integer(Map<String,Object> owner,String key,boolean required,String path,int fallback){Object value=owner.get(key);if(value==null){if(required)throw new DecodeFailure(path,"is required");return fallback;}if(!(value instanceof Number number)||number.longValue()!=number.doubleValue()||number.longValue()<Integer.MIN_VALUE||number.longValue()>Integer.MAX_VALUE)throw new DecodeFailure(path,"must be an integer");return number.intValue();}
    private static float decimal(Map<String,Object> owner,String key,float fallback,String path){Object value=owner.get(key);if(value==null)return fallback;if(!(value instanceof Number number))throw new DecodeFailure(path+"."+key,"must be numeric");float result=number.floatValue();if(!Float.isFinite(result))throw new DecodeFailure(path+"."+key,"must be finite");return result;}
    private static boolean bool(Map<String,Object> owner,String key,boolean fallback,String path){Object value=owner.get(key);if(value==null)return fallback;if(!(value instanceof Boolean result))throw new DecodeFailure(path,"must be boolean");return result;}
    private static List<Integer> integers(Object value,String path){if(value==null)return List.of();if(!(value instanceof List<?> list))throw new DecodeFailure(path,"must be an array");List<Integer> result=new ArrayList<>();for(Object element:list){if(!(element instanceof Number number)||number.longValue()!=number.doubleValue())throw new DecodeFailure(path,"must contain integers");try{result.add(Math.toIntExact(number.longValue()));}catch(ArithmeticException failure){throw new DecodeFailure(path,"contains an integer outside the supported range");}}return List.copyOf(result);}
    private static List<String> strings(Object value,String path){if(value==null)return List.of();if(!(value instanceof List<?> list))throw new DecodeFailure(path,"must be an array");List<String> result=new ArrayList<>();for(Object element:list){if(!(element instanceof String text))throw new DecodeFailure(path,"must contain strings");result.add(text);}return List.copyOf(result);}
    private static float[] floatArray(Object value,int length,String path){if(!(value instanceof List<?> list)||list.size()!=length)throw new DecodeFailure(path,"must contain "+length+" numbers");float[] result=new float[length];for(int i=0;i<length;i++){if(!(list.get(i) instanceof Number n))throw new DecodeFailure(path,"must contain numbers");result[i]=n.floatValue();if(!Float.isFinite(result[i]))throw new DecodeFailure(path,"contains non-finite value");}return result;}
    private static Vector3f vec3(Object value,Vector3f fallback,String path){if(value==null)return new Vector3f(fallback);float[] v=floatArray(value,3,path);return new Vector3f(v[0],v[1],v[2]);}
    private static Vector4f vec4(Object value,Vector4f fallback,String path){if(value==null)return new Vector4f(fallback);float[] v=floatArray(value,4,path);return new Vector4f(v[0],v[1],v[2],v[3]);}
    private static void textureInfo(Object raw,PbrTextureRole role,Map<PbrTextureRole,Integer> refs,int textureCount,String path){Map<String,Object> info=map(raw,path);if(info==null)return;int texCoord=integer(info,"texCoord",false,path+".texCoord",0);if(texCoord!=0)throw new DecodeFailure(path+".texCoord","only TEXCOORD_0 is supported, got "+texCoord);int index=integer(info,"index",true,path+".index");if(index<0||index>=textureCount)throw new DecodeFailure(path+".index","texture index out of range: "+index);refs.put(role,index);if(info.containsKey("extensions"))throw new DecodeFailure(path+".extensions","texture extensions are unsupported");}

    private record Document(byte[] json,byte[] bin,List<String>warnings){}
    private record SceneChoice(int index,String name,List<Integer> roots){}
    private static final class DecodeFailure extends RuntimeException {
        private final String location;
        private DecodeFailure(String location, String message) {
            super(message);
            this.location = location;
        }
    }
    @FunctionalInterface private interface AccessorConsumer{void accept(int outputIndex,int component,ByteBuffer buffer,int offset,int componentType,boolean normalized);}
}
