package com.kaleblangley.haikalat.core;

/**
 * Anti-aliasing selection for the colour chain.  Exactly one mode is active at
 * a time; the modes are mutually exclusive by construction.
 *
 * <p>{@link #SMAA} is SMAA 1x after tone mapping and requires tone mapping to
 * be enabled ({@code ToneMappingMode} other than {@code NONE}).  It does not
 * combine with FXAA, TAA or MSAA and does not use temporal history.</p>
 */
public enum AntiAliasingMode {
    NONE,
    MSAA,
    FXAA,
    TAA,
    SMAA
}
