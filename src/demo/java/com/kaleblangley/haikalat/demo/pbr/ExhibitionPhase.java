package com.kaleblangley.haikalat.demo.pbr;

/** The two deterministic phases every station exposes. */
public enum ExhibitionPhase {
    /** Production baseline: the feature contribution is off. */
    BASELINE("A", "基线"),
    /** The feature under observation is on with the station preset. */
    EFFECT("B", "效果");

    private final String key;
    private final String label;

    ExhibitionPhase(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public ExhibitionPhase other() {
        return this == BASELINE ? EFFECT : BASELINE;
    }
}
