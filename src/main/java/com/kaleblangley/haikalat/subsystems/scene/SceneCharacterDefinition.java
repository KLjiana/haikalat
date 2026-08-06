package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, GL-free character binding declared by a serialized scene. */
public record SceneCharacterDefinition(
        String id,
        String object,
        AssetId animationLibrary,
        AssetId animationGraph,
        String initialState,
        Map<String, ParameterValue> parameters
) {
    public static final int MAX_PARAMETERS = 64;

    public SceneCharacterDefinition {
        id = requireText(id, "id");
        object = requireText(object, "object");
        animationLibrary = Objects.requireNonNull(animationLibrary, "animationLibrary");
        animationGraph = Objects.requireNonNull(animationGraph, "animationGraph");
        initialState = requireText(initialState, "initialState");
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("character parameter count exceeds "
                    + MAX_PARAMETERS);
        }
        LinkedHashMap<String, ParameterValue> copy = new LinkedHashMap<>();
        parameters.forEach((name, value) -> copy.put(requireText(name, "parameter name"),
                Objects.requireNonNull(value, "parameter value")));
        parameters = Map.copyOf(copy);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    /** Typed literal used for initial graph parameters. */
    public record ParameterValue(Type type, boolean booleanValue, int intValue,
                                 float floatValue) {
        public enum Type { BOOLEAN, INTEGER, FLOAT }

        public ParameterValue {
            type = Objects.requireNonNull(type, "type");
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("float parameter must be finite");
            }
        }

        public static ParameterValue ofBoolean(boolean value) {
            return new ParameterValue(Type.BOOLEAN, value, 0, value ? 1.0f : 0.0f);
        }

        public static ParameterValue ofInteger(int value) {
            return new ParameterValue(Type.INTEGER, false, value, value);
        }

        public static ParameterValue ofFloat(float value) {
            return new ParameterValue(Type.FLOAT, false, 0, value);
        }

        public Object value() {
            return switch (type) {
                case BOOLEAN -> booleanValue;
                case INTEGER -> intValue;
                case FLOAT -> floatValue;
            };
        }
    }
}
