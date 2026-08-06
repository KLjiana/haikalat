package com.kaleblangley.haikalat.subsystems.animation;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnimationGraphParserTest {
    private static final AssetId SOURCE = AssetId.of("demo", "actors/player.graph.json");

    @Test
    void parsesTypedParametersStatesAndPredicates() {
        AnimationGraphDocument graph = AnimationGraphParser.parse(SOURCE, """
                {"format":"haikalat.animation-graph/1","skeleton":"player",
                 "parameters":[
                   {"name":"grounded","type":"BOOLEAN","default":true},
                   {"name":"speed","type":"FLOAT","default":0.0},
                   {"name":"attack","type":"TRIGGER"}],
                 "states":[
                   {"id":"idle","clip":"idle_default","loop":"LOOP"},
                   {"id":"run","clip":"run_default","loop":"LOOP"},
                   {"id":"attack","clip":"attack_light","loop":"ONCE"}],
                 "entry":"idle",
                 "transitions":[
                   {"from":"idle","to":"run","duration":0.12,
                    "when":{"float":"speed","greaterThan":0.1}},
                   {"from":"ANY","to":"attack","duration":0.08,
                    "interrupt":"ANY","when":{"trigger":"attack"}}]}
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals("player", graph.skeleton());
        assertEquals(3, graph.parameters().size());
        assertEquals(AnimationGraph.ParameterType.FLOAT,
                graph.parameters().get(1).type());
        assertEquals(3, graph.states().size());
        assertEquals(AnimationPlayer.LoopMode.ONCE, graph.states().get(2).loopMode());
        assertEquals(2, graph.transitions().size());
        assertEquals(AnimationGraph.Comparison.FLOAT_GREATER,
                graph.transitions().getFirst().conditions().getFirst().comparison());
        assertEquals("__ANY__", graph.transitions().get(1).from());
    }

    @Test
    void rejectsUnknownFieldsInvalidTypesAndUnknownPredicateParameters() {
        assertMessageContains("""
                {"format":"haikalat.animation-graph/1","skeleton":"player",
                 "parameters":[],"states":[{"id":"idle","clip":"idle","loop":"LOOP"}],
                 "entry":"idle","unknown":true}
                """, "unknown field");
        assertMessageContains("""
                {"format":"haikalat.animation-graph/1","skeleton":"player",
                 "parameters":[{"name":"speed","type":"FLOAT","default":0}],
                 "states":[{"id":"idle","clip":"idle","loop":"LOOP"}],"entry":"idle",
                 "transitions":[{"from":"idle","to":"idle","when":{"trigger":"speed"}}]}
                """, "must be TRIGGER");
        assertMessageContains("""
                {"format":"haikalat.animation-graph/1","skeleton":"player",
                 "parameters":[],"states":[{"id":"idle","clip":"idle","loop":"LOOP"}],
                 "entry":"idle","transitions":[{"from":"idle","to":"idle",
                 "when":{"float":"missing","greaterThan":0}}]}
                """, "unknown parameter");
    }

    private static void assertMessageContains(String json, String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AnimationGraphParser.parse(SOURCE, json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(true, failure.getMessage().toLowerCase().contains(expected.toLowerCase()),
                failure.getMessage());
    }
}
