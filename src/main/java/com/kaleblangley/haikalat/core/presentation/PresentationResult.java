package com.kaleblangley.haikalat.core.presentation;

/** Outcome of a host-directed render call. */
public enum PresentationResult {
    RENDERED,
    SKIPPED_ZERO_EXTENT,
    /** The pipeline is paused by its master feature switch; the host target was not touched. */
    SKIPPED_PAUSED
}
