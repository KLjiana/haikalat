package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SceneJsonParserTest {
    private static final AssetId SOURCE = AssetId.of("demo", "scenes/showcase.scene.json");

    @Test
    void parsesCompleteSceneAndNormalizesQuaternion() {
        String json = """
                {
                  "format": "haikalat.scene",
                  "version": 1,
                  "camera": {
                    "node": "camera-main",
                    "projection": {
                      "type": "perspective",
                      "fovYDegrees": 60,
                      "near": 0.1,
                      "far": 500
                    }
                  },
                  "nodes": [
                    {"id":"world"},
                    {"id":"camera-main","parent":"world",
                     "transform":{"translation":[0,2,6],"rotation":[0,0,0,2]}},
                    {"id":"hero","parent":"world",
                     "renderable":{"type":"gltf","asset":"./gltf/hero.glb",
                                   "animated":true,"initialAnimation":"idle",
                                   "loop":true,"castShadows":false}},
                    {"id":"sun","parent":"world",
                     "light":{"type":"directional","color":[1,0.9,0.8],
                              "intensity":3,"castShadows":true}}
                  ]
                }
                """;

        SceneDefinition scene = SceneJsonParser.parse(SOURCE, json.getBytes(StandardCharsets.UTF_8));

        assertEquals(SOURCE.resolve("./gltf/hero.glb"),
                scene.nodes().get(2).renderable().asset());
        assertEquals(1.0f, scene.nodes().get(1).transform().rotationW(), 0.0001f);
        assertEquals("camera-main", scene.camera().node());
    }

    @Test
    void rejectsUnknownFieldsDuplicateFieldsAmbiguousReferencesAndCycles() {
        assertMessageContains("""
                {"format":"haikalat.scene","version":1,"unknown":true,
                 "camera":{"node":"a","projection":{"type":"perspective","fovYDegrees":60,"near":0.1,"far":10}},
                 "nodes":[{"id":"a"}]}
                """, "unknown field");
        assertMessageContains("""
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"a","projection":{"type":"perspective","fovYDegrees":60,"near":0.1,"far":10}},
                 "nodes":[{"id":"a","id":"b"}]}
                """, "duplicate");
        assertMessageContains("""
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"a","projection":{"type":"perspective","fovYDegrees":60,"near":0.1,"far":10}},
                 "nodes":[{"id":"a","renderable":{"type":"gltf","asset":"hero.glb"}}]}
                """, "namespace:path");
        assertMessageContains("""
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"a","projection":{"type":"perspective","fovYDegrees":60,"near":0.1,"far":10}},
                 "nodes":[{"id":"a","parent":"b"},{"id":"b","parent":"a"}]}
                """, "cycle");
    }

    @Test
    void rejectsUnsupportedProjectionAndInvalidTransform() {
        String prefix = """
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"a","projection":%s},
                 "nodes":[{"id":"a"}]}
                """;
        assertMessageContains(prefix.formatted(
                "{\"type\":\"orthographic\",\"fovYDegrees\":60,\"near\":0.1,\"far\":10}"),
                "unsupported camera projection");
        assertMessageContains("""
                {"format":"haikalat.scene","version":1,
                 "camera":{"node":"a","projection":{"type":"perspective","fovYDegrees":60,"near":0.1,"far":10}},
                 "nodes":[{"id":"a","transform":{"rotation":[0,0,0,0]}}]}
                """, "non-zero");
    }

    private static void assertMessageContains(String json, String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SceneJsonParser.parse(SOURCE, json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(true, failure.getMessage().toLowerCase().contains(expected.toLowerCase()),
                failure.getMessage());
    }
}
