package com.kaleblangley.haikalat.subsystems.animation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict CPU parser for the constrained animation graph sidecar format. */
public final class AnimationGraphParser {
    public static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    public static final int MAX_PARAMETERS = 128;
    public static final int MAX_STATES = 512;
    public static final int MAX_TRANSITIONS = 2048;
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private AnimationGraphParser() {}

    public static AnimationGraphDocument parse(AssetId source, byte[] encoded) {
        if (encoded == null) throw error(source, "$", "document must not be null");
        if (encoded.length > MAX_DOCUMENT_BYTES) {
            throw error(source, "$", "document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
        }
        try (JsonParser parser = JSON.createParser(encoded)) {
            Object root = readValue(parser, 0);
            if (parser.nextToken() != null) throw error(source, "$", "trailing JSON token");
            return decode(source, map(root, source, "$"));
        } catch (GraphFailure failure) {
            throw failure.error;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException illegal) throw illegal;
            throw error(source, "$", "invalid animation graph JSON: " + failure.getMessage());
        }
    }

    private static AnimationGraphDocument decode(AssetId source, Map<String, Object> root) {
        rejectUnknown(source, "$", root, Set.of("format", "skeleton", "parameters",
                "states", "entry", "transitions"));
        String format = text(source, root, "format", "$.format", true);
        if (!AnimationGraphDocument.FORMAT.equals(format)) {
            throw error(source, "$.format", "must be \""
                    + AnimationGraphDocument.FORMAT + "\"");
        }
        String skeleton = text(source, root, "skeleton", "$.skeleton", true);
        List<Object> rawParameters = array(source, root.get("parameters"), "$.parameters", true);
        if (rawParameters.size() > MAX_PARAMETERS) {
            throw error(source, "$.parameters", "parameter count exceeds " + MAX_PARAMETERS);
        }
        List<AnimationGraphDocument.Parameter> parameters = new ArrayList<>();
        Map<String, AnimationGraph.ParameterType> parameterTypes = new HashMap<>();
        for (int i = 0; i < rawParameters.size(); i++) {
            String path = "$.parameters[" + i + "]";
            Map<String, Object> value = map(rawParameters.get(i), source, path);
            rejectUnknown(source, path, value, Set.of("name", "type", "default"));
            String name = text(source, value, "name", path + ".name", true);
            String typeName = text(source, value, "type", path + ".type", true);
            AnimationGraph.ParameterType type = enumValue(source, path + ".type", typeName,
                    AnimationGraph.ParameterType.class);
            if (parameterTypes.putIfAbsent(name, type) != null) {
                throw error(source, path + ".name", "duplicate parameter: " + name);
            }
            Object defaultValue = value.get("default");
            parameters.add(parseParameter(source, path, name, type, defaultValue));
        }

        List<Object> rawStates = array(source, root.get("states"), "$.states", true);
        if (rawStates.isEmpty()) throw error(source, "$.states", "must not be empty");
        if (rawStates.size() > MAX_STATES) {
            throw error(source, "$.states", "state count exceeds " + MAX_STATES);
        }
        List<AnimationGraphDocument.State> states = new ArrayList<>();
        Set<String> stateIds = new HashSet<>();
        for (int i = 0; i < rawStates.size(); i++) {
            String path = "$.states[" + i + "]";
            Map<String, Object> value = map(rawStates.get(i), source, path);
            rejectUnknown(source, path, value, Set.of("id", "clip", "loop", "playbackSpeed",
                    "playbackSpeedParameter", "syncGroup", "enterSignal", "exitSignal",
                    "startOffset"));
            String id = text(source, value, "id", path + ".id", true);
            if (!stateIds.add(id)) throw error(source, path + ".id", "duplicate state: " + id);
            String clip = text(source, value, "clip", path + ".clip", true);
            String loopText = text(source, value, "loop", path + ".loop", true);
            AnimationPlayer.LoopMode loop = switch (loopText) {
                case "LOOP" -> AnimationPlayer.LoopMode.LOOP;
                case "ONCE" -> AnimationPlayer.LoopMode.ONCE;
                default -> throw error(source, path + ".loop", "must be LOOP or ONCE");
            };
            float speed = number(source, value, "playbackSpeed", path + ".playbackSpeed", 1.0f);
            String speedParameter = optionalText(source, value, "playbackSpeedParameter",
                    path + ".playbackSpeedParameter");
            String syncGroup = optionalText(source, value, "syncGroup", path + ".syncGroup");
            String enterSignal = optionalText(source, value, "enterSignal", path + ".enterSignal");
            String exitSignal = optionalText(source, value, "exitSignal", path + ".exitSignal");
            float offset = number(source, value, "startOffset", path + ".startOffset", 0.0f);
            try {
                states.add(new AnimationGraphDocument.State(id, clip, loop, speed,
                        speedParameter, syncGroup, enterSignal, exitSignal, offset));
            } catch (IllegalArgumentException failure) {
                throw error(source, path, failure.getMessage());
            }
        }
        String entry = text(source, root, "entry", "$.entry", true);
        if (!stateIds.contains(entry)) throw error(source, "$.entry", "unknown state: " + entry);

        List<Object> rawTransitions = array(source, root.get("transitions"), "$.transitions", false);
        if (rawTransitions.size() > MAX_TRANSITIONS) {
            throw error(source, "$.transitions", "transition count exceeds " + MAX_TRANSITIONS);
        }
        List<AnimationGraphDocument.Transition> transitions = new ArrayList<>();
        for (int i = 0; i < rawTransitions.size(); i++) {
            transitions.add(parseTransition(source, rawTransitions.get(i), i, stateIds,
                    parameterTypes));
        }
        return new AnimationGraphDocument(skeleton, parameters, states, entry, transitions);
    }

