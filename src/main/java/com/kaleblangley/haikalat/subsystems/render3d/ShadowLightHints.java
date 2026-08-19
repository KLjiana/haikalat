package com.kaleblangley.haikalat.subsystems.render3d;

/** Non-destructive content hints used by the bounded shadow scheduler. */
public record ShadowLightHints(int priority) {
    public static final ShadowLightHints DEFAULT = new ShadowLightHints(0);

    public static ShadowLightHints priority(int value) {
        return new ShadowLightHints(value);
    }
}
