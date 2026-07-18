package com.kaleblangley.haikalat.core.assets.gltf;

import java.util.Objects;

/** glTF scene 选择策略。 */
public sealed interface SceneSelection permits SceneSelection.Default, SceneSelection.ByIndex, SceneSelection.ByName {
    record Default() implements SceneSelection {}
    record ByIndex(int index) implements SceneSelection {
        public ByIndex { if (index < 0) throw new IllegalArgumentException("scene index must be non-negative"); }
    }
    record ByName(String name) implements SceneSelection {
        public ByName {
            name = Objects.requireNonNull(name, "name").strip();
            if (name.isEmpty()) throw new IllegalArgumentException("scene name must not be blank");
        }
    }
}
