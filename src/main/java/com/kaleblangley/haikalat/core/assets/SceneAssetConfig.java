package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record SceneAssetConfig(
        Map<String, ShaderAsset> shaders,
        Map<String, TextureDef> textures,
        Map<String, ModelDef> models,
        Map<String, ObjectDef> objects,
        Map<String, LightDef> lights
) {
    public SceneAssetConfig {
        shaders = Map.copyOf(Objects.requireNonNull(shaders, "shaders"));
        textures = Map.copyOf(Objects.requireNonNull(textures, "textures"));
        models = Map.copyOf(Objects.requireNonNull(models, "models"));
        objects = Map.copyOf(Objects.requireNonNull(objects, "objects"));
        lights = Map.copyOf(Objects.requireNonNull(lights, "lights"));
    }

    public static SceneAssetConfig load(ResourceLocator locator, String path) {
        return parse(locator.readString(AssetRef.of(path)));
    }

    public static SceneAssetConfig parse(String source) {
        Map<String, ShaderAsset> shaders = new LinkedHashMap<>();
        Map<String, TextureDef> textures = new LinkedHashMap<>();
        Map<String, ModelDef> models = new LinkedHashMap<>();
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        Map<String, LightDef> lights = new LinkedHashMap<>();

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
                    case "texture" -> textures.put(parts[1], new TextureDef(AssetRef.of(parts[2]),
                            parts.length < 4 || Boolean.parseBoolean(parts[3])));
                    case "model" -> models.put(parts[1], new ModelDef(AssetRef.of(parts[2])));
                    case "object" -> objects.put(parts[1], parseObject(parts));
                    case "light" -> lights.put(parts[1], parseLight(parts));
                    default -> throw new GlException("Unknown scene config directive: " + parts[0]);
                }
            } catch (RuntimeException e) {
                throw new GlException("Invalid scene config at line " + (lineNumber + 1) + ": " + lines[lineNumber], e);
            }
        }
        return new SceneAssetConfig(shaders, textures, models, objects, lights);
    }

    private static ObjectDef parseObject(String[] parts) {
        requireLength(parts, 12);
        return new ObjectDef(
                parts[2],
                parts[3],
                new Vector3f(Float.parseFloat(parts[4]), Float.parseFloat(parts[5]), Float.parseFloat(parts[6])),
                new Vector3f(Float.parseFloat(parts[7]), Float.parseFloat(parts[8]), Float.parseFloat(parts[9])),
                Float.parseFloat(parts[10]),
                Boolean.parseBoolean(parts[11]));
    }

    private static LightDef parseLight(String[] parts) {
        requireLength(parts, 12);
        return new LightDef(
                parts[2],
                new Vector3f(Float.parseFloat(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5])),
                new Vector3f(Float.parseFloat(parts[6]), Float.parseFloat(parts[7]), Float.parseFloat(parts[8])),
                Float.parseFloat(parts[9]),
                Float.parseFloat(parts[10]),
                Boolean.parseBoolean(parts[11]));
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

    public record TextureDef(AssetRef path, boolean flipVertically) {
    }

    public record ModelDef(AssetRef path) {
    }

    public record ObjectDef(
            String model,
            String material,
            Vector3f position,
            Vector3f rotationRadians,
            float scale,
            boolean castShadows
    ) {
        public ObjectDef {
            position = new Vector3f(Objects.requireNonNull(position, "position"));
            rotationRadians = new Vector3f(Objects.requireNonNull(rotationRadians, "rotationRadians"));
        }
    }

    public record LightDef(
            String type,
            Vector3f positionOrDirection,
            Vector3f color,
            float intensity,
            float range,
            boolean castShadows
    ) {
        public LightDef {
            positionOrDirection = new Vector3f(Objects.requireNonNull(positionOrDirection, "positionOrDirection"));
            color = new Vector3f(Objects.requireNonNull(color, "color"));
        }
    }
}
