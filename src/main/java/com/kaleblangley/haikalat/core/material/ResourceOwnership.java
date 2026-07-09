package com.kaleblangley.haikalat.core.material;

/**
 * Describes whether a runtime material closes the GL resources it references.
 *
 * <p>Use {@link #BORROWED} when shaders, textures, and samplers are owned by an asset manager/cache or by
 * surrounding demo setup code. Use {@link #OWNED} only for convenience runtime materials that exclusively
 * own their referenced GL resources and should close them together.</p>
 */
public enum ResourceOwnership {
    BORROWED,
    OWNED
}
