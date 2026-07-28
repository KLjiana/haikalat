package com.kaleblangley.haikalat.subsystems.scene;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict streaming parser and validator for the versioned {@code haikalat.scene} format. */
public final class SceneJsonParser {
    public static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_STRING_LENGTH = 4096;
    public static final int MAX_NODES = 10_000;
    public static final int MAX_PARENT_DEPTH = 256;
    public static final int MAX_GLTF_RENDERABLES = 2_048;
    public static final int MAX_DIRECTIONAL_LIGHTS = 2;
    public static final int MAX_POINT_LIGHTS = 8;
    public static final int MAX_SPOT_LIGHTS = 4;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private SceneJsonParser() {}

    public static SceneDefinition parse(AssetId source, byte[] encoded) {
        if (encoded == null) throw error(source, "$", "document must not be null");
        if (encoded.length > MAX_DOCUMENT_BYTES) {
            throw error(source, "$", "document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
        }
        try (JsonParser parser = FACTORY.createParser(encoded)) {
            Context context = new Context(source, parser);
            SceneDefinition definition = context.parseDocument();
            if (parser.nextToken() != null) {
                throw context.fail("$", "trailing JSON token");
            }
            return definition;
        } catch (SceneParseFailure failure) {
            throw (IllegalArgumentException) failure.getCause();
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException) throw (IllegalArgumentException) failure;
            throw error(source, "$", "invalid scene JSON: " + failure.getMessage(), failure);
        }
    }

    private static IllegalArgumentException error(AssetId source, String path, String message) {
        return new IllegalArgumentException("Scene " + source + " " + path + ": " + message);
    }

    private static IllegalArgumentException error(AssetId source, String path, String message,
                                                  Throwable cause) {
        return new IllegalArgumentException("Scene " + source + " " + path + ": " + message, cause);
    }

    private static final class Context {
        private final AssetId source;
        private final JsonParser parser;
        private int depth;
        private int renderables;
        private int directionalLights;
        private int pointLights;
        private int spotLights;

        Context(AssetId source, JsonParser parser) {
            this.source = source;
            this.parser = parser;
        }

        SceneDefinition parseDocument() throws IOException {
            if (parser.nextToken() == null) throw fail("$", "document is empty");
            expectStartObject("$");
            String format = null;
            Integer version = null;
            SceneDefinition.CameraDefinition camera = null;
            List<SceneDefinition.NodeDefinition> nodes = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = field("$");
                switch (field) {
                    case "format" -> format = string("$.format");
                    case "version" -> version = integer("$.version");
                    case "camera" -> camera = parseCamera("$.camera");
                    case "nodes" -> nodes = parseNodes("$.nodes");
                    default -> throw fail("$." + field, "unknown field");
                }
            }
            depth--;
            if (!SceneDefinition.FORMAT.equals(format)) {
                throw fail("$.format", "must be \"" + SceneDefinition.FORMAT + "\"");
            }
            if (!Integer.valueOf(SceneDefinition.VERSION).equals(version)) {
                throw fail("$.version", "must be integer 1");
            }
            if (camera == null) throw fail("$.camera", "is required");
            if (nodes == null) throw fail("$.nodes", "is required");
            validateNodes(camera, nodes);
            return new SceneDefinition(source, camera, nodes);
        }

