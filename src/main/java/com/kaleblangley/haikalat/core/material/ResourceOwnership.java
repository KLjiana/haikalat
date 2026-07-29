package com.kaleblangley.haikalat.core.material;

/**
 * Describes whether Haikalat owns the lifetime of a referenced native resource.
 *
 * <p>Use {@link #BORROWED} for host-, cache-, or asset-manager-owned objects:
 * Haikalat may use them but must not delete, reallocate, or mutate their storage
 * contract. Use {@link #OWNED} only when the corresponding Haikalat owner is
 * solely responsible for releasing the object.</p>
 */
public enum ResourceOwnership {
    BORROWED,
    OWNED
}
