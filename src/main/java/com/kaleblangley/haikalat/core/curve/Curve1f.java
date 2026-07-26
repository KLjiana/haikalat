package com.kaleblangley.haikalat.core.curve;

/**
 * Immutable scalar curve sampled with normalized time.
 * Implementations reject non-finite values and values outside {@code [0, 1]}.
 */
@FunctionalInterface
public interface Curve1f {
    float sample(float normalizedTime);
}
