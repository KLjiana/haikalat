package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.index;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfChecks.limit;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.bool;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.decimal;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.integer;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.map;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.object;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.string;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.vec3;
import static com.kaleblangley.haikalat.core.assets.gltf.GltfJson.vec4;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_REPEAT;

/** glTF sampler、texture、image 与 metallic-roughness 材质解码阶段。 */
final class GltfMaterialDecoder {
    private final AssetRef source;
    private final GltfAssetLimits limits;
    private final GltfUriResolver uriResolver;
    private final GltfBufferTable buffers;
    private final boolean strictExtensions;

    GltfMaterialDecoder(AssetRef source, GltfAssetLimits limits,
                        GltfUriResolver uriResolver, GltfBufferTable buffers,
                        boolean strictExtensions) {
        this.source = source;
        this.limits = limits;
        this.uriResolver = uriResolver;
        this.buffers = buffers;
        this.strictExtensions = strictExtensions;
    }

    Result decode(List<Map<String, Object>> samplerDefinitions,
                  List<Map<String, Object>> textureDefinitions,
                  List<Map<String, Object>> imageDefinitions,
                  List<Map<String, Object>> materialDefinitions) {
        List<LoadedGltfScene.SamplerDef> samplers = decodeSamplers(samplerDefinitions);
        List<LoadedGltfScene.TextureDef> textures = decodeTextures(textureDefinitions,
                imageDefinitions.size(), samplers.size());
        ImageResult imageResult = decodeImages(imageDefinitions);
        List<LoadedGltfScene.MaterialDef> materials = decodeMaterials(materialDefinitions,
                textures.size());
        return new Result(samplers, textures, imageResult.images(), materials,
                imageResult.encodedBytes());
    }

    LoadedGltfScene.MaterialDef defaultMaterial(int index) {
        PbrMaterialProperties properties = new PbrMaterialProperties(new Vector4f(1.0f),
                1.0f, 1.0f, 1.0f, 1.0f, new Vector3f(), Map.of());
        return new LoadedGltfScene.MaterialDef(index, "glTF default", properties,
                Map.of(), false, GltfAlphaMode.OPAQUE, 0.0f);
    }

