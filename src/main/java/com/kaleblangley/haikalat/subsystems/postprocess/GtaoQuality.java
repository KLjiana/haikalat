package com.kaleblangley.haikalat.subsystems.postprocess;

/** Fixed GTAO sample budgets exposed by the public configuration contract. */
public enum GtaoQuality {
    LOW(2, 4),
    MEDIUM(4, 4),
    HIGH(6, 6);

    private final int directions;
    private final int stepsPerDirection;

    GtaoQuality(int directions, int stepsPerDirection) {
        this.directions = directions;
        this.stepsPerDirection = stepsPerDirection;
    }

    public int directions() {
        return directions;
    }

    public int stepsPerDirection() {
        return stepsPerDirection;
    }
}
