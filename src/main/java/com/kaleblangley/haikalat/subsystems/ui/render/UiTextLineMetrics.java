package com.kaleblangley.haikalat.subsystems.ui.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 单行 shaping 结果中可用于 caret、命中与水平滚动的不可变几何。 */
public record UiTextLineMetrics(String text, List<CaretStop> stops, double width) {
    public record CaretStop(int utf16Offset, double x) {
        public CaretStop {
            if (utf16Offset < 0 || !Double.isFinite(x) || x < 0.0) {
                throw new IllegalArgumentException("caret stop must contain a valid offset and position");
            }
        }
    }

    public UiTextLineMetrics {
        text = Objects.requireNonNull(text, "text");
        if (!Double.isFinite(width) || width < 0.0) {
            throw new IllegalArgumentException("text width must be finite and non-negative");
        }
        ArrayList<CaretStop> sorted = new ArrayList<>(Objects.requireNonNull(stops, "stops"));
        sorted.sort(Comparator.comparingInt(CaretStop::utf16Offset));
        if (sorted.isEmpty() || sorted.getFirst().utf16Offset() != 0
                || sorted.getLast().utf16Offset() != text.length()) {
            throw new IllegalArgumentException("caret stops must cover the complete UTF-16 range");
        }
        stops = List.copyOf(sorted);
    }

    /** 返回指定 UTF-16 offset 的水平位置；合字内部使用相邻 stop 线性插值。 */
    public double xAt(int utf16Offset) {
        if (utf16Offset < 0 || utf16Offset > text.length()) {
            throw new IndexOutOfBoundsException("caret offset outside text: " + utf16Offset);
        }
        CaretStop previous = stops.getFirst();
        for (CaretStop stop : stops) {
            if (stop.utf16Offset() == utf16Offset) return stop.x();
            if (stop.utf16Offset() > utf16Offset) {
                int span = stop.utf16Offset() - previous.utf16Offset();
                if (span <= 0) return previous.x();
                double fraction = (double) (utf16Offset - previous.utf16Offset()) / span;
                return previous.x() + (stop.x() - previous.x()) * fraction;
            }
            previous = stop;
        }
        return previous.x();
    }

    /** 返回最接近指定水平位置的 UTF-16 caret stop。 */
    public int offsetAt(double x) {
        if (!Double.isFinite(x)) throw new IllegalArgumentException("caret x must be finite");
        CaretStop nearest = stops.getFirst();
        double distance = Math.abs(x - nearest.x());
        for (CaretStop stop : stops) {
            double candidate = Math.abs(x - stop.x());
            if (candidate < distance) {
                nearest = stop;
                distance = candidate;
            }
        }
        return nearest.utf16Offset();
    }

    /** 创建不依赖字体后端的等宽近似，供占位 painter 使用。 */
    public static UiTextLineMetrics approximate(String text, double advance) {
        Objects.requireNonNull(text, "text");
        if (!Double.isFinite(advance) || advance < 0.0) {
            throw new IllegalArgumentException("advance must be finite and non-negative");
        }
        ArrayList<CaretStop> stops = new ArrayList<>();
        stops.add(new CaretStop(0, 0.0));
        int codePoints = 0;
        for (int offset = 0; offset < text.length();) {
            offset += Character.charCount(text.codePointAt(offset));
            codePoints++;
            stops.add(new CaretStop(offset, codePoints * advance));
        }
        return new UiTextLineMetrics(text, stops, codePoints * advance);
    }
}