    private static AnimationGraphDocument.Parameter parseParameter(AssetId source, String path,
                                                                    String name,
                                                                    AnimationGraph.ParameterType type,
                                                                    Object value) {
        try {
            return switch (type) {
                case BOOLEAN -> new AnimationGraphDocument.Parameter(name, type,
                        value == null ? false : bool(source, value, path + ".default", true), 0.0f, 0);
                case FLOAT -> new AnimationGraphDocument.Parameter(name, type, false,
                        value == null ? 0.0f : finiteNumber(source, value, path + ".default"), 0);
                case INTEGER -> new AnimationGraphDocument.Parameter(name, type, false, 0.0f,
                        value == null ? 0 : integer(source, value, path + ".default"));
                case TRIGGER -> {
                    if (value != null) throw error(source, path + ".default",
                            "trigger parameter must not declare a default");
                    yield new AnimationGraphDocument.Parameter(name, type, false, 0.0f, 0);
                }
            };
        } catch (IllegalArgumentException failure) {
            throw error(source, path, failure.getMessage());
        }
    }

    private static AnimationGraphDocument.Transition parseTransition(
            AssetId source, Object raw, int index, Set<String> states,
            Map<String, AnimationGraph.ParameterType> parameterTypes) {
        String path = "$.transitions[" + index + "]";
        Map<String, Object> value = map(raw, source, path);
        rejectUnknown(source, path, value, Set.of("from", "to", "duration", "exitTime",
                "destinationOffset", "interrupt", "queued", "when"));
        String from = text(source, value, "from", path + ".from", true);
        if (from.equals("ANY") || from.equals("*")) from = "__ANY__";
        else if (!states.contains(from)) throw error(source, path + ".from", "unknown state: " + from);
        String to = text(source, value, "to", path + ".to", true);
        if (!states.contains(to)) throw error(source, path + ".to", "unknown state: " + to);
        float duration = number(source, value, "duration", path + ".duration", 0.0f);
        float exitTime = number(source, value, "exitTime", path + ".exitTime", -1.0f);
        float offset = number(source, value, "destinationOffset", path + ".destinationOffset", 0.0f);
        String interrupt = optionalText(source, value, "interrupt", path + ".interrupt");
        AnimationGraph.InterruptionPolicy policy = interrupt.isEmpty()
                ? AnimationGraph.InterruptionPolicy.NONE
                : enumValue(source, path + ".interrupt", interrupt,
                        AnimationGraph.InterruptionPolicy.class);
        boolean queued = bool(source, value.get("queued"), path + ".queued", false);
        List<AnimationGraphDocument.Condition> conditions = parseConditions(source,
                value.get("when"), path + ".when", parameterTypes);
        try {
            return new AnimationGraphDocument.Transition(from, to, duration, exitTime, offset,
                    policy, queued, conditions);
        } catch (IllegalArgumentException failure) {
            throw error(source, path, failure.getMessage());
        }
    }

