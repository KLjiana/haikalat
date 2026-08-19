package com.kaleblangley.haikalat.subsystems.render3d;

/** Built-in deterministic policies for assigning the bounded shadow budget. */
public enum ShadowSelectionMode {
    /** Content priority followed by stable scene-light identity. */
    SCENE_ORDER,
    /** Content priority followed by bounded camera influence and stable identity. */
    CAMERA_IMPORTANCE
}
