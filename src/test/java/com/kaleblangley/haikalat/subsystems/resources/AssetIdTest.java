package com.kaleblangley.haikalat.subsystems.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetIdTest {
    @Test
    void normalizesNamespacePathAndExtension() {
        AssetId id = AssetId.of("Game.Mod", "models\\characters/./hero.GLTF");

        assertEquals("game.mod", id.namespace());
        assertEquals("models/characters/hero.GLTF", id.path());
        assertEquals("gltf", id.extension());
        assertEquals("game.mod:models/characters/hero.GLTF", id.toString());
    }

    @Test
    void resolvesRelativeResourcesWithoutEscapingNamespaceRoot() {
        AssetId model = AssetId.of("game", "models/actors/hero.gltf");

        assertEquals(AssetId.of("game", "models/textures/hero.png"),
                model.resolve("../textures/hero.png"));
        assertThrows(IllegalArgumentException.class,
                () -> model.resolve("../../../outside.bin"));
        assertThrows(IllegalArgumentException.class,
                () -> model.resolve("/absolute.bin"));
    }

    @Test
    void parseUsesExplicitOrDefaultNamespace() {
        assertEquals(AssetId.of("demo", "scene/main.gltf"),
                AssetId.parse("demo:scene/main.gltf"));
        assertEquals(AssetId.of("textures/grid.png"),
                AssetId.parse("textures/grid.png"));
    }
}
