package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.BlendMode;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Parses the legacy line-oriented scene manifest syntax. */
final class SceneTextManifestParser {
    private SceneTextManifestParser() {
    }

    static SceneAssetConfig parse(String source) {
        Map<String, ShaderAsset> shaders = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.TextureDef> textures = new LinkedHashMap<>();
        Map<String, MaterialDef> materials = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.ModelDef> models = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.ObjectDef> objects = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.LightDef> lights = new LinkedHashMap<>();
        Map<String, SceneAssetConfig.GltfSceneDef> gltfScenes = new LinkedHashMap<>();

        String[] lines = Objects.requireNonNull(source, "source").split("\\R");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = stripComment(lines[lineNumber]).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            try {
                switch (parts[0]) {
                    case "shader" -> shaders.put(parts[1], ShaderAsset.of(parts[2], parts[3]));
                    case "texture" -> textures.put(parts[1], new SceneAssetConfig.TextureDef(AssetRef.of(parts[2]),
                            parts.length < 4 || Boolean.parseBoolean(parts[3]),
                            parts.length < 5 ? TextureColorSpace.LINEAR : SceneValueParser.colorSpace(parts[4])));
                    case "material" -> materials.put(parts[1], parseMaterial(parts));
                    case "model" -> models.put(parts[1], new SceneAssetConfig.ModelDef(AssetRef.of(parts[2])));
                    case "object" -> objects.put(parts[1], parseObject(parts));
                    case "light" -> lights.put(parts[1], parseLight(parts));
                    case "gltf" -> gltfScenes.put(parts[1], parseGltf(parts));
                    default -> throw new GlException("Unknown scene config directive: " + parts[0]);
                }
            } catch (RuntimeException e) {
                throw new GlException("Invalid scene config at line " + (lineNumber + 1) + ": " + lines[lineNumber], e);
            }
        }
        return new SceneAssetConfig(shaders, textures, materials, models, objects, lights, gltfScenes);
    }

    private static MaterialDef parseMaterial(String[] parts) {
        requireLength(parts, 3);
        BlendMode blendMode = parts.length >= 4 ? BlendMode.valueOf(parts[3].toUpperCase()) : BlendMode.OPAQUE;
        boolean depthTest = parts.length < 5 || Boolean.parseBoolean(parts[4]);
        return new MaterialDef(parts[2], List.of(), blendMode, depthTest);
    }

    private static SceneAssetConfig.ObjectDef parseObject(String[] parts) {
        requireLength(parts, 12);
        return new SceneAssetConfig.ObjectDef(
                parts[2],
                parts[3],
                vector(parts, 4),
                vector(parts, 7),
                Float.parseFloat(parts[10]),
                Boolean.parseBoolean(parts[11]));
    }

    private static SceneAssetConfig.LightDef parseLight(String[] parts) {
        requireLength(parts, 12);
        return new SceneAssetConfig.LightDef(
                parts[2],
                vector(parts, 3),
                vector(parts, 6),
                Float.parseFloat(parts[9]),
                Float.parseFloat(parts[10]),
                Boolean.parseBoolean(parts[11]));
    }

    private static SceneAssetConfig.GltfSceneDef parseGltf(String[] parts) {
        requireLength(parts, 12);
        return new SceneAssetConfig.GltfSceneDef(AssetRef.of(parts[2]), parts[3],
                vector(parts, 4), vector(parts, 7),
                Float.parseFloat(parts[10]), Boolean.parseBoolean(parts[11]));
    }

    private static Vector3f vector(String[] parts, int start) {
        return new Vector3f(Float.parseFloat(parts[start]), Float.parseFloat(parts[start + 1]),
                Float.parseFloat(parts[start + 2]));
    }

    private static void requireLength(String[] parts, int length) {
        if (parts.length < length) {
            throw new GlException("Expected at least " + length + " tokens");
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }
}