    private static List<AnimationGraphDocument.Condition> parseConditions(
            AssetId source, Object raw, String path,
            Map<String, AnimationGraph.ParameterType> types) {
        if (raw == null) return List.of();
        Map<String, Object> value = map(raw, source, path);
        if (value.isEmpty()) throw error(source, path, "condition must not be empty");
        List<AnimationGraphDocument.Condition> result = new ArrayList<>();
        if (value.containsKey("boolean")) {
            checkKeys(source, path, value, Set.of("boolean", "equals"));
            String parameter = text(source, value, "boolean", path + ".boolean", true);
            requireType(source, path, types, parameter, AnimationGraph.ParameterType.BOOLEAN);
            result.add(new AnimationGraphDocument.Condition(parameter,
                    AnimationGraph.Comparison.BOOLEAN_EQUALS,
                    bool(source, value.get("equals"), path + ".equals", true), 0.0f, 0));
        } else if (value.containsKey("float")) {
            checkKeys(source, path, value, Set.of("float", "greaterThan", "greaterOrEqual",
                    "lessThan", "lessOrEqual"));
            String parameter = text(source, value, "float", path + ".float", true);
            requireType(source, path, types, parameter, AnimationGraph.ParameterType.FLOAT);
            int predicates = 0;
            AnimationGraph.Comparison comparison = null;
            float threshold = 0.0f;
            for (String key : List.of("greaterThan", "greaterOrEqual", "lessThan", "lessOrEqual")) {
                if (value.containsKey(key)) {
                    predicates++;
                    comparison = switch (key) {
                        case "greaterThan" -> AnimationGraph.Comparison.FLOAT_GREATER;
                        case "greaterOrEqual" -> AnimationGraph.Comparison.FLOAT_GREATER_OR_EQUAL;
                        case "lessThan" -> AnimationGraph.Comparison.FLOAT_LESS;
                        default -> AnimationGraph.Comparison.FLOAT_LESS_OR_EQUAL;
                    };
                    threshold = finiteNumber(source, value.get(key), path + "." + key);
                }
            }
            if (predicates != 1) throw error(source, path, "float predicate requires exactly one comparison");
            result.add(new AnimationGraphDocument.Condition(parameter, comparison, false,
                    threshold, 0));
        } else if (value.containsKey("integer")) {
            checkKeys(source, path, value, Set.of("integer", "equals", "notEquals",
                    "lessThan", "greaterThan"));
            String parameter = text(source, value, "integer", path + ".integer", true);
            requireType(source, path, types, parameter, AnimationGraph.ParameterType.INTEGER);
            String selected = null;
            for (String key : List.of("equals", "notEquals", "lessThan", "greaterThan")) {
                if (value.containsKey(key)) {
                    if (selected != null) throw error(source, path, "integer predicate requires one comparison");
                    selected = key;
                }
            }
            if (selected == null) throw error(source, path, "integer predicate requires a comparison");
            AnimationGraph.Comparison comparison = switch (selected) {
                case "equals" -> AnimationGraph.Comparison.INTEGER_EQUALS;
                case "notEquals" -> AnimationGraph.Comparison.INTEGER_NOT_EQUALS;
                case "lessThan" -> AnimationGraph.Comparison.INTEGER_LESS;
                default -> AnimationGraph.Comparison.INTEGER_GREATER;
            };
            result.add(new AnimationGraphDocument.Condition(parameter, comparison, false,
                    0.0f, integer(source, value.get(selected), path + "." + selected)));
        } else if (value.containsKey("trigger")) {
            checkKeys(source, path, value, Set.of("trigger"));
            String parameter = text(source, value, "trigger", path + ".trigger", true);
            requireType(source, path, types, parameter, AnimationGraph.ParameterType.TRIGGER);
            result.add(new AnimationGraphDocument.Condition(parameter,
                    AnimationGraph.Comparison.TRIGGERED, false, 0.0f, 0));
        } else {
            throw error(source, path, "unsupported predicate; use boolean, float, integer or trigger");
        }
        return List.copyOf(result);
    }

    private static void requireType(AssetId source, String path,
                                    Map<String, AnimationGraph.ParameterType> types,
                                    String name, AnimationGraph.ParameterType expected) {
        AnimationGraph.ParameterType actual = types.get(name);
        if (actual == null) throw error(source, path, "unknown parameter: " + name);
        if (actual != expected) throw error(source, path, "parameter '" + name
                + "' must be " + expected + " but is " + actual);
    }