    private List<LoadedGltfScene.SamplerDef> decodeSamplers(
            List<Map<String, Object>> definitions) {
        List<LoadedGltfScene.SamplerDef> result = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            Map<String, Object> definition = definitions.get(index);
            String path = "samplers[" + index + "]";
            int min = integer(definition, "minFilter", false, path + ".minFilter",
                    GL_LINEAR_MIPMAP_LINEAR);
            int mag = integer(definition, "magFilter", false, path + ".magFilter", GL_LINEAR);
            int wrapS = integer(definition, "wrapS", false, path + ".wrapS", GL_REPEAT);
            int wrapT = integer(definition, "wrapT", false, path + ".wrapT", GL_REPEAT);
            if (!Set.of(9728, 9729, 9984, 9985, 9986, 9987).contains(min)
                    || !Set.of(9728, 9729).contains(mag)
                    || !Set.of(33071, 33648, 10497).contains(wrapS)
                    || !Set.of(33071, 33648, 10497).contains(wrapT)) {
                throw fail(path, "invalid sampler enum");
            }
            result.add(new LoadedGltfScene.SamplerDef(index, min, mag, wrapS, wrapT));
        }
        return List.copyOf(result);
    }

    private List<LoadedGltfScene.TextureDef> decodeTextures(
            List<Map<String, Object>> definitions, int imageCount, int samplerCount) {
        List<LoadedGltfScene.TextureDef> result = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            Map<String, Object> definition = definitions.get(index);
            String path = "textures[" + index + "]";
            if (strictExtensions && definition.containsKey("extensions")) {
                throw fail(path + ".extensions", "texture extensions are not supported");
            }
            int image = integer(definition, "source", true, path + ".source");
            int sampler = integer(definition, "sampler", false, path + ".sampler", -1);
            index(image, imageCount, path + ".source");
            if (sampler >= 0) index(sampler, samplerCount, path + ".sampler");
            result.add(new LoadedGltfScene.TextureDef(index, image, sampler));
        }
        return List.copyOf(result);
    }

    private ImageResult decodeImages(List<Map<String, Object>> definitions) {
        List<LoadedGltfScene.ImageDef> result = new ArrayList<>(definitions.size());
        long encodedBytes = 0L;
        for (int index = 0; index < definitions.size(); index++) {
            Map<String, Object> definition = definitions.get(index);
            String path = "images[" + index + "]";
            String uri = string(definition, "uri", false, path + ".uri");
            String mime = string(definition, "mimeType", false, path + ".mimeType");
            int view = integer(definition, "bufferView", false, path + ".bufferView", -1);
            if ((uri == null) == (view < 0)) {
                throw fail(path, "exactly one of uri or bufferView is required");
            }
            byte[] encoded;
            String sourceUri = uri == null ? "bufferView[" + view + "]" : uri;
            if (uri != null) {
                encoded = uriResolver.resolve(uri, true, path + ".uri");
                if (mime == null && uri.startsWith("data:")) mime = uri.substring(5, uri.indexOf(';'));
                if (mime == null) mime = inferImageMime(uri, encoded);
            } else {
                if (mime == null) throw fail(path + ".mimeType", "bufferView image requires mimeType");
                encoded = buffers.viewBytes(view, path + ".bufferView");
            }
            if (!Set.of("image/png", "image/jpeg").contains(mime)) {
                throw fail(path + ".mimeType", "only PNG/JPEG images are supported");
            }
            if (!imageSignatureMatches(encoded, mime)) {
                throw fail(path, "mimeType " + mime + " does not match image signature");
            }
            limit(source, "imageBytes", encoded.length, limits.imageBytes(), path);
            try {
                encodedBytes = Math.addExact(encodedBytes, encoded.length);
            } catch (ArithmeticException error) {
                throw fail(path, "encoded image byte total overflows", error);
            }
            result.add(new LoadedGltfScene.ImageDef(index,
                    string(definition, "name", false, path + ".name"), mime, sourceUri, encoded));
        }
        return new ImageResult(List.copyOf(result), encodedBytes);
    }

    private List<LoadedGltfScene.MaterialDef> decodeMaterials(
            List<Map<String, Object>> definitions, int textureCount) {
        List<LoadedGltfScene.MaterialDef> result = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            Map<String, Object> definition = definitions.get(index);
            String path = "materials[" + index + "]";
            String alpha = string(definition, "alphaMode", false, path + ".alphaMode");
            GltfAlphaMode alphaMode = switch (alpha == null ? "OPAQUE" : alpha) {
                case "OPAQUE" -> GltfAlphaMode.OPAQUE;
                case "MASK" -> GltfAlphaMode.MASK;
                case "BLEND" -> GltfAlphaMode.BLEND;
                default -> throw fail(path + ".alphaMode", "unknown alpha mode " + alpha);
            };
            float alphaCutoff = alphaMode == GltfAlphaMode.MASK
                    ? decimal(definition, "alphaCutoff", 0.5f, path) : 0.0f;
            if (alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
                throw fail(path + ".alphaCutoff", "must be in [0, 1]");
            }
            if (strictExtensions && definition.containsKey("extensions")) {
                throw fail(path + ".extensions", "material extensions are not supported");
            }
            Map<String, Object> pbr = object(definition, "pbrMetallicRoughness", false,
                    path + ".pbrMetallicRoughness");
            if (pbr == null) pbr = Map.of();
            String pbrPath = path + ".pbrMetallicRoughness";
            Vector4f base = vec4(pbr.get("baseColorFactor"), new Vector4f(1.0f),
                    pbrPath + ".baseColorFactor");
            float metallic = decimal(pbr, "metallicFactor", 1.0f, pbrPath);
            float roughness = decimal(pbr, "roughnessFactor", 1.0f, pbrPath);
            float normalScale = 1.0f;
            float occlusionStrength = 1.0f;
            Vector3f emissive = vec3(definition.get("emissiveFactor"), new Vector3f(),
                    path + ".emissiveFactor");
            EnumMap<PbrTextureRole, Integer> references = new EnumMap<>(PbrTextureRole.class);
            textureInfo(pbr.get("baseColorTexture"), PbrTextureRole.BASE_COLOR, references,
                    textureCount, pbrPath + ".baseColorTexture");
            textureInfo(pbr.get("metallicRoughnessTexture"), PbrTextureRole.METALLIC_ROUGHNESS,
                    references, textureCount, pbrPath + ".metallicRoughnessTexture");
            Map<String, Object> normal = map(definition.get("normalTexture"), path + ".normalTexture");
            if (normal != null) {
                textureInfo(normal, PbrTextureRole.NORMAL, references, textureCount,
                        path + ".normalTexture");
                normalScale = decimal(normal, "scale", 1.0f, path + ".normalTexture");
            }
            Map<String, Object> occlusion = map(definition.get("occlusionTexture"),
                    path + ".occlusionTexture");
            if (occlusion != null) {
                textureInfo(occlusion, PbrTextureRole.OCCLUSION, references, textureCount,
                        path + ".occlusionTexture");
                occlusionStrength = decimal(occlusion, "strength", 1.0f,
                        path + ".occlusionTexture");
            }
            textureInfo(definition.get("emissiveTexture"), PbrTextureRole.EMISSIVE, references,
                    textureCount, path + ".emissiveTexture");
            PbrMaterialProperties properties;
            try {
                properties = new PbrMaterialProperties(base, metallic, roughness,
                        normalScale, occlusionStrength, emissive, Map.of());
            } catch (IllegalArgumentException error) {
                throw fail(path, error.getMessage(), error);
            }
            result.add(new LoadedGltfScene.MaterialDef(index,
                    string(definition, "name", false, path + ".name"), properties,
                    references, bool(definition, "doubleSided", false, path + ".doubleSided"),
                    alphaMode, alphaCutoff));
        }
        return List.copyOf(result);
    }

    private void textureInfo(Object raw, PbrTextureRole role,
                             Map<PbrTextureRole, Integer> references,
                             int textureCount, String path) {
        Map<String, Object> info = map(raw, path);
        if (info == null) return;
        int texCoord = integer(info, "texCoord", false, path + ".texCoord", 0);
        if (texCoord != 0) {
            throw fail(path + ".texCoord", "only TEXCOORD_0 is supported, got " + texCoord);
        }
        int index = integer(info, "index", true, path + ".index");
        if (index < 0 || index >= textureCount) {
            throw fail(path + ".index", "texture index out of range: " + index);
        }
        references.put(role, index);
        if (strictExtensions && info.containsKey("extensions")) {
            throw fail(path + ".extensions", "texture extensions are unsupported");
        }
    }

    private static boolean imageSignatureMatches(byte[] bytes, String mime) {
        boolean png = bytes.length >= 8 && (bytes[0] & 255) == 137
                && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71;
        boolean jpeg = bytes.length >= 3 && (bytes[0] & 255) == 255
                && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
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

    private GltfAssetException fail(String location, String message) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location, message);
    }

    private GltfAssetException fail(String location, String message, Throwable cause) {
        return new GltfAssetException(source, GltfAssetException.Phase.DECODE, location,
                null, message, cause);
    }

    record Result(List<LoadedGltfScene.SamplerDef> samplers,
                  List<LoadedGltfScene.TextureDef> textures,
                  List<LoadedGltfScene.ImageDef> images,
                  List<LoadedGltfScene.MaterialDef> materials,
                  long encodedImageBytes) {
    }

    private record ImageResult(List<LoadedGltfScene.ImageDef> images, long encodedBytes) {
    }
}
