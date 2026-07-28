package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Ordered linear gradient description for an SDF primitive. */
public record UiGradient(List<UiGradientStop> stops, float angleRadians) {
    public UiGradient {
        if (stops == null || stops.size() < 2 || stops.size() > 8) {
            throw new IllegalArgumentException("gradient requires 2..8 stops");
        }
        List<UiGradientStop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingDouble(UiGradientStop::offset));
        if (sorted.getFirst().offset() > 0.0f || sorted.getLast().offset() < 1.0f) {
            throw new IllegalArgumentException("gradient stops must cover [0, 1]");
        }
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index).offset() <= sorted.get(index - 1).offset()) {
                throw new IllegalArgumentException("gradient stop offsets must be strictly increasing");
            }
        }
        if (!Float.isFinite(angleRadians)) throw new IllegalArgumentException("gradient angle");
        stops = List.copyOf(sorted);
    }

    public static UiGradient linear(UiColor start, UiColor end) {
        return new UiGradient(List.of(new UiGradientStop(0.0f, start),
                new UiGradientStop(1.0f, end)), 0.0f);
    }

    public UiGradient withAngle(float angle) {
        return new UiGradient(stops, angle);
    }

    public UiColor firstColor() {
        return stops.getFirst().color();
    }

    public UiColor lastColor() {
        return stops.getLast().color();
    }
}