    private static Object readValue(JsonParser parser, int depth) throws IOException {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting exceeds 64");
        JsonToken token = parser.nextToken();
        if (token == null) throw new IllegalArgumentException("document is empty");
        return switch (token) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) {
                        throw new IllegalArgumentException("expected field name");
                    }
                    String name = parser.currentName();
                    map.put(name, readValue(parser, depth + 1));
                }
                yield map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    list.add(readCurrentValue(parser, depth + 1));
                }
                yield list;
            }
            default -> readScalar(parser, token);
        };
    }

    private static Object readCurrentValue(JsonParser parser, int depth) throws IOException {
        // The array loop positions on the value token. Scalars are decoded
        // directly; nested containers are handled by the same recursive path
        // after moving one token backwards is impossible, so parse containers
        // inline here.
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            // This branch is reached only for nested values. The recursive
            // parser expects to advance, therefore consume the current start
            // token manually and recurse over its contents.
            if (token == JsonToken.START_OBJECT) {
                Map<String, Object> map = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.currentName();
                    parser.nextToken();
                    map.put(name, readCurrentValue(parser, depth + 1));
                }
                return map;
            }
            List<Object> list = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) list.add(readCurrentValue(parser, depth + 1));
            return list;
        }
        return readScalar(parser, token);
    }

    private static Object readScalar(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case VALUE_STRING -> parser.getText();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NUMBER_INT -> parser.getNumberType() == com.fasterxml.jackson.core.JsonParser.NumberType.INT
                    ? parser.getIntValue() : parser.getLongValue();
            case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("unexpected JSON token " + token);
        };
    }

    private static Map<String, Object> map(Object value, AssetId source, String path) {
        if (!(value instanceof Map<?, ?> raw)) throw error(source, path, "must be an object");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    private static List<Object> array(AssetId source, Object value, String path, boolean required) {
        if (value == null && !required) return List.of();
        if (!(value instanceof List<?> raw)) throw error(source, path, "must be an array");
        return List.copyOf(raw);
    }

    private static String text(AssetId source, Map<String, Object> owner, String key,
                               String path, boolean required) {
        Object value = owner.get(key);
        if (value == null) {
            if (required) throw error(source, path, "is required");
            return "";
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw error(source, path, "must be a non-blank string");
        }
        return text;
    }

    private static String optionalText(AssetId source, Map<String, Object> owner,
                                       String key, String path) {
        return owner.containsKey(key) ? text(source, owner, key, path, true) : "";
    }

    private static float number(AssetId source, Map<String, Object> owner, String key,
                                String path, float fallback) {
        return owner.containsKey(key) ? finiteNumber(source, owner.get(key), path) : fallback;
    }

    private static float finiteNumber(AssetId source, Object value, String path) {
        if (!(value instanceof Number number)) throw error(source, path, "must be a finite number");
        float result = number.floatValue();
        if (!Float.isFinite(result)) throw error(source, path, "must be a finite number");
        return result;
    }

    private static int integer(AssetId source, Object value, String path) {
        if (!(value instanceof Number number) || number.longValue() != number.doubleValue()
                || number.longValue() < Integer.MIN_VALUE || number.longValue() > Integer.MAX_VALUE) {
            throw error(source, path, "must be a 32-bit integer");
        }
        return number.intValue();
    }

    private static boolean bool(AssetId source, Object value, String path, boolean required) {
        if (value == null) {
            if (required) throw error(source, path, "is required");
            return false;
        }
        if (!(value instanceof Boolean result)) throw error(source, path, "must be boolean");
        return result;
    }

    private static <E extends Enum<E>> E enumValue(AssetId source, String path, String value,
                                                    Class<E> type) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw error(source, path, "unsupported value: " + value);
        }
    }

    private static void rejectUnknown(AssetId source, String path, Map<String, Object> value,
                                      Set<String> allowed) {
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) throw error(source, path + "." + key, "unknown field");
        }
    }

    private static void checkKeys(AssetId source, String path, Map<String, Object> value,
                                  Set<String> allowed) {
        rejectUnknown(source, path, value, allowed);
    }

    private static IllegalArgumentException error(AssetId source, String path, String message) {
        return new IllegalArgumentException("AnimationGraph " + source + " " + path + ": " + message);
    }

    private static final class GraphFailure extends RuntimeException {
        final IllegalArgumentException error;
        GraphFailure(IllegalArgumentException error) { this.error = error; }
    }
}