        private SceneDefinition.CameraDefinition parseCamera(String path) throws IOException {
            expectStartObject(path);
            String node = null;
            SceneDefinition.ProjectionDefinition projection = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = field(path);
                switch (field) {
                    case "node" -> node = string(path + ".node");
                    case "projection" -> projection = parseProjection(path + ".projection");
                    default -> throw fail(path + "." + field, "unknown field");
                }
            }
            depth--;
            if (node == null) throw fail(path + ".node", "is required");
            if (projection == null) throw fail(path + ".projection", "is required");
            try {
                return new SceneDefinition.CameraDefinition(node, projection);
            } catch (IllegalArgumentException failure) {
                throw fail(path, failure.getMessage());
            }
        }

        private SceneDefinition.ProjectionDefinition parseProjection(String path) throws IOException {
            expectStartObject(path);
            String type = null;
            Float fov = null;
            Float near = null;
            Float far = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = field(path);
                switch (field) {
                    case "type" -> type = string(path + ".type");
                    case "fovYDegrees" -> fov = number(path + ".fovYDegrees");
                    case "near" -> near = number(path + ".near");
                    case "far" -> far = number(path + ".far");
                    default -> throw fail(path + "." + field, "unknown field");
                }
            }
            depth--;
            if (type == null) throw fail(path + ".type", "is required");
            if (fov == null) throw fail(path + ".fovYDegrees", "is required");
            if (near == null) throw fail(path + ".near", "is required");
            if (far == null) throw fail(path + ".far", "is required");
            try {
                return new SceneDefinition.ProjectionDefinition(type, fov, near, far);
            } catch (IllegalArgumentException failure) {
                throw fail(path, failure.getMessage());
            }
        }

        private List<SceneDefinition.NodeDefinition> parseNodes(String path) throws IOException {
            expect(JsonToken.START_ARRAY, path);
            List<SceneDefinition.NodeDefinition> nodes = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (nodes.size() >= MAX_NODES) {
                    throw fail(path, "node count exceeds " + MAX_NODES);
                }
                nodes.add(parseNode(path + "[" + nodes.size() + "]"));
            }
            return List.copyOf(nodes);
        }

        private SceneDefinition.NodeDefinition parseNode(String path) throws IOException {
            expectStartObject(path);
            String id = null;
            String parent = null;
            SceneDefinition.TransformDefinition transform = SceneDefinition.TransformDefinition.identity();
            SceneDefinition.RenderableDefinition renderable = null;
            SceneDefinition.LightDefinition light = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = field(path);
                switch (field) {
                    case "id" -> id = string(path + ".id");
                    case "parent" -> parent = string(path + ".parent");
                    case "transform" -> transform = parseTransform(path + ".transform");
                    case "renderable" -> renderable = parseRenderable(path + ".renderable");
                    case "light" -> light = parseLight(path + ".light");
                    default -> throw fail(path + "." + field, "unknown field");
                }
            }
            depth--;
            if (id == null) throw fail(path + ".id", "is required");
            try {
                return new SceneDefinition.NodeDefinition(id, parent, transform, renderable, light);
            } catch (IllegalArgumentException failure) {
                throw fail(path, failure.getMessage());
            }
        }

        private SceneDefinition.TransformDefinition parseTransform(String path) throws IOException {
            expectStartObject(path);
            float[] translation = {0.0f, 0.0f, 0.0f};
            float[] rotation = {0.0f, 0.0f, 0.0f, 1.0f};
            float[] scale = {1.0f, 1.0f, 1.0f};
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = field(path);
                switch (field) {
                    case "translation" -> translation = array(path + ".translation", 3);
                    case "rotation" -> rotation = array(path + ".rotation", 4);
                    case "scale" -> scale = array(path + ".scale", 3);
                    default -> throw fail(path + "." + field, "unknown field");
                }
            }
            depth--;
            try {
                return new SceneDefinition.TransformDefinition(
                        translation[0], translation[1], translation[2],
                        rotation[0], rotation[1], rotation[2], rotation[3],
                        scale[0], scale[1], scale[2]);
            } catch (IllegalArgumentException failure) {
                throw fail(path, failure.getMessage());
            }
        }

        private SceneDefinition.RenderableDefinition parseRenderable(String path) throws IOException {
            expectStartObject(path);
            String type = null;
            String asset = null;
            String scene = "default";
            boolean animated = false;
            String initialAnimation = null;
            boolean loop = true;
            boolean castShadows = true;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = field(path);
                switch (field) {
                    case "type" -> type = string(path + ".type");
                    case "asset" -> asset = string(path + ".asset");
                    case "scene" -> scene = string(path + ".scene");
                    case "animated" -> animated = bool(path + ".animated");
                    case "initialAnimation" -> initialAnimation = string(path + ".initialAnimation");
                    case "loop" -> loop = bool(path + ".loop");
                    case "castShadows" -> castShadows = bool(path + ".castShadows");
                    default -> throw fail(path + "." + field, "unknown field");
                }
            }
            depth--;
            if (type == null) throw fail(path + ".type", "is required");
            if (asset == null) throw fail(path + ".asset", "is required");
            AssetId id = parseAssetReference(path + ".asset", asset);
            try {
                renderables++;
                if (renderables > MAX_GLTF_RENDERABLES) {
                    throw fail(path, "gltf renderable count exceeds " + MAX_GLTF_RENDERABLES);
                }
                return new SceneDefinition.RenderableDefinition(
                        type, id, scene, animated, initialAnimation, loop, castShadows);
            } catch (IllegalArgumentException failure) {
                throw fail(path, failure.getMessage());
            }
        }

        private SceneDefinition.LightDefinition parseLight(String path) throws IOException {
            expectStartObject(path);
            String type = null;
            float[] color = {1.0f, 1.0f, 1.0f};
            float intensity = 1.0f;
            float range = 0.0f;
            float inner = 0.0f;
            float outer = 0.0f;
            boolean castShadows = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = field(path);
                switch (field) {
                    case "type" -> type = string(path + ".type");
                    case "color" -> color = array(path + ".color", 3);
                    case "intensity" -> intensity = number(path + ".intensity");
                    case "range" -> range = number(path + ".range");
                    case "innerConeDegrees" -> inner = number(path + ".innerConeDegrees");
                    case "outerConeDegrees" -> outer = number(path + ".outerConeDegrees");
                    case "castShadows" -> castShadows = bool(path + ".castShadows");
                    default -> throw fail(path + "." + field, "unknown field");
                }
            }
            depth--;
            if (type == null) throw fail(path + ".type", "is required");
            try {
                switch (type) {
                    case "directional" -> directionalLights++;
                    case "point" -> pointLights++;
                    case "spot" -> spotLights++;
                    default -> { }
                }
                if (directionalLights > MAX_DIRECTIONAL_LIGHTS) {
                    throw fail(path, "directional light count exceeds " + MAX_DIRECTIONAL_LIGHTS);
                }
                if (pointLights > MAX_POINT_LIGHTS) {
                    throw fail(path, "point light count exceeds " + MAX_POINT_LIGHTS);
                }
                if (spotLights > MAX_SPOT_LIGHTS) {
                    throw fail(path, "spot light count exceeds " + MAX_SPOT_LIGHTS);
                }
                return new SceneDefinition.LightDefinition(type, color[0], color[1], color[2],
                        intensity, range, inner, outer, castShadows);
            } catch (IllegalArgumentException failure) {
                throw fail(path, failure.getMessage());
            }
        }

        private AssetId parseAssetReference(String path, String value) {
            if (value.startsWith("./") || value.startsWith("../")) {
                try {
                    return source.resolve(value);
                } catch (IllegalArgumentException failure) {
                    throw fail(path, failure.getMessage());
                }
            }
            if (value.indexOf(':') < 1 || value.startsWith("/") || value.contains("\\")
                    || value.matches("^[A-Za-z]:.*")) {
                throw fail(path, "asset reference must use namespace:path or ./relative/path");
            }
            try {
                return AssetId.parse(value);
            } catch (IllegalArgumentException failure) {
                throw fail(path, failure.getMessage());
            }
        }

        private void validateNodes(SceneDefinition.CameraDefinition camera,
                                   List<SceneDefinition.NodeDefinition> nodes) {
            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                String id = nodes.get(i).id();
                if (indexes.putIfAbsent(id, i) != null) {
                    throw fail("$.nodes[" + i + "].id", "duplicate node id: " + id);
                }
            }
            if (!indexes.containsKey(camera.node())) {
                throw fail("$.camera.node", "unknown node: " + camera.node());
            }
            for (int i = 0; i < nodes.size(); i++) {
                String parent = nodes.get(i).parent();
                if (parent != null && !indexes.containsKey(parent)) {
                    throw fail("$.nodes[" + i + "].parent", "unknown node: " + parent);
                }
            }
            int[] marks = new int[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) visitParent(i, nodes, indexes, marks, 0);
        }

        private void visitParent(int index, List<SceneDefinition.NodeDefinition> nodes,
                                 Map<String, Integer> indexes, int[] marks, int depth) {
            if (depth > MAX_PARENT_DEPTH) {
                throw fail("$.nodes[" + index + "].parent",
                        "parent depth exceeds " + MAX_PARENT_DEPTH);
            }
            if (marks[index] == 2) return;
            if (marks[index] == 1) {
                throw fail("$.nodes[" + index + "].parent", "parent cycle detected");
            }
            marks[index] = 1;
            String parent = nodes.get(index).parent();
            if (parent != null) visitParent(indexes.get(parent), nodes, indexes, marks, depth + 1);
            marks[index] = 2;
        }

        private String field(String path) throws IOException {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw fail(path, "expected field name");
            }
            String name = parser.currentName();
            if (parser.nextToken() == null) throw fail(path + "." + name, "missing value");
            return name;
        }

        private String string(String path) throws IOException {
            expect(JsonToken.VALUE_STRING, path);
            String value = parser.getText();
            if (value.length() > MAX_STRING_LENGTH) {
                throw fail(path, "string exceeds " + MAX_STRING_LENGTH + " characters");
            }
            return value;
        }

        private int integer(String path) throws IOException {
            expect(JsonToken.VALUE_NUMBER_INT, path);
            try {
                return parser.getIntValue();
            } catch (IOException failure) {
                throw fail(path, "expected a 32-bit integer");
            }
        }

        private float number(String path) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_FLOAT
                    && parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                throw fail(path, "expected finite number");
            }
            double value = parser.getDoubleValue();
            if (!Double.isFinite(value) || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
                throw fail(path, "number must be finite and representable as float");
            }
            return (float) value;
        }

        private boolean bool(String path) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_TRUE
                    && parser.currentToken() != JsonToken.VALUE_FALSE) {
                throw fail(path, "expected boolean");
            }
            return parser.getBooleanValue();
        }

        private float[] array(String path, int expected) throws IOException {
            expect(JsonToken.START_ARRAY, path);
            float[] values = new float[expected];
            for (int i = 0; i < expected; i++) {
                if (parser.nextToken() == JsonToken.END_ARRAY) {
                    throw fail(path, "expected " + expected + " numbers");
                }
                values[i] = number(path + "[" + i + "]");
            }
            if (parser.nextToken() != JsonToken.END_ARRAY) {
                throw fail(path, "expected exactly " + expected + " numbers");
            }
            return values;
        }

        private void expectStartObject(String path) throws IOException {
            expect(JsonToken.START_OBJECT, path);
            depth++;
            if (depth > MAX_DEPTH) throw fail(path, "JSON nesting exceeds " + MAX_DEPTH);
        }

        private void expect(JsonToken token, String path) throws IOException {
            if (parser.currentToken() != token) throw fail(path, "expected " + token);
        }

        private SceneParseFailure fail(String path, String message) {
            return new SceneParseFailure(error(source, path, message));
        }

        private SceneParseFailure fail(String path, String message, Throwable cause) {
            return new SceneParseFailure(error(source, path, message, cause));
        }
    }

    private static final class SceneParseFailure extends RuntimeException {
        SceneParseFailure(IllegalArgumentException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
